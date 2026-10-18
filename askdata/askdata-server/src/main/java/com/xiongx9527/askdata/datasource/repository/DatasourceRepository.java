package com.xiongx9527.askdata.datasource.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xiongx9527.askdata.common.util.IdGenerator;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源表（core_datasource）JDBC 访问层。
 * 表结构移植自 SQLBot alembic（core_datasource；字段名不变，JSONB → JSON 文本）。
 */
@Repository
public class DatasourceRepository {

    private static final String COLUMNS =
            "id, name, description, type, type_name, configuration, create_time, create_by, status, num, oid, "
                    + "table_relation, embedding, recommended_config";

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    public DatasourceRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    /** /datasource/list：按工作空间（oid）列出，按名称排序（对齐 SQLBot get_datasource_list）。 */
    public List<Map<String, Object>> listByOid(long oid) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_datasource WHERE oid = ? ORDER BY name", oid);
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_datasource WHERE id = ?", id).stream().findFirst();
    }

    public void insert(long id, String name, String description, String type, String typeName, String configuration,
                       long createBy, long oid) {
        jdbc.update("INSERT INTO core_datasource (id, name, description, type, type_name, configuration, "
                        + "create_time, create_by, status, num, oid, table_relation, embedding, recommended_config) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, description, type, typeName, configuration, new Timestamp(System.currentTimeMillis()),
                createBy, "Success", null, oid, null, null, 1);
    }

    /** /datasource/update：仅更新连接相关字段（对齐 SQLBot update_ds 的 exclude_unset 语义）。 */
    public void update(long id, String name, String description, String type, String typeName,
                       String configuration) {
        jdbc.update("UPDATE core_datasource SET name = ?, description = ?, type = ?, type_name = ?, "
                        + "configuration = ?, status = 'Success' WHERE id = ?",
                name, description, type, typeName, configuration, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM core_datasource WHERE id = ?", id);
    }

    /** 名称唯一性（对齐 SQLBot check_name：同 oid 内唯一，update 排除自身）。 */
    public boolean existsName(long oid, String name, Long excludeId) {
        if (excludeId == null) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM core_datasource WHERE name = ? AND oid = ?", Long.class, name, oid);
            return count != null && count > 0;
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core_datasource WHERE name = ? AND oid = ? AND id <> ?",
                Long.class, name, oid, excludeId);
        return count != null && count > 0;
    }

    public void updateNum(long id, String num) {
        jdbc.update("UPDATE core_datasource SET num = ? WHERE id = ?", num, id);
    }

    public void updateTableRelation(long id, String relationJson) {
        jdbc.update("UPDATE core_datasource SET table_relation = ? WHERE id = ?", relationJson, id);
    }

    public String findTableRelation(long id) {
        return jdbc.queryForList("SELECT table_relation FROM core_datasource WHERE id = ?", id).stream()
                .findFirst()
                .map(row -> row.get("table_relation") == null ? null : String.valueOf(row.get("table_relation")))
                .orElse(null);
    }

    public boolean exists(long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM core_datasource WHERE id = ?", Long.class, id);
        return count != null && count > 0;
    }

    public void updateRecommendedConfig(long id, int recommendedConfig) {
        jdbc.update("UPDATE core_datasource SET recommended_config = ? WHERE id = ?", recommendedConfig, id);
    }

    public Integer recommendedConfigOf(long id) {
        return jdbc.queryForList("SELECT recommended_config FROM core_datasource WHERE id = ?", id).stream()
                .findFirst()
                .map(row -> row.get("recommended_config") == null ? null
                        : ((Number) row.get("recommended_config")).intValue())
                .orElse(null);
    }

    /** oid 列表（admin 的 ws 查询用）。 */
    public List<Long> idsByOid(long oid) {
        return jdbc.queryForList("SELECT id FROM core_datasource WHERE oid = ?", Long.class, oid);
    }

    public long nextId() {
        return idGenerator.nextId();
    }

    static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
