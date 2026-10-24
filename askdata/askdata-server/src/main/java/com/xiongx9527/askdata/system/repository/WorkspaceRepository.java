package com.xiongx9527.askdata.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作空间表（sys_workspace）JDBC 访问层。
 * 表结构移植自 SQLBot alembic 001（字段名不变）。
 */
@Repository
public class WorkspaceRepository {

    private final JdbcTemplate jdbc;

    public WorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "id, name, create_time";

    public List<Map<String, Object>> list() {
        // 对齐 SQLBot：按名称升序
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_workspace ORDER BY name");
    }

    /** 指定用户在空间选择器中的可选工作空间（uid=1 为全部，对齐 SQLBot user_ws_list）。 */
    public List<Map<String, Object>> listOfUser(long uid) {
        if (uid == 1L) {
            return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_workspace ORDER BY name, create_time");
        }
        return jdbc.queryForList(
                "SELECT s.id, s.name, s.create_time FROM sys_workspace s JOIN sys_user_ws w ON w.oid = s.id "
                        + "WHERE w.uid = ? ORDER BY s.name, s.create_time", uid);
    }

    /** 按名称精确匹配工作空间（用户导入的 workspace 列解析用）。 */
    public Optional<Map<String, Object>> findByName(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT id, name, create_time FROM sys_workspace WHERE name = ? LIMIT 1", name)
                .stream().findFirst();
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_workspace WHERE id = ?", id).stream().findFirst();
    }

    public void insert(long id, String name, long createTime) {
        jdbc.update("INSERT INTO sys_workspace (id, name, create_time) VALUES (?, ?, ?)", id, name, createTime);
    }

    public void update(long id, String name) {
        jdbc.update("UPDATE sys_workspace SET name = ? WHERE id = ?", name, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM sys_workspace WHERE id = ?", id);
    }
}
