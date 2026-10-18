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
 * skeleton 占位端点：登录闭环（A3）所需的少量工作台首屏依赖，
 * 返回最小可用形状，避免前端首屏 404 弹错。
 * 后续子任务（system-foundation / chat-core）以真实实现替换。
 */
@RestController
@RequestMapping({"api/v1"})
public class SkeletonStubController {

    /** /user/ws（SQLBot Whitelist 语义：登录用户的工作空间选项） */
    @GetMapping({"/user/ws"})
    public Result<List<Map<String, Object>>> userWs() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("id", 1L);
        ws.put("uid", 1L);
        ws.put("oid", 1L);
        ws.put("name", "i18n_default_workspace");
        ws.put("weight", 100);
        list.add(ws);
        return Result.ok(list);
    }

    /** /chat/list（chat-core 子任务替换；skeleton 返回空会话列表） */
    @GetMapping({"/chat/list"})
    public Result<List<Map<String, Object>>> chatList() {
        return Result.ok(new ArrayList<>());
    }

    /** /system/parameter/chat（system-foundation 子任务替换；skeleton 返回空参数集） */
    @GetMapping({"/system/parameter/chat"})
    public Result<List<Map<String, Object>>> parameterChat() {
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
