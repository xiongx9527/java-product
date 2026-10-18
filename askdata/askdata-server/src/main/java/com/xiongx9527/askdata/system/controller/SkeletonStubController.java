package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 占位端点：仅保留 chat-core 子任务接管前的首屏依赖与 D4 的 license 兜底。
 * - /chat/list：chat-core 子任务替换；
 * - /system/license*：D4 社区版只读信息，不实现校验（保持 stub）。
 * /user/ws 与 /system/parameter/chat 已由 system-foundation 的真实实现接管。
 */
@RestController
@RequestMapping({"api/v1"})
public class SkeletonStubController {

    /** /chat/list（chat-core 子任务替换；当前返回空会话列表） */
    @GetMapping({"/chat/list"})
    public Result<List<Map<String, Object>>> chatList() {
        return Result.ok(new ArrayList<>());
    }

    /** /system/license（D4：社区版兜底，不实现校验） */
    @GetMapping({"/system/license"})
    public Result<Map<String, Object>> license() {
        Map<String, Object> license = new LinkedHashMap<>();
        license.put("status", "community");
        license.put("license", null);
        return Result.ok(license);
    }

    /** /system/license/version（D4：社区版只读信息） */
    @GetMapping({"/system/license/version"})
    public Result<String> licenseVersion() {
        return Result.ok("community");
    }
}
