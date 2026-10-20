package com.xiongx9527.askdata.prompt;

import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 自定义提示词（对齐 SQLBot 前端 api/prompt.ts 契约；SQLBot 该组路由位于 xpack，
 * 此处按 D4「行为可用」重写）：分页/CRUD/导出/模板/导入。
 */
@RestController
@RequestMapping({"api/v1"})
public class CustomPromptController {

    private final CustomPromptService customPromptService;

    public CustomPromptController(CustomPromptService customPromptService) {
        this.customPromptService = customPromptService;
    }

    @PostMapping({"/system/custom_prompt/{type}/page/{current_page}/{page_size}"})
    public Result<PagerResult<Map<String, Object>>> pager(@PathVariable String type,
                                                          @PathVariable("current_page") int currentPage,
                                                          @PathVariable("page_size") int pageSize,
                                                          @RequestBody(required = false) Map<String, Object> filter) {
        return Result.ok(customPromptService.pager(type, currentPage, pageSize,
                filter == null ? new java.util.HashMap<>() : filter));
    }

    @PutMapping({"/system/custom_prompt"})
    public Result<Long> createOrUpdate(@RequestBody Map<String, Object> info) {
        return Result.ok(customPromptService.createOrUpdate(info));
    }

    @DeleteMapping({"/system/custom_prompt"})
    public Result<Void> delete(@RequestBody List<Long> idList) {
        customPromptService.delete(idList);
        return Result.ok();
    }

    @GetMapping({"/system/custom_prompt/{id}"})
    public Result<Map<String, Object>> editor(@PathVariable long id) {
        return Result.ok(customPromptService.editor(id));
    }

    @PostMapping({"/system/custom_prompt/{type}/export"})
    public void export(HttpServletResponse response, @PathVariable String type,
                       @RequestBody(required = false) Map<String, Object> filter) throws IOException {
        customPromptService.export(response, type, filter == null ? new java.util.HashMap<>() : filter);
    }

    @GetMapping({"/system/custom_prompt/template"})
    public void template(HttpServletResponse response) throws IOException {
        customPromptService.template(response);
    }

    @PostMapping({"/system/custom_prompt/{type}/uploadExcel"})
    public Result<Map<String, Object>> uploadExcel(@PathVariable String type,
                                                   @RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        return Result.ok(customPromptService.uploadExcel(type, file.getBytes(),
                filename == null ? "" : filename));
    }
}
