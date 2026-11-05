package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.fw.plus.auth.social.AuthorizationUrlRequest;
import com.xiongx9527.fw.plus.auth.social.IdpConfig;
import com.xiongx9527.fw.plus.auth.social.SocialLoginProviderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部 IdP 服务编排（A7）：门面保留原方法签名，内部委托框架 fw-plus-auth-social 的 provider registry。
 *
 * <p>框架提供 9 类 IdP 能力（含 token 缓存与失效自愈、OIDC/SAML2 签名校验），product 仅做：
 * <ul>
 *   <li>配置适配：{@link ExternalIdpConfig} → 框架 {@link IdpConfig}（逐字段拷贝；企业级 provider
 *       用 config.getId() 拼 token 缓存 key，故以 type.name() 填充）；</li>
 *   <li>回调适配：{@link IdpCallback} 的 ticket/samlResponse/raw 映射进框架 parameters；</li>
 *   <li>账号命名空间化：以 {@code type.name().toLowerCase() + ":" + externalId} 归一，避免跨 IdP 碰撞。</li>
 * </ul>
 * 原自研 provider 已弃用，统一由框架实现。
 */
public class ExternalIdpService {

    /**
     * 账号命名空间前缀：保持与历史自研 provider 一致（saml:/wechat: 而非 saml2:/wechat_work:），
     * 以兼容已落库（LocalAccountLinker 绑定）的外部账号。
     */
    private static final Map<String, String> NAMESPACE = new LinkedHashMap<String, String>();
    static {
        NAMESPACE.put("OIDC", "oidc");
        NAMESPACE.put("CAS", "cas");
        NAMESPACE.put("SAML2", "saml");
        NAMESPACE.put("WECHAT_WORK", "wechat");
        NAMESPACE.put("DINGTALK", "dingtalk");
        NAMESPACE.put("FEISHU", "feishu");
        NAMESPACE.put("LARK", "lark");
        NAMESPACE.put("LDAP", "ldap");
        NAMESPACE.put("WECHAT_SCAN", "wechat_scan");
    }

    private final SocialLoginProviderRegistry registry;

    public ExternalIdpService(SocialLoginProviderRegistry registry) {
        this.registry = registry;
    }

    public String authorizationUrl(IdpType type, ExternalIdpConfig cfg, String state) {
        if (type == null) {
            throw new IllegalArgumentException("idp type is required");
        }
        IdpConfig fw = toFwConfig(type, cfg);
        AuthorizationUrlRequest req = new AuthorizationUrlRequest();
        req.setState(state);
        req.setRedirectUri(cfg.getRedirectUri());
        return registry.get(toFwType(type)).authorizationUrl(fw, req);
    }

    public ExternalIdentity resolveIdentity(IdpType type, ExternalIdpConfig cfg, IdpCallback callback,
                                           IdpHttpClient http) {
        if (type == null) {
            throw new IllegalArgumentException("idp type is required");
        }
        IdpConfig fw = toFwConfig(type, cfg);
        com.xiongx9527.fw.plus.auth.social.IdpCallback fwCb = toFwCallback(callback);
        // framework 内部走自带 SocialHttpUtils，http 参数忽略
        com.xiongx9527.fw.plus.auth.social.ExternalIdentity e =
                registry.get(toFwType(type)).resolveIdentity(fw, fwCb);
        String ns = NAMESPACE.get(type.name());
        if (ns == null) {
            ns = type.name().toLowerCase();
        }
        String externalId = e.getExternalId() != null ? e.getExternalId() : e.getAccount();
        return new ExternalIdentity(ns + ":" + externalId, e.getName(), e.getEmail(), externalId,
                e.getAttributes());
    }

    private IdpConfig toFwConfig(IdpType type, ExternalIdpConfig cfg) {
        IdpConfig fw = new IdpConfig();
        fw.setId(type.name()); // 企业级 provider 用其拼 token 缓存 key
        fw.setType(toFwType(type));
        fw.setDisplayName(cfg.getDisplayName());
        fw.setEnabled(cfg.isEnabled());
        fw.setClientId(cfg.getClientId());
        fw.setClientSecret(cfg.getClientSecret());
        fw.setRedirectUri(cfg.getRedirectUri());
        fw.setAuthorizeUrl(cfg.getAuthorizeUrl());
        fw.setTokenUrl(cfg.getTokenUrl());
        fw.setUserInfoUrl(cfg.getUserInfoUrl());
        fw.setLogoutUrl(cfg.getLogoutUrl());
        Map<String, String> extra = cfg.getExtra();
        fw.setExtra(extra == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(extra));
        return fw;
    }

    private com.xiongx9527.fw.plus.auth.social.IdpCallback toFwCallback(IdpCallback cb) {
        com.xiongx9527.fw.plus.auth.social.IdpCallback fw =
                new com.xiongx9527.fw.plus.auth.social.IdpCallback();
        fw.setCode(cb.getCode());
        fw.setState(cb.getState());
        Map<String, String> params = new LinkedHashMap<String, String>();
        if (cb.getTicket() != null) {
            params.put("ticket", cb.getTicket());
        }
        if (cb.getSamlResponse() != null) {
            params.put("SAMLResponse", cb.getSamlResponse());
        }
        if (cb.getRaw() != null) {
            params.put("raw", cb.getRaw());
        }
        fw.setParameters(params);
        return fw;
    }

    private com.xiongx9527.fw.plus.auth.social.IdpType toFwType(IdpType type) {
        return com.xiongx9527.fw.plus.auth.social.IdpType.valueOf(type.name());
    }
}
