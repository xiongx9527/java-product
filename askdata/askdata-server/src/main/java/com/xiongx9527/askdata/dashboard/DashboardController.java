package com.xiongx9527.askdata.dashboard;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘（对齐 SQLBot apps/dashboard/api/dashboard_api.py，prefix=/dashboard）：
 * 资源树/加载/创建/更新/画布/名称校验/删除/移动。
 */
@RestController
@RequestMapping({"api/v1"})
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @PostMapping({"/dashboard/list_resource"})
    public Result<List<Map<String, Object>>> listResource(@RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(dashboardService.listResource(body == null ? new java.util.HashMap<>() : body));
    }

    @PostMapping({"/dashboard/load_resource"})
    public Result<Map<String, Object>> loadResource(@RequestBody Map<String, Object> body) {
        return Result.ok(dashboardService.loadResource(body));
    }

    @PostMapping({"/dashboard/create_resource"})
    public Result<Map<String, Object>> createResource(@RequestBody Map<String, Object> body) {
        return Result.ok(dashboardService.createResource(body));
    }

    @PostMapping({"/dashboard/update_resource"})
    public Result<Map<String, Object>> updateResource(@RequestBody Map<String, Object> body) {
        return Result.ok(dashboardService.updateResource(body));
    }

    @PostMapping({"/dashboard/create_canvas"})
    public Result<Map<String, Object>> createCanvas(@RequestBody Map<String, Object> body) {
        return Result.ok(dashboardService.createCanvas(body));
    }

    @PostMapping({"/dashboard/update_canvas"})
    public Result<Map<String, Object>> updateCanvas(@RequestBody Map<String, Object> body) {
        return Result.ok(dashboardService.updateCanvas(body));
    }

    @PostMapping({"/dashboard/check_name"})
    public Result<Boolean> checkName(@RequestBody Map<String, Object> body) {
        return Result.ok(dashboardService.checkName(body));
    }

    @DeleteMapping({"/dashboard/delete_resource/{resource_id}/{name}"})
    public Result<Boolean> deleteResource(@PathVariable("resource_id") String resourceId,
                                          @PathVariable String name) {
        return Result.ok(dashboardService.deleteResource(resourceId));
    }

    @DeleteMapping({"/dashboard/move_resource/{id}"})
    public Result<Void> moveResource(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        dashboardService.moveResource(id, body == null ? new java.util.HashMap<>() : body);
        return Result.ok();
    }
}
