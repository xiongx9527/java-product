package com.xiongx9527.askdata.system.audit;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.service.WorkspaceService;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询（SQLBot /system/audit 位于 xpack，此处按 D4「行为可用」重写，
 * 响应结构与前端 views/system/audit 消费字段对齐）：
 * 分页/选项/导出。写入侧见 {@link AuditRecorder}。
 */
@Service
public class AuditLogService {
    @Autowired
    private MessageResolver messageResolver;

    private final AuditLogRepository auditLogRepository;

    private final CurrentUserService currentUserService;

    private final WorkspaceRepository workspaceRepository;

    public AuditLogService(AuditLogRepository auditLogRepository, CurrentUserService currentUserService,
                           WorkspaceRepository workspaceRepository) {
        this.auditLogRepository = auditLogRepository;
        this.currentUserService = currentUserService;
        this.workspaceRepository = workspaceRepository;
    }

    /** GET /system/audit/page/{current_page}/{page_size}。 */
    public PagerResult<Map<String, Object>> pager(int currentPage, int pageSize, String name,
                                                  List<String> optTypeList, List<String> uidList,
                                                  List<String> oidList, String logStatus,
                                                  Long timeFrom, Long timeTo) {
        currentUserService.requireAdmin();
        int size = Math.max(10, pageSize);
        List<Map<String, Object>> rows = auditLogRepository.page(currentPage, size, name, optTypeList, uidList,
                oidList, logStatus, timeFrom, timeTo);
        long total = auditLogRepository.count(name, optTypeList, uidList, oidList, logStatus, timeFrom, timeTo);
        return new PagerResult<>(toResults(rows), total, currentPage, size);
    }

