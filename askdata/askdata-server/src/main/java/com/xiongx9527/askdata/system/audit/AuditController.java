package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 审计日志（SQLBot /system/audit 位于 xpack，此处按 D4「行为可用」重写；
 * 契约对齐前端 api/audit.ts 与 views/system/audit）：
 * 分页/选项/导出。多值筛选参数以 {@code __} 分隔（与前端 configParams 一致）。
 */
@RestController
@RequestMapping({"api/v1"})
public class AuditController {

    private final AuditLogService auditLogService;

    public AuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping({"/system/audit/page/{current_page}/{page_size}"})
    public Result<PagerResult<Map<String, Object>>> pager(@PathVariable("current_page") int currentPage,
                                                          @PathVariable("page_size") int pageSize,
                                                          @RequestParam(required = false) String name,
                                                          @RequestParam(required = false) String opt_type_list,
                                                          @RequestParam(required = false) String uid_list,
                                                          @RequestParam(required = false) String oid_list,
                                                          @RequestParam(required = false) String log_status,
                                                          @RequestParam(required = false) String time_range) {
        Long[] range = AuditLogService.splitTimeRange(time_range);
        return Result.ok(auditLogService.pager(currentPage, pageSize, name,
                AuditLogService.splitMulti(opt_type_list), AuditLogService.splitMulti(uid_list),
                AuditLogService.splitMulti(oid_list), log_status, range[0], range[1]));
    }

    @GetMapping({"/system/audit/get_options"})
    public Result<List<Map<String, Object>>> getOptions() {
        return Result.ok(auditLogService.options());
    }

    @GetMapping({"/system/audit/export"})
    public void export(HttpServletResponse response,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) String opt_type_list,
                       @RequestParam(required = false) String uid_list,
                       @RequestParam(required = false) String oid_list,
                       @RequestParam(required = false) String log_status,
                       @RequestParam(required = false) String time_range) throws IOException {
        Long[] range = AuditLogService.splitTimeRange(time_range);
        auditLogService.export(response, name, AuditLogService.splitMulti(opt_type_list),
                AuditLogService.splitMulti(uid_list), AuditLogService.splitMulti(oid_list), log_status,
                range[0], range[1]);
    }
}
