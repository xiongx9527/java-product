package com.xiongx9527.askdata.common.util;

import com.xiongx9527.fw.plus.common.uid.worker.WorkerIdAssigner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * 元数据库无关的 worker id 分配器（对齐框架 {@code DisposableWorkerIdAssigner} 的 WORKER_NODE 语义）。
 *
 * <p>框架实现用 {@code PreparedStatement#setObject} 写入 {@link java.util.Date}；PostgreSQL JDBC
 * 无法推断该类型（{@code Can't infer the SQL type to use for an instance of java.util.Date}），
 * 导致元数据库切到 PostgreSQL 时 UID 生成器无法启动。这里显式使用 JDBC 的字符串/时间戳类型，
 * H2 / PostgreSQL / MySQL 均可执行，表结构与列含义保持不变。</p>
 */
public class PortableWorkerIdAssigner implements WorkerIdAssigner {

    private static final int NODE_TYPE_ACTUAL = 1;

    private final JdbcTemplate jdbcTemplate;

    public PortableWorkerIdAssigner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long assignWorkerId() {
        long now = System.currentTimeMillis();
        Timestamp timestamp = new Timestamp(now);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO WORKER_NODE (host_name, port, type, launch_date, created, modified) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, hostName());
            statement.setString(2, String.valueOf(currentPid()));
            statement.setInt(3, NODE_TYPE_ACTUAL);
            // 历史 schema.sql 的 launch_date 为 VARCHAR，保持字符串写入以兼容旧库。
            statement.setString(4, timestamp.toString());
            statement.setTimestamp(5, timestamp);
            statement.setTimestamp(6, timestamp);
            return statement;
        }, keyHolder);
        Number key = null;
        java.util.Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && !keys.isEmpty()) {
            Object id = keys.get("id");
            if (!(id instanceof Number)) {
                id = keys.values().iterator().next();
            }
            if (id instanceof Number) {
                key = (Number) id;
            }
        }
        if (key == null) {
            throw new IllegalStateException("WORKER_NODE 插入未返回自增主键");
        }
        return key.longValue();
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 当前进程号（Java 8 无 ProcessHandle，退回 RuntimeMXBean 的 "pid@host" 前缀）。 */
    private static long currentPid() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return Long.parseLong(at > 0 ? name.substring(0, at) : name);
        } catch (Exception e) {
            return -1L;
        }
    }
}
