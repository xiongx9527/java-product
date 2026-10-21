package com.xiongx9527.askdata.system.authentication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSO 第三方授权（brief D9：统一使用 JustAuth）。
 *
 * <p>JustAuth 负责授权跳转、回调换 token、拉取用户信息三段协议；本类只做：</p>
 * <ol>
 *   <li>把 {@code sys_authentication.config}（JSON）翻译成 JustAuth 的 {@link AuthConfig}；</li>
 *   <li>用 JustAuth 的 {@link AuthRequest} 生成授权 URL / 处理回调；</li>
 *   <li>把 JustAuth 的 {@link AuthUser} 归一成本地用户字段（account / name / email）。</li>
 * </ol>
 *
 * <p>各认证源的 config 字段（对齐前端 {@code views/system/authentication/*.vue}）：</p>
 * <ul>
 *   <li>CAS（type=1）：{@code idpUri} / {@code casCallbackDomain} / {@code mapping}；</li>
 *   <li>OIDC（type=2）：{@code client_id} / {@code client_secret} / {@code metadata_url} /
 *       {@code redirect_uri} / {@code realm} / {@code scope} / {@code mapping}；</li>
 *   <li>LDAP（type=3）：{@code server_address} / {@code bind_dn} / {@code bind_pwd} /
 *       {@code ou} / {@code user_filter} / {@code mapping}；</li>
 *   <li>OAuth2（type=4）：{@code authorize_url} / {@code token_url} / {@code userinfo_url} /
 *       {@code scope} / {@code client_id} / {@code client_secret} / {@code redirect_url} /
 *       {@code token_auth_method} / {@code userinfo_auth_method} / {@code mapping}。</li>
 * </ul>
 *
 * <p>注意：JustAuth 内置的是各家<b>公有</b> IdP 的通用协议实现（github/gitee/企业微信/钉钉/飞书 等），
 * 面向企业内部的 CAS / OIDC / LDAP / SAML 需要各自的客户端。askdata 侧的做法是：
 * OIDC / OAuth2 走 JustAuth 的通用 {@code AuthDefaultRequest}（自定义 endpoint），
 * CAS / LDAP / SAML 走各自的轻量客户端（{@link SsoClientFactory}）。</p>
 */
@Component
public class SsoAuthenticator {

    private final ObjectMapper objectMapper;

    public SsoAuthenticator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 认证源类型码（与前端 origin_mapping 逐字一致）。 */
    public static final int TYPE_CAS = 1;
    public static final int TYPE_OIDC = 2;
    public static final int TYPE_LDAP = 3;
    public static final int TYPE_OAUTH2 = 4;
    public static final int TYPE_SAML2 = 5;

    /** 认证源名称（与前端 origin_mapping 逐字一致）。 */
    public static String nameOf(int type) {
        switch (type) {
            case TYPE_CAS:
                return "cas";
            case TYPE_OIDC:
                return "oidc";
            case TYPE_LDAP:
                return "ldap";
            case TYPE_OAUTH2:
                return "oauth2";
            case TYPE_SAML2:
                return "saml2";
            default:
                return null;
        }
    }

    /** 解析 config JSON（失败返回空 Map）。 */
    public Map<String, Object> parseConfig(String config) {
        if (config == null || config.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(config, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 构造 JustAuth 的授权配置。
     *
     * @param config 认证源 config（JSON 解析后的 Map）
     * @param redirectUri 回调地址（缺省用 config 里的 redirect_uri / redirect_url）
     */
    public AuthConfig toAuthConfig(Map<String, Object> config, String redirectUri) {
        AuthConfig.AuthConfigBuilder builder = AuthConfig.builder();
        builder.clientId(str(config, "client_id"));
        builder.clientSecret(str(config, "client_secret"));
        String effectiveRedirect = redirectUri;
        if (effectiveRedirect == null || effectiveRedirect.isEmpty()) {
            effectiveRedirect = str(config, "redirect_uri");
        }
        if (effectiveRedirect == null || effectiveRedirect.isEmpty()) {
            effectiveRedirect = str(config, "redirect_url");
        }
        builder.redirectUri(effectiveRedirect);
        builder.ignoreCheckState(true);
        String scope = str(config, "scope");
        if (scope != null && !scope.trim().isEmpty()) {
            builder.scopes(java.util.Arrays.asList(scope.trim().split("[ ,]+")));
        }
        return builder.build();
    }

    /**
     * 生成授权跳转 URL（JustAuth 负责拼 state 与回调参数）。
     *
     * @param type     认证源类型
     * @param config   认证源配置
     * @param callback 回调参数（code / state）
     */
    public String authorizeUrl(int type, Map<String, Object> config, AuthCallback callback) {
        AuthRequest request = SsoClientFactory.request(type, config, callback);
        return request.authorize(callback.getState());
    }

    /**
     * 执行回调换取用户信息。
     *
     * @return JustAuth 的用户信息；失败时返回 null
     */
    public AuthUser login(int type, Map<String, Object> config, AuthCallback callback) {
        AuthRequest request = SsoClientFactory.request(type, config, callback);
        AuthResponse<AuthUser> response = request.login(callback);
        if (response == null || !response.ok() || response.getData() == null) {
            return null;
        }
        return response.getData();
    }

    /**
     * 把 JustAuth 的用户信息归一为本地用户字段。
     *
     * <p>{@code mapping} 允许把第三方字段映射到本地字段（如 {@code {"account":"userId"}}）；
     * 未配置时按常见约定取值：account 取 username / email / uuid，name 取 nickname / username，
     * email 取 email。</p>
     */
    public Map<String, Object> toLocalUser(AuthUser authUser, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> mapping = parseConfig(asString(config.get("mapping")));
        result.put("account", resolve(mapping, "account", authUser, "username", "email", "uuid"));
        result.put("name", resolve(mapping, "name", authUser, "nickname", "username", "uuid"));
        result.put("email", resolve(mapping, "email", authUser, "email", "username"));
        result.put("platform_uid", resolve(mapping, "platform_uid", authUser, "uuid", "username", "email"));
        return result;
    }

    private String resolve(Map<String, Object> mapping, String localKey, AuthUser authUser, String... candidates) {
        Object mapped = mapping.get(localKey);
        if (mapped != null) {
            String key = String.valueOf(mapped);
            String value = readRaw(authUser, key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        for (String candidate : candidates) {
            String value = readRaw(authUser, candidate);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** 从 JustAuth 用户信息或其 rawUserInfo 中按字段名取值。 */
    private String readRaw(AuthUser authUser, String field) {
        if (authUser == null || field == null) {
            return null;
        }
        switch (field) {
            case "uuid":
                return authUser.getUuid();
            case "username":
                return authUser.getUsername();
            case "nickname":
                return authUser.getNickname();
            case "email":
                return authUser.getEmail();
            case "avatar":
                return authUser.getAvatar();
            case "company":
                return authUser.getCompany();
            case "location":
                return authUser.getLocation();
            case "remark":
                return authUser.getRemark();
            default:
                break;
        }
        if (authUser.getRawUserInfo() != null) {
            Object value = authUser.getRawUserInfo().get(field);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String str(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 供测试/诊断：当前支持的认证源类型。 */
    public List<Integer> supportedTypes() {
        return java.util.Arrays.asList(TYPE_CAS, TYPE_OIDC, TYPE_LDAP, TYPE_OAUTH2, TYPE_SAML2);
    }
}
