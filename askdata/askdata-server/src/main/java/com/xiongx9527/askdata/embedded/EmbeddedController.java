package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * 嵌入应用管理（对齐 SQLBot 前端 api/embedded.ts 的 embeddedApi；SQLBot 该组路由位于
 * xpack，此处按 D4「行为可用」重写）：页面嵌入应用（type=4）分页/CRUD/密钥重置。
 */
@RestController
@RequestMapping({"api/v1"})
public class EmbeddedController {

    private final AssistantService assistantService;

    public EmbeddedController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @GetMapping({"/system/embedded/{page_num}/{page_size}"})
    public Result<com.xiongx9527.askdata.common.web.PageResult<Map<String, Object>>> pager(
            @PathVariable("page_num") int pageNum,
            @PathVariable("page_size") int pageSize,
            @RequestParam(required = false) String keyword) {
        return Result.ok(assistantService.pager(pageNum, pageSize, keyword));
    }

    @GetMapping({"/system/embedded/{id}"})
    public Result<Map<String, Object>> editor(@PathVariable long id) {
        return Result.ok(assistantService.editor(id));
    }

    @PostMapping({"/system/embedded"})
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> creator) {
        return Result.ok(assistantService.createEmbedded(creator));
    }

    @PutMapping({"/system/embedded"})
    public Result<Void> update(@RequestBody Map<String, Object> editor) {
        assistantService.updateEmbedded(editor);
        return Result.ok();
    }

    @DeleteMapping({"/system/embedded"})
    public Result<Void> delete(@RequestBody List<Long> idList) {
        assistantService.deleteEmbedded(idList);
        return Result.ok();
    }

    /** 密钥重置（对齐前端 embeddedApi.secret → PATCH /system/embedded/secret/{id}）。 */
    @PatchMapping({"/system/embedded/secret/{id}"})
    public Result<String> resetSecret(@PathVariable long id) {
        return Result.ok(assistantService.resetSecret(id));
    }
}
