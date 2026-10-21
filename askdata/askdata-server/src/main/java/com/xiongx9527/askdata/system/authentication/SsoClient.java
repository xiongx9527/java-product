package com.xiongx9527.askdata.system.authentication;

import java.util.Map;

/**
 * SSO 认证源的统一客户端契约。
 *
 * <p>让 CAS / OIDC / LDAP / SAML 四类协议对上层（{@code AuthenticationService}）呈现同一形状：
 * 授权跳转、回调换用户信息、连通性校验。</p>
 */
public interface SsoClient {

    /**
     * 生成授权跳转 URL（前端 {@code GET /system/authentication/login/{type}} 返回该 URL，
     * 前端 {@code window.location.href = res}）。
     *
     * @param config   认证源配置
     * @param callback 回调参数（state / code）
     */
    String authorizeUrl(Map<String, Object> config, SsoCallback callback);

    /**
     * 回调换取用户信息。
     *
     * @return 归一后的本地用户字段（account / name / email / platform_uid）；失败返回 null
     */
    Map<String, Object> exchange(Map<String, Object> config, SsoCallback callback);

    /**
     * 连通性校验（前端 {@code PATCH /system/authentication/status} 的 "测试连接"）。
     *
     * @return true 表示该认证源配置可用
     */
    boolean validate(Map<String, Object> config);

    /** SSO 回调参数（code / state / ticket 等）。 */
    class SsoCallback {
        private final String code;
        private final String state;
        private final String ticket;

        public SsoCallback(String code, String state, String ticket) {
            this.code = code;
            this.state = state;
            this.ticket = ticket;
        }

        public String getCode() {
            return code;
        }

        public String getState() {
            return state;
        }

        public String getTicket() {
            return ticket;
        }
    }
}
