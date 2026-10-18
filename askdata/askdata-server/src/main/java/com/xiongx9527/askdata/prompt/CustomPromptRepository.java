package com.xiongx9527.askdata.prompt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自定义提示词 custom_prompt 表 JDBC 访问层。
 * 表结构移植自 SQLBot alembic 046/069（字段名不变；type ∈ GENERATE_SQL/ANALYSIS/PREDICT_DATA，
 * datasource_ids 原为 JSONB，此处以 JSON 数组文本存储）。
 */
@Repository
public class CustomPromptRepository {

    private final JdbcTemplate jdbc;

    private final com.xiongx9527.askdata.common.util.IdGenerator idGenerator;

    public CustomPromptRepository(JdbcTemplate jdbc,
                                  com.xiongx9527.askdata.common.util.IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    private static final String COLUMNS =
            "id, oid, type, create_time, name, prompt, specific_ds, datasource_ids, advanced_application";

    /** 分页（对齐 SQLBot xpack custom_prompt 分页语义：按类型过滤 + 名称关键词）。 */
    public List<Map<String, Object>> page(long oid, String type, String name, List<Long> dsList,
                                          List<Long> advList, int pageNum, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM custom_prompt WHERE oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (type != null && !type.isEmpty()) {
            sql.append(" AND type = ?");
            args.add(type);
        }
        if (name != null && !name.isEmpty()) {
            sql.append(" AND name LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(name) + "%");
        }
        if (dsList != null && !dsList.isEmpty()) {
            sql.append(" AND (specific_ds = FALSE");
            for (Long dsId : dsList) {
                sql.append(" OR INSTR(',' || COALESCE(REPLACE(REPLACE(datasource_ids, '[', ''), ']', ''), '') "
                        + "|| ',', ',' || ? || ',') > 0");
                args.add(String.valueOf(dsId));
            }
            sql.append(")");
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

    public long count(long oid, String type, String name, List<Long> dsList, List<Long> advList) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM custom_prompt WHERE oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (type != null && !type.isEmpty()) {
            sql.append(" AND type = ?");
            args.add(type);
        }
        if (name != null && !name.isEmpty()) {
            sql.append(" AND name LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(name) + "%");
        }
        if (dsList != null && !dsList.isEmpty()) {
            sql.append(" AND (specific_ds = FALSE");
            for (Long dsId : dsList) {
                sql.append(" OR INSTR(',' || COALESCE(REPLACE(REPLACE(datasource_ids, '[', ''), ']', ''), '') "
                        + "|| ',', ',' || ? || ',') > 0");
                args.add(String.valueOf(dsId));
            }
            sql.append(")");
        }
        if (advList != null && !advList.isEmpty()) {
            sql.append(" AND advanced_application IN (").append(placeholders(advList.size())).append(")");
            args.addAll(advList);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /** 导出用全量查询（不分页）。 */
    public List<Map<String, Object>> all(long oid, String type, String name, List<Long> dsList,
                                         List<Long> advList) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM custom_prompt WHERE oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (type != null && !type.isEmpty()) {
            sql.append(" AND type = ?");
            args.add(type);
        }
        if (name != null && !name.isEmpty()) {
            sql.append(" AND name LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(name) + "%");
        }
        if (dsList != null && !dsList.isEmpty()) {
            sql.append(" AND (specific_ds = FALSE");
            for (Long dsId : dsList) {
                sql.append(" OR INSTR(',' || COALESCE(REPLACE(REPLACE(datasource_ids, '[', ''), ']', ''), '') "
                        + "|| ',', ',' || ? || ',') > 0");
                args.add(String.valueOf(dsId));
            }
            sql.append(")");
        }
        if (advList != null && !advList.isEmpty()) {
            sql.append(" AND advanced_application IN (").append(placeholders(advList.size())).append(")");
            args.addAll(advList);
        }
        sql.append(" ORDER BY create_time DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM custom_prompt WHERE id = ?", id).stream().findFirst();
    }

    /** 同类型同名校验（对齐 SQLBot xpack 名称唯一性）。 */
    public boolean nameExists(long oid, String type, String name, Long excludeId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM custom_prompt WHERE oid = ? AND type = ? "
                + "AND name = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        args.add(type);
        args.add(name);
        if (excludeId != null) {
            sql.append(" AND id <> ?");
            args.add(excludeId);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count != null && count > 0;
    }

    public long insert(long oid, String type, String name, String prompt, boolean specificDs,
                       String datasourceIdsJson, Long advancedApplication, long createTime) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO custom_prompt (id, oid, type, create_time, name, prompt, specific_ds, "
                        + "datasource_ids, advanced_application) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, oid, type, createTime, name, prompt, specificDs, datasourceIdsJson, advancedApplication);
        return id;
    }

    public void update(long id, String name, String prompt, boolean specificDs, String datasourceIdsJson,
                       Long advancedApplication) {
        jdbc.update("UPDATE custom_prompt SET name = ?, prompt = ?, specific_ds = ?, datasource_ids = ?, "
                        + "advanced_application = ? WHERE id = ?",
                name, prompt, specificDs, datasourceIdsJson, advancedApplication, id);
    }

    public int deleteByIds(long oid, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Object> args = new ArrayList<>();
        args.add(oid);
        args.addAll(ids);
        return jdbc.update("DELETE FROM custom_prompt WHERE oid = ? AND id IN ("
                + placeholders(ids.size()) + ")", args.toArray());
    }

    public boolean exists(long oid, long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM custom_prompt WHERE oid = ? AND id = ?",
                Long.class, oid, id);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // JSON 与名称查询
    // ------------------------------------------------------------------

    public String datasourceIdsJson(List<Long> ids) {
        StringBuilder sb = new StringBuilder("[");
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                sb.append(i > 0 ? "," : "").append(ids.get(i));
            }
        }
        return sb.append("]").toString();
    }

    public List<Long> parseDatasourceIds(String json) {
        List<Long> result = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return result;
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String part : body.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    result.add(Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {
                    // 忽略非法元素
                }
            }
        }
        return result;
    }

    /** 按名称解析高级应用（Excel 导入用）。 */
    public List<Map<String, Object>> findAssistantsByName(long oid, String name) {
        return jdbc.queryForList("SELECT id, name FROM sys_assistant WHERE type = 1 AND oid = ? AND name = ?",
                oid, name);
    }

    /** 高级应用（assistant type=1）id → name。 */
    public Map<Long, String> assistantNames(List<Long> ids) {
        Map<Long, String> result = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        StringBuilder sql = new StringBuilder("SELECT id, name FROM sys_assistant WHERE type = 1 AND id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i > 0 ? ",?" : "?");
        }
        sql.append(")");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), ids.toArray());
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            if (id instanceof Number) {
                result.put(((Number) id).longValue(),
                        row.get("name") == null ? null : String.valueOf(row.get("name")));
            }
        }
        return result;
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
