package com.xiongx9527.askdata.common.datasource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源名称查询（防御式）：{@code core_datasource} 由数据源模块管理，
 * 训练/术语/提示词等模块仅需 id → name 展示。表不存在（未集成数据源模块）时返回空映射，
 * 对应字段为 null，不影响管理面使用。
 */
@Component
public class DatasourceNameLookup {

    private final JdbcTemplate jdbc;

    public DatasourceNameLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量查询数据源 id → name；表缺失或查询失败时返回空映射。 */
    public Map<Long, String> namesOf(Collection<Long> ids) {
        if (ids == null || ids.isEmpty() || !tableExists("core_datasource")) {
            return Collections.emptyMap();
        }
        StringBuilder sql = new StringBuilder("SELECT id, name FROM core_datasource WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i > 0 ? ",?" : "?");
        }
        sql.append(")");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), ids.toArray());
            Map<Long, String> result = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Object id = row.get("id");
                if (id instanceof Number) {
                    result.put(((Number) id).longValue(), row.get("name") == null ? null : String.valueOf(row.get("name")));
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** 按名称解析数据源 id（Excel 导入用；表缺失时返回 null）。 */
    public Long idByName(String name) {
        if (name == null || name.isEmpty() || !tableExists("core_datasource")) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, name FROM core_datasource WHERE name = ?", name);
            for (Map<String, Object> row : rows) {
                Object id = row.get("id");
                if (id instanceof Number) {
                    return ((Number) id).longValue();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean tableExists(String table) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = UPPER(?)",
                    Integer.class, table);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
