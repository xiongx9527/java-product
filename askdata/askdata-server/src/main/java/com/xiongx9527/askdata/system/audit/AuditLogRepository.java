package com.xiongx9527.askdata.system.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计日志 sys_logs 表查询访问层。
 * 表结构移植自 SQLBot alembic 055/057/068（字段名不变）。
 */
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbc;

    public AuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 分页查询（对齐 SQLBot 前端 audit 页筛选参数）：
     * name=用户名称关键词；opt_type_list/uid_list/oid_list/log_status 支持多值（__ 分隔）；
     * time_range 为 start__end（毫秒时间戳）。
     */
    public List<Map<String, Object>> page(int pageNum, int pageSize, String name, List<String> optTypeList,
                                          List<String> uidList, List<String> oidList, String logStatus,
                                          Long timeFrom, Long timeTo) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, operation_type, operation_detail, user_id, user_name, operation_status, oid, "
                        + "ip_address, create_time, module, remark, resource_id, resource_name, error_message, "
                        + "request_method, request_path "
                        + "FROM sys_logs WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (name != null && !name.isEmpty()) {
            sql.append(" AND user_name LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(name) + "%");
        }
        if (optTypeList != null && !optTypeList.isEmpty()) {
            sql.append(" AND operation_type IN (").append(placeholders(optTypeList.size())).append(")");
            args.addAll(optTypeList);
        }
        if (uidList != null && !uidList.isEmpty()) {
            sql.append(" AND user_id IN (").append(placeholders(uidList.size())).append(")");
            args.addAll(uidList);
        }
        if (oidList != null && !oidList.isEmpty()) {
            sql.append(" AND oid IN (").append(placeholders(oidList.size())).append(")");
            args.addAll(oidList);
        }
        if (logStatus != null && !logStatus.isEmpty()) {
            sql.append(" AND operation_status = ?");
            args.add(logStatus);
        }
        if (timeFrom != null) {
            sql.append(" AND create_time >= ?");
            args.add(timeFrom);
        }
        if (timeTo != null) {
            sql.append(" AND create_time <= ?");
            args.add(timeTo);
        }
        sql.append(" ORDER BY create_time DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long count(String name, List<String> optTypeList, List<String> uidList, List<String> oidList,
                      String logStatus, Long timeFrom, Long timeTo) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sys_logs WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (name != null && !name.isEmpty()) {
            sql.append(" AND user_name LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(name) + "%");
        }
        if (optTypeList != null && !optTypeList.isEmpty()) {
            sql.append(" AND operation_type IN (").append(placeholders(optTypeList.size())).append(")");
            args.addAll(optTypeList);
        }
        if (uidList != null && !uidList.isEmpty()) {
            sql.append(" AND user_id IN (").append(placeholders(uidList.size())).append(")");
            args.addAll(uidList);
        }
        if (oidList != null && !oidList.isEmpty()) {
            sql.append(" AND oid IN (").append(placeholders(oidList.size())).append(")");
            args.addAll(oidList);
        }
        if (logStatus != null && !logStatus.isEmpty()) {
            sql.append(" AND operation_status = ?");
            args.add(logStatus);
        }
        if (timeFrom != null) {
            sql.append(" AND create_time >= ?");
            args.add(timeFrom);
        }
        if (timeTo != null) {
            sql.append(" AND create_time <= ?");
            args.add(timeTo);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /** 导出用全量查询（与分页同筛选，不分页）。 */
    public List<Map<String, Object>> all(String name, List<String> optTypeList, List<String> uidList,
                                         List<String> oidList, String logStatus, Long timeFrom, Long timeTo) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, operation_type, operation_detail, user_id, user_name, operation_status, oid, "
                        + "ip_address, create_time, module, remark, resource_id, resource_name, error_message, "
                        + "request_method, request_path "
                        + "FROM sys_logs WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (name != null && !name.isEmpty()) {
            sql.append(" AND user_name LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(name) + "%");
        }
        if (optTypeList != null && !optTypeList.isEmpty()) {
            sql.append(" AND operation_type IN (").append(placeholders(optTypeList.size())).append(")");
            args.addAll(optTypeList);
        }
        if (uidList != null && !uidList.isEmpty()) {
            sql.append(" AND user_id IN (").append(placeholders(uidList.size())).append(")");
            args.addAll(uidList);
        }
        if (oidList != null && !oidList.isEmpty()) {
            sql.append(" AND oid IN (").append(placeholders(oidList.size())).append(")");
            args.addAll(oidList);
        }
        if (logStatus != null && !logStatus.isEmpty()) {
            sql.append(" AND operation_status = ?");
            args.add(logStatus);
        }
        if (timeFrom != null) {
            sql.append(" AND create_time >= ?");
            args.add(timeFrom);
        }
        if (timeTo != null) {
            sql.append(" AND create_time <= ?");
            args.add(timeTo);
        }
        sql.append(" ORDER BY create_time DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 操作类型选项（get_options；与前端 tree-select 的 value/label 契约一致；value 为 H2 保留字，用 opt_value 别名）。 */
    public List<Map<String, Object>> operationTypeOptions() {
        return jdbc.queryForList("SELECT DISTINCT operation_type AS opt_value FROM sys_logs "
                + "WHERE operation_type IS NOT NULL ORDER BY operation_type");
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
