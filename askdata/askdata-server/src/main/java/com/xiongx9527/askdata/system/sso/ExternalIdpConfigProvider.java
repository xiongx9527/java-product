package com.xiongx9527.askdata.system.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 外部 IdP 配置中心（A7）：由 askdata.sso.providers.<TYPE>.* 注入，
 * 例如 askdata.sso.providers.OIDC.enabled=true 与 authorize-url/token-url/user-info-url/client-id/client-secret/redirect-uri。
 */
@Component
@ConfigurationProperties(prefix = "askdata.sso")
public class ExternalIdpConfigProvider {

    private Map<IdpType, ExternalIdpConfig> providers = new HashMap<>();

    public Map<IdpType, ExternalIdpConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<IdpType, ExternalIdpConfig> providers) {
        this.providers = providers;
    }

    public ExternalIdpConfig get(IdpType type) {
        return providers.get(type);
    }
}
