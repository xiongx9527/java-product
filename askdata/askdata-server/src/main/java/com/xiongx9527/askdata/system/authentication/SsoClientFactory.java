package com.xiongx9527.askdata.system.authentication;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.config.AuthSource;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthDefaultRequest;
import me.zhyd.oauth.request.AuthRequest;

import java.util.Map;

/**
 * SSO 客户端工厂（brief D9：第三方授权统一走 JustAuth）。
 *
 * <p>JustAuth 内置各家公有 IdP 的协议实现，但企业内部的 CAS / OIDC / LDAP / SAML 端点是可配置的。
 * 这里按认证源类型分派：</p>
 * <ul>
 *   <li><b>OIDC（2）/ OAuth2（4）</b>：用 {@link GenericOAuth2Request}——JustAuth 的
 *       {@link AuthDefaultRequest} 模板，authorize / token / userinfo 三个端点全部来自认证源 config；</li>
 *   <li><b>CAS（1）</b>：CAS 协议不是 OAuth2，JustAuth 无对应实现；
 *       用 {@link CasSsoClient} 做 service ticket 校验 + 属性取回（CAS 2.0/3.0 协议）；</li>
 *   <li><b>LDAP（3）</b>：用 {@link LdapSsoClient} 做 bind + 用户属性查询（LDAP 协议）；</li>
 *   <li><b>SAML2（5）</b>：用 {@link Saml2SsoClient} 做断言解析（依赖 opensaml 运行时，缺失时降级报错）。</li>
 * </ul>
 */
public final class SsoClientFactory {

    private SsoClientFactory() {
    }

    /**
     * 构造认证源对应的 JustAuth 请求对象。
     *
     * @param type     认证源类型（见 {@link SsoAuthenticator} 的 TYPE_* 常量）
     * @param config   认证源配置
     * @param callback 回调参数
     */
    public static AuthRequest request(int type, Map<String, Object> config, AuthCallback callback) {
        switch (type) {
            case SsoAuthenticator.TYPE_OIDC:
            case SsoAuthenticator.TYPE_OAUTH2:
                return genericOAuth2(config);
            case SsoAuthenticator.TYPE_CAS:
            case SsoAuthenticator.TYPE_LDAP:
            case SsoAuthenticator.TYPE_SAML2:
                throw new UnsupportedOperationException(
                        "SSO type " + type + " does not use the JustAuth request flow");
            default:
                throw new UnsupportedOperationException("Unsupported SSO type: " + type);
        }
    }

    /**
     * 通用 OAuth2 / OIDC 请求（端点全部来自 config）。
     *
     * <p>JustAuth 的 {@code AuthDefaultRequest} 抽象类已经把「授权跳转 / code 换 token /
     * 拉 userinfo / 解析 JSON」四段流程的骨架写好了，抽象方法只有两个：
     * {@code getAccessToken} 与 {@code getUserInfo}；端点 URL 由 {@link AuthSource} 提供。
     * 这里把 config 里的端点包成一个 source，实现即可。</p>
     */
    static AuthRequest genericOAuth2(Map<String, Object> config) {
        AuthConfig authConfig = AuthConfig.builder()
                .clientId(str(config, "client_id"))
                .clientSecret(str(config, "client_secret"))
                .redirectUri(firstNonEmpty(str(config, "redirect_uri"), str(config, "redirect_url")))
                .ignoreCheckState(true)
                .build();
        GenericOAuth2Source source = new GenericOAuth2Source(config);
        return new GenericOAuth2Request(authConfig, source, config);
    }

