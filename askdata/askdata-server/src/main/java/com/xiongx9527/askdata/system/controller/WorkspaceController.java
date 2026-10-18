package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.service.WorkspaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作空间与成员（对齐 SQLBot apps/system/api/workspace.py）：
 * CRUD 仅管理员；uws 成员接口为 ws_admin（管理员或空间管理员）。
 */
@RestController
@RequestMapping({"api/v1"})
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping({"/system/workspace"})
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(workspaceService.list());
    }

    @PostMapping({"/system/workspace"})
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> creator) {
        return Result.ok(workspaceService.create(creator));
    }

    @PutMapping({"/system/workspace"})
    public Result<Void> update(@RequestBody Map<String, Object> editor) {
        workspaceService.update(editor);
        return Result.ok();
    }

    @GetMapping({"/system/workspace/{id}"})
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(workspaceService.get(id));
    }

    @DeleteMapping({"/system/workspace/{id}"})
    public Result<Void> delete(@PathVariable long id) {
        workspaceService.delete(id);
        return Result.ok();
    }

    // ------------------------------------------------------------------
    // 成员（uws）
    // ------------------------------------------------------------------

    @GetMapping({"/system/workspace/uws/pager/{pageNum}/{pageSize}"})
    public Result<PageResult<Map<String, Object>>> memberPager(@PathVariable int pageNum, @PathVariable int pageSize,
                                                               @RequestParam(required = false) String keyword,
                                                               @RequestParam(required = false) Long oid) {
        return Result.ok(workspaceService.memberPager(pageNum, pageSize, keyword, oid));
    }

    @GetMapping({"/system/workspace/uws/option/pager/{pageNum}/{pageSize}"})
    public Result<PageResult<Map<String, Object>>> optionPager(@PathVariable int pageNum, @PathVariable int pageSize,
                                                               @RequestParam(defaultValue = "0") long oid,
                                                               @RequestParam(required = false) String keyword) {
        return Result.ok(workspaceService.optionPager(pageNum, pageSize, keyword, oid));
    }

    @GetMapping({"/system/workspace/uws/option"})
    public Result<Map<String, Object>> optionUser(@RequestParam(required = false) String keyword) {
        return Result.ok(workspaceService.optionUser(keyword));
    }

    @PostMapping({"/system/workspace/uws"})
    public Result<Void> uwsCreate(@RequestBody Map<String, Object> creator) {
        workspaceService.uwsCreate(creator);
        return Result.ok();
    }

    @PutMapping({"/system/workspace/uws"})
    public Result<Void> uwsUpdate(@RequestBody Map<String, Object> editor) {
        workspaceService.uwsUpdate(editor);
        return Result.ok();
    }

    @DeleteMapping({"/system/workspace/uws"})
    public Result<Void> uwsDelete(@RequestBody Map<String, Object> dto) {
        workspaceService.uwsDelete(dto);
        return Result.ok();
    }
}
