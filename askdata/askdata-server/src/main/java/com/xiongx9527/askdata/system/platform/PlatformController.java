package com.xiongx9527.askdata.system.platform;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.authentication.AuthenticationRepository;
import com.xiongx9527.askdata.system.authentication.AuthenticationService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业平台端点（对齐 SQLBot xpack {@code sqlbot_xpack/platform/api.py}）。
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET /system/platform}：平台卡片列表（前端 {@code views/system/platform/index.vue} 消费，
 *       逐项读 {@code id/type/name/config/enable/valid}，并按 {@code type < 9} 过滤）；</li>
 *   <li>{@code POST /system/platform/user/sync}：批量同步企业 IM 用户
 *       （前端 {@code api/system.ts} 的 {@code modelApi.userSync}）。</li>
 * </ul>
 *
 * <p>注意：平台卡片的配置存 {@code sys_authentication}（type 6..9 对应企业微信/钉钉/飞书/LarkSuite），
 * 与前端 {@code PlatformForm.vue} 的 {@code busiMapping} 一致。</p>
 */
@RestController
@RequestMapping({"api/v1"})
public class PlatformController {

    private final AuthenticationRepository authenticationRepository;

    private final PlatformSyncService platformSyncService;

    private final CurrentUserService currentUserService;

    private final EnterprisePlatformService enterprisePlatformService;

    private final AuthenticationService authenticationService;

    public PlatformController(AuthenticationRepository authenticationRepository,
                              PlatformSyncService platformSyncService,
                              CurrentUserService currentUserService,
                              EnterprisePlatformService enterprisePlatformService,
                              AuthenticationService authenticationService) {
        this.authenticationRepository = authenticationRepository;
        this.platformSyncService = platformSyncService;
        this.currentUserService = currentUserService;
        this.enterprisePlatformService = enterprisePlatformService;
        this.authenticationService = authenticationService;
    }

    /** GET /system/platform：平台卡片列表（type &gt;= 6 的企业 IM 平台）。 */
    @GetMapping({"/system/platform"})
    public Result<List<Map<String, Object>>> list() {
        currentUserService.requireAdmin();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : authenticationRepository.list()) {
            if (Rows.intg(row, "type", 0) < 6) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Rows.lng(row, "id", 0L));
            item.put("type", Rows.intg(row, "type", 0));
            item.put("name", Rows.str(row, "name"));
            item.put("config", Rows.str(row, "config"));
            item.put("enable", Rows.bool(row, "enable", false));
            item.put("valid", Rows.bool(row, "valid", false));
            result.add(item);
        }
        return Result.ok(result);
    }

    /**
     * GET /system/platform/client/{origin}：平台客户端信息（A30，公共路径）。
     *
     * <p>返回 {@code sys_authentication} 中该平台的真实配置（{@code client_id}/{@code corpid}/
     * {@code agent_id}/{@code enable}/{@code valid}），登录页扫码组件据此调第三方 SDK；
     * 平台未配置时 {@code client_id=null, enable=false}（前端提示"平台未配置或已禁用"）。</p>
     */
    @GetMapping({"/system/platform/client/{origin}"})
    public Result<Map<String, Object>> client(@PathVariable int origin) {
        return Result.ok(enterprisePlatformService.clientInfo(origin));
    }

    /**
     * POST /system/platform/org/{id}：平台组织/用户树（A33）。
     *
     * <p>前端 {@code api/system.ts} 的 {@code platform(id, lazy, pid)}；返回
     * {@code {tree:[{id, name, options:{is_user, email}, children?}]}}。未配置/已禁用时抛出
     * 带平台名的可读错误。</p>
     */
    @PostMapping({"/system/platform/org/{id}"})
    public Result<Map<String, Object>> org(@PathVariable int id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        boolean lazy = body != null && Boolean.parseBoolean(String.valueOf(body.get("lazy")));
        Object pid = body == null ? null : body.get("pid");
        return Result.ok(enterprisePlatformService.orgTree(id, lazy, pid == null ? null : String.valueOf(pid)));
    }

    /**
     * POST /system/platform/sso/{type}：企业 IM 扫码登录（A31，公共路径）。
     *
     * <p>前端 {@code Handler.third_party_authentication} 在 URL {@code state} 命中平台标记时
     * 把回调参数 POST 到这里；与 {@code /system/authentication/sso/{type}} 共用同一条
     * 「平台 code → 平台用户 → 本地用户 → 签发 token」链路。平台未配置或已禁用时返回可读错误。</p>
     */
    @PostMapping({"/system/platform/sso/{type}"})
    public Result<Map<String, Object>> sso(@PathVariable int type,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        Map<String, Object> user = authenticationService.ssoLogin(type, body == null ? new LinkedHashMap<>() : body, request);
        StpUtil.login(Rows.lng(user, "id", 0L));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access_token", StpUtil.getTokenValue());
        result.put("exp", StpUtil.getTokenTimeout());
        Map<String, Object> platformInfo = new LinkedHashMap<>();
        platformInfo.put("origin", type);
        platformInfo.put("account", Rows.str(user, "account"));
        result.put("platform_info", platformInfo);
        return Result.ok(result);
    }

    /** POST /system/platform/user/sync：批量同步企业平台用户到本地 sys_user（幂等）。 */
    @PostMapping({"/system/platform/user/sync"})
    public Result<Map<String, Object>> userSync(@RequestBody Map<String, Object> body) {
        return Result.ok(platformSyncService.sync(body));
    }
}
