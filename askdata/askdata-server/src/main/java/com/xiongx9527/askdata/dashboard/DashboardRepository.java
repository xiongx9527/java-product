package com.xiongx9527.askdata.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 仪表盘 core_dashboard 表 JDBC 访问层。
 * 表结构移植自 SQLBot alembic 009_1/011（字段名不变；create_time/update_time 为秒级时间戳）。
 */
@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String LIST_COLUMNS = "id, name, type, node_type, pid, create_time";

    private static final String COLUMNS = "id, name, pid, workspace_id, org_id, level, node_type, type, "
            + "canvas_style_data, component_data, canvas_view_info, mobile_layout, status, self_watermark_status, "
            + "sort, create_time, create_by, update_time, update_by, remark, source, delete_flag, delete_time, "
            + "delete_by, version, content_id, check_version";

    /** 资源树列表（对齐 SQLBot list_resource：按工作空间+创建人过滤）。 */
    public List<Map<String, Object>> list(String workspaceId, String createBy, String nodeType) {
        StringBuilder sql = new StringBuilder("SELECT " + LIST_COLUMNS + " FROM core_dashboard WHERE "
                + "workspace_id = ? AND create_by = ?");
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.add(createBy);
        if (nodeType != null && !nodeType.isEmpty()) {
            sql.append(" AND node_type = ?");
            args.add(nodeType);
        }
        sql.append(" ORDER BY create_time DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Optional<Map<String, Object>> findById(String id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM core_dashboard WHERE id = ?", id).stream().findFirst();
    }

    /** 名称查重（对齐 SQLBot validate_name）。 */
    public long countByName(String workspaceId, String createBy, String name, String excludeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM core_dashboard WHERE workspace_id = ? AND create_by = ? AND name = ?");
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.add(createBy);
        args.add(name);
        if (excludeId != null && !excludeId.isEmpty()) {
            sql.append(" AND id <> ?");
            args.add(excludeId);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public void insert(String id, String name, String pid, String workspaceId, String orgId, Integer level,
                       String nodeType, String type, String canvasStyleData, String componentData,
                       String canvasViewInfo, Integer status, String createBy, long createTime) {
        jdbc.update("INSERT INTO core_dashboard (id, name, pid, workspace_id, org_id, level, node_type, type, "
                        + "canvas_style_data, component_data, canvas_view_info, status, create_by, create_time, "
                        + "version, content_id, check_version, mobile_layout, self_watermark_status, sort, "
                        + "delete_flag) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 3, '0', '1', 0, 0, 0, 0)",
                id, name, pid, workspaceId, orgId, level, nodeType, type, canvasStyleData, componentData,
                canvasViewInfo, status == null ? 1 : status, createBy, createTime);
    }

    public void updateName(String id, String name, String updateBy, long updateTime) {
        jdbc.update("UPDATE core_dashboard SET name = ?, update_by = ?, update_time = ? WHERE id = ?",
                name, updateBy, updateTime, id);
    }

    public void updateCanvas(String id, String name, String componentData, String canvasStyleData,
                             String canvasViewInfo, String updateBy, long updateTime) {
        jdbc.update("UPDATE core_dashboard SET name = ?, component_data = ?, canvas_style_data = ?, "
                        + "canvas_view_info = ?, update_by = ?, update_time = ? WHERE id = ?",
                name, componentData, canvasStyleData, canvasViewInfo, updateBy, updateTime, id);
    }

    public void updatePid(String id, String pid, String updateBy, long updateTime) {
        jdbc.update("UPDATE core_dashboard SET pid = ?, update_by = ?, update_time = ? WHERE id = ?",
                pid, updateBy, updateTime, id);
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM core_dashboard WHERE id = ?", id);
    }

    /** 创建人/更新人名称（load_resource 的 create_name/update_name）。 */
    public Map<Long, String> userNames(List<Long> uids) {
        Map<Long, String> result = new HashMap<>();
        if (uids == null || uids.isEmpty()) {
            return result;
        }
        StringBuilder sql = new StringBuilder("SELECT id, name FROM sys_user WHERE id IN (");
        for (int i = 0; i < uids.size(); i++) {
            sql.append(i > 0 ? ",?" : "?");
        }
        sql.append(")");
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), uids.toArray())) {
            Object id = row.get("id");
            if (id instanceof Number) {
                result.put(((Number) id).longValue(), row.get("name") == null ? null : String.valueOf(row.get("name")));
            }
        }
        return result;
    }

    public Map<Long, String> userNamesOf(String... createByValues) {
        List<Long> uids = new ArrayList<>();
        for (String value : createByValues) {
            try {
                if (value != null && !value.isEmpty()) {
                    uids.add(Long.parseLong(value));
                }
            } catch (NumberFormatException ignored) {
                // 非数字 create_by（历史数据）忽略
            }
        }
        return userNames(uids);
    }
}
