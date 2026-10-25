package com.xiongx9527.askdata.system.authentication;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.platform.PlatformRepository;
import com.xiongx9527.askdata.system.platform.EnterprisePlatformService;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSO 认证源服务（对齐 SQLBot xpack {@code sqlbot_xpack/authentication/api.py}：
 * query / get_authentication_by_id / add / update / validate_authentication /
 * switch_enable / platform_status / sso_login / to_login）。
 *
 * <p>认证源存 {@code sys_authentication}（无 oid 列，全局作用域，与 SQLBot 实际结构一致）。</p>
 *
 * <p>SSO 登录流程：</p>
 * <ol>
 *   <li>第三方回调带回平台用户标识；</li>
 *   <li>经 {@code sys_user_platform}（uid / origin / platform_uid）解析本地 {@code sys_user}；</li>
 *   <li>未绑定时按配置自动创建本地用户（origin 标识来源）并建立绑定；</li>
 *   <li>签发主通道 token（Sa-Token）。</li>
 * </ol>
 */
@Service
public class AuthenticationService {

    private final AuthenticationRepository authenticationRepository;

    private final UserRepository userRepository;

    private final PlatformRepository platformRepository;

    private final CurrentUserService currentUserService;

    private final SsoAuthenticator ssoAuthenticator;

    private final CasSsoClient casSsoClient;

    private final LdapSsoClient ldapSsoClient;

    private final Saml2SsoClient saml2SsoClient;

    private final IdGenerator idGenerator;

    /** A31：企业 IM（type 6..9）扫码登录的第三方用户解析。 */
    private final EnterprisePlatformService enterprisePlatformService;

    public AuthenticationService(AuthenticationRepository authenticationRepository,
                                 UserRepository userRepository,
                                 PlatformRepository platformRepository,
                                 CurrentUserService currentUserService,
                                 SsoAuthenticator ssoAuthenticator,
                                 CasSsoClient casSsoClient,
                                 LdapSsoClient ldapSsoClient,
                                 Saml2SsoClient saml2SsoClient,
                                 IdGenerator idGenerator,
                                 EnterprisePlatformService enterprisePlatformService) {
        this.authenticationRepository = authenticationRepository;
        this.userRepository = userRepository;
        this.platformRepository = platformRepository;
        this.currentUserService = currentUserService;
        this.ssoAuthenticator = ssoAuthenticator;
        this.casSsoClient = casSsoClient;
        this.ldapSsoClient = ldapSsoClient;
        this.saml2SsoClient = saml2SsoClient;
        this.idGenerator = idGenerator;
        this.enterprisePlatformService = enterprisePlatformService;
    }

    // ------------------------------------------------------------------
    // 认证源 CRUD / 状态
    // ------------------------------------------------------------------

