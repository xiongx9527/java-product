package com.xiongx9527.askdata.training;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xiongx9527.askdata.common.util.IdGenerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQL 示例训练表 data_training JDBC 访问层。
 * 表结构移植自 SQLBot alembic 042/050/051（字段名不变）。
 */
@Repository
public class TrainingRepository {

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    public TrainingRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    private static final String COLUMNS =
            "id, oid, datasource, create_time, question, description, enabled, advanced_application";

    /** 分页查询（按创建时间倒序）；关键词/数据源/高级应用筛选对齐 SQLBot build_data_training_query。 */
    public List<Map<String, Object>> page(long oid, String question, List<Long> dsList, List<Long> advList,
                                          int pageNum, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM data_training WHERE oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (question != null && !question.isEmpty()) {
            sql.append(" AND question LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(question) + "%");
        }
        if (dsList != null && !dsList.isEmpty()) {
            sql.append(" AND datasource IN (").append(placeholders(dsList.size())).append(")");
            args.addAll(dsList);
        }
        if (advList != null && !advList.isEmpty()) {
            sql.append(" AND advanced_application IN (").append(placeholders(advList.size())).append(")");
            args.addAll(advList);
        }
        sql.append(" ORDER BY create_time DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long count(long oid, String question, List<Long> dsList, List<Long> advList) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM data_training WHERE oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (question != null && !question.isEmpty()) {
            sql.append(" AND question LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(question) + "%");
        }
        if (dsList != null && !dsList.isEmpty()) {
            sql.append(" AND datasource IN (").append(placeholders(dsList.size())).append(")");
            args.addAll(dsList);
        }
        if (advList != null && !advList.isEmpty()) {
            sql.append(" AND advanced_application IN (").append(placeholders(advList.size())).append(")");
            args.addAll(advList);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /** 导出用全量查询（对齐 SQLBot get_all_data_training，不分页）。 */
    public List<Map<String, Object>> all(long oid, String question, List<Long> dsList, List<Long> advList) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM data_training WHERE oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (question != null && !question.isEmpty()) {
            sql.append(" AND question LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(question) + "%");
        }
        if (dsList != null && !dsList.isEmpty()) {
            sql.append(" AND datasource IN (").append(placeholders(dsList.size())).append(")");
            args.addAll(dsList);
        }
        if (advList != null && !advList.isEmpty()) {
            sql.append(" AND advanced_application IN (").append(placeholders(advList.size())).append(")");
            args.addAll(advList);
        }
        sql.append(" ORDER BY create_time DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM data_training WHERE id = ?", id).stream().findFirst();
    }

    /**
     * 重复校验（对齐 SQLBot create_training/update_training）：
     * 同 oid 同 question，且 datasource/advanced_application 作用域冲突即视为重复。
     */
    public boolean duplicateExists(long oid, String question, Long datasource, Long advancedApplication, Long excludeId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM data_training WHERE oid = ? AND question = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        args.add(question);
        if (datasource != null && advancedApplication != null) {
            sql.append(" AND (datasource = ? OR advanced_application = ?)");
            args.add(datasource);
            args.add(advancedApplication);
        } else if (datasource != null) {
            sql.append(" AND datasource = ?");
            args.add(datasource);
        } else if (advancedApplication != null) {
            sql.append(" AND advanced_application = ?");
            args.add(advancedApplication);
        }
        if (excludeId != null) {
            sql.append(" AND id <> ?");
            args.add(excludeId);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count != null && count > 0;
    }

    public long insert(long oid, Long datasource, String question, String description, boolean enabled,
                       Long advancedApplication, long createTime) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "enabled, advanced_application) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, oid, datasource, createTime, question, description, enabled, advancedApplication);
        return id;
    }

    public void update(long id, Long datasource, String question, String description, boolean enabled,
                       Long advancedApplication) {
        jdbc.update("UPDATE data_training SET datasource = ?, question = ?, description = ?, enabled = ?, "
                        + "advanced_application = ? WHERE id = ?",
                datasource, question, description, enabled, advancedApplication, id);
    }

    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE data_training SET enabled = ? WHERE id = ?", enabled, id);
    }

    public int deleteByIds(long oid, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Object> args = new ArrayList<>();
        args.add(oid);
        args.addAll(ids);
        return jdbc.update("DELETE FROM data_training WHERE oid = ? AND id IN ("
                + placeholders(ids.size()) + ")", args.toArray());
    }

    public boolean exists(long oid, long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM data_training WHERE oid = ? AND id = ?",
                Long.class, oid, id);
        return count != null && count > 0;
    }

    /** 高级应用（assistant type=1）id → name（术语/训练展示用）。 */
    public Map<Long, String> assistantNames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        StringBuilder sql = new StringBuilder("SELECT id, name FROM sys_assistant WHERE type = 1 AND id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i > 0 ? ",?" : "?");
        }
        sql.append(")");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), ids.toArray());
        Map<Long, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            if (id instanceof Number) {
                result.put(((Number) id).longValue(), row.get("name") == null ? null : String.valueOf(row.get("name")));
            }
        }
        return result;
    }

    /** 按名称解析高级应用（Excel 导入用；对齐 SQLBot assistant_name_to_id）。 */
    public List<Map<String, Object>> findAssistantsByName(long oid, String name) {
        return jdbc.queryForList("SELECT id, name FROM sys_assistant WHERE type = 1 AND oid = ? AND name = ?",
                oid, name);
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i > 0 ? ",?" : "?");
        }
        return sb.toString();
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
    }
}
