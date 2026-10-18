package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 占位端点：仅保留 D4 的 license 兜底。
 * - /system/license*：D4 社区版只读信息，不实现校验（保持 stub）。
 * /user/ws 与 /system/parameter/chat 已由 system-foundation 的真实实现接管；
 * /chat/* 已由 chat-core 子任务的 {@code ChatController} 接管（本类不再占位，避免路由冲突）。
 */
@RestController
@RequestMapping({"api/v1"})
public class SkeletonStubController {

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
