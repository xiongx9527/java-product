package com.xiongx9527.askdata.system.authentication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SSO 登出的 IdP 联动地址解析（A24，对齐 SQLBot {@code xpack_logout} 语义）。
 *
 * <p>SQLBot 的 {@code POST /login/logout} 在 {@code dto.origin != 0}（第三方来源登录）时
 * 走 xpack 平台登出：返回 IdP 的登出地址，前端 {@code stores/user.ts} 拿到该字符串后
 * {@code window.location.href = res}，由 IdP 终止单点会话——<b>不只是清理本地 token</b>。
 * 本类按已保存的认证源（{@code sys_authentication.type = origin}）解析该地址：</p>
 *
 * <ol>
 *   <li>配置里显式给出的登出地址优先（{@code logoutUrl} / {@code logout_url} /
 *       {@code endSessionEndpoint} / {@code end_session_endpoint} / {@code sloUrl} / {@code slo_url}）；</li>
 *   <li>CAS：{@code {idpUri}/logout}（带 {@code service=casCallbackDomain} 便于回跳）；</li>
 *   <li>SAML2：IdP 元数据的 {@code SingleLogoutService/@Location}；</li>
 *   <li>LDAP / 企业 IM 无标准登出端点，返回 null（前端回落到登录页）。</li>
 * </ol>
 */
@Service
public class SsoLogoutUrlResolver {

    /** 显式登出地址候选键（不同认证源编辑器里的字段名）。 */
    private static final String[] EXPLICIT_KEYS = {
            "logoutUrl", "logout_url", "endSessionEndpoint", "end_session_endpoint", "sloUrl", "slo_url"
    };

    private final AuthenticationRepository authenticationRepository;

    private final Saml2SsoClient saml2SsoClient;

    private final ObjectMapper objectMapper;

    public SsoLogoutUrlResolver(AuthenticationRepository authenticationRepository,
                                Saml2SsoClient saml2SsoClient, ObjectMapper objectMapper) {
        this.authenticationRepository = authenticationRepository;
        this.saml2SsoClient = saml2SsoClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析来源对应的 IdP 登出地址。
     *
     * @param origin 登录来源（{@code sys_authentication.type}；0 表示本地登录）
     * @return 可跳转的登出地址；无联动端点时返回 null
     */
    public String resolve(long origin) {
        if (origin <= 0) {
            return null;
        }
        Optional<Map<String, Object>> stored = authenticationRepository.findByType((int) origin);
        if (!stored.isPresent()) {
            return null;
        }
        Map<String, Object> config = parseConfig(Rows.str(stored.get(), "config"));
        for (String key : EXPLICIT_KEYS) {
            String value = text(config.get(key));
            if (value != null) {
                return value;
            }
        }
        switch ((int) origin) {
            case SsoAuthenticator.TYPE_CAS:
                return casLogout(config);
            case SsoAuthenticator.TYPE_SAML2:
                return saml2SsoClient.logoutEndpointFromMetadata(text(config.get("idpMetaUrl")));
            default:
                return null;
        }
    }

    /** CAS：{@code {idpUri}/logout[?service=...]}。 */
    private String casLogout(Map<String, Object> config) {
        String idpUri = text(config.get("idpUri"));
        if (idpUri == null) {
            return null;
        }
        String base = idpUri.endsWith("/") ? idpUri.substring(0, idpUri.length() - 1) : idpUri;
        String service = text(config.get("casCallbackDomain"));
        return service == null ? base + "/logout" : base + "/logout?service=" + urlEncode(service);
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
