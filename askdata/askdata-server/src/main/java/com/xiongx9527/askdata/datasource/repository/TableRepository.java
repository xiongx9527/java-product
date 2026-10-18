package com.xiongx9527.askdata.datasource.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xiongx9527.askdata.common.util.IdGenerator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源表元数据（core_table）JDBC 访问层。
 * 表结构移植自 SQLBot alembic（core_table；字段名不变）。
 */
@Repository
public class TableRepository {

    private static final String COLUMNS = "id, ds_id, checked, table_name, table_comment, custom_comment, embedding";

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    public TableRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    /** /datasource/tableList：按表名排序（对齐 SQLBot get_tables_by_ds_id）。 */
    public List<Map<String, Object>> listByDsId(long dsId) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_table WHERE ds_id = ? ORDER BY table_name", dsId);
    }

    public List<Map<String, Object>> listByDsIdChecked(long dsId) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_table WHERE ds_id = ? AND checked = TRUE "
                + "ORDER BY table_name", dsId);
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_table WHERE id = ?", id).stream().findFirst();
    }

    public Optional<Map<String, Object>> findByDsAndName(long dsId, String tableName) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_table WHERE ds_id = ? AND table_name = ?",
                dsId, tableName).stream().findFirst();
    }

    public long insert(long dsId, boolean checked, String tableName, String tableComment, String customComment) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO core_table (id, ds_id, checked, table_name, table_comment, custom_comment, embedding) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, dsId, checked, tableName, tableComment, customComment, null);
        return id;
    }

    /** 对齐 SQLBot update_table：仅更新 checked 与 custom_comment。 */
    public void updateCheckedAndComment(long id, boolean checked, String customComment) {
        jdbc.update("UPDATE core_table SET checked = ?, custom_comment = ? WHERE id = ?", checked, customComment, id);
    }

    /** uploadDsSchema：按 (ds_id, table_name) 更新自定义表备注。 */
    public int updateCustomCommentByDsAndName(long dsId, String tableName, String customComment) {
        return jdbc.update("UPDATE core_table SET custom_comment = ? WHERE ds_id = ? AND table_name = ?",
                customComment, dsId, tableName);
    }

    /** 对齐 SQLBot sync_table：已存在表仅刷新 table_comment。 */
    public void updateTableComment(long id, String tableComment) {
        jdbc.update("UPDATE core_table SET table_comment = ? WHERE id = ?", tableComment, id);
    }

    public void deleteByDsId(long dsId) {
        jdbc.update("DELETE FROM core_table WHERE ds_id = ?", dsId);
    }

    /** 同步后清理：删除不在勾选集合内的表（对齐 SQLBot id.not_in 语义）。 */
    public void deleteByDsIdNotIn(long dsId, List<Long> keepIds) {
        if (keepIds == null || keepIds.isEmpty()) {
            deleteByDsId(dsId);
            return;
        }
        jdbc.update("DELETE FROM core_table WHERE ds_id = ? AND id NOT IN (" + placeholders(keepIds.size()) + ")",
                args(dsId, keepIds));
    }

    public long countByDsId(long dsId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM core_table WHERE ds_id = ?", Long.class, dsId);
        return count == null ? 0L : count;
    }

    private static Object[] args(long dsId, List<Long> ids) {
        Object[] result = new Object[ids.size() + 1];
        result[0] = dsId;
        for (int i = 0; i < ids.size(); i++) {
            result[i + 1] = ids.get(i);
        }
        return result;
    }

    static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
