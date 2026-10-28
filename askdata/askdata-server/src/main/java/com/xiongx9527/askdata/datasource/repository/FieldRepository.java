package com.xiongx9527.askdata.datasource.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源字段元数据（core_field）JDBC 访问层。
 * 表结构移植自 SQLBot alembic（core_field；字段名不变）。
 */
@Repository
public class FieldRepository {

    private static final String COLUMNS =
            "id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index";

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    public FieldRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    /** /datasource/fieldList：按 field_index 排序（对齐 SQLBot get_fields_by_table_id）。 */
    public List<Map<String, Object>> listByTableId(long tableId) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_field WHERE table_id = ? ORDER BY field_index",
                tableId);
    }

    /** 按字段名模糊匹配（对齐 SQLBot 的三种大小写 like 取并集，此处等价为忽略大小写包含）。 */
    public List<Map<String, Object>> listByTableIdLike(long tableId, String fieldName) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_field WHERE table_id = ? "
                + "AND LOWER(field_name) LIKE LOWER(?) ORDER BY field_index",
                tableId, "%" + fieldName + "%");
    }

    /** A19：按字段 id 查询（fieldEnum 按 CoreField.id 取字段定义）。 */
    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_field WHERE id = ?", id).stream().findFirst();
    }

    public Optional<Map<String, Object>> findByTableAndName(long tableId, String fieldName) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_field WHERE table_id = ? AND field_name = ?",
                tableId, fieldName).stream().findFirst();
    }

    public long insert(long dsId, long tableId, boolean checked, String fieldName, String fieldType,
                       String fieldComment, String customComment, int fieldIndex) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, "
                        + "custom_comment, field_index) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, dsId, tableId, checked, fieldName, fieldType, fieldComment, customComment, fieldIndex);
        return id;
    }

    /** 对齐 SQLBot update_field：仅更新 checked 与 custom_comment。 */
    public void updateCheckedAndComment(long id, boolean checked, String customComment) {
        jdbc.update("UPDATE core_field SET checked = ?, custom_comment = ? WHERE id = ?", checked, customComment, id);
    }

    /** uploadDsSchema：按 (ds_id, table_id, field_name) 更新自定义字段备注。 */
    public int updateCustomCommentByTableAndName(long dsId, long tableId, String fieldName, String customComment) {
        return jdbc.update("UPDATE core_field SET custom_comment = ? WHERE ds_id = ? AND table_id = ? AND field_name = ?",
                customComment, dsId, tableId, fieldName);
    }

    /** 对齐 SQLBot sync_fields：已存在字段刷新注释/序号/类型。 */
    public void updateMeta(long id, String fieldComment, int fieldIndex, String fieldType) {
        jdbc.update("UPDATE core_field SET field_comment = ?, field_index = ?, field_type = ? WHERE id = ?",
                fieldComment, fieldIndex, fieldType, id);
    }

    public void deleteByDsId(long dsId) {
        jdbc.update("DELETE FROM core_field WHERE ds_id = ?", dsId);
    }

    public void deleteByTableId(long tableId) {
        jdbc.update("DELETE FROM core_field WHERE table_id = ?", tableId);
    }

    /** 同步后清理：删除不在同步集合内的字段（对齐 SQLBot id.not_in 语义）。 */
    public void deleteByTableIdNotIn(long tableId, List<Long> keepIds) {
        if (keepIds == null || keepIds.isEmpty()) {
            deleteByTableId(tableId);
            return;
        }
        jdbc.update("DELETE FROM core_field WHERE table_id = ? AND id NOT IN (" + placeholders(keepIds.size()) + ")",
                args(tableId, keepIds));
    }

    /** 同步后清理：删除该数据源内已不在勾选集合内的表所属字段（对齐 SQLBot table_id.not_in 语义）。 */
    public void deleteByTableIdsNotIn(long dsId, List<Long> keepTableIds) {
        if (keepTableIds == null || keepTableIds.isEmpty()) {
            return;
        }
        // 必须按 ds_id 收窄：不同数据源的表 id 互不相关，只带 table_id NOT IN 会误删其它数据源的字段，
        // 导致别的数据源选表后字段元数据静默丢失（问数拿不到字段只能生成 success=false）。
        jdbc.update("DELETE FROM core_field WHERE ds_id = ? AND table_id NOT IN (" + placeholders(keepTableIds.size()) + ")",
                args(dsId, keepTableIds));
    }

    /**
     * A13：按 id 集合查询字段名映射（外键段 {@code table.field=table.field} 渲染用）。
     */
    public Map<Long, String> fieldNamesByIds(List<Long> ids) {
        Map<Long, String> result = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, field_name FROM core_field WHERE id IN ("
                + placeholders(ids.size()) + ")", ids.toArray());
        for (Map<String, Object> row : rows) {
            result.put(Rows.lng(row, "id", 0L), Rows.str(row, "field_name"));
        }
        return result;
    }

    private static Object[] args(long tableId, List<Long> ids) {
        Object[] result = new Object[ids.size() + 1];
        result[0] = tableId;
        for (int i = 0; i < ids.size(); i++) {
            result[i + 1] = ids.get(i);
        }
        return result;
    }

    static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
