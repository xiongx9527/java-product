package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据权限配置（对齐 SQLBot 前端 api/permissions.ts；SQLBot 后端该组路由位于 xpack，
 * 此处按 D4「行为可用」重写）：规则组 list/save/delete。
 */
@RestController
@RequestMapping({"api/v1"})
public class DsPermissionController {

    private final DsPermissionService dsPermissionService;

    public DsPermissionController(DsPermissionService dsPermissionService) {
        this.dsPermissionService = dsPermissionService;
    }

    @PostMapping({"/ds_permission/list"})
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(dsPermissionService.list());
    }

    @PostMapping({"/ds_permission/save"})
    public Result<Long> save(@RequestBody Map<String, Object> data) {
        return Result.ok(dsPermissionService.save(data));
    }

    @PostMapping({"/ds_permission/delete/{id}"})
    public Result<Void> delete(@PathVariable long id) {
        dsPermissionService.delete(id);
        return Result.ok();
    }
}