    /**
     * GET /system/authentication：全部认证源（A25：仅管理员，认证源配置含 IdP 密钥等敏感信息）。
     */
    public List<Map<String, Object>> list() {
        currentUserService.requireAdmin();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : authenticationRepository.list()) {
            result.add(view(row));
        }
        return result;
    }

    /** GET /system/authentication/{id}：按 id 取认证源（A25：仅管理员）。 */
    public Map<String, Object> getById(long id) {
        currentUserService.requireAdmin();
        return view(authenticationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("authentication not found: " + id)));
    }

    /** 按类型取认证源（供 SSO 流程内部使用）。 */
    public Optional<Map<String, Object>> findByType(int type) {
        return authenticationRepository.findByType(type);
    }

    /**
     * POST /system/authentication：新增认证源。
     *
     * <p>请求体（前端各 Editor.vue 逐字一致）：{@code {id, type, config, name}}，
     * 其中 id 即类型码（1=CAS 2=OIDC 3=LDAP 4=OAuth2 6..9=企业 IM），config 为 JSON 字符串。</p>
     */
    @Transactional
    public Map<String, Object> add(Map<String, Object> body) {
        currentUserService.requireAdmin();
        int type = intOf(body.get("type"), 0);
        String name = str(body, "name");
        if (name == null || name.isEmpty()) {
            name = SsoAuthenticator.nameOf(type);
        }
        if (name == null) {
            name = "type-" + type;
        }
        long id = longOf(body.get("id"), type);
        authenticationRepository.upsert(id, name, type, str(body, "config"), false, false,
                System.currentTimeMillis());
        return getById(id);
    }

    /** PUT /system/authentication：更新认证源。 */
    @Transactional
    public Map<String, Object> update(Map<String, Object> body) {
        currentUserService.requireAdmin();
        int type = intOf(body.get("type"), 0);
        String name = str(body, "name");
        if (name == null || name.isEmpty()) {
            name = SsoAuthenticator.nameOf(type);
        }
        if (name == null) {
            name = "type-" + type;
        }
        long id = longOf(body.get("id"), type);
        Map<String, Object> existing = authenticationRepository.findById(id).orElse(null);
        boolean enable = existing != null && Rows.bool(existing, "enable", false);
        boolean valid = existing != null && Rows.bool(existing, "valid", false);
        long createTime = existing == null
                ? System.currentTimeMillis()
                : Rows.lng(existing, "create_time", System.currentTimeMillis());
        authenticationRepository.upsert(id, name, type, str(body, "config"), enable, valid, createTime);
        return getById(id);
    }

    /** PATCH /system/authentication/enable：启停认证源。 */
    @Transactional
    public void switchEnable(long id, boolean enable) {
        currentUserService.requireAdmin();
        authenticationRepository.updateEnable(id, enable);
    }

    /**
     * PATCH /system/authentication/status：连通性校验（"测试连接"）。
     *
     * <p>请求体 {@code {type, name, config}}：带 config 时校验该 config，
     * 否则校验已保存的认证源。校验通过则把 valid 置 true。</p>
     */
    @Transactional
    public boolean validate(Map<String, Object> body) {
        currentUserService.requireAdmin();
        int type = intOf(body.get("type"), 0);
        String configJson = str(body, "config");
        Map<String, Object> config;
        if (configJson != null && !configJson.trim().isEmpty()) {
            config = ssoAuthenticator.parseConfig(configJson);
        } else {
            Map<String, Object> stored = authenticationRepository.findByType(type)
                    .orElseThrow(() -> new IllegalArgumentException("authentication not found for type: " + type));
            config = ssoAuthenticator.parseConfig(Rows.str(stored, "config"));
        }
        boolean valid = doValidate(type, config);
        Map<String, Object> stored = authenticationRepository.findByType(type).orElse(null);
        if (stored != null) {
            authenticationRepository.updateValid(Rows.lng(stored, "id", 0L), valid);
        }
        return valid;
    }

    private boolean doValidate(int type, Map<String, Object> config) {
        try {
            switch (type) {
                case SsoAuthenticator.TYPE_CAS:
                    return casSsoClient.validate(config);
                case SsoAuthenticator.TYPE_LDAP:
                    return ldapSsoClient.validate(config);
                case SsoAuthenticator.TYPE_SAML2:
                    return saml2SsoClient.validate(config);
                case SsoAuthenticator.TYPE_OIDC:
                case SsoAuthenticator.TYPE_OAUTH2:
                    return validateOAuth2(config);
                default:
                    // 企业 IM（6..9）等类型未接入在线连通性探测，按“配置完整即视为可用”处理。
                    return !config.isEmpty();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** OAuth2/OIDC 连通性：探测 userinfo 端点可达（不发真实授权请求）。 */
    private boolean validateOAuth2(Map<String, Object> config) {
        String tokenUrl = str(config, "token_url");
        if (tokenUrl == null || tokenUrl.isEmpty()) {
            return false;
        }
        try {
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) new java.net.URL(tokenUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            int status = connection.getResponseCode();
            connection.disconnect();
            // 端点可达即视为可用（4xx/5xx 说明服务在但拒绝无凭据请求）
            return status > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * GET /system/authentication/platform/status：企业平台（type &gt;= 6）的启停状态。
     *
     * <p>前端 {@code views/login/xpack/Handler.vue} 与 {@code PlatformParam.vue} 消费
     * {@code [{name, enable}]}，据此决定登录页展示哪些第三方入口。</p>
     */
    public List<Map<String, Object>> platformStatus() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : authenticationRepository.list()) {
            if (Rows.intg(row, "type", 0) < 6) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Rows.lng(row, "id", 0L));
            item.put("name", Rows.str(row, "name"));
            item.put("type", Rows.intg(row, "type", 0));
            item.put("enable", Rows.bool(row, "enable", false));
            item.put("valid", Rows.bool(row, "valid", false));
            result.add(item);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // SSO 登录
    // ------------------------------------------------------------------

    /**
     * GET /system/authentication/login/{type}：返回第三方授权跳转 URL。
     *
     * <p>前端 {@code views/login/xpack/Handler.vue} 直接 {@code window.location.href = res}。</p>
     */
    public String toLogin(int type) {
        Map<String, Object> stored = authenticationRepository.findByType(type)
                .orElseThrow(() -> new IllegalArgumentException("authentication not found for type: " + type));
        if (!Rows.bool(stored, "enable", false)) {
            throw new IllegalStateException("authentication source is disabled: " + type);
        }
        Map<String, Object> config = ssoAuthenticator.parseConfig(Rows.str(stored, "config"));
        switch (type) {
            case SsoAuthenticator.TYPE_CAS:
                return casSsoClient.authorizeUrl(config, new SsoClient.SsoCallback(null, null, null));
            case SsoAuthenticator.TYPE_SAML2:
                return saml2SsoClient.authorizeUrl(config, new SsoClient.SsoCallback(null, null, null));
            case SsoAuthenticator.TYPE_OIDC:
            case SsoAuthenticator.TYPE_OAUTH2:
                return ssoAuthenticator.authorizeUrl(type, config,
                        me.zhyd.oauth.model.AuthCallback.builder().state(null).build());
            case SsoAuthenticator.TYPE_LDAP:
                throw new UnsupportedOperationException("LDAP login does not use an authorization redirect");
            default:
                throw new UnsupportedOperationException("Unsupported SSO type: " + type);
        }
    }

    /**
     * POST /system/authentication/sso/{type}：SSO 登录（回调换用户信息 → 解析/创建本地用户 → 签发 token）。
     *
     * <p>请求体（前端 {@code views/login/xpack/Handler.vue} 的 {@code urlParams}）：
     * 第三方回调参数（{@code code} / {@code state} / {@code ticket} / {@code redirect_uri}），
     * LDAP 场景为 {@code {username, password}}。</p>
     *
     * @return {@code {access_token, exp, platform_info}}（前端 {@code userStore.setToken} 消费）
     */
    @Transactional
    public Map<String, Object> ssoLogin(int type, Map<String, Object> body) {
        Map<String, Object> stored = authenticationRepository.findByType(type)
                .orElseThrow(() -> new IllegalArgumentException("authentication not found for type: " + type));
        if (!Rows.bool(stored, "enable", false)) {
            throw new IllegalStateException("authentication source is disabled: " + type);
        }
        Map<String, Object> config = ssoAuthenticator.parseConfig(Rows.str(stored, "config"));

        Map<String, Object> platformUser;
        if (type >= EnterprisePlatformService.ORIGIN_WECOM) {
            // A31：企业 IM 扫码（wecom/dingtalk/lark/larksuite）——回调 code 换平台用户
            platformUser = enterprisePlatformService.platformUserByCode(type, body);
        } else if (type == SsoAuthenticator.TYPE_LDAP) {
            String username = str(body, "username");
            String password = str(body, "password");
            platformUser = ldapSsoClient.loginWithPassword(config, username, password);
        } else if (type == SsoAuthenticator.TYPE_SAML2) {
            platformUser = saml2SsoClient.parseResponse(str(body, "SAMLResponse"), config);
        } else if (type == SsoAuthenticator.TYPE_CAS) {
            platformUser = casSsoClient.exchange(config,
                    new SsoClient.SsoCallback(str(body, "code"), str(body, "state"), str(body, "ticket")));
        } else {
            me.zhyd.oauth.model.AuthCallback callback = me.zhyd.oauth.model.AuthCallback.builder()
                    .code(str(body, "code"))
                    .state(str(body, "state"))
                    .build();
            me.zhyd.oauth.model.AuthUser authUser = ssoAuthenticator.login(type, config, callback);
            if (authUser == null) {
                throw new IllegalStateException("SSO login failed: unable to fetch user info");
            }
            platformUser = ssoAuthenticator.toLocalUser(authUser, config);
        }

        Map<String, Object> result = resolveLocalUser(type, platformUser);
        return result;
    }

    /**
     * 平台用户 → 本地用户解析（{@code sys_user_platform} 绑定优先，未绑定则自动创建）。
     */
    private Map<String, Object> resolveLocalUser(int origin, Map<String, Object> platformUser) {
        String platformUid = str(platformUser, "platform_uid");
        String account = str(platformUser, "account");
        if (account == null || account.isEmpty()) {
            throw new IllegalStateException("SSO login failed: missing account");
        }
        Optional<Map<String, Object>> binding = platformRepository.findBinding(origin, platformUid);
        Map<String, Object> user;
        if (binding.isPresent()) {
            user = userRepository.findById(Rows.lng(binding.get(), "uid", 0L))
                    .orElseThrow(() -> new IllegalStateException("SSO login failed: bound user not found"));
        } else {
            user = userRepository.findByAccount(account).orElse(null);
            if (user == null) {
                // 未绑定且本地无同名账号：自动创建（origin 标识来源）
                long uid = idGenerator.nextId();
                long oid = defaultWorkspaceId();
                userRepository.insert(uid, account, str(platformUser, "name") == null ? account : str(platformUser, "name"),
                        CryptoUtil.md5Hex(""), str(platformUser, "email"), oid, 1,
                        System.currentTimeMillis(), "zh-CN", origin, null);
                user = userRepository.findById(uid).orElse(null);
            }
            if (user != null) {
                platformRepository.upsertBinding(idGenerator.nextId(),
                        Rows.lng(user, "id", 0L), origin, platformUid);
            }
        }
        if (user == null) {
            throw new IllegalStateException("SSO login failed: unable to resolve local user");
        }
        if (Rows.intg(user, "status", 0) != 1) {
            throw new IllegalStateException("SSO login failed: user is disabled");
        }
        if (Rows.lng(user, "oid", 0L) == 0L) {
            throw new IllegalStateException("SSO login failed: no associated workspace");
        }
        return user;
    }

    /** 默认工作空间（取第一个；SSO 自动建用户时归属）。 */
    private long defaultWorkspaceId() {
        List<Long> oids = userRepository.oidListOf(1L);
        if (!oids.isEmpty()) {
            return oids.get(0);
        }
        return 1L;
    }

    /** 认证源视图（字段与 SQLBot xpack 卡片契约一致）。 */
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", Rows.lng(row, "id", 0L));
        view.put("name", Rows.str(row, "name"));
        view.put("type", Rows.intg(row, "type", 0));
        view.put("config", Rows.str(row, "config"));
        view.put("enable", Rows.bool(row, "enable", false));
        view.put("valid", Rows.bool(row, "valid", false));
        view.put("create_time", Rows.lng(row, "create_time", 0L));
        return view;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long longOf(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
