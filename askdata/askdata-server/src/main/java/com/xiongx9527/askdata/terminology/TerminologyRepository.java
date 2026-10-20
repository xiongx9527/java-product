package com.xiongx9527.askdata.terminology;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 术语库 terminology 表 JDBC 访问层。
 * 表结构移植自 SQLBot alembic 039/041/045/050/069（字段名不变；pid 非空为同义词子记录，
 * datasource_ids 原为 JSONB，此处以 JSON 数组文本存储）。
 */
@Repository
public class TerminologyRepository {

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    private final com.xiongx9527.askdata.common.util.IdGenerator idGenerator;

    public TerminologyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                 com.xiongx9527.askdata.common.util.IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    private static final String COLUMNS =
            "id, oid, pid, create_time, word, description, specific_ds, datasource_ids, enabled, "
                    + "advanced_application";

    /**
     * 父术语分页（对齐 SQLBot build_terminology_query 的 parent 语义）：
     * 关键词命中父词或任一子词时返回该父词；ds_list 筛选 specific_ds=false 或
     * datasource_ids 含任一数据源；adv_list 筛选高级应用。
     */
    public List<Map<String, Object>> pageParents(long oid, String word, List<Long> dsList, List<Long> advList,
                                                 int pageNum, int pageSize) {
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT t.id, t.oid, t.create_time, t.word, t.description, t.specific_ds, "
                        + "t.datasource_ids, t.enabled, t.advanced_application FROM terminology t "
                        + "LEFT JOIN terminology c ON c.pid = t.id WHERE t.pid IS NULL AND t.oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (word != null && !word.isEmpty()) {
            sql.append(" AND (t.word LIKE ? ESCAPE '\\' OR c.word LIKE ? ESCAPE '\\')");
            args.add("%" + escapeLike(word) + "%");
            args.add("%" + escapeLike(word) + "%");
        }
        appendScopeFilters(sql, args, dsList, advList);
        sql.append(" ORDER BY t.create_time DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long countParents(long oid, String word, List<Long> dsList, List<Long> advList) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(DISTINCT t.id) FROM terminology t "
                        + "LEFT JOIN terminology c ON c.pid = t.id WHERE t.pid IS NULL AND t.oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (word != null && !word.isEmpty()) {
            sql.append(" AND (t.word LIKE ? ESCAPE '\\' OR c.word LIKE ? ESCAPE '\\')");
            args.add("%" + escapeLike(word) + "%");
            args.add("%" + escapeLike(word) + "%");
        }
        appendScopeFilters(sql, args, dsList, advList);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /** 导出用全量父术语（不分页）。 */
    public List<Map<String, Object>> allParents(long oid, String word, List<Long> dsList, List<Long> advList) {
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT t.id, t.oid, t.create_time, t.word, t.description, t.specific_ds, "
                        + "t.datasource_ids, t.enabled, t.advanced_application FROM terminology t "
                        + "LEFT JOIN terminology c ON c.pid = t.id WHERE t.pid IS NULL AND t.oid = ?");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (word != null && !word.isEmpty()) {
            sql.append(" AND (t.word LIKE ? ESCAPE '\\' OR c.word LIKE ? ESCAPE '\\')");
            args.add("%" + escapeLike(word) + "%");
            args.add("%" + escapeLike(word) + "%");
        }
        appendScopeFilters(sql, args, dsList, advList);
        sql.append(" ORDER BY t.create_time DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 作用域筛选（对齐 SQLBot）：
     * ds_list：specific_ds=false 或 datasource_ids JSON 含任一 id；
     * adv_list：advanced_application 命中。
     */
    private void appendScopeFilters(StringBuilder sql, List<Object> args, List<Long> dsList, List<Long> advList) {
        if (dsList != null && !dsList.isEmpty()) {
            sql.append(" AND (t.specific_ds = FALSE");
            for (Long dsId : dsList) {
                sql.append(" OR INSTR(',' || COALESCE(REPLACE(REPLACE(t.datasource_ids, '[', ''), ']', ''), '') "
                        + "|| ',', ',' || ? || ',') > 0");
                args.add(String.valueOf(dsId));
            }
            sql.append(")");
        }
        if (advList != null && !advList.isEmpty()) {
            sql.append(" AND t.advanced_application IN (").append(placeholders(advList.size())).append(")");
            args.addAll(advList);
        }
    }

    /** 子术语（同义词）批量查询：pid → word 列表。 */
    public Map<Long, List<String>> childWords(Collection<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        StringBuilder sql = new StringBuilder("SELECT pid, word FROM terminology WHERE pid IN (");
        for (int i = 0; i < parentIds.size(); i++) {
            sql.append(i > 0 ? ",?" : "?");
        }
        sql.append(") ORDER BY id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), parentIds.toArray());
        Map<Long, List<String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long pid = toLong(row.get("pid"));
            if (pid == null) {
                continue;
            }
            result.computeIfAbsent(pid, key -> new ArrayList<>()).add(str(row.get("word")));
        }
        return result;
    }

    public Optional<Map<String, Object>> findParentById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM terminology WHERE id = ? AND pid IS NULL", id)
                .stream().findFirst();
    }

