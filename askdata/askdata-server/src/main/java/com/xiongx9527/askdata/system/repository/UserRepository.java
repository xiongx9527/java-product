package com.xiongx9527.askdata.system.repository;

import com.xiongx9527.askdata.common.cache.CacheNames;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户表（sys_user）JDBC 访问层。
 * 表结构移植自 SQLBot alembic 001/010/021/049/063（字段名不变）。
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String USER_COLUMNS =
            "id, account, name, password, email, oid, status, create_time, language, origin, system_variables";

    /** /user/pager 可排序字段白名单（对齐 SQLBot SORT_COLUMNS）。 */
    private static final Map<String, String> SORT_COLUMNS;

    static {
        Map<String, String> sort = new HashMap<>();
        sort.put("account", "u.account");
        sort.put("create_time", "u.create_time");
        sort.put("name", "u.name");
        sort.put("email", "u.email");
        sort.put("status", "u.status");
        SORT_COLUMNS = Collections.unmodifiableMap(sort);
    }

    public Optional<Map<String, Object>> findByAccount(String account) {
        return jdbc
                .queryForList("SELECT " + USER_COLUMNS + " FROM sys_user WHERE account = ? LIMIT 1", account)
                .stream().findFirst();
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc
                .queryForList("SELECT " + USER_COLUMNS + " FROM sys_user WHERE id = ?", id)
                .stream().findFirst();
    }

    /** uws weight（前端 isSpaceAdmin 判定用）；无记录返回 0。 */
    public int weightOf(long uid, long oid) {
        return jdbc.queryForList("SELECT weight FROM sys_user_ws WHERE uid = ? AND oid = ? LIMIT 1", uid, oid)
                .stream().findFirst()
                .map(m -> ((Number) m.get("weight")).intValue())
                .orElse(0);
    }

    public boolean existsAccount(String account, Long excludeId) {
        if (excludeId == null) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE account = ?", Long.class, account);
            return count != null && count > 0;
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE account = ? AND id <> ?", Long.class, account, excludeId);
        return count != null && count > 0;
    }

    /** /user/pager 单页数据（excludeUid=1 排除超级管理员，对齐 SQLBot）。 */
    public List<Map<String, Object>> pagerRows(String keyword, Integer status, List<Integer> origins,
                                               List<Integer> oidlist, String orderBy, boolean desc,
                                               int offset, int size) {
        boolean withKeyword = keyword != null && !keyword.trim().isEmpty();
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (withKeyword) {
            // A24：带关键字时保留相关性排序（对齐 SQLBot apps/system/api/user.py pager）：
            // match_count（命中字段数）降序 → total_score（精确 0/前缀 1/包含 2/未命中 3 之和）升序
            // → 用户选择的排序字段。派生表让 ORDER BY 能引用计算列，同时保留 DISTINCT。
            String kw = keyword.trim().toLowerCase(java.util.Locale.ROOT);
            sql.append("SELECT t.id, t.account, t.name, t.email, t.status, t.create_time, t.language, ")
                    .append("t.origin, t.oid, t.system_variables FROM (SELECT DISTINCT ")
                    .append("u.id AS id, u.account AS account, u.name AS name, u.email AS email, ")
                    .append("u.status AS status, u.create_time AS create_time, u.language AS language, ")
                    .append("u.origin AS origin, u.oid AS oid, u.system_variables AS system_variables, ")
                    .append("(").append(matchFlag("u.account")).append(" + ").append(matchFlag("u.name"))
                    .append(" + ").append(matchFlag("u.email")).append(") AS match_count, ")
                    .append("(").append(score("u.account")).append(" + ").append(score("u.name"))
                    .append(" + ").append(score("u.email")).append(") AS total_score ")
                    .append("FROM sys_user u LEFT JOIN sys_user_ws w ON u.id = w.uid WHERE u.id <> 1");
            appendFilters(sql, args, keyword, status, origins, oidlist);
            sql.append(") t ORDER BY t.match_count DESC, t.total_score ASC, t.")
                    .append(sortColumnName(orderBy)).append(desc ? " DESC" : " ASC");
            sql.append(" LIMIT ? OFFSET ?");
            // 计算列参数按 SQL 中出现顺序：先 3 个 matchFlag（包含匹配），再每个字段 3 个 score 参数
            String contains = "%" + escapeLike(kw) + "%";
            List<Object> scoreArgs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                scoreArgs.add(contains);
            }
            for (int i = 0; i < 3; i++) {
                scoreArgs.add(kw);
                scoreArgs.add(escapeLike(kw) + "%");
                scoreArgs.add(contains);
            }
            List<Object> all = new ArrayList<>();
            all.addAll(scoreArgs);
            all.addAll(args);
            all.add(size);
            all.add(offset);
            return jdbc.queryForList(sql.toString(), all.toArray());
        }
        sql.append("SELECT DISTINCT u.id, u.account, u.name, u.email, u.status, u.create_time, u.language, ")
                .append("u.origin, u.oid, u.system_variables ")
                .append("FROM sys_user u LEFT JOIN sys_user_ws w ON u.id = w.uid WHERE u.id <> 1");
        appendFilters(sql, args, keyword, status, origins, oidlist);
        sql.append(" ORDER BY ").append(sortColumn(orderBy)).append(desc ? " DESC" : " ASC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 相关性分数表达式（0=精确、1=前缀、2=包含、3=未命中），参数依次为
     * {@code keyword / keyword% / %keyword%}（均小写）。
     */
    private static String score(String column) {
        return "(CASE WHEN LOWER(" + column + ") = ? THEN 0 "
                + "WHEN LOWER(" + column + ") LIKE ? ESCAPE '\\' THEN 1 "
                + "WHEN LOWER(" + column + ") LIKE ? ESCAPE '\\' THEN 2 ELSE 3 END)";
    }

    /** 命中标记表达式（1=该字段命中关键字），参数同 {@link #score(String)}。 */
    private static String matchFlag(String column) {
        return "(CASE WHEN LOWER(" + column + ") LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END)";
    }

    public long pagerCount(String keyword, Integer status, List<Integer> origins, List<Integer> oidlist) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(DISTINCT u.id) FROM sys_user u LEFT JOIN sys_user_ws w ON u.id = w.uid WHERE u.id <> 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, status, origins, oidlist);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String keyword, Integer status,
                               List<Integer> origins, List<Integer> oidlist) {
        if (status != null) {
            sql.append(" AND u.status = ?");
            args.add(status);
        }
        if (origins != null && !origins.isEmpty()) {
            sql.append(" AND u.origin IN (").append(placeholders(origins.size())).append(")");
            args.addAll(origins);
        }
        if (oidlist != null && !oidlist.isEmpty()) {
            sql.append(" AND w.oid IN (").append(placeholders(oidlist.size())).append(")");
            args.addAll(oidlist);
        }
        if (keyword != null && !keyword.isEmpty()) {
            String pattern = "%" + escapeLike(keyword.toLowerCase()) + "%";
            sql.append(" AND (LOWER(u.account) LIKE ? ESCAPE '\\' OR LOWER(u.name) LIKE ? ESCAPE '\\'"
                    + " OR LOWER(u.email) LIKE ? ESCAPE '\\')");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
    }

    /** 用户绑定的工作空间 id 列表（按工作空间名排序，对齐 SQLBot user_ws_options）。 */
    public List<Long> oidListOf(long uid) {
        return jdbc.queryForList(
                "SELECT w.oid FROM sys_user_ws w JOIN sys_workspace s ON s.id = w.oid "
                        + "WHERE w.uid = ? ORDER BY s.name, s.create_time", Long.class, uid);
    }

    /** 当前 oid 指向该空间的用户（删除空间后需要重新选择/置 0）。 */
    public List<Long> uidsByOid(long oid) {
        return jdbc.queryForList("SELECT id FROM sys_user WHERE oid = ?", Long.class, oid);
    }

    /** 批量取 uid → oid 绑定（/user/pager 组装 oid_list）。 */
    public List<Map<String, Object>> oidBindingsOf(List<Long> uids) {
        if (uids == null || uids.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT w.uid, w.oid FROM sys_user_ws w JOIN sys_workspace s ON s.id = w.oid "
                        + "WHERE w.uid IN (").append(placeholders(uids.size())).append(")")
                .append(" ORDER BY s.name, s.create_time");
        return jdbc.queryForList(sql.toString(), uids.toArray());
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.USER_INFO_BY_ACCOUNT, allEntries = true)
    })
    public void insert(long id, String account, String name, String password, String email, long oid,
                       int status, long createTime, String language, int origin, String systemVariables) {
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, "
                        + "language, origin, system_variables) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, account, name, password, email, oid, status, createTime, language, origin, systemVariables);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.USER_INFO_BY_ACCOUNT, allEntries = true)
    })
    public void updateProfile(long id, String name, String email, int status, int origin, String systemVariables) {
        jdbc.update("UPDATE sys_user SET name = ?, email = ?, status = ?, origin = ?, system_variables = ? WHERE id = ?",
                name, email, status, origin, systemVariables, id);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.USER_INFO_BY_ACCOUNT, allEntries = true)
    })
    public void updateOid(long id, long oid) {
        jdbc.update("UPDATE sys_user SET oid = ? WHERE id = ?", oid, id);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.USER_INFO_BY_ACCOUNT, allEntries = true)
    })
    public void updateStatus(long id, int status) {
        jdbc.update("UPDATE sys_user SET status = ? WHERE id = ?", status, id);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.USER_INFO_BY_ACCOUNT, allEntries = true)
    })
    public void updateLanguage(long id, String language) {
        jdbc.update("UPDATE sys_user SET language = ? WHERE id = ?", language, id);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.USER_INFO_BY_ACCOUNT, allEntries = true)
    })
    public void updatePassword(long id, String md5Password) {
        jdbc.update("UPDATE sys_user SET password = ? WHERE id = ?", md5Password, id);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.USER_INFO_BY_ACCOUNT, allEntries = true)
    })
    public void deleteById(long id) {
        jdbc.update("DELETE FROM sys_user WHERE id = ?", id);
    }

    private static String sortColumn(String orderBy) {
        return SORT_COLUMNS.getOrDefault(orderBy == null ? "" : orderBy, "u.account");
    }

    /** 相关性排序派生表外层的排序列（不带表别名）。 */
    private static String sortColumnName(String orderBy) {
        String column = sortColumn(orderBy);
        return column.startsWith("u.") ? column.substring(2) : column;
    }

    static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
    }
}
