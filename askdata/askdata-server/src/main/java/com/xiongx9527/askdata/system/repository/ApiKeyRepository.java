package com.xiongx9527.askdata.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API Key 表（sys_apikey）JDBC 访问层。
 * 表结构移植自 SQLBot alembic 056（字段名不变）。
 */
@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "id, access_key, secret_key, create_time, uid, status";

    public List<Map<String, Object>> listByUid(long uid) {
        return jdbc.queryForList(
                "SELECT " + COLUMNS + " FROM sys_apikey WHERE uid = ? ORDER BY create_time DESC", uid);
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_apikey WHERE id = ?", id).stream().findFirst();
    }

    public long countByUid(long uid) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_apikey WHERE uid = ?", Long.class, uid);
        return count == null ? 0L : count;
    }

    public void insert(long id, String accessKey, String secretKey, long createTime, long uid) {
        jdbc.update("INSERT INTO sys_apikey (id, access_key, secret_key, create_time, uid, status) "
                + "VALUES (?, ?, ?, ?, ?, TRUE)", id, accessKey, secretKey, createTime, uid);
    }

    public void updateStatus(long id, boolean status) {
        jdbc.update("UPDATE sys_apikey SET status = ? WHERE id = ?", status, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM sys_apikey WHERE id = ?", id);
    }
}
