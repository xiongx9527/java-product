package com.xiongx9527.askdata.system.sso;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.web.TokenVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * 外部 IdP 登录端点（A7）：/api/v1/sso/{type}/authorize 跳转授权，
 * /api/v1/sso/{type}/callback 处理回调、自动开通本地账号并签发 token。
 */
@RestController
@RequestMapping("/api/v1/sso")
public class ExternalIdpController {

    private final ExternalIdpService idpService;
    private final ExternalIdpConfigProvider configProvider;
    private final LocalAccountLinker linker;

    private final IdpStateStore stateStore;

    public ExternalIdpController(ExternalIdpService idpService,
                                 ExternalIdpConfigProvider configProvider,
                                 LocalAccountLinker linker,
                                 IdpStateStore stateStore,
                                 com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver) {
        this.idpService = idpService;
        this.configProvider = configProvider;
        this.linker = linker;
        this.stateStore = stateStore;
        this.messageResolver = messageResolver;
    }

    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    /**
     * 跳转 IdP 授权页：state 由服务端签发（一次性、限时），不再由调用方传入，
     * 回调必须带回同一个 state，否则拒绝（防登录 CSRF）。
     */
    @GetMapping("/{type}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String type) {
        IdpType idpType = parse(type);
        ExternalIdpConfig cfg = configProvider.get(idpType);
        if (cfg == null || !cfg.isEnabled()) {
            throw new BadRequestException(messageResolver.get("idp.not.configured", type));
        }
        String state = stateStore.issue(idpType);
        String url = idpService.authorizationUrl(idpType, cfg, state);
        return ResponseEntity.status(302).location(URI.create(url)).build();
    }

    @GetMapping("/{type}/callback")
    public Result<TokenVO> callback(@PathVariable String type,
                                    @RequestParam Map<String, String> params) {
        IdpType idpType = parse(type);
        ExternalIdpConfig cfg = configProvider.get(idpType);
        if (cfg == null || !cfg.isEnabled()) {
            throw new BadRequestException(messageResolver.get("idp.not.configured", type));
        }
        // 先校验 state：未知/已使用/过期/协议不匹配一律拒绝
        try {
            stateStore.consume(idpType, params.get("state"));
        } catch (IllegalStateException e) {
            throw new BadRequestException(e.getMessage());
        }
        IdpCallback cb = IdpCallback.fromQuery(params);
        ExternalIdentity identity = idpService.resolveIdentity(idpType, cfg, cb,
                IdpHttpClient.defaultClient());
        long uid = linker.link(identity);
        StpUtil.login(uid);
        return Result.ok(new TokenVO(StpUtil.getTokenValue()));
    }

    private IdpType parse(String type) {
        try {
            return IdpType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(messageResolver.get("idp.type.unsupported", type));
        }
    }
}
