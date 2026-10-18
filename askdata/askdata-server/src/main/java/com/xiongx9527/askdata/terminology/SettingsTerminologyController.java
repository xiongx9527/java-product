package com.xiongx9527.askdata.terminology;

import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.common.web.Result;
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
 * 术语库 settings 命名空间（对齐 SQLBot 前端 api/setting.ts 与 api/auth.ts 的
 * /settings/terminology 契约；SQLBot 该路由位于 xpack，此处按 D4「行为可用」重写，
 * 与 /system/terminology 共用同一服务与数据表）。
 */
@RestController
@RequestMapping({"api/v1"})
public class SettingsTerminologyController {

    private final TerminologyService terminologyService;

    public SettingsTerminologyController(TerminologyService terminologyService) {
        this.terminologyService = terminologyService;
    }

    @GetMapping({"/settings/terminology/pager/{page_number}/{page_size}"})
    public Result<PagerResult<Map<String, Object>>> pager(@PathVariable("page_number") int pageNumber,
                                                          @PathVariable("page_size") int pageSize) {
        return Result.ok(terminologyService.pager(pageNumber, pageSize, null, null, null));
    }

    @PostMapping({"/settings/terminology"})
    public Result<Long> add(@RequestBody Map<String, Object> data) {
        return Result.ok(terminologyService.createOrUpdate(data));
    }

    @PutMapping({"/settings/terminology"})
    public Result<Long> edit(@RequestBody Map<String, Object> data) {
        return Result.ok(terminologyService.createOrUpdate(data));
    }

    @DeleteMapping({"/settings/terminology/{id}"})
    public Result<Void> delete(@PathVariable long id) {
        terminologyService.delete(java.util.Collections.singletonList(id));
        return Result.ok();
    }

    @GetMapping({"/settings/terminology/{id}"})
    public Result<Map<String, Object>> query(@PathVariable long id) {
        return Result.ok(terminologyService.editor(id));
    }
}
