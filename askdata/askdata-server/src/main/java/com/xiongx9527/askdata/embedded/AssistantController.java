package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.web.Result;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 小助手/嵌入应用（对齐 SQLBot apps/system/api/assistant.py，prefix=/system/assistant）：
 * 列表/高级应用/CRUD/外观配置/图片。
 */
@RestController
@RequestMapping({"api/v1"})
public class AssistantController {

    private final AssistantService assistantService;

    private final AssistantFileStorage fileStorage;

    public AssistantController(AssistantService assistantService, AssistantFileStorage fileStorage) {
        this.assistantService = assistantService;
        this.fileStorage = fileStorage;
    }

    @GetMapping({"/system/assistant"})
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(assistantService.list());
    }

    @GetMapping({"/system/assistant/advanced_application"})
    public Result<List<Map<String, Object>>> advancedApplication() {
        return Result.ok(assistantService.advancedApplications());
    }

    /**
     * Current assistant available datasources (aligns SQLBot GET /system/assistant/ds).
     *
     * <p>A17：{@code X-SQLBOT-ASSISTANT-ONLINE} 在运行时切换在线/离线通道；缺省 id 时按
     * 嵌入 token 的 {@code assistant_id} 解析助手。</p>
     */
    @GetMapping({"/system/assistant/ds"})
    public Result<List<Map<String, Object>>> datasources(
            @RequestParam(value = "id", required = false) Long id,
            @RequestHeader(value = "X-SQLBOT-ASSISTANT-ONLINE", required = false) String online) {
        return Result.ok(assistantService.datasources(id, online));
    }

    /** 兼容既有调用方（无在线/离线通道头）。 */
    public Result<List<Map<String, Object>>> datasources(Long id) {
        return datasources(id, null);
    }

    /**
     * 高级应用按请求校验宿主下发的动态数据源（A8）：不落库、不返回连接密钥。
     * body: {@code {assistant_id: 1, datasource: {type: "mysql", name: "...", configuration: {...}}}}
     */
    @PostMapping({"/system/assistant/dynamic_datasource"})
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> checkDynamicDatasource(@RequestBody Map<String, Object> body) {
        long assistantId = com.xiongx9527.askdata.common.util.Rows.lng(body, "assistant_id", 0L);
        Object config = body.get("datasource");
        Map<String, Object> hostConfig = config instanceof Map
                ? (Map<String, Object>) config : null;
        return Result.ok(assistantService.checkDynamicDatasource(assistantId, hostConfig));
    }

    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.CREATE)
    @PostMapping({"/system/assistant"})
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> creator) {
        return Result.ok(assistantService.create(creator));
    }

    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.UPDATE)
    @PutMapping({"/system/assistant"})
    public Result<Void> update(@RequestBody Map<String, Object> editor) {
        assistantService.update(editor);
        return Result.ok();
    }

    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.DELETE)
    @DeleteMapping({"/system/assistant/{id}"})
    public Result<Void> delete(@PathVariable long id) {
        assistantService.delete(id);
        return Result.ok();
    }

    /** 外观配置（对齐 SQLBot PATCH /system/assistant/ui；data JSON + files）。 */
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.UPDATE)
    @PatchMapping({"/system/assistant/ui"})
    public Result<Void> ui(@RequestParam("data") String data,
                           @RequestParam(value = "files", required = false) List<MultipartFile> files)
            throws Exception {
        assistantService.updateUi(data, files);
        return Result.ok();
    }

    /** 图片读取（对齐 SQLBot GET /system/assistant/picture/{file_id}）。 */
    @GetMapping({"/system/assistant/picture/{file_id}"})
    public ResponseEntity<Resource> picture(@PathVariable("file_id") String fileId) throws IOException {
        Resource resource = fileStorage.load(fileId);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, AssistantFileStorage.contentTypeOf(fileId))
                .body(resource);
    }
}
