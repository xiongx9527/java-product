package com.xiongx9527.askdata.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB 版单 worker 守卫锁：元数据库表行独占锁（默认后端）。
 *
 * <p><b>为何不用 PostgreSQL advisory lock</b>：SQLBot 用 {@code pg_try_advisory_lock}，
 * 但 askdata 的元数据库是 H2，<b>没有 advisory lock 语义</b>。故采用表行独占锁：</p>
 * <ul>
 *   <li>守卫表 {@link #LOCK_TABLE}（一行，键为任务名）；</li>
 *   <li>获取锁 = 在<b>独立连接</b>上 {@code SELECT ... FOR UPDATE} 锁定该行，
 *       <b>保持事务不提交</b>，行锁持续到释放时提交/回滚；</li>
 *   <li>行锁被其它实例持有时该语句会阻塞，故设置 {@code LOCK_TIMEOUT}（默认 1s）后转为失败，
 *       即「未获得锁」。</li>
 * </ul>
 *
 * <p><b>作用域</b>：H2 的行锁只在<b>同一数据库文件</b>内有效，故多实例共享同一元数据库时
 * 提供跨实例互斥；各实例各用本地库时退化为按库互斥（各自回填自己的库，语义正确）。</p>
 */
public class DbWorkerLock implements WorkerLock {

    private static final Logger log = LoggerFactory.getLogger(DbWorkerLock.class);

    /** 守卫表名（幂等创建）。 */
    public static final String LOCK_TABLE = "sys_single_worker_lock";

    /** 获取行锁的等待超时（秒）；超时视为「未获得锁」。 */
    private static final int LOCK_TIMEOUT_SECONDS = 1;

    private final JdbcTemplate jdbc;

    /** 防抖窗口：最近在该窗口内已执行过的任务不再重复执行（0/负数表示不防抖）。 */
    private final Duration debounce;

    /** 持有的连接（键 → 连接），用于释放时提交并关闭。 */
    private final Map<String, Connection> heldConnections = new ConcurrentHashMap<>();

    public DbWorkerLock(JdbcTemplate jdbc, Duration debounce) {
        this.jdbc = jdbc;
        this.debounce = debounce;
    }

    @Override
    public boolean tryAcquire(String key) {
        if (!ensureTable()) {
            return false;
        }
        if (isDebounced(key)) {
            log.info("单 worker 守卫 {} 在防抖窗口内已执行过，跳过（窗口 {}s）", key, debounce.getSeconds());
            return false;
        }
        return lockRow(key);
    }

    /**
     * 防抖判定：读取上次执行时间（{@code acquired_at}），落在窗口内则跳过。
     *
     * <p>时间戳在获取锁的同一事务里刷新，故只有<b>成功执行并释放</b>后窗口才生效；
     * 进程异常退出时事务回滚，时间戳不刷新，任务可被重新执行（安全）。</p>
     */
    private boolean isDebounced(String key) {
        if (debounce == null || debounce.isZero() || debounce.isNegative()) {
            return false;
        }
        try {
            Long at = jdbc.queryForObject(
                    "SELECT acquired_at FROM " + LOCK_TABLE + " WHERE lock_key = ?", Long.class, key);
            if (at == null) {
                return false;
            }
            long age = System.currentTimeMillis() - at;
            return age >= 0 && age < debounce.toMillis();
        } catch (Exception e) {
            // 读不到时间戳（无行/异常）→ 不拦截
            return false;
        }
    }

    @Override
    public void release(String key) {
        releaseConnection(key);
    }

    @Override
    public String name() {
        return "db";
    }

    /** 幂等创建守卫表。 */
    private boolean ensureTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + LOCK_TABLE + " ("
                    + "lock_key VARCHAR(255) PRIMARY KEY, "
                    + "acquired_at BIGINT, "
                    + "owner VARCHAR(255))");
            return true;
        } catch (Exception e) {
            log.info("创建单 worker 守卫表失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 以行锁方式抢占锁：独立连接 + 显式开启事务，拿到锁后保持事务不提交，
     * 直到 {@link #release(String)} 才提交/回滚，从而在任务执行期间持续持锁。
     */
    private boolean lockRow(String key) {
        Connection connection = null;
        try {
            connection = jdbc.getDataSource().getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT lock_key FROM " + LOCK_TABLE + " WHERE lock_key = ? FOR UPDATE")) {
                ps.setString(1, key);
                ps.setQueryTimeout(LOCK_TIMEOUT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // 行不存在：插入并锁定（首次执行）
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO " + LOCK_TABLE + " (lock_key, acquired_at, owner) "
                                        + "SELECT ?, ?, ? WHERE NOT EXISTS "
                                        + "(SELECT 1 FROM " + LOCK_TABLE + " WHERE lock_key = ?)")) {
                            insert.setString(1, key);
                            insert.setLong(2, System.currentTimeMillis());
                            insert.setString(3, ownerId());
                            insert.setString(4, key);
                            insert.executeUpdate();
                        }
                    }
                }
            }
            // 刷新执行时间（防抖窗口用）：与持锁同一事务，释放提交后才对其它实例可见
            try (PreparedStatement upd = connection.prepareStatement(
                    "UPDATE " + LOCK_TABLE + " SET acquired_at = ?, owner = ? WHERE lock_key = ?")) {
                upd.setLong(1, System.currentTimeMillis());
                upd.setString(2, ownerId());
                upd.setString(3, key);
                upd.executeUpdate();
            }
            // 保持事务开启 → 行锁持续持有
            heldConnections.put(key, connection);
            return true;
        } catch (SQLException e) {
            closeQuietly(connection);
            return false;
        }
    }

    /** 释放时提交并关闭持有的连接（行锁随之释放）。 */
    private void releaseConnection(String key) {
        Connection connection = heldConnections.remove(key);
        if (connection == null) {
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            log.debug("释放守卫事务失败（忽略）: {}", e.getMessage());
        }
        closeQuietly(connection);
    }

    static String ownerId() {
        try {
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = pid.indexOf('@');
            return at > 0 ? pid.substring(0, at) : pid;
        } catch (Exception e) {
            return "unknown";
        }
    }

    static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // ignore
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // ignore
        }
    }
}