    /**
     * 重复校验（对齐 SQLBot create_terminology 的作用域冲突规则）：
     * 同 oid 下，words 中任一词与既有记录（父或子）相同且作用域冲突即重复。
     */
    /**
     * 问数上下文用：返回当前组织下、生效且作用域命中的全部术语（父 + 子）行。
     * 作用域对齐 SQLBot select_terminology_by_word：advancedApp 非空 -> 仅该高级应用；
     * 否则 dsId 非空 -> 全部数据源或含该数据源；否则 -> 仅全部数据源（specific_ds=false）。
     */
    public List<Map<String, Object>> findEnabledTerms(long oid, Long dsId, Long advancedApp) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, pid, word, description FROM terminology WHERE oid = ? AND enabled = TRUE");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (advancedApp != null) {
            sql.append(" AND advanced_application = ?");
            args.add(advancedApp);
        } else if (dsId != null) {
            sql.append(" AND (specific_ds = FALSE OR specific_ds IS NULL");
            sql.append(" OR INSTR(',' || COALESCE(REPLACE(REPLACE(datasource_ids, '[', ''), ']', ''), '') "
                    + "|| ',', ',' || ? || ',') > 0)");
            args.add(String.valueOf(dsId));
            sql.append(")");
        } else {
            sql.append(" AND (specific_ds = FALSE OR specific_ds IS NULL)");
        }
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public boolean duplicateExists(long oid, List<String> words, boolean specificDs, List<Long> datasourceIds,
                                   Long advancedApplication, Long excludeId) {
        if (words.isEmpty()) {
            return false;
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM terminology WHERE oid = ? AND word IN (");
        sql.append(placeholders(words.size())).append(")");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        args.addAll(words);
        if (excludeId != null) {
            sql.append(" AND id <> ?");
            args.add(excludeId);
        }
        if (specificDs && datasourceIds != null && !datasourceIds.isEmpty()) {
            // 指定数据源：与全部数据源冲突，或与有数据源重叠的记录冲突
            sql.append(" AND (specific_ds = FALSE");
            for (Long dsId : datasourceIds) {
                sql.append(" OR INSTR(',' || COALESCE(REPLACE(REPLACE(datasource_ids, '[', ''), ']', ''), '') "
                        + "|| ',', ',' || ? || ',') > 0");
                args.add(String.valueOf(dsId));
            }
            sql.append(")");
        } else if (!specificDs) {
            // 全部数据源：与任一有数据源记录冲突，也与同为全部数据源的记录冲突
            sql.append(" AND (specific_ds = FALSE OR COALESCE(datasource_ids, '') <> '')");
        } else {
            // specific_ds 但不选数据源：仅与同为不选数据源的记录冲突
            sql.append(" AND specific_ds = TRUE AND COALESCE(datasource_ids, '') = ''");
        }
        if (advancedApplication != null) {
            sql.append(" AND advanced_application = ?");
            args.add(advancedApplication);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count != null && count > 0;
    }

    public long insertParent(long oid, String word, String description, boolean specificDs, String datasourceIdsJson,
                             boolean enabled, Long advancedApplication, long createTime) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO terminology (id, oid, pid, create_time, word, description, specific_ds, "
                        + "datasource_ids, enabled, advanced_application) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)",
                id, oid, createTime, word, description, specificDs, datasourceIdsJson, enabled, advancedApplication);
        return id;
    }

    public long insertChild(long oid, long pid, String word, boolean specificDs, String datasourceIdsJson,
                            boolean enabled, Long advancedApplication, long createTime) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO terminology (id, oid, pid, create_time, word, description, specific_ds, "
                        + "datasource_ids, enabled, advanced_application) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)",
                id, oid, pid, createTime, word, specificDs, datasourceIdsJson, enabled, advancedApplication);
        return id;
    }

    public void updateParent(long id, String word, String description, boolean specificDs, String datasourceIdsJson,
                             boolean enabled, Long advancedApplication) {
        jdbc.update("UPDATE terminology SET word = ?, description = ?, specific_ds = ?, datasource_ids = ?, "
                        + "enabled = ?, advanced_application = ? WHERE id = ?",
                word, description, specificDs, datasourceIdsJson, enabled, advancedApplication, id);
    }

    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE terminology SET enabled = ? WHERE id = ? OR pid = ?", enabled, id, id);
    }

    public void deleteChildren(long pid) {
        jdbc.update("DELETE FROM terminology WHERE pid = ?", pid);
    }

    public int deleteByIds(long oid, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM terminology WHERE oid = ? AND (id IN (");
        sql.append(placeholders(ids.size())).append(") OR pid IN (").append(placeholders(ids.size())).append("))");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        args.addAll(ids);
        args.addAll(ids);
        return jdbc.update(sql.toString(), args.toArray());
    }

    public boolean exists(long oid, long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM terminology WHERE oid = ? AND id = ?",
                Long.class, oid, id);
        return count != null && count > 0;
    }

    /** 高级应用（assistant type=1）id → name（术语展示用）。 */
    public Map<Long, String> assistantNames(List<Long> ids) {
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
                result.put(((Number) id).longValue(),
                        row.get("name") == null ? null : String.valueOf(row.get("name")));
            }
        }
        return result;
    }

    /** 按名称解析高级应用（Excel 导入用；对齐 SQLBot assistant_name_to_id）。 */
    public List<Map<String, Object>> findAssistantsByName(long oid, String name) {
        return jdbc.queryForList("SELECT id, name FROM sys_assistant WHERE type = 1 AND oid = ? AND name = ?",
                oid, name);
    }

    // ------------------------------------------------------------------
    // JSON 与工具
    // ------------------------------------------------------------------

    public String datasourceIdsJson(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? Collections.emptyList() : ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    public List<Long> parseDatasourceIds(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
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
