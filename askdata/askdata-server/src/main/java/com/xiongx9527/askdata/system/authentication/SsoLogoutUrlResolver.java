package com.xiongx9527.askdata.system.authentication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

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
 *   <li>SAML2：IdP 元数据的 {@code SingleLogoutService/@Location} + 按协议生成的
 *       {@code LogoutRequest}（{@code SAMLRequest=base64(deflate(xml))}）；</li>
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
        return resolve(origin, null);
    }

    /**
     * 解析来源对应的 IdP 登出地址，并附带登出后的回跳地址。
     *
     * <p>登出是浏览器整页跳到 IdP 的，IdP 若不能把用户送回本系统，浏览器就会停在 IdP 自己的
     * 登出页（部分 IdP 表现为跳到 {@code /#/passport/logout}）。某些 IdP 的
     * {@code /sign/force/logout} 支持 {@code redirect_uri} 参数（其 {@code LogoutEndpoint.forceLogout}
     * 会把它拼到登出页地址上，前端登出组件再 {@code location.href} 跳回），标准 OIDC 则用
     * {@code post_logout_redirect_uri}；两者都带上，不认识的 IdP 会忽略。</p>
     *
     * @param origin  登录来源（{@code sys_authentication.type}；0 表示本地登录）
     * @param request 当前登出请求，用于推导回跳地址；为空时回退到认证源配置里的回调地址
     * @return 可跳转的登出地址；无联动端点时返回 null
     */
    public String resolve(long origin, HttpServletRequest request) {
        return resolve(origin, null, request);
    }

    /**
     * 解析来源对应的 IdP 登出地址（带平台侧用户标识）。
     *
     * <p>SAML2 的 SLO 必须在地址里带 {@code LogoutRequest}，其中 {@code NameID} 取自登录时保存的
     * 平台用户标识（{@link Saml2SsoClient#SESSION_NAME_ID}）；缺失时 IdP 无法定位会话，
     * Casdoor 会直接返回 {@code unexpected EOF}。</p>
     *
     * @param origin      登录来源（{@code sys_authentication.type}）
     * @param platformUid 平台侧用户标识（SAML2 即 NameID），其他协议忽略
     * @param request     当前登出请求，用于推导回跳地址
     */
    public String resolve(long origin, String platformUid, HttpServletRequest request) {
        if (origin <= 0) {
            return null;
        }
        Map<String, Object> config = configOf(origin);
        if (config == null) {
            return null;
        }
        if ((int) origin == SsoAuthenticator.TYPE_SAML2) {
            // SAML2：协议要求带 LogoutRequest，回跳由 IdP 侧 saml_single_logout_url 决定
            return saml2SsoClient.buildLogoutRequestUrl(config, platformUid);
        }
        String url = rawResolve(origin, config);
        if (url == null) {
            return null;
        }
        String backTo = returnTo(origin, request);
        if (backTo == null) {
            return url;
        }
        return appendRedirect(url, backTo);
    }

    private Map<String, Object> configOf(long origin) {
        Optional<Map<String, Object>> stored = authenticationRepository.findByType((int) origin);
        return stored.isPresent() ? parseConfig(Rows.str(stored.get(), "config")) : null;
    }

    private String rawResolve(long origin, Map<String, Object> config) {
        if (origin <= 0) {
            return null;
        }
        for (String key : EXPLICIT_KEYS) {
            String value = text(config.get(key));
            if (value != null) {
                return value;
            }
        }
        switch ((int) origin) {
            case SsoAuthenticator.TYPE_CAS:
                return casLogout(config);
            default:
                return null;
        }
    }

    /** 登出后回到本系统登录页：优先当前访问来源，其次认证源里登记的回调地址。 */
    private String returnTo(long origin, HttpServletRequest request) {
        if (request != null && text(request.getHeader("Origin")) != null) {
            return RequestBaseUrl.resolve(request);
        }
        Optional<Map<String, Object>> stored = authenticationRepository.findByType((int) origin);
        if (stored.isPresent()) {
            Map<String, Object> config = parseConfig(Rows.str(stored.get(), "config"));
            String configured = firstNonBlank(text(config.get("redirect_uri")), text(config.get("redirect_url")),
                    text(config.get("casCallbackDomain")));
            if (configured != null) {
                return configured;
            }
        }
        return request == null ? null : RequestBaseUrl.resolve(request);
    }

    private static String appendRedirect(String url, String backTo) {
        StringBuilder sb = new StringBuilder(url);
        char sep = url.indexOf('?') >= 0 ? '&' : '?';
        // 按参数名边界判断：post_logout_redirect_uri 里也含有 redirect_uri 子串
        if (!hasParam(url, "redirect_uri")) {
            sb.append(sep).append("redirect_uri=").append(urlEncode(backTo));
            sep = '&';
        }
        if (!hasParam(url, "post_logout_redirect_uri")) {
            sb.append(sep).append("post_logout_redirect_uri=").append(urlEncode(backTo));
        }
        return sb.toString();
    }

    private static boolean hasParam(String url, String name) {
        return java.util.regex.Pattern.compile("[?&]" + name + "=").matcher(url).find();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
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
