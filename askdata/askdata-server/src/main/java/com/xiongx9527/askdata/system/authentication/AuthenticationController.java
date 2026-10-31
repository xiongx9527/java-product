package com.xiongx9527.askdata.system.authentication;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSO 认证源端点（对齐 SQLBot xpack {@code sqlbot_xpack/authentication/api.py}）。
 *
 * <p>端点（前端契约逐字一致）：</p>
 * <ul>
 *   <li>{@code GET /system/authentication}：认证源列表（前端按 name 组成卡片）；</li>
 *   <li>{@code GET /system/authentication/{id}}：按 id 取认证源（各 Editor.vue 加载配置）；</li>
 *   <li>{@code POST /system/authentication}：新增；</li>
 *   <li>{@code PUT /system/authentication}：更新；</li>
 *   <li>{@code PATCH /system/authentication/enable}：启停；</li>
 *   <li>{@code PATCH /system/authentication/status}：连通性校验（"测试连接"）；</li>
 *   <li>{@code GET /system/authentication/status}：全部认证源的启停/校验状态；</li>
 *   <li>{@code GET /system/authentication/platform/status}：企业平台（type&gt;=6）状态（公共路径）；</li>
 *   <li>{@code GET /system/authentication/login/{type}}：授权跳转 URL（公共路径）；</li>
 *   <li>{@code POST /system/authentication/sso/{type}}：SSO 登录（公共路径）。</li>
 * </ul>
 */
@RestController
@RequestMapping({"api/v1"})
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    private final CurrentUserService currentUserService;

    public AuthenticationController(AuthenticationService authenticationService,
                                    CurrentUserService currentUserService) {
        this.authenticationService = authenticationService;
        this.currentUserService = currentUserService;
    }

    @GetMapping({"/system/authentication"})
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(authenticationService.list());
    }

    @GetMapping({"/system/authentication/status"})
    public Result<List<Map<String, Object>>> status() {
        return Result.ok(authenticationService.list());
    }

    @GetMapping({"/system/authentication/{id}"})
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(authenticationService.getById(id));
    }

    @AuditLog(module = AuditModule.SETTING, operation = AuditOperationType.CREATE)
    @PostMapping({"/system/authentication"})
    public Result<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        return Result.ok(authenticationService.add(body));
    }

    @AuditLog(module = AuditModule.SETTING, operation = AuditOperationType.UPDATE)
    @PutMapping({"/system/authentication"})
    public Result<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        return Result.ok(authenticationService.update(body));
    }

    @AuditLog(module = AuditModule.SETTING, operation = AuditOperationType.UPDATE_STATUS)
    @PatchMapping({"/system/authentication/enable"})
    public Result<Void> enable(@RequestBody Map<String, Object> body) {
        long id = longOf(body.get("id"), 0L);
        boolean enable = boolOf(body.get("enable"), false);
        authenticationService.switchEnable(id, enable);
        return Result.ok();
    }

    @PatchMapping({"/system/authentication/status"})
    public Result<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        return Result.ok(authenticationService.validate(body));
    }

    /** GET /system/authentication/platform/status：企业平台状态（公共路径）。 */
    @GetMapping({"/system/authentication/platform/status"})
    public Result<List<Map<String, Object>>> platformStatus() {
        return Result.ok(authenticationService.platformStatus());
    }

    /** GET /system/authentication/login/{type}：第三方授权跳转 URL（公共路径）。 */
    @GetMapping({"/system/authentication/login/{type}"})
    public Result<String> login(@PathVariable int type) {
        return Result.ok(authenticationService.toLogin(type));
    }

    /**
     * POST /system/authentication/sso/{type}：SSO 登录（公共路径）。
     *
     * <p>响应：{@code {access_token, exp, platform_info}}，前端 {@code Handler.vue} 消费
     * {@code res.access_token} / {@code res.exp} / {@code res.platform_info}。</p>
     */
    @PostMapping({"/system/authentication/sso/{type}"})
    public Result<Map<String, Object>> sso(@PathVariable int type, @RequestBody Map<String, Object> body) {
        Map<String, Object> user = authenticationService.ssoLogin(type, body);
        StpUtil.login(longOf(user.get("id"), 0L));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access_token", StpUtil.getTokenValue());
        result.put("exp", StpUtil.getTokenTimeout());
        Map<String, Object> platformInfo = new LinkedHashMap<>();
        platformInfo.put("origin", type);
        platformInfo.put("account", user.get("account"));
        result.put("platform_info", platformInfo);
        return Result.ok(result);
    }

    private static long longOf(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return "true".equalsIgnoreCase(text) || "1".equals(text);
            }
        }
        return fallback;
    }
}
