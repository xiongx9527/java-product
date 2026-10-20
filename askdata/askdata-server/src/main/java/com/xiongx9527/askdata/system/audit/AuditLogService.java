package com.xiongx9527.askdata.system.audit;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
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

    public AuditLogService(AuditLogRepository auditLogRepository, CurrentUserService currentUserService) {
        this.auditLogRepository = auditLogRepository;
        this.currentUserService = currentUserService;
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

    private String typeLabel(String code) {
        for (AuditOperationType type : AuditOperationType.values()) {
            if (type.code().equals(code)) {
                return code;
            }
        }
        return code;
    }

    private List<Map<String, Object>> toResults(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String status = Rows.str(row, "operation_status");
            result.add(Rows.of(
                    "id", String.valueOf(Rows.lng(row, "id", 0L)),
                    "operation_type", Rows.str(row, "operation_type"),
                    "operation_type_name", Rows.str(row, "operation_type"),
                    "operation_detail_info", Rows.str(row, "operation_detail"),
                    "user_name", Rows.str(row, "user_name"),
                    "oid_name", Rows.str(row, "oid"),
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