    static String str(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 通用 OAuth2 / OIDC 的 JustAuth source（authorize / token / userinfo 端点可配置）。
     */
    static final class GenericOAuth2Source implements AuthSource {

        private final String authorizeUrl;
        private final String tokenUrl;
        private final String userInfoUrl;

        GenericOAuth2Source(Map<String, Object> config) {
            this.authorizeUrl = str(config, "authorize_url");
            this.tokenUrl = str(config, "token_url");
            this.userInfoUrl = str(config, "userinfo_url");
        }

        @Override
        public String authorize() {
            return authorizeUrl;
        }

        @Override
        public String accessToken() {
            return tokenUrl;
        }

        @Override
        public String userInfo() {
            return userInfoUrl;
        }

        @Override
        public String getName() {
            return "sqlbot-generic-oauth2";
        }

        @Override
        public Class<? extends AuthDefaultRequest> getTargetClass() {
            return GenericOAuth2Request.class;
        }
    }

    /**
     * 通用 OAuth2 / OIDC 请求实现。
     *
     * <p>token 端点按 {@code token_auth_method} 选 basic（Basic 头）或 form（body 传参）；
     * userinfo 端点按 {@code userinfo_auth_method} 选 header（Bearer）或 form。</p>
     */
    public static final class GenericOAuth2Request extends AuthDefaultRequest {

        /** 认证源配置（注意：父类的 config 字段是 AuthConfig，此处用 sourceConfig 区分）。 */
        private final Map<String, Object> sourceConfig;

        GenericOAuth2Request(AuthConfig authConfig, AuthSource source, Map<String, Object> config) {
            super(authConfig, source);
            this.sourceConfig = config;
        }

        @Override
        protected AuthToken getAccessToken(AuthCallback callback) {
            String code = callback.getCode();
            if (code == null || code.isEmpty()) {
                throw new IllegalStateException("Missing authorization code");
            }
            String method = str(sourceConfig, "token_auth_method");
            boolean basic = method == null || "basic".equalsIgnoreCase(method);
            String tokenUrl = accessTokenUrl(code);

            if (basic) {
                String credentials = config.getClientId() + ":" + config.getClientSecret();
                String encoded = java.util.Base64.getEncoder()
                        .encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                com.xkcoding.http.support.HttpHeader header = new com.xkcoding.http.support.HttpHeader();
                header.add("Authorization", "Basic " + encoded);
                header.add("Content-Type", "application/x-www-form-urlencoded");
                String response = new me.zhyd.oauth.utils.HttpUtils(config.getHttpConfig())
                        .post(tokenUrl, "", header).getBody();
                return parseToken(response);
            }
            // form 方式：client_id / client_secret 已在 accessTokenUrl 的 query 中
            String response = new me.zhyd.oauth.utils.HttpUtils(config.getHttpConfig())
                    .post(tokenUrl).getBody();
            return parseToken(response);
        }

        @Override
        protected AuthUser getUserInfo(AuthToken authToken) {
            String method = str(sourceConfig, "userinfo_auth_method");
            boolean form = "form".equalsIgnoreCase(method);
            String response = form
                    ? doPostUserInfo(authToken)
                    : doGetUserInfo(authToken);
            JSONObject json = JSON.parseObject(response);
            if (json == null) {
                throw new IllegalStateException("Invalid userinfo response");
            }
            return AuthUser.builder()
                    .uuid(firstNonEmpty(json.getString("sub"), json.getString("id"), json.getString("uuid"),
                            json.getString("user_id"), json.getString("openid")))
                    .username(firstNonEmpty(json.getString("preferred_username"), json.getString("username"),
                            json.getString("login"), json.getString("name"), json.getString("email")))
                    .nickname(firstNonEmpty(json.getString("nickname"), json.getString("name"),
                            json.getString("display_name"), json.getString("cn_name")))
                    .email(firstNonEmpty(json.getString("email"), json.getString("mail")))
                    .avatar(firstNonEmpty(json.getString("avatar_url"), json.getString("picture"),
                            json.getString("avatar")))
                    .rawUserInfo(json)
                    .token(authToken)
                    .build();
        }

        private AuthToken parseToken(String response) {
            JSONObject json = JSON.parseObject(response);
            if (json == null) {
                throw new IllegalStateException("Invalid token response");
            }
            return AuthToken.builder()
                    .accessToken(firstNonEmpty(json.getString("access_token"), json.getString("accessToken")))
                    .refreshToken(firstNonEmpty(json.getString("refresh_token"), json.getString("refreshToken")))
                    .expireIn(json.getIntValue("expires_in"))
                    .tokenType(firstNonEmpty(json.getString("token_type"), json.getString("tokenType")))
                    .idToken(firstNonEmpty(json.getString("id_token"), json.getString("idToken")))
                    .scope(json.getString("scope"))
                    .build();
        }
    }
}
