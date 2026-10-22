package com.xiongx9527.askdata.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据权限 ds_rules / ds_permission 表 JDBC 访问层。
 * 表结构移植自 SQLBot alembic 026/027/030/071（字段名不变；permission_list/user_list
 * 原为 JSONB，此处以 JSON 文本存储）。
 *
 * <p>ds_rules 为规则组（名称 + 生效用户），ds_permission 为组内单条规则
 * （行权限 expression_tree / 列权限 permissions）；问数执行时的过滤由
 * datasource-engine/chat-core 消费 ds_permission 配置实现。</p>
 */
@Repository
public class DsPermissionRepository {

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    private final com.xiongx9527.askdata.common.util.IdGenerator idGenerator;

    public DsPermissionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                 com.xiongx9527.askdata.common.util.IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    // ------------------------------------------------------------------
    // 规则组 ds_rules
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listRules(Long oid) {
        if (oid == null) {
            return jdbc.queryForList("SELECT id, name, description, permission_list, user_list, white_list_user, "
                    + "create_time, oid FROM ds_rules ORDER BY create_time DESC");
        }
        return jdbc.queryForList("SELECT id, name, description, permission_list, user_list, white_list_user, "
                + "create_time, oid FROM ds_rules WHERE oid = ? OR oid IS NULL ORDER BY create_time DESC", oid);
    }

    public Optional<Map<String, Object>> findRule(long id) {
        return jdbc.queryForList("SELECT id, name, description, permission_list, user_list, white_list_user, "
                + "create_time, oid FROM ds_rules WHERE id = ?", id).stream().findFirst();
    }

    public long insertRule(String name, String description, String permissionListJson, String userListJson,
                           String whiteListUserJson, Long oid, long createTime) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO ds_rules (id, enable, name, description, permission_list, user_list, "
                        + "white_list_user, create_time, oid) VALUES (?, TRUE, ?, ?, ?, ?, ?, ?, ?)",
                id, name, description, permissionListJson, userListJson, whiteListUserJson, createTime, oid);
        return id;
    }

    public void updateRule(long id, String name, String description, String permissionListJson,
                           String userListJson, String whiteListUserJson) {
        jdbc.update("UPDATE ds_rules SET name = ?, description = ?, permission_list = ?, user_list = ?, "
                + "white_list_user = ? WHERE id = ?",
                name, description, permissionListJson, userListJson, whiteListUserJson, id);
    }

    public void deleteRule(long id) {
        jdbc.update("DELETE FROM ds_rules WHERE id = ?", id);
    }

    // ------------------------------------------------------------------
    // 单条规则 ds_permission（auth_target_id 指向 ds_rules.id）
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listPermissionsByRule(long ruleId) {
        return jdbc.queryForList("SELECT id, name, type, ds_id, table_id, expression_tree, permissions, "
                        + "white_list_user, enable, create_time FROM ds_permission "
                        + "WHERE auth_target_id = ? ORDER BY id", ruleId);
    }

    public void deletePermissionsByRule(long ruleId) {
        jdbc.update("DELETE FROM ds_permission WHERE auth_target_id = ?", ruleId);
    }

    /** 按表 + 类型（row/column）列出启用中的权限条目（行/列权限查询用）。 */
    public List<Map<String, Object>> listPermissionsByTableAndType(long tableId, String type) {
        return jdbc.queryForList("SELECT id, name, type, ds_id, table_id, expression_tree, permissions, "
                + "white_list_user, enable, create_time, auth_target_id FROM ds_permission "
                + "WHERE table_id = ? AND type = ? AND enable = TRUE", tableId, type);
    }

    public void insertPermission(long ruleId, String name, String type, Long dsId, Long tableId,
                                 String expressionTree, String permissions, String whiteListUserJson,
                                 long createTime) {
        jdbc.update("INSERT INTO ds_permission (id, enable, auth_target_type, auth_target_id, type, ds_id, "
                        + "table_id, expression_tree, permissions, white_list_user, create_time, name) "
                        + "VALUES (?, TRUE, 'rule', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                idGenerator.nextId(), ruleId, type, dsId, tableId, expressionTree, permissions,
                whiteListUserJson, createTime, name);
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Collections.emptyList() : value);
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T fromJson(String json, Class<T> type, T fallback) {
        if (json == null || json.isEmpty()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    public List<Map<String, Object>> jsonArray(String json) {
        try {
            return objectMapper.readValue(json == null || json.isEmpty() ? "[]" : json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public List<Long> longArray(String json) {
        try {
            return objectMapper.readValue(json == null || json.isEmpty() ? "[]" : json,
                    new TypeReference<List<Long>>() {
                    });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
