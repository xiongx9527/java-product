package com.xiongx9527.askdata.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单 worker 启动任务守卫（对齐 SQLBot {@code common/utils/distributed_lock.py} 的 SingleWorkerGuard）。
 *
 * <p><b>目标</b>：启动期的一次性任务（如向量回填）在<b>集群内只由一个实例执行</b>；
 * 未获得守卫的实例直接跳过且<b>不重试</b>；应用关闭时释放守卫。</p>
 *
 * <p><b>实现选择（为何不用 PostgreSQL advisory lock）</b>：
 * SQLBot 用 {@code pg_try_advisory_lock}，但 askdata 的元数据库是 H2，
 * <b>没有 advisory lock 语义</b>。故本实现采用<b>表行独占锁</b>：</p>
 * <ul>
 *   <li>守卫表 {@code sys_single_worker_lock}（一行，键为任务名）；</li>
 *   <li>获取守卫 = 在<b>独立连接</b>上 {@code SELECT ... FOR UPDATE} 锁定该行，
 *       提交后行锁随事务结束释放——故需要在持有期间持续持有该连接；
 *       H2 的行锁在 {@code SELECT ... FOR UPDATE} 事务提交/回滚时释放，
 *       因此本实现改为：<b>以独立连接开启事务并保持不提交</b>，行锁持续到
 *       {@code release()} 时回滚/提交；</li>
 *   <li>若行锁被其它实例持有，{@code SELECT ... FOR UPDATE} 会阻塞——
 *       故设置 {@code LOCK_TIMEOUT}（默认 1s）后转为失败，即「未获得守卫」。</li>
 * </ul>
 *
 * <p><b>兜底</b>：H2 的行锁只在<b>同一数据库文件</b>内有效，故本守卫仅在
 * 「多实例共享同一元数据库」时提供跨实例互斥；若各实例使用各自的本地 H2 文件，
 * 则退化为进程内守卫（{@link #tryAcquireInProcess()}），此时每个实例都会执行回填——
 * 回填本身幂等（只填空向量），不会造成数据错误。</p>
 */
@Component
public class SingleWorkerGuard {

    private static final Logger log = LoggerFactory.getLogger(SingleWorkerGuard.class);

    /** 守卫表名（幂等创建）。 */
    public static final String LOCK_TABLE = "sys_single_worker_lock";

    /** 向量回填任务的守卫键（对齐 SQLBot 的 {@code sqlbot:startup:embedding}）。 */
    public static final String EMBEDDING_BACKFILL_KEY = "sqlbot:startup:embedding";

    /** 获取行锁的等待超时（秒）；超时视为「未获得守卫」。 */
    private static final int LOCK_TIMEOUT_SECONDS = 1;

    private final JdbcTemplate jdbc;

    /** 本进程持有的守卫（键 → 持有状态）。 */
    private final java.util.Map<String, Boolean> acquired = new java.util.concurrent.ConcurrentHashMap<>();

    /** 进程内守卫（同一 JVM 多上下文的兜底）。 */
    private final AtomicBoolean inProcess = new AtomicBoolean(false);

    public SingleWorkerGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 尝试获取守卫（单次尝试，不重试）。
     *
     * @param key 任务键
     * @return true 表示本实例获得守卫、应当执行任务
     */
    public boolean tryAcquire(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (acquired.containsKey(key)) {
            return true;
        }
        if (!ensureTable()) {
            // 表不可用：退化为进程内守卫（保证单实例内只跑一次）
            return tryAcquireInProcess(key);
        }
        try {
            if (lockRow(key)) {
                acquired.put(key, Boolean.TRUE);
                log.info("已获取单 worker 守卫：{}", key);
                return true;
            }
            log.info("未获取单 worker 守卫（其它实例已持有，跳过且不重试）：{}", key);
            return false;
        } catch (Exception e) {
            log.info("获取单 worker 守卫失败（退化为进程内守卫）：{} - {}", key, e.getMessage());
            return tryAcquireInProcess(key);
        }
    }

    /**
     * 释放守卫（应用关闭时调用）。
     *
     * @param key 任务键
     */
    public void release(String key) {
        if (key == null) {
            return;
        }
        if (acquired.remove(key) != null) {
            // 提交并关闭持有连接 → H2 行锁随之释放
            releaseConnection(key);
            log.info("已释放单 worker 守卫：{}", key);
        }
        inProcess.set(false);
    }

    /** 释放全部守卫（应用关闭时调用）。 */
    public void releaseAll() {
        for (String key : new java.util.ArrayList<>(acquired.keySet())) {
            release(key);
        }
    }

    /** 进程内守卫（同一 JVM 内只允许一个实例执行）。 */
    public boolean tryAcquireInProcess(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (acquired.containsKey(key)) {
            return true;
        }
        if (inProcess.compareAndSet(false, true)) {
            acquired.put(key, Boolean.TRUE);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

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
     * 以行锁方式抢占守卫。
     *
     * <p>使用独立连接并显式开启事务：H2 的 {@code SELECT ... FOR UPDATE} 行锁
     * 持续到事务结束；这里在拿到锁后<b>保持事务不提交</b>，
     * 直到 {@link #release(String)} 才提交/回滚，从而在任务执行期间持续持锁。</p>
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
            // 保持事务开启 → 行锁持续持有
            heldConnections.put(key, connection);
            return true;
        } catch (SQLException e) {
            closeQuietly(connection);
            return false;
        }
    }

    /** 持有的连接（键 → 连接），用于释放时提交并关闭。 */
    private final java.util.Map<String, Connection> heldConnections = new java.util.concurrent.ConcurrentHashMap<>();

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

    private static String ownerId() {
        try {
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = pid.indexOf('@');
            return at > 0 ? pid.substring(0, at) : pid;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void closeQuietly(Connection connection) {
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
