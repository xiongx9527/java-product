package com.xiongx9527.askdata.system.sso;

import java.util.EnumMap;
import java.util.Map;

/**
 * 外部 IdP 服务编排（A7）：按协议路由到对应 {@link ExternalIdpProvider}，
 * 并暴露授权 URL 与回调身份解析。
 */
public class ExternalIdpService {

    private final Map<IdpType, ExternalIdpProvider> providers = new EnumMap<>(IdpType.class);

    public ExternalIdpService() {
        register(new OidcProvider());
        register(new CasProvider());
        register(new Saml2Provider());
        register(new WechatWorkProvider());
        register(new DingTalkProvider());
        register(new FeishuProvider());
        register(new LarkProvider());
    }

    public void register(ExternalIdpProvider provider) {
        providers.put(provider.type(), provider);
    }

    public String authorizationUrl(IdpType type, ExternalIdpConfig cfg, String state) {
        return require(type).authorizationUrl(cfg, state);
    }

    public ExternalIdentity resolveIdentity(IdpType type, ExternalIdpConfig cfg, IdpCallback callback,
                                           IdpHttpClient http) {
        return require(type).resolveIdentity(cfg, callback, http);
    }

    private ExternalIdpProvider require(IdpType type) {
        if (type == null) {
            throw new IllegalArgumentException("idp type is required");
        }
        ExternalIdpProvider p = providers.get(type);
        if (p == null) {
            throw new IllegalArgumentException("unsupported idp type: " + type);
        }
        return p;
    }
}
