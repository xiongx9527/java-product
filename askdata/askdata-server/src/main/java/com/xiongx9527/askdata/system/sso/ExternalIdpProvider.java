package com.xiongx9527.askdata.system.sso;

/**
 * 外部 IdP 协议适配（A7）：每种协议实现授权跳转 URL 与回调身份解析。
 * 解析过程仅依赖可 Mock 的 {@link IdpHttpClient}，便于以 mock IdP 验证。
 */
public interface ExternalIdpProvider {

    IdpType type();

    /** 构造引导用户跳转到 IdP 的授权 URL。 */
    String authorizationUrl(ExternalIdpConfig cfg, String state);

    /** 用回调参数经 IdP 换取并解析出外部身份。 */
    ExternalIdentity resolveIdentity(ExternalIdpConfig cfg, IdpCallback callback, IdpHttpClient http);
}
