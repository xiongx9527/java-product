package com.xiongx9527.askdata.system.authentication;

import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SAML2 配置端点（A25）：补齐前端 {@code views/system/authentication/SAML2Editor.vue} 调用的
 * {@code /setting/authentication/info/saml}、{@code /save/saml}、{@code /validate/saml2}。
 *
 * <p>SQLBot 这三个路由位于 xpack（{@code /setting/authentication/...}），askdata 按 D4「行为可用」
 * 在核心实现。前端 {@code VITE_API_BASE_URL=/api/v1}，故后端映射为
 * {@code /api/v1/setting/authentication/...}。</p>
 */
@RestController
@RequestMapping({"api/v1/setting/authentication"})
public class Saml2SettingsController {

    private final Saml2SettingsService saml2SettingsService;

    public Saml2SettingsController(Saml2SettingsService saml2SettingsService) {
        this.saml2SettingsService = saml2SettingsService;
    }

    /** GET /setting/authentication/info/saml：当前 SAML2 配置（未配置时返回空默认体）。 */
    @GetMapping({"/info/saml"})
    public Result<Map<String, Object>> info() {
        return Result.ok(saml2SettingsService.info());
    }

    /** POST /setting/authentication/save/saml：校验必填项并持久化。 */
    @AuditLog(module = AuditModule.SETTING, operation = AuditOperationType.UPDATE)
    @PostMapping({"/save/saml"})
    public Result<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(saml2SettingsService.save(body));
    }

    /** POST /setting/authentication/validate/saml2：校验证书与元数据（{@code data} 为 "true"/"false"）。 */
    @PostMapping({"/validate/saml2"})
    public Result<Map<String, Object>> validate(@RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(saml2SettingsService.validate(body));
    }
}
