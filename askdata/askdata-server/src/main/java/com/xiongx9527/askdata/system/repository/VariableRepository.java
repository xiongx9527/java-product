package com.xiongx9527.askdata.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统变量表（system_variable）JDBC 访问层。
 * 表结构移植自 SQLBot alembic 062（value 为 JSON 文本，字段名不变）。
 */
@Repository
public class VariableRepository {

    private final JdbcTemplate jdbc;

    public VariableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "id, name, var_type, type, `value`, create_time, create_by";

    /** /sys_variable/listAll：name 为空时全量；否则按名称模糊过滤且排除 system。 */
    public List<Map<String, Object>> listAll(String name) {
        if (name == null || name.isEmpty()) {
            return jdbc.queryForList(
                    "SELECT " + COLUMNS + " FROM system_variable ORDER BY type DESC, name");
        }
        return jdbc.queryForList(
                "SELECT " + COLUMNS + " FROM system_variable WHERE LOWER(name) LIKE ? ESCAPE '\\' AND type <> 'system' "
                        + "ORDER BY type DESC, name",
                "%" + UserRepository.escapeLike(name.toLowerCase()) + "%");
    }

    /** /sys_variable/listPage：分页数据。 */
    public List<Map<String, Object>> listPage(String name, int offset, int size) {
        if (name == null || name.isEmpty()) {
            return jdbc.queryForList("SELECT " + COLUMNS + " FROM system_variable "
                    + "ORDER BY type DESC, name LIMIT ? OFFSET ?", size, offset);
        }
        String pattern = "%" + UserRepository.escapeLike(name.toLowerCase()) + "%";
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM system_variable "
                        + "WHERE LOWER(name) LIKE ? ESCAPE '\\' AND type <> 'system' "
                        + "ORDER BY type DESC, name LIMIT ? OFFSET ?",
                pattern, size, offset);
    }

    public long listPageCount(String name) {
        Long count;
        if (name == null || name.isEmpty()) {
            count = jdbc.queryForObject("SELECT COUNT(*) FROM system_variable", Long.class);
        } else {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM system_variable WHERE LOWER(name) LIKE ? ESCAPE '\\' AND type <> 'system'",
                    Long.class, "%" + UserRepository.escapeLike(name.toLowerCase()) + "%");
        }
        return count == null ? 0L : count;
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM system_variable WHERE id = ?", id).stream()
                .findFirst();
    }

    public boolean existsName(String name, Long excludeId) {
        Long count;
        if (excludeId == null) {
            count = jdbc.queryForObject("SELECT COUNT(*) FROM system_variable WHERE name = ?", Long.class, name);
        } else {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM system_variable WHERE name = ? AND id <> ?", Long.class, name, excludeId);
        }
        return count != null && count > 0;
    }

    public void insert(long id, String name, String varType, String value, java.sql.Timestamp createTime,
                       Long createBy) {
        jdbc.update("INSERT INTO system_variable (id, name, var_type, type, `value`, create_time, create_by) "
                + "VALUES (?, ?, ?, 'custom', ?, ?, ?)", id, name, varType, value, createTime, createBy);
    }

    public void update(long id, String name, String varType, String value) {
        jdbc.update("UPDATE system_variable SET name = ?, var_type = ?, `value` = ? WHERE id = ?",
                name, varType, value, id);
    }

    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Object> args = new ArrayList<>(ids);
        jdbc.update("DELETE FROM system_variable WHERE id IN (" + UserRepository.placeholders(ids.size()) + ")",
                args.toArray());
    }
}
