package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 占位端点（license 已由 system/license/LicenseController 的真实实现接管）。
 * /user/ws 与 /system/parameter/chat 已由 system-foundation 的真实实现接管；
 * /chat/* 已由 chat-core 子任务的 {@code ChatController} 接管（本类不再占位，避免路由冲突）。
 */
@RestController
@RequestMapping({"api/v1"})
public class SkeletonStubController {

}
