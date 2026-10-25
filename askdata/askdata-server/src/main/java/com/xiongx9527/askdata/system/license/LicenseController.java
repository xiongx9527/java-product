package com.xiongx9527.askdata.system.license;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 许可证端点（A28；对齐 SQLBot xpack {@code /system/license}，前端
 * {@code web/src/api/license.ts} 逐字一致）。
 *
 * <ul>
 *   <li>{@code GET /system/license}：当前状态（{@code valid/invalid/expired/community}）+ 明细；</li>
 *   <li>{@code POST /system/license}：ws admin 上传 {@code {license_key}} 并持久化生效；</li>
 *   <li>{@code GET /system/license/version}：真实版本号（不再固定 community）。</li>
 * </ul>
 */
@RestController
@RequestMapping({"api/v1"})
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    /** GET /system/license（公共路径：登录页/关于页读取）。 */
    @GetMapping({"/system/license"})
    public Result<Map<String, Object>> license() {
        return Result.ok(licenseService.status());
    }

    /** POST /system/license：上传许可证（ws admin）。 */
    @PostMapping({"/system/license"})
    public Result<Map<String, Object>> upload(@RequestBody(required = false) Map<String, Object> body) {
        Object key = body == null ? null : body.get("license_key");
        return Result.ok(licenseService.upload(key == null ? null : String.valueOf(key)));
    }

    /** GET /system/license/version：真实版本。 */
    @GetMapping({"/system/license/version"})
    public Result<String> licenseVersion() {
        return Result.ok(licenseService.version());
    }
}
