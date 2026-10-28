package com.xiongx9527.askdata.scheduled;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 定时问数任务 API（A10）。
 */
@RestController
@RequestMapping({"api/v1"})
public class ScheduledTaskController {

    private final ScheduledTaskService service;

    public ScheduledTaskController(ScheduledTaskService service) {
        this.service = service;
    }

    @PostMapping({"/scheduled_task/list"})
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(service.list());
    }

    @GetMapping({"/scheduled_task/{id}"})
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping({"/scheduled_task/save"})
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        return Result.ok(service.save(body));
    }

    @PostMapping({"/scheduled_task/delete/{id}"})
    public Result<Void> delete(@PathVariable long id) {
        service.delete(id);
        return Result.ok();
    }

    @PostMapping({"/scheduled_task/start/{id}"})
    public Result<Map<String, Object>> start(@PathVariable long id) {
        return Result.ok(service.start(id));
    }

    @PostMapping({"/scheduled_task/stop/{id}"})
    public Result<Map<String, Object>> stop(@PathVariable long id) {
        return Result.ok(service.stop(id));
    }

    @PostMapping({"/scheduled_task/run/{id}"})
    public Result<Map<String, Object>> runNow(@PathVariable long id) {
        return Result.ok(service.runNow(id));
    }

    @PostMapping({"/scheduled_task/retry/{runId}"})
    public Result<Map<String, Object>> retry(@PathVariable long runId) {
        return Result.ok(service.retry(runId));
    }

    @PostMapping({"/scheduled_task/runs/{taskId}"})
    public Result<List<Map<String, Object>>> runs(@PathVariable long taskId) {
        return Result.ok(service.runs(taskId));
    }

    /**
     * 下载某次运行的导出文件（集群：导出落共享文件存储，任意实例均可服务）。
     */
    @GetMapping({"/scheduled_task/run/{runId}/export"})
    public org.springframework.http.ResponseEntity<byte[]> downloadExport(@PathVariable long runId) {
        ScheduledTaskService.ExportFile file = service.exportFile(runId);
        String lower = file.filename().toLowerCase(java.util.Locale.ROOT);
        String contentType = lower.endsWith(".csv") ? "text/csv"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename() + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .body(file.content());
    }
}

