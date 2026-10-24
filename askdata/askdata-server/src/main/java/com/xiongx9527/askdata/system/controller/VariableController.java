package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.service.VariableService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统变量（对齐 SQLBot apps/system/api/variable_api.py）。
 * F-05：保存/删除接入审计（MODULE=SETTING，对齐 SQLBot system_variables 的 setting 模块口径）。
 */
@RestController
@RequestMapping({"api/v1"})
public class VariableController {

    private final VariableService variableService;

    public VariableController(VariableService variableService) {
        this.variableService = variableService;
    }

    @AuditLog(module = AuditModule.SETTING, operation = AuditOperationType.CREATE_OR_UPDATE)
    @PostMapping({"/sys_variable/save"})
    public Result<Boolean> save(@RequestBody Map<String, Object> variable) {
        return Result.ok(variableService.save(variable));
    }

    @AuditLog(module = AuditModule.SETTING, operation = AuditOperationType.DELETE)
    @PostMapping({"/sys_variable/delete"})
    public Result<Void> delete(@RequestBody List<Long> ids) {
        variableService.delete(ids);
        return Result.ok();
    }

    @PostMapping({"/sys_variable/listAll"})
    public Result<List<Map<String, Object>>> listAll(@RequestBody(required = false) Map<String, Object> variable) {
        return Result.ok(variableService.listAll(variable));
    }

    @PostMapping({"/sys_variable/listPage/{pageNum}/{pageSize}"})
    public Result<PageResult<Map<String, Object>>> listPage(@PathVariable int pageNum, @PathVariable int pageSize,
                                                            @RequestBody(required = false) Map<String, Object> variable) {
        return Result.ok(variableService.listPage(pageNum, pageSize, variable));
    }
}
