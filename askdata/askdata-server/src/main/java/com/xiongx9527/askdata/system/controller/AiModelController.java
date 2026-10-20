package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.service.AiModelService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * AI 模型（对齐 SQLBot apps/system/api/aimodel.py）：
 * CRUD/默认/连通检查/工作空间映射仅管理员；list/by_ws 为 ws_admin。
 */
@RestController
@RequestMapping({"api/v1"})
public class AiModelController {

    private final AiModelService aiModelService;

    public AiModelController(AiModelService aiModelService) {
        this.aiModelService = aiModelService;
    }

    @GetMapping({"/system/aimodel"})
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword) {
        return Result.ok(aiModelService.list(keyword));
    }

    @PostMapping({"/system/aimodel"})
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> creator) {
        return Result.ok(aiModelService.create(creator));
    }

    @PutMapping({"/system/aimodel"})
    public Result<Void> update(@RequestBody Map<String, Object> editor) {
        aiModelService.update(editor);
        return Result.ok();
    }

    /** SQLBot check_default：无默认模型时返回错误。 */
    @GetMapping({"/system/aimodel/default"})
    public Result<Void> checkDefault() {
        aiModelService.requireDefaultModel();
        return Result.ok();
    }

    @PutMapping({"/system/aimodel/default/{id}"})
    public Result<Void> setDefault(@PathVariable long id) {
        aiModelService.setDefault(id);
        return Result.ok();
    }

    @GetMapping({"/system/aimodel/list/by_ws"})
    public Result<List<Map<String, Object>>> listByWs() {
        return Result.ok(aiModelService.listByWs());
    }

    @GetMapping({"/system/aimodel/{id}"})
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(aiModelService.editor(id));
    }

    @DeleteMapping({"/system/aimodel/{id}"})
    public Result<Void> delete(@PathVariable long id) {
        aiModelService.delete(id);
        return Result.ok();
    }

    /**
     * 连通检查：NDJSON 流式响应（每行 {@code {"content":".."}} 或 {@code {"error":".."}}），
     * 与前端 {@code modelApi.check} → reader 按行 JSON.parse 的消费方式一致。
     */
    @PostMapping({"/system/aimodel/status", "/system/aimodel/status/"})
    public void status(@RequestBody Map<String, Object> info, HttpServletResponse response) throws IOException {
        aiModelService.streamStatus(info, response);
    }

    @GetMapping({"/system/aimodel/{id}/ws_mapping"})
    public Result<List<String>> wsMapping(@PathVariable long id) {
        return Result.ok(aiModelService.wsMapping(id));
    }

    @PutMapping({"/system/aimodel/{id}/ws_mapping"})
    public Result<List<String>> replaceWsMapping(@PathVariable long id, @RequestBody List<String> wsIds) {
        return Result.ok(aiModelService.replaceWsMapping(id, wsIds));
    }

    @PostMapping({"/system/aimodel/{id}/ws_mapping"})
    public Result<List<String>> addWsMapping(@PathVariable long id, @RequestBody List<String> wsIds) {
        return Result.ok(aiModelService.addWsMapping(id, wsIds));
    }

    @DeleteMapping({"/system/aimodel/{id}/ws_mapping"})
    public Result<List<String>> deleteWsMapping(@PathVariable long id, @RequestBody List<String> wsIds) {
        return Result.ok(aiModelService.deleteWsMapping(id, wsIds));
    }
}
