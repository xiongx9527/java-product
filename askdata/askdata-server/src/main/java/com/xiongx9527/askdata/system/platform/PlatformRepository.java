package com.xiongx9527.askdata.system.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 企业平台用户绑定表（sys_user_platform）与平台临时令牌表（sys_platform_token）JDBC 访问层。
 *
 * <p>DDL 与 SQLBot 运行实例的实际结构一致：</p>
 * <pre>
 * sys_user_platform: id BIGINT PK / uid BIGINT NOT NULL / origin INT NOT NULL DEFAULT 0 /
 *                    platform_uid VARCHAR(255) NOT NULL
 * sys_platform_token: id BIGINT PK / token VARCHAR(255) NOT NULL /
 *                     create_time BIGINT NOT NULL / exp_time BIGINT NOT NULL
 * </pre>
 */
@Repository
public class PlatformRepository {

    private final JdbcTemplate jdbc;

    public PlatformRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // sys_user_platform
    // ------------------------------------------------------------------

    private static final String PLATFORM_COLUMNS = "id, uid, origin, platform_uid";

    /** 按 (origin, platform_uid) 查绑定（SSO 回调解析本地用户时用）。 */
    public Optional<Map<String, Object>> findBinding(int origin, String platformUid) {
        return jdbc.queryForList("SELECT " + PLATFORM_COLUMNS + " FROM sys_user_platform "
                + "WHERE origin = ? AND platform_uid = ? LIMIT 1", origin, platformUid)
                .stream().findFirst();
    }

    /** 按 uid 列出全部绑定。 */
    public List<Map<String, Object>> listBindings(long uid) {
        return jdbc.queryForList("SELECT " + PLATFORM_COLUMNS + " FROM sys_user_platform WHERE uid = ? ORDER BY id",
                uid);
    }

    /** 列出某来源（origin）的全部绑定（同步幂等判定用）。 */
    public List<Map<String, Object>> listByOrigin(int origin) {
        return jdbc.queryForList("SELECT " + PLATFORM_COLUMNS + " FROM sys_user_platform WHERE origin = ? ORDER BY id",
                origin);
    }

    /** 建立绑定（重复调用按 (origin, platform_uid) 覆盖，天然幂等）。 */
    public void upsertBinding(long id, long uid, int origin, String platformUid) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_platform WHERE origin = ? AND platform_uid = ?",
                Integer.class, origin, platformUid);
        if (exists != null && exists > 0) {
            jdbc.update("UPDATE sys_user_platform SET uid = ? WHERE origin = ? AND platform_uid = ?",
                    uid, origin, platformUid);
            return;
        }
        jdbc.update("INSERT INTO sys_user_platform (id, uid, origin, platform_uid) VALUES (?, ?, ?, ?)",
                id, uid, origin, platformUid);
    }

    public void deleteBinding(long id) {
        jdbc.update("DELETE FROM sys_user_platform WHERE id = ?", id);
    }

    // ------------------------------------------------------------------
    // sys_platform_token
    // ------------------------------------------------------------------

    private static final String TOKEN_COLUMNS = "id, token, create_time, exp_time";

    public void insertToken(long id, String token, long createTime, long expTime) {
        jdbc.update("INSERT INTO sys_platform_token (id, token, create_time, exp_time) VALUES (?, ?, ?, ?)",
                id, token, createTime, expTime);
    }

    public Optional<Map<String, Object>> findToken(String token) {
        return jdbc.queryForList("SELECT " + TOKEN_COLUMNS + " FROM sys_platform_token WHERE token = ? LIMIT 1",
                token).stream().findFirst();
    }

    /** 清理过期令牌（返回删除条数）。 */
    public int deleteExpired(long now) {
        return jdbc.update("DELETE FROM sys_platform_token WHERE exp_time < ?", now);
    }
}
