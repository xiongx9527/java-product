package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.service.ApiKeyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API Key（对齐 SQLBot apps/system/api/apikey.py）：当前用户维度的列表/创建/启停/删除。
 * F-05：创建/启停/删除接入审计（MODULE=API_KEY，资源名取 sys_apikey.access_key）。
 */
@RestController
@RequestMapping({"api/v1"})
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping({"/system/apikey"})
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(apiKeyService.list());
    }

    @AuditLog(module = AuditModule.API_KEY, operation = AuditOperationType.CREATE)
    @PostMapping({"/system/apikey"})
    public Result<Long> create() {
        return Result.ok(apiKeyService.create());
    }

    @AuditLog(module = AuditModule.API_KEY, operation = AuditOperationType.UPDATE_STATUS)
    @PutMapping({"/system/apikey/status"})
    public Result<Void> status(@RequestBody Map<String, Object> dto) {
        apiKeyService.changeStatus(Rows.lng(dto, "id", 0L), Rows.bool(dto, "status", true));
        return Result.ok();
    }

    @AuditLog(module = AuditModule.API_KEY, operation = AuditOperationType.DELETE)
    @DeleteMapping({"/system/apikey/{id}"})
    public Result<Void> delete(@PathVariable long id) {
        apiKeyService.delete(id);
        return Result.ok();
    }
}
