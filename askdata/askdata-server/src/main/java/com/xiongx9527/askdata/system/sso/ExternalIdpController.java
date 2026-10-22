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

    public ExternalIdpController(ExternalIdpService idpService,
                                 ExternalIdpConfigProvider configProvider,
                                 LocalAccountLinker linker) {
        this.idpService = idpService;
        this.configProvider = configProvider;
        this.linker = linker;
    }

    @GetMapping("/{type}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String type,
                                          @RequestParam(required = false) String state) {
        IdpType idpType = parse(type);
        ExternalIdpConfig cfg = configProvider.get(idpType);
        if (cfg == null || !cfg.isEnabled()) {
            throw new BadRequestException("idp not configured or disabled: " + type);
        }
        String url = idpService.authorizationUrl(idpType, cfg, state);
        return ResponseEntity.status(302).location(URI.create(url)).build();
    }

    @GetMapping("/{type}/callback")
    public Result<TokenVO> callback(@PathVariable String type,
                                    @RequestParam Map<String, String> params) {
        IdpType idpType = parse(type);
        ExternalIdpConfig cfg = configProvider.get(idpType);
        if (cfg == null || !cfg.isEnabled()) {
            throw new BadRequestException("idp not configured or disabled: " + type);
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
            throw new BadRequestException("unsupported idp type: " + type);
        }
    }
}
