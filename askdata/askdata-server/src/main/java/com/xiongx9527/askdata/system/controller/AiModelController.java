package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.Rows;
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

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * AI 模型（对齐 SQLBot apps/system/api/aimodel.py）：
 * CRUD/默认/连通检查/工作空间映射仅管理员；list/by_ws 为 ws_admin。
 * F-05：写操作与管理动作全量接入审计（CREATE/UPDATE/DELETE/CREATE_OR_UPDATE + 默认设置）。
 */
@RestController
@RequestMapping({"api/v1"})
public class AiModelController {

    private final AiModelService aiModelService;

    private final com.xiongx9527.askdata.ai.EmbeddingIndexService embeddingIndexService;

    private final com.xiongx9527.askdata.system.service.CurrentUserService currentUserService;

    public AiModelController(AiModelService aiModelService,
                             com.xiongx9527.askdata.ai.EmbeddingIndexService embeddingIndexService,
                             com.xiongx9527.askdata.system.service.CurrentUserService currentUserService) {
        this.aiModelService = aiModelService;
        this.embeddingIndexService = embeddingIndexService;
        this.currentUserService = currentUserService;
    }

    /** 向量索引状态：是否待重建、进度与上次结果（管理员）。 */
    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.VIEW)
    @GetMapping({"/system/aimodel/embedding/status"})
    public Result<Map<String, Object>> embeddingStatus() {
        currentUserService.requireAdmin();
        return Result.ok(embeddingIndexService.status());
    }

    /** 一键重新向量：全空间强制重算；任务进行中时返回 started=false（管理员）。 */
    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.UPDATE)
    @PostMapping({"/system/aimodel/embedding/rebuild"})
    public Result<Map<String, Object>> embeddingRebuild(@RequestBody(required = false) Map<String, Object> body) {
        currentUserService.requireAdmin();
        String mode = body == null ? null : Rows.str(body, "mode");
        return Result.ok(embeddingIndexService.rebuild(mode));
    }

    /** 停止正在进行的向量重建：置停止信号，当前工作空间完成后标记 interrupted（管理员）。 */
    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.UPDATE)
    @PostMapping({"/system/aimodel/embedding/rebuild/stop"})
    public Result<Map<String, Object>> embeddingRebuildStop() {
        currentUserService.requireAdmin();
        return Result.ok(embeddingIndexService.stop());
    }

    /** 取消未完成的重建记录（释放「重建期间锁定默认向量模型」约束），管理员。 */
    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.UPDATE)
    @PostMapping({"/system/aimodel/embedding/rebuild/cancel"})
    public Result<Map<String, Object>> embeddingRebuildCancel() {
        currentUserService.requireAdmin();
        return Result.ok(embeddingIndexService.cancel());
    }

    @GetMapping({"/system/aimodel"})
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword) {
        return Result.ok(aiModelService.list(keyword));
    }

    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.CREATE)
    @PostMapping({"/system/aimodel"})
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> creator) {
        return Result.ok(aiModelService.create(creator));
    }

    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.UPDATE)
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

    /** 默认模型状态：区分「未配置」与「已配置但不可用」（前端 chat/ds 提示分流用）。 */
    @GetMapping({"/system/aimodel/default/status"})
    public Result<Map<String, Object>> defaultStatus() {
        return Result.ok(aiModelService.defaultStatus());
    }

    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.UPDATE)
    @PutMapping({"/system/aimodel/default/{id}"})
    public Result<Void> setDefault(@PathVariable long id) {
        aiModelService.setDefault(id);
        return Result.ok();
    }

    /** 列表内开关：仅切换「是否生效」（enable），约束与 update 一致（需已连通校验、向量需维度、默认不可停用）。 */
    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.UPDATE)
    @PutMapping({"/system/aimodel/{id}/enable"})
    public Result<Void> setEnable(@PathVariable long id, @RequestParam boolean enable) {
        aiModelService.setEnable(id, enable);
        return Result.ok();
    }

    /**
     * 上游模型列表拉取：代理请求 {@code /models}，返回 {@code {source, models[], error?}}。
     * source 为 remote / preset / none；preset 表示前端回退供应商静态清单。
     */
    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.UPDATE)
    @PostMapping({"/system/aimodel/models"})
    public Result<Map<String, Object>> models(@RequestBody Map<String, Object> body) {
        return Result.ok(aiModelService.listModels(body));
    }

    @GetMapping({"/system/aimodel/list/by_ws"})
    public Result<List<Map<String, Object>>> listByWs() {
        return Result.ok(aiModelService.listByWs());
    }

    @GetMapping({"/system/aimodel/{id}"})
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(aiModelService.editor(id));
    }

    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.DELETE)
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

    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.CREATE_OR_UPDATE)
    @PutMapping({"/system/aimodel/{id}/ws_mapping"})
    public Result<List<String>> replaceWsMapping(@PathVariable long id, @RequestBody List<String> wsIds) {
        return Result.ok(aiModelService.replaceWsMapping(id, wsIds));
    }

    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.ADD)
    @PostMapping({"/system/aimodel/{id}/ws_mapping"})
    public Result<List<String>> addWsMapping(@PathVariable long id, @RequestBody List<String> wsIds) {
        return Result.ok(aiModelService.addWsMapping(id, wsIds));
    }

    @AuditLog(module = AuditModule.AI_MODEL, operation = AuditOperationType.DELETE)
    @DeleteMapping({"/system/aimodel/{id}/ws_mapping"})
    public Result<List<String>> deleteWsMapping(@PathVariable long id, @RequestBody List<String> wsIds) {
        return Result.ok(aiModelService.deleteWsMapping(id, wsIds));
    }
}
