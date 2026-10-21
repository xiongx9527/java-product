package com.xiongx9527.askdata.system.platform;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.authentication.AuthenticationRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public PlatformController(AuthenticationRepository authenticationRepository,
                              PlatformSyncService platformSyncService,
                              CurrentUserService currentUserService) {
        this.authenticationRepository = authenticationRepository;
        this.platformSyncService = platformSyncService;
        this.currentUserService = currentUserService;
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

    /** GET /system/platform/client/{origin}：平台客户端信息（公共路径）。 */
    @GetMapping({"/system/platform/client/{origin}"})
    public Result<Map<String, Object>> client(@PathVariable int origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("origin", origin);
        result.put("client_id", null);
        result.put("enable", false);
        return Result.ok(result);
    }

    /** POST /system/platform/user/sync：批量同步企业平台用户到本地 sys_user（幂等）。 */
    @PostMapping({"/system/platform/user/sync"})
    public Result<Map<String, Object>> userSync(@RequestBody Map<String, Object> body) {
        return Result.ok(platformSyncService.sync(body));
    }
}
