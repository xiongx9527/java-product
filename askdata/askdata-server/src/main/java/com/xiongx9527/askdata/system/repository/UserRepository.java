package com.xiongx9527.askdata.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/**
 * 登录/用户必需表（sys_user / sys_user_ws）的最小 JDBC 访问层。
 * 表结构移植自 SQLBot alembic 001/010/021/049（字段名不变）。
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String USER_COLUMNS =
            "id, account, name, password, email, oid, status, create_time, language, origin";

    public Optional<Map<String, Object>> findByAccount(String account) {
        return jdbc
                .queryForList("SELECT " + USER_COLUMNS + " FROM sys_user WHERE account = ? LIMIT 1", account)
                .stream().findFirst();
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc
                .queryForList("SELECT " + USER_COLUMNS + " FROM sys_user WHERE id = ?", id)
                .stream().findFirst();
    }

    /** uws weight（前端 isSpaceAdmin 判定用）；无记录返回 0。 */
    public int weightOf(long uid, long oid) {
        return jdbc.queryForList("SELECT weight FROM sys_user_ws WHERE uid = ? AND oid = ? LIMIT 1", uid, oid)
                .stream().findFirst()
                .map(m -> ((Number) m.get("weight")).intValue())
                .orElse(0);
    }

    public void updatePassword(long id, String md5Password) {
        jdbc.update("UPDATE sys_user SET password = ? WHERE id = ?", md5Password, id);
    }
}
