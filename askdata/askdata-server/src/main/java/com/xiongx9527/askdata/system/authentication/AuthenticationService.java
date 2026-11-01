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

import javax.servlet.http.HttpServletRequest;

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
    /** 校验结果：是否有效 + 失败原因（供前端展示）。 */
    private static final class ValidationResult {
        final boolean valid;
        final String msg;
        final String reason;

        ValidationResult(boolean valid, String msg, String reason) {
            this.valid = valid;
            this.msg = msg;
            this.reason = reason;
        }
    }

    @Transactional
    public Map<String, Object> validate(Map<String, Object> body) {
        currentUserService.requireAdmin();
        int type = intOf(body.get("type"), 0);
        String configJson = str(body, "config");
        Map<String, Object> config;
        if (configJson != null && !configJson.trim().isEmpty()) {
            config = ssoAuthenticator.parseConfig(configJson);
        } else {
            Map<String, Object> stored = authenticationRepository.findByType(type).orElse(null);
            if (stored == null) {
                // 未配置该类型认证源：返回干净失败（带 type 信息），不再抛出原始异常
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("valid", false);
                result.put("msg", "ds.connection_failed");
                result.put("reason", "authentication source not found for type: " + type);
                return result;
            }
            config = ssoAuthenticator.parseConfig(Rows.str(stored, "config"));
        }
        ValidationResult vr = doValidate(type, config);
        Map<String, Object> stored = authenticationRepository.findByType(type).orElse(null);
        if (stored != null) {
            authenticationRepository.updateValid(Rows.lng(stored, "id", 0L), vr.valid);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", vr.valid);
        result.put("msg", vr.valid ? null : vr.msg);
        result.put("reason", vr.valid ? null : vr.reason);
        return result;
    }

    private ValidationResult doValidate(int type, Map<String, Object> config) {
        try {
            boolean ok;
            switch (type) {
                case SsoAuthenticator.TYPE_CAS:
                    ok = casSsoClient.validate(config);
                    break;
                case SsoAuthenticator.TYPE_LDAP:
                    ok = ldapSsoClient.validate(config);
                    break;
                case SsoAuthenticator.TYPE_SAML2:
                    ok = saml2SsoClient.validate(config);
                    break;
                case SsoAuthenticator.TYPE_OIDC:
                case SsoAuthenticator.TYPE_OAUTH2:
                    ok = validateOAuth2(config);
                    break;
                default: {
                    Map<String, Object> r = enterprisePlatformService.validateConfig(type, config);
                    boolean v = Boolean.TRUE.equals(r.get("valid"));
                    Object reasonObj = r.get("reason");
                    String reason = v ? null : (reasonObj == null ? null : String.valueOf(reasonObj));
                    // validateConfig 不再返回 msg（与前端 t('ds.connection_failed') 兜底重复）；
                    // 由前端统一用 t('ds.connection_failed') 作为前缀，reason 为真实错误说明。
                    return new ValidationResult(v, null, reason);
                }
            }
            if (ok) {
                return new ValidationResult(true, null, null);
            }
            return new ValidationResult(false, "ds.connection_failed", null);
        } catch (Exception e) {
            return new ValidationResult(false, "ds.connection_failed", e.getMessage());
        }
    }

    /** OAuth2/OIDC 连通性：探测 userinfo 端点可达（不发真实授权请求）。 */
    private boolean validateOAuth2(Map<String, Object> config) {
        String tokenUrl = str(config, "token_url");
        if (tokenUrl == null || tokenUrl.isEmpty()) {
            throw new IllegalStateException("missing token_url in OIDC/OAuth2 config");
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
            throw new IllegalStateException("OIDC/OAuth2 token endpoint unreachable: " + e.getMessage(), e);
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
        boolean anyQrEnable = false;
        for (Map<String, Object> row : authenticationRepository.list()) {
            int type = Rows.intg(row, "type", 0);
            boolean enable = Rows.bool(row, "enable", false);
            // 登录页入口只在「已启用且已配置」时出现：避免仅打开发布开关、
            // 但 IdP 元数据/证书等尚未填写时，登录页出现点了会失败的 SSO 入口
            // （即「SAML2 还没生效却已在登录页出现」的问题）。
            boolean configured = configHasContent(type, Rows.str(row, "config"));
            boolean showOnLogin = enable && configured;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Rows.lng(row, "id", 0L));
            // name 用类型对应的规范名（cas/oidc/ldap/oauth2/saml2/wecom/...）：
            // 登录页按 loginCategory[name] 渲染入口，若沿用库里可自定义的 name，
            // 名字被改过或大小写不一致时入口就不显示了。
            item.put("name", ssoTypeName(type));
            item.put("type", type);
            item.put("enable", showOnLogin);
            item.put("valid", Rows.bool(row, "valid", false));
            result.add(item);
            if (type >= 6 && showOnLogin) {
                anyQrEnable = true;
            }
        }
        // 登录页据本接口渲染各登录入口：CAS/OIDC/OAuth2/LDAP 用各自 name；
        // 企业 IM 扫码入口统一由聚合项 qrcode 控制（任一 IM 启用即显示）。
        Map<String, Object> qrcode = new LinkedHashMap<>();
        qrcode.put("name", "qrcode");
        qrcode.put("enable", anyQrEnable);
        result.add(qrcode);
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
    public String toLogin(int type, HttpServletRequest request) {
        Map<String, Object> stored = authenticationRepository.findByType(type)
                .orElseThrow(() -> new IllegalArgumentException("authentication not found for type: " + type));
        if (!Rows.bool(stored, "enable", false)) {
            throw new IllegalStateException("authentication source is disabled: " + type);
        }
        Map<String, Object> config = ssoAuthenticator.parseConfig(Rows.str(stored, "config"));
        // 回调地址按当前访问来源动态生成（不写死到配置）：前端同源请求带来的 Origin 头即
        // 公网/内网入口，确保 authorize 跳转里的 redirect_uri 与浏览器实际落点一致，
        // 也与该来源在后端 ssoLogin 回调换码时使用的 redirect_uri 一致。
        String redirect = resolveRedirect(config, request);
        switch (type) {
            case SsoAuthenticator.TYPE_CAS:
                config.put("casCallbackDomain", redirect);
                return casSsoClient.authorizeUrl(config, new SsoClient.SsoCallback(null, null, null));
            case SsoAuthenticator.TYPE_SAML2:
                return saml2SsoClient.authorizeUrl(config, new SsoClient.SsoCallback(null, null, null));
            case SsoAuthenticator.TYPE_OIDC:
            case SsoAuthenticator.TYPE_OAUTH2:
                config.put("redirect_uri", redirect);
                config.put("redirect_url", redirect);
                // state 里带认证源标记：回调落到前端后靠它识别是哪个 SSO 源
                // （JustAuth 默认的随机 state 不含标记，前端会当成普通访问而不发起换码登录）。
                String state = "fit2cloud-" + ssoTypeName(type) + "-"
                        + java.util.UUID.randomUUID().toString().replace("-", "");
                return ssoAuthenticator.authorizeUrl(type, config,
                        me.zhyd.oauth.model.AuthCallback.builder().state(state).build());
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
    public Map<String, Object> ssoLogin(int type, Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> stored = authenticationRepository.findByType(type)
                .orElseThrow(() -> new IllegalArgumentException("authentication not found for type: " + type));
        if (!Rows.bool(stored, "enable", false)) {
            throw new IllegalStateException("authentication source is disabled: " + type);
        }
        Map<String, Object> config = ssoAuthenticator.parseConfig(Rows.str(stored, "config"));
        // 与 toLogin 同一来源：配置里写了回调地址就用配置，保证 authorize 与 exchange 两端一致。
        String redirect = resolveRedirect(config, request);

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
            config.put("casCallbackDomain", redirect);
            platformUser = casSsoClient.exchange(config,
                    new SsoClient.SsoCallback(str(body, "code"), str(body, "state"), str(body, "ticket")));
        } else {
            me.zhyd.oauth.model.AuthCallback callback = me.zhyd.oauth.model.AuthCallback.builder()
                    .code(str(body, "code"))
                    .state(str(body, "state"))
                    .build();
            config.put("redirect_uri", redirect);
            config.put("redirect_url", redirect);
            me.zhyd.oauth.model.AuthUser authUser = ssoAuthenticator.login(type, config, callback);
            if (authUser == null) {
                throw new IllegalStateException("SSO login failed: unable to fetch user info");
            }
            platformUser = ssoAuthenticator.toLocalUser(authUser, config);
        }

        Map<String, Object> resolved = resolveLocalUser(type, platformUser);
        // 带上平台侧用户标识（SAML2 即 NameID）：登出构造 IdP 单点登出请求（SLO）时需要，
        // ACS 会把它写进 Sa-Token 会话，LogoutRequest 的 NameID 就取自这里。
        Map<String, Object> result = new LinkedHashMap<>(resolved);
        result.put("platform_uid", str(platformUser, "platform_uid"));
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
            // 已绑定用户每次登录用平台最新信息刷新姓名/邮箱/账号（仅非空值），
            // 修正首次建号时字段缺失（如邮箱为空、账号用了复杂 uid）后无法自动纠正的问题。
            if (origin >= EnterprisePlatformService.ORIGIN_WECOM) {
                // 账号仍等于平台原始 ID（未被手动改过）时，同步为更友好的账号；手动改过则保留。
                String currentAccount = Rows.str(user, "account");
                String newAccount = str(platformUser, "account");
                String accountToSet = currentAccount != null && currentAccount.equals(platformUid)
                        && newAccount != null && !newAccount.equals(currentAccount) ? newAccount : null;
                userRepository.refreshProfile(Rows.lng(user, "id", 0L),
                        str(platformUser, "name"), str(platformUser, "email"), accountToSet);
                user = userRepository.findById(Rows.lng(user, "id", 0L)).orElse(user);
            }
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

    /**
     * 认证源是否已真正配置：config JSON 含至少一个非空字段（非空串/非零数字/true 布尔）。
     * 用于 {@code platformStatus()} 决定是否在登录页渲染该 SSO 入口——仅打开发布开关
     * 而未填写任何配置的行不渲染，避免登录页出现点了会失败的入口。
     */
    private boolean configHasContent(int type, String configJson) {
        Map<String, Object> config = ssoAuthenticator.parseConfig(configJson);
        for (Map.Entry<String, Object> e : config.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof Boolean) {
                if (Boolean.TRUE.equals(v)) {
                    return true;
                }
                continue;
            }
            if (v instanceof Number) {
                return true;
            }
            if (!String.valueOf(v).trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从请求推导当前访问基础地址（scheme://host[:port][contextPath]），用于动态生成 SSO 回调地址。
     *
     * <p>优先级：</p>
     * <ol>
     *   <li>反向代理透传的 {@code X-Forwarded-Proto} + {@code X-Forwarded-Host}（花生壳等隧道场景）；</li>
     *   <li>浏览器同源请求自带的 {@code Origin} 头（最可靠，等于用户实际访问的公网/内网入口）；</li>
     *   <li>{@code Referer} 兜底；</li>
     *   <li>最后回退到请求自身的 scheme/host/port。</li>
     * </ol>
     * <p>回调地址因此不必写死在配置里：访问域名变化（如 localhost ↔ 花生壳域名）时自动适配，
     * 且与前端 {@code getSQLBotAddr()}（origin + pathname）保持一致。</p>
     */
    /**
     * 回调地址：配置里显式填写时优先使用（对齐 IdP 后台登记的回调域名），否则按当前访问来源推导。
     *
     * <p>IdP 只登记了隧道/公网域名时，用 127.0.0.1 等其它入口访问也不会被 IdP 以
     * “Invalid redirect” 拒绝；配置留空时保持原来的动态推导行为。</p>
     */
    private String resolveRedirect(Map<String, Object> config, HttpServletRequest request) {
        String configured = firstNonBlank(Rows.str(config, "redirect_uri"), Rows.str(config, "redirect_url"),
                Rows.str(config, "casCallbackDomain"));
        return isNotBlank(configured) ? configured.trim() : redirectUri(request);
    }

    /** SSO 认证源标识（与前端 {@code origin_mapping} 的名称一致）。 */
    private static String ssoTypeName(int type) {
        switch (type) {
            case SsoAuthenticator.TYPE_CAS:
                return "cas";
            case SsoAuthenticator.TYPE_OIDC:
                return "oidc";
            case SsoAuthenticator.TYPE_LDAP:
                return "ldap";
            case SsoAuthenticator.TYPE_OAUTH2:
                return "oauth2";
            case SsoAuthenticator.TYPE_SAML2:
                return "saml2";
            case EnterprisePlatformService.ORIGIN_WECOM:
                return "wecom";
            case EnterprisePlatformService.ORIGIN_DINGTALK:
                return "dingtalk";
            case EnterprisePlatformService.ORIGIN_LARK:
                return "lark";
            case EnterprisePlatformService.ORIGIN_LARKSUITE:
                return "larksuite";
            default:
                return "sso";
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String redirectUri(HttpServletRequest request) {
        return RequestBaseUrl.resolve(request);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
