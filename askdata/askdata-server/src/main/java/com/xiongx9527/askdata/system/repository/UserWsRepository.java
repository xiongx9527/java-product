package com.xiongx9527.askdata.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户×工作空间绑定表（sys_user_ws）JDBC 访问层。
 * 表结构移植自 SQLBot alembic 001（字段名不变）。
 */
@Repository
public class UserWsRepository {

    private final JdbcTemplate jdbc;

    public UserWsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> find(long uid, long oid) {
        return jdbc.queryForList("SELECT id, uid, oid, weight FROM sys_user_ws WHERE uid = ? AND oid = ? LIMIT 1",
                uid, oid).stream().findFirst();
    }

    public int weightOf(long uid, long oid) {
        return find(uid, oid).map(row -> ((Number) row.get("weight")).intValue()).orElse(0);
    }

    public List<Long> oidsOf(long uid) {
        return jdbc.queryForList("SELECT oid FROM sys_user_ws WHERE uid = ?", Long.class, uid);
    }

    public void insert(long id, long uid, long oid, int weight) {
        jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (?, ?, ?, ?)", id, uid, oid, weight);
    }

    public void updateWeight(long uid, long oid, int weight) {
        jdbc.update("UPDATE sys_user_ws SET weight = ? WHERE uid = ? AND oid = ?", weight, uid, oid);
    }

    public void delete(long uid, long oid) {
        jdbc.update("DELETE FROM sys_user_ws WHERE uid = ? AND oid = ?", uid, oid);
    }

    public void deleteBatch(List<Long> uids, long oid) {
        if (uids == null || uids.isEmpty()) {
            return;
        }
        String placeholders = UserRepository.placeholders(uids.size());
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(oid);
        args.addAll(uids);
        jdbc.update("DELETE FROM sys_user_ws WHERE oid = ? AND uid IN (" + placeholders + ")", args.toArray());
    }

    public void deleteAllForUser(long uid) {
        jdbc.update("DELETE FROM sys_user_ws WHERE uid = ?", uid);
    }

    public void deleteAllForWorkspace(long oid) {
        jdbc.update("DELETE FROM sys_user_ws WHERE oid = ?", oid);
    }

    public List<Long> uidsOfWorkspace(long oid) {
        return jdbc.queryForList("SELECT uid FROM sys_user_ws WHERE oid = ?", Long.class, oid);
    }

    /** /system/workspace/uws/pager：工作空间成员分页（排除超管 uid=1）。 */
    public List<Map<String, Object>> pagerRows(long oid, String keyword, int offset, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT u.id, u.account, u.name, u.email, u.status, u.create_time, u.oid, w.weight "
                        + "FROM sys_user u JOIN sys_user_ws w ON u.id = w.uid WHERE w.oid = ? AND u.id <> 1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(oid);
        appendKeyword(sql, args, keyword);
        sql.append(" ORDER BY u.account, u.create_time LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long pagerCount(long oid, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM sys_user u JOIN sys_user_ws w ON u.id = w.uid WHERE w.oid = ? AND u.id <> 1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(oid);
        appendKeyword(sql, args, keyword);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /** uws/option/pager：尚未绑定到该工作空间的用户（排除超管）。 */
    public List<Map<String, Object>> optionPagerRows(long oid, String keyword, int offset, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT u.id, u.account, u.name FROM sys_user u WHERE u.id <> 1 AND NOT EXISTS "
                        + "(SELECT 1 FROM sys_user_ws w WHERE w.uid = u.id AND w.oid = ?)");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(oid);
        appendOptionKeyword(sql, args, keyword, true);
        sql.append(" ORDER BY u.account, u.create_time LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long optionPagerCount(long oid, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM sys_user u WHERE u.id <> 1 AND NOT EXISTS "
                        + "(SELECT 1 FROM sys_user_ws w WHERE w.uid = u.id AND w.oid = ?)");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(oid);
        appendOptionKeyword(sql, args, keyword, true);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /** uws/option：按账号/姓名精确匹配、未绑定该工作空间的单个用户。 */
    public Optional<Map<String, Object>> optionUser(String keyword, long oid) {
        return jdbc.queryForList(
                "SELECT u.id, u.account, u.name FROM sys_user u WHERE u.id <> 1 AND (u.account = ? OR u.name = ?) "
                        + "AND NOT EXISTS (SELECT 1 FROM sys_user_ws w WHERE w.uid = u.id AND w.oid = ?) LIMIT 1",
                keyword, keyword, oid).stream().findFirst();
    }

    private void appendKeyword(StringBuilder sql, java.util.List<Object> args, String keyword) {
        if (keyword != null && !keyword.isEmpty()) {
            String pattern = "%" + UserRepository.escapeLike(keyword.toLowerCase()) + "%";
            sql.append(" AND (LOWER(u.account) LIKE ? ESCAPE '\\' OR LOWER(u.name) LIKE ? ESCAPE '\\'"
                    + " OR LOWER(u.email) LIKE ? ESCAPE '\\')");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
    }

    private void appendOptionKeyword(StringBuilder sql, java.util.List<Object> args, String keyword, boolean fuzzy) {
        if (keyword != null && !keyword.isEmpty()) {
            String pattern = "%" + UserRepository.escapeLike(keyword.toLowerCase()) + "%";
            sql.append(" AND (LOWER(u.account) LIKE ? ESCAPE '\\' OR LOWER(u.name) LIKE ? ESCAPE '\\')");
            args.add(pattern);
            args.add(pattern);
        }
    }
}