    /** GET /system/audit/get_options（操作类型树选项；value/label 与前端 tree-select 对齐）。 */
    public List<Map<String, Object>> options() {
        currentUserService.requireAdmin();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : auditLogRepository.operationTypeOptions()) {
            String value = Rows.str(row, "opt_value");
            result.add(Rows.of("value", value, "label", typeLabel(value)));
        }
        return result;
    }

    /**
     * A22：操作类型返回本地化标签而非 code；未知 code 原样返回（不抛异常，保证列表可展示）。
     * 标签随请求 locale 变化（经 {@link MessageResolver}），不硬编码。
     */
    private String typeLabel(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        for (AuditOperationType type : AuditOperationType.values()) {
            if (type.code().equals(code)) {
                // 复用 SQLBot 对齐的操作类型键 i18n_audit.<code>（五语种齐全）
                return messageResolver.get("i18n_audit." + code);
            }
        }
        return code;
    }

    private List<Map<String, Object>> toResults(List<Map<String, Object>> rows) {
        Map<Long, String> workspaceNames = workspaceNamesOf(rows);
        String language = requestLanguage();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String status = Rows.str(row, "operation_status");
            long oid = Rows.lng(row, "oid", 0L);
            String workspaceName = oid == 0L ? null : workspaceNames.get(oid);
            result.add(Rows.of(
                    "id", String.valueOf(Rows.lng(row, "id", 0L)),
                    "operation_type", Rows.str(row, "operation_type"),
                    "operation_type_name", typeLabel(Rows.str(row, "operation_type")),
                    "operation_detail_info", detailInfo(row),
                    "user_name", Rows.str(row, "user_name"),
                    "oid_name", WorkspaceService.localizeWorkspaceName(workspaceName, language),
                    "operation_status", status,
                    "operation_status_name", "success".equals(status) ? messageResolver.get("audit.status.success") : messageResolver.get("audit.status.fail"),
                    "ip_address", Rows.str(row, "ip_address"),
                    "create_time", Rows.lng(row, "create_time", 0L),
                    "module", Rows.str(row, "module"),
                    "remark", Rows.str(row, "remark"),
                    "resource_name", Rows.str(row, "resource_name"),
                    "error_message", Rows.str(row, "error_message")));
        }
        return result;
    }

    /**
     * A22：操作详情列必须是真实详情。优先取写入侧记录的 {@code operation_detail}；
     * 历史行为空时按「本地化操作类型 + 资源名」组合，再退化为真实请求方法与路径，
     * 不返回空值或占位符。
     */
    private String detailInfo(Map<String, Object> row) {
        String detail = Rows.str(row, "operation_detail");
        if (hasText(detail)) {
            return detail;
        }
        String typeName = typeLabel(Rows.str(row, "operation_type"));
        String resourceName = Rows.str(row, "resource_name");
        if (hasText(resourceName)) {
            return typeName == null ? resourceName : typeName + ": " + resourceName;
        }
        String path = Rows.str(row, "request_path");
        if (hasText(path)) {
            String method = Rows.str(row, "request_method");
            return hasText(method) ? method + " " + path : path;
        }
        return typeName;
    }

    /** 批量解析本页日志的 oid → 工作空间名称（A22：oid_name 不返回数字 id）。 */
    private Map<Long, String> workspaceNamesOf(List<Map<String, Object>> rows) {
        java.util.Set<Long> oids = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            long oid = Rows.lng(row, "oid", 0L);
            if (oid != 0L) {
                oids.add(oid);
            }
        }
        return workspaceRepository.namesOf(oids);
    }

    /** 当前请求语言（用于工作空间内置名本地化）；locale 不可用时回退用户设置。 */
    private String requestLanguage() {
        java.util.Locale locale = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        String tag = locale == null ? "" : locale.toLanguageTag();
        if (tag.isEmpty() || "und".equals(tag)) {
            return Rows.str(currentUserService.currentUser(), "language");
        }
        return tag;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** GET /system/audit/export（xlsx 流）。 */
    public void export(HttpServletResponse response, String name, List<String> optTypeList, List<String> uidList,
                       List<String> oidList, String logStatus, Long timeFrom, Long timeTo) throws IOException {
        currentUserService.requireAdmin();
        List<Map<String, Object>> results = toResults(auditLogRepository.all(name, optTypeList, uidList, oidList,
                logStatus, timeFrom, timeTo));
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column(messageResolver.get("audit.export.col.type"), "operation_type_name"));
        columns.add(new ExcelUtils.Column(messageResolver.get("audit.export.col.detail"), "operation_detail_info"));
        columns.add(new ExcelUtils.Column(messageResolver.get("audit.export.col.user"), "user_name"));
        columns.add(new ExcelUtils.Column(messageResolver.get("audit.export.col.workspace"), "oid_name"));
        columns.add(new ExcelUtils.Column(messageResolver.get("audit.export.col.status"), "operation_status_name"));
        columns.add(new ExcelUtils.Column(messageResolver.get("audit.export.col.ip"), "ip_address"));
        columns.add(new ExcelUtils.Column(messageResolver.get("audit.export.col.time"), "create_time"));
        byte[] bytes = excelBytes(columns, results);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"audit.xlsx\"");
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        }
    }

    private byte[] excelBytes(List<ExcelUtils.Column> columns, List<Map<String, Object>> rows) throws IOException {
        List<ExcelUtils.MapStringArray> adapted = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            adapted.add(key -> {
                Object value = row.get(key);
                return value == null ? null : String.valueOf(value);
            });
        }
        return ExcelUtils.writeXlsx(columns, adapted);
    }

    /** 前端多值筛选参数解析：{@code a__b} → [a, b]（对齐 audit 页 configParams 拼接格式）。 */
    public static List<String> splitMulti(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return new ArrayList<>(Arrays.asList(value.split("__")));
    }

    /** 前端时间区间解析：{@code start__end}（毫秒）。 */
    public static Long[] splitTimeRange(String value) {
        if (value == null || value.isEmpty()) {
            return new Long[]{null, null};
        }
        String[] parts = value.split("__");
        Long from = parseLong(parts.length > 0 ? parts[0] : null);
        Long to = parseLong(parts.length > 1 ? parts[1] : null);
        return new Long[]{from, to};
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
