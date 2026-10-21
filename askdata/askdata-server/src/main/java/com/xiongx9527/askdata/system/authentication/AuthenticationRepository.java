package com.xiongx9527.askdata.system.authentication;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSO 认证源表（sys_authentication）JDBC 访问层。
 *
 * <p>表结构与 SQLBot 运行实例（{@code 192.168.1.100} 的 PG 库 {@code sqlbot}）的实际 DDL 一致：
 * {@code id BIGINT PK / name VARCHAR(255) NOT NULL / type INT NOT NULL / config TEXT /
 * enable BOOLEAN NOT NULL / valid BOOLEAN NOT NULL / create_time BIGINT NOT NULL}。
 * 该表<b>不含</b> oid 列（作用域为全局），与 SQLBot 实际结构一致。</p>
 */
@Repository
public class AuthenticationRepository {

    private final JdbcTemplate jdbc;

    public AuthenticationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "id, name, type, config, enable, valid, create_time";

    /** 全部认证源（按 id 升序；前端按 name 分组成卡片）。 */
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_authentication ORDER BY id");
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_authentication WHERE id = ?", id)
                .stream().findFirst();
    }

    /** 按类型查（type 即认证源类型码：1=CAS 2=OIDC 3=LDAP 4=OAuth2 5=SAML2 6..9=企业 IM）。 */
    public Optional<Map<String, Object>> findByType(int type) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_authentication WHERE type = ? LIMIT 1", type)
                .stream().findFirst();
    }

    public Optional<Map<String, Object>> findByName(String name) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_authentication WHERE name = ? LIMIT 1", name)
                .stream().findFirst();
    }

    /** 新增认证源（同 id 覆盖写，天然幂等）。 */
    public void upsert(long id, String name, int type, String config, boolean enable, boolean valid,
                       long createTime) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sys_authentication WHERE id = ?",
                Integer.class, id);
        if (exists != null && exists > 0) {
            jdbc.update("UPDATE sys_authentication SET name = ?, type = ?, config = ?, enable = ?, valid = ? "
                    + "WHERE id = ?", name, type, config, enable, valid, id);
            return;
        }
        jdbc.update("INSERT INTO sys_authentication (id, name, type, config, enable, valid, create_time) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, name, type, config, enable, valid, createTime);
    }

    /** 启停（enable）。 */
    public void updateEnable(long id, boolean enable) {
        jdbc.update("UPDATE sys_authentication SET enable = ? WHERE id = ?", enable, id);
    }

    /** 校验状态（valid）。 */
    public void updateValid(long id, boolean valid) {
        jdbc.update("UPDATE sys_authentication SET valid = ? WHERE id = ?", valid, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM sys_authentication WHERE id = ?", id);
    }
}
