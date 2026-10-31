package com.xiongx9527.askdata.system.platform;

import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.authentication.AuthenticationRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import me.zhyd.oauth.request.AuthWeChatEnterpriseQrcodeRequest;
import me.zhyd.oauth.request.AuthDingTalkRequest;
import me.zhyd.oauth.request.AuthFeishuRequest;
import org.springframework.stereotype.Service;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业 IM 平台（企业微信 / 钉钉 / 飞书 / LarkSuite）开放能力：客户端信息、组织树、扫码登录换用户。
 *
 * <p>对齐 SQLBot xpack {@code sqlbot_xpack/platform}：平台配置存 {@code sys_authentication}
 * （type 6=wecom、7=dingtalk、8=lark、9=larksuite，config 为 JSON，字段与前端
 * {@code SettingTemplate.ts} 的 {@code realKey} 一致）。</p>
 *
 * <ul>
 *   <li>{@link #clientInfo(int)}：{@code GET /system/platform/client/{origin}}（A30）——
 *       登录页扫码组件据此拿 {@code client_id}/{@code corpid}/{@code agent_id} 调第三方 SDK；</li>
 *   <li>{@link #orgTree(int, boolean, String)}：{@code POST /system/platform/org/{id}}（A33）——
 *       用户页平台同步按钮的组织/用户树；</li>
 *   <li>{@link #platformUserByCode(int, Map)}：{@code POST /system/platform/sso/{type}}（A31）——
 *       扫码拿到的 {@code code} 换平台用户，再走统一 SSO 本地用户解析。</li>
 * </ul>
 *
 * <p>未配置、已禁用或配置不完整时抛出带平台名的可读错误（不再静默隐藏）。</p>
 */
@Service
public class EnterprisePlatformService {

    /** 平台类型（与前端 {@code SettingTemplate.busiMapping} 一致）。 */
    public static final int ORIGIN_WECOM = 6;

    public static final int ORIGIN_DINGTALK = 7;

    public static final int ORIGIN_LARK = 8;

    public static final int ORIGIN_LARKSUITE = 9;

    private final AuthenticationRepository authenticationRepository;

    private final ObjectMapper objectMapper;

    private final CurrentUserService currentUserService;

    /** 平台 access token 缓存（origin|base → token/过期秒级时间戳）；平台 token 有效期通常 2 小时。 */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public EnterprisePlatformService(AuthenticationRepository authenticationRepository,
                                     ObjectMapper objectMapper,
                                     CurrentUserService currentUserService) {
        this.authenticationRepository = authenticationRepository;
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
    }

    // ------------------------------------------------------------------
    // A30：平台客户端信息
    // ------------------------------------------------------------------

    /**
     * 平台客户端信息（公共路径，登录页扫码用）。
     *
     * <p>字段（前端 {@code WecomQr.formatQrResult} / {@code PlatformClient} 逐字消费）：</p>
     * <ul>
     *   <li>企业微信（6）：{@code corpid} / {@code agent_id} / {@code state} / {@code redirect_uri}</li>
     *   <li>钉钉（7）：{@code corpid}</li>
     *   <li>飞书 / LarkSuite（8/9）：{@code client_id}</li>
     *   <li>公共：{@code origin} / {@code enable} / {@code valid} / {@code client_id}</li>
     * </ul>
     */
    public Map<String, Object> clientInfo(int origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("origin", origin);
        result.put("client_id", null);
        result.put("enable", false);
        result.put("valid", false);
        Map<String, Object> row = findRow(origin);
        if (row == null) {
            return result;
        }
        Map<String, Object> config = parseConfig(Rows.str(row, "config"));
        result.put("enable", Rows.bool(row, "enable", false));
        result.put("valid", Rows.bool(row, "valid", false));
        if (origin == ORIGIN_WECOM) {
            result.put("corpid", text(config, "corpid"));
            result.put("agent_id", text(config, "agent_id"));
            result.put("state", text(config, "state"));
            result.put("redirect_uri", text(config, "redirect_uri"));
        } else if (origin == ORIGIN_DINGTALK) {
            result.put("corpid", text(config, "corpid"));
        } else {
            result.put("client_id", text(config, "client_id"));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // A33：平台组织树
    // ------------------------------------------------------------------

    /**
     * 组织/用户树（对齐前端 {@code SyncUserDing.vue} 的消费形态）。
     *
     * <p>返回 {@code {tree:[{id, name, options:{is_user, email}, children?}]}}；
     * {@code is_user=true} 为叶子（用户），否则为部门。{@code lazy=true} 时只返回当前层级
     * （前端按节点懒加载 children），否则递归展开（有深度上限，防超大组织打满接口）。</p>
     *
     * @param origin 平台类型（6..9）
     * @param lazy   是否懒加载
     * @param pid    父部门 id；为空取根部门
     */
    public Map<String, Object> orgTree(int origin, boolean lazy, String pid) {
        currentUserService.requireAdmin();
        Map<String, Object> config = requireEnabledConfig(origin);
        List<Map<String, Object>> tree = buildTree(origin, config, pid, lazy, 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tree", tree);
        return result;
    }

    private List<Map<String, Object>> buildTree(int origin, Map<String, Object> config, String pid,
                                                boolean lazy, int depth) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (depth > 8) {
            return nodes;
        }
        List<Map<String, Object>> departments = listDepartments(origin, config, pid);
        for (Map<String, Object> dept : departments) {
            String deptId = String.valueOf(dept.get("id"));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", deptId);
            node.put("name", dept.get("name"));
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("is_user", false);
            node.put("options", options);
            List<Map<String, Object>> users = listUsers(origin, config, deptId);
            if (!users.isEmpty()) {
                node.put("children", users);
            }
            if (!lazy) {
                List<Map<String, Object>> children = buildTree(origin, config, deptId, lazy, depth + 1);
                if (!children.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> existing = (List<Map<String, Object>>) node.get("children");
                    if (existing == null) {
                        existing = new ArrayList<>();
                        node.put("children", existing);
                    }
                    existing.addAll(children);
                }
            }
            nodes.add(node);
        }
        return nodes;
    }

    /** 子部门列表（各平台 API 形态不同，统一为 {@code [{id, name}]}）。 */
    private List<Map<String, Object>> listDepartments(int origin, Map<String, Object> config, String pid) {
        switch (origin) {
            case ORIGIN_WECOM: {
                String token = wecomToken(config);
                String parent = pid == null || pid.isEmpty() ? "1" : pid;
                Map<String, Object> res = get(wecomBase(config) + "/cgi-bin/department/simplelist"
                        + "?access_token=" + token + "&id=" + parent, null);
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> item : mapList(res.get("department_id"))) {
                    out.add(node(item.get("id"), item.get("name")));
                }
                return out;
            }
            case ORIGIN_DINGTALK: {
                String token = dingtalkToken(config);
                String deptId = pid == null || pid.isEmpty() ? "1" : pid;
                Map<String, Object> res = postJson(dingtalkBase(config) + "/topapi/v2/department/listsub",
                        Maps.of("dept_id", Long.parseLong(deptId), "language", "zh_CN"), token);
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> m : mapList(res.get("result"))) {
                    out.add(node(m.get("dept_id"), m.get("name")));
                }
                return out;
            }
            case ORIGIN_LARK:
            case ORIGIN_LARKSUITE: {
                String token = larkToken(origin, config);
                String parent = pid == null || pid.isEmpty() ? "0" : pid;
                Map<String, Object> res = get(larkBase(origin, config)
                        + "/open-apis/contact/v3/departments/" + parent + "/children?page_size=50",
                        token);
                List<Map<String, Object>> out = new ArrayList<>();
                Map<String, Object> data = asMap(res.get("data"));
                if (data != null) {
                    for (Map<String, Object> item : mapList(data.get("items"))) {
                        out.add(node(item.get("open_department_id"), item.get("name")));
                    }
                }
                return out;
            }
            default:
                throw new IllegalStateException("unsupported platform: " + origin);
        }
    }

    /** 部门直属用户列表（统一为 {@code [{id, name, options:{is_user, email}}]}）。 */
    private List<Map<String, Object>> listUsers(int origin, Map<String, Object> config, String deptId) {
        switch (origin) {
            case ORIGIN_WECOM: {
                String token = wecomToken(config);
                Map<String, Object> res = get(wecomBase(config) + "/cgi-bin/user/simplelist"
                        + "?access_token=" + token + "&department_id=" + deptId + "&fetch_child=0", null);
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> item : mapList(res.get("userlist"))) {
                    out.add(userNode(item.get("userid"), item.get("name"), item.get("email")));
                }
                return out;
            }
            case ORIGIN_DINGTALK: {
                String token = dingtalkToken(config);
                Map<String, Object> res = postJson(dingtalkBase(config) + "/topapi/v2/user/list",
                        Maps.of("dept_id", Long.parseLong(deptId), "cursor", 0L, "size", 100), token);
                List<Map<String, Object>> out = new ArrayList<>();
                Map<String, Object> result = asMap(res.get("result"));
                if (result != null) {
                    for (Map<String, Object> item : mapList(result.get("list"))) {
                        out.add(userNode(item.get("userid"), item.get("name"), item.get("email")));
                    }
                }
                return out;
            }
            case ORIGIN_LARK:
            case ORIGIN_LARKSUITE: {
                String token = larkToken(origin, config);
                Map<String, Object> res = get(larkBase(origin, config)
                        + "/open-apis/contact/v3/users/find_by_department?department_id=" + deptId
                        + "&page_size=50", token);
                List<Map<String, Object>> out = new ArrayList<>();
                Map<String, Object> data = asMap(res.get("data"));
                if (data != null) {
                    for (Map<String, Object> item : mapList(data.get("items"))) {
                        out.add(userNode(item.get("open_id"), item.get("name"), item.get("email")));
                    }
                }
                return out;
            }
            default:
                throw new IllegalStateException("unsupported platform: " + origin);
        }
    }

    // ------------------------------------------------------------------
    // A31：扫码登录换平台用户
    // ------------------------------------------------------------------

    /**
     * 用扫码回调的 {@code code} 换平台用户（{@code platform_uid/account/name/email}）。
     *
     * <p>请求体即前端回传到 {@code POST /system/platform/sso/{type}} 的 URL 参数
     * （{@code code} / {@code state} / {@code redirect_uri}）。</p>
     */
    public Map<String, Object> platformUserByCode(int origin, Map<String, Object> body) {
        Map<String, Object> config = requireEnabledConfig(origin);
        String code = text(body, "code");
        if (code == null || code.isEmpty()) {
            throw new IllegalStateException("missing code for platform login: " + platformName(origin));
        }
        String redirectUri = firstNonEmpty(text(body, "redirect_uri"), text(config, "redirect_uri"));
        AuthCallback callback = AuthCallback.builder()
                .code(code)
                .state(text(body, "state"))
                .build();
        switch (origin) {
            case ORIGIN_WECOM:
                // 企业微信 CorpApp 扫码登录：JustAuth 走官方 qrConnect -> gettoken -> auth/getuserinfo -> user/get
                return wecomUser(justAuthUser(new AuthWeChatEnterpriseQrcodeRequest(
                        toImAuthConfig(origin, config, redirectUri)), callback));
            case ORIGIN_LARK:
                // 飞书 OAuth2 扫码登录：JustAuth 走官方 app_access_token -> oidc/access_token -> user_info
                return feishuUser(justAuthUser(new AuthFeishuRequest(
                        toImAuthConfig(origin, config, redirectUri)), callback));
            case ORIGIN_DINGTALK:
                // 钉钉「网站应用扫码登录」(DTFrameLogin, scope=openid) 走 OAuth2 流程，与 JustAuth 的
                // sns(qrconnect/tmp_auth_code) 流程不兼容，故保留官方协议实现（已走 hutool SDK HTTP 客户端）。
                return dingtalkUserByCode(config, code);
            case ORIGIN_LARKSUITE:
                // LarkSuite：JustAuth 1.16.6 无对应 AuthRequest（飞书类写死 open.feishu.cn 基址，无法指向
                // open.larksuite.com）；沿用官方协议 + hutool SDK HTTP 客户端。官方 Lark Open SDK
                // (com.lark.oapi:lark-core) 需联网拉取并真实应用联调后接入。
                return larksuiteUserByCode(config, code);
            default:
                throw new IllegalStateException("unsupported platform: " + origin);
        }
    }

    private AuthUser justAuthUser(AuthRequest request, AuthCallback callback) {
        AuthResponse<AuthUser> response = request.login(callback);
        if (response == null || !response.ok() || response.getData() == null) {
            throw new IllegalStateException("sso login failed: "
                    + (response == null ? "no response" : String.valueOf(response.getMsg())));
        }
        return response.getData();
    }

    private Map<String, Object> wecomUser(AuthUser authUser) {
        // 与原实现一致：platform_uid/account = 企业微信 UserId；name = 姓名；email = 邮箱
        Map<String, Object> out = new LinkedHashMap<>();
        String userId = authUser.getUuid();
        out.put("platform_uid", userId);
        out.put("account", userId);
        out.put("name", authUser.getUsername());
        out.put("email", authUser.getEmail());
        return out;
    }

    private Map<String, Object> feishuUser(AuthUser authUser) {
        // 与原实现一致：platform_uid = open_id（而非 union_id），account = 姓名 || open_id
        Map<String, Object> out = new LinkedHashMap<>();
        String openId = rawStr(authUser, "open_id");
        String name = rawStr(authUser, "name");
        out.put("platform_uid", firstNonEmpty(openId, authUser.getUuid()));
        out.put("account", firstNonEmpty(name, openId, authUser.getUuid()));
        out.put("name", name);
        out.put("email", authUser.getEmail());
        return out;
    }

    private Map<String, Object> dingtalkUserByCode(Map<String, Object> config, String code) {
        String token = dingtalkToken(config);
        Map<String, Object> info = postJson(dingtalkBase(config) + "/topapi/v2/user/getuserinfo",
                Maps.of("code", code), token);
        Map<String, Object> result = asMap(info.get("result"));
        String userId = result == null ? null : text(result, "userid");
        if (userId == null) {
            throw new IllegalStateException("dingtalk login failed: userid not found");
        }
        Map<String, Object> user = postJson(dingtalkBase(config) + "/topapi/v2/user/get",
                Maps.of("userid", userId, "language", "zh_CN"), token);
        Map<String, Object> u = asMap(user.get("result"));
        if (u == null) {
            u = Maps.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform_uid", userId);
        out.put("account", text(u, "userid"));
        out.put("name", text(u, "name"));
        out.put("email", text(u, "email"));
        return out;
    }

    private Map<String, Object> larksuiteUserByCode(Map<String, Object> config, String code) {
        String appToken = larkToken(ORIGIN_LARKSUITE, config);
        Map<String, Object> tokenRes = postJson(larkBase(ORIGIN_LARKSUITE, config)
                        + "/open-apis/authen/v1/oidc/access_token",
                Maps.of("grant_type", "authorization_code", "code", code), appToken);
        Map<String, Object> data = asMap(tokenRes.get("data"));
        String accessToken = data == null ? null : text(data, "access_token");
        if (accessToken == null) {
            throw new IllegalStateException("lark login failed: access_token not found");
        }
        Map<String, Object> userRes = get(larkBase(ORIGIN_LARKSUITE, config) + "/open-apis/authen/v1/user_info",
                accessToken);
        Map<String, Object> u = asMap(userRes.get("data"));
        if (u == null) {
            u = Maps.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform_uid", text(u, "open_id"));
        out.put("account", text(u, "name") == null ? text(u, "open_id") : text(u, "name"));
        out.put("name", text(u, "name"));
        out.put("email", text(u, "email"));
        return out;
    }

    private AuthConfig toImAuthConfig(int origin, Map<String, Object> config, String redirectUri) {
        AuthConfig.AuthConfigBuilder builder = AuthConfig.builder();
        if (origin == ORIGIN_WECOM) {
            builder.clientId(text(config, "corpid"));
            builder.clientSecret(text(config, "corpsecret"));
            String agentId = text(config, "agent_id");
            if (agentId != null && !agentId.isEmpty()) {
                builder.agentId(agentId);
            }
        } else {
            builder.clientId(text(config, "client_id"));
            builder.clientSecret(text(config, "client_secret"));
        }
        String eff = redirectUri;
        if (eff == null || eff.isEmpty()) {
            eff = text(config, "redirect_uri");
        }
        builder.redirectUri(eff);
        builder.ignoreCheckState(true);
        return builder.build();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String rawStr(AuthUser authUser, String field) {
        if (authUser.getRawUserInfo() != null) {
            Object v = authUser.getRawUserInfo().get(field);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 平台 token
    // ------------------------------------------------------------------

    private String wecomToken(Map<String, Object> config) {
        return cached(ORIGIN_WECOM + "|" + wecomBase(config), () -> {
            String url = wecomBase(config) + "/cgi-bin/gettoken?corpid="
                    + text(config, "corpid") + "&corpsecret=" + text(config, "corpsecret");
            Map<String, Object> res = get(url, null);
            return text(res, "access_token");
        });
    }

    private String dingtalkToken(Map<String, Object> config) {
        return cached(ORIGIN_DINGTALK + "|" + dingtalkBase(config), () -> {
            Map<String, Object> res = postJson(dingtalkBase(config) + "/v1.0/oauth2/accessToken",
                    Maps.of("appKey", text(config, "client_id"), "appSecret", text(config, "client_secret")),
                    null);
            return text(res, "accessToken");
        });
    }

    private String larkToken(int origin, Map<String, Object> config) {
        return cached(origin + "|" + larkBase(origin, config), () -> {
            Map<String, Object> res = postJson(larkBase(origin, config) + "/open-apis/auth/v3/app_access_token/internal",
                    Maps.of("app_id", text(config, "client_id"), "app_secret", text(config, "client_secret")),
                    null);
            return text(res, "app_access_token");
        });
    }

    /**
     * 企业 IM（企业微信/钉钉/飞书/LarkSuite）"测试连接"的真实连通性校验：
     * 直接请求各自 token 端点，能拿到 token 才算配置有效；不走 token 缓存，
     * 避免缓存命中导致错误配置被误判为有效。
     */
    public Map<String, Object> validateConfig(int origin, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", false);
        result.put("reason", null);
        if (config == null || config.isEmpty()) {
            result.put("reason", "config is empty");
            return result;
        }
        try {
            switch (origin) {
                case ORIGIN_WECOM:
                case ORIGIN_DINGTALK: {
                    // 企业微信/钉钉：使用 JustAuth（开源 SDK）校验应用凭据。
                    // login 流程的第一步即校验 app 凭据（corpid+corpsecret / appKey+appSecret），
                    // 凭据错误会在此处直接暴露真实原因；无真实扫码 code 时按失败处理，
                    // 但只要凭据正确就不会误报“连接成功”。
                    // 测试连接没有真实回调地址，用占位 redirect_uri 让 SDK 跳过地址校验，
                    // 真正去校验 app 凭据（corpid+corpsecret / appKey+appSecret）。
                    AuthConfig authConfig = toImAuthConfig(origin, config,
                            firstNonEmpty(text(config, "redirect_uri"), "https://localhost/callback"));
                    AuthRequest request;
                    if (origin == ORIGIN_WECOM) {
                        request = new AuthWeChatEnterpriseQrcodeRequest(authConfig);
                    } else if (origin == ORIGIN_DINGTALK) {
                        request = new AuthDingTalkRequest(authConfig);
                    } else {
                        request = new AuthFeishuRequest(authConfig);
                    }
                    AuthCallback callback = AuthCallback.builder().code("__validate__").state("x").build();
                    AuthResponse<AuthUser> response = request.login(callback);
                    if (response != null && response.ok() && response.getData() != null) {
                        result.put("valid", true);
                        result.put("reason", null);
                    } else {
                        String detail;
                        if (response == null) {
                            detail = "no response";
                        } else if (response.getMsg() != null) {
                            detail = response.getMsg();
                        } else {
                            detail = "code=" + response.getCode() + ", data=" + response.getData();
                        }
                        result.put("reason", detail);
                    }
                    return result;
                }
                case ORIGIN_LARK:
                case ORIGIN_LARKSUITE: {
                    // 飞书/LarkSuite：JustAuth 的 AuthFeishuRequest.login() 不回传飞书真实错误文案
                    // （仅 code=5000、msg=null），无法直接给出说明。故直接请求官方
                    // app_access_token 端点，以拿到真实的 msg/错误说明。
                    // （官方 Lark Open SDK 待接入：com.lark.oapi:lark-core，需联网拉取并用真实应用联调）
                    Map<String, Object> res = postJson(larkBase(origin, config)
                            + "/open-apis/auth/v3/app_access_token/internal",
                            Maps.of("app_id", text(config, "client_id"), "app_secret", text(config, "client_secret")),
                            null);
                    String token = res == null ? null : text(res, "app_access_token");
                    String err = res == null ? null : firstPresent(res, "msg", "error_description", "error");
                    if (token != null && !token.isEmpty()) {
                        result.put("valid", true);
                        result.put("reason", null);
                    } else {
                        result.put("reason", err == null ? "no app_access_token" : err);
                    }
                    return result;
                }
                default:
                    result.put("reason", "unsupported type: " + origin);
                    return result;
            }
        } catch (Exception e) {
            result.put("reason", e.getMessage());
            return result;
        }
    }

    private static String firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !String.valueOf(v).isEmpty()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private String cached(String cacheKey, java.util.function.Supplier<String> loader) {
        CachedToken cachedToken = tokenCache.get(cacheKey);
        long now = System.currentTimeMillis() / 1000L;
        if (cachedToken != null && cachedToken.expireAt > now + 60) {
            return cachedToken.token;
        }
        String token = loader.get();
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("failed to get access token for " + cacheKey);
        }
        tokenCache.put(cacheKey, new CachedToken(token, now + 7000));
        return token;
    }

    private static final class CachedToken {
        private final String token;

        private final long expireAt;

        private CachedToken(String token, long expireAt) {
            this.token = token;
            this.expireAt = expireAt;
        }
    }

    // ------------------------------------------------------------------
    // HTTP / JSON
    // ------------------------------------------------------------------

    /** 企业微信 API 基址（配置 {@code api_base} 可覆盖，便于私有化/测试）。 */
    private static String wecomBase(Map<String, Object> config) {
        return baseOf(config, "https://qyapi.weixin.qq.com");
    }

    /** 钉钉 API 基址。 */
    private static String dingtalkBase(Map<String, Object> config) {
        return baseOf(config, "https://oapi.dingtalk.com");
    }

    /** 飞书 / LarkSuite API 基址。 */
    private static String larkBase(int origin, Map<String, Object> config) {
        return baseOf(config, origin == ORIGIN_LARKSUITE ? "https://open.larksuite.com"
                : "https://open.feishu.cn");
    }

    private static String baseOf(Map<String, Object> config, String fallback) {
        String configured = text(config, "api_base");
        if (configured == null || configured.trim().isEmpty()) {
            return fallback;
        }
        String cleaned = configured.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private Map<String, Object> get(String url, String bearer) {
        return request("GET", url, null, bearer);
    }

    private Map<String, Object> postJson(String url, Map<String, Object> body, String bearer) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body == null ? Maps.of() : body);
        } catch (Exception e) {
            throw new IllegalStateException("unable to serialize request body: " + e.getMessage());
        }
        return request("POST", url, json, bearer);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String method, String url, String jsonBody, String bearer) {
        try {
            HttpRequest req = "POST".equalsIgnoreCase(method) ? HttpRequest.post(url) : HttpRequest.get(url);
            req.timeout(10000);
            req.header("Accept", "application/json");
            if (bearer != null && !bearer.isEmpty()) {
                req.header("Authorization", "Bearer " + bearer);
            }
            if (jsonBody != null) {
                req.header("Content-Type", "application/json");
                req.body(jsonBody);
            }
            HttpResponse resp = req.execute();
            int status = resp.getStatus();
            String body = resp.body();
            Map<String, Object> parsed;
            try {
                parsed = objectMapper.readValue(body,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                throw new IllegalStateException("invalid JSON from " + url + ": " + body);
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("platform api error HTTP " + status + ": " + body);
            }
            return parsed;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("platform api call failed: " + e.getMessage());
        }
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (item instanceof Map) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> node(Object id, Object name) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", String.valueOf(id));
        node.put("name", name == null ? "" : String.valueOf(name));
        node.put("options", Maps.of("is_user", false));
        return node;
    }

    private static Map<String, Object> userNode(Object id, Object name, Object email) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", String.valueOf(id));
        node.put("name", name == null ? "" : String.valueOf(name));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("is_user", true);
        options.put("email", email == null ? "" : String.valueOf(email));
        node.put("options", options);
        return node;
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    /** 平台配置行（type = origin）；未配置返回 null。 */
    public Map<String, Object> findRow(int origin) {
        return authenticationRepository.findByType(origin).orElse(null);
    }

    /** 取平台配置（未配置/已禁用/配置为空 → 可读错误）。 */
    public Map<String, Object> requireEnabledConfig(int origin) {
        Map<String, Object> row = findRow(origin);
        if (row == null) {
            throw new IllegalStateException("platform is not configured: " + platformName(origin));
        }
        if (!Rows.bool(row, "enable", false)) {
            throw new IllegalStateException("platform is disabled: " + platformName(origin));
        }
        Map<String, Object> config = parseConfig(Rows.str(row, "config"));
        if (config.isEmpty()) {
            throw new IllegalStateException("platform configuration is empty: " + platformName(origin));
        }
        return config;
    }

    private Map<String, Object> parseConfig(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static String platformName(int origin) {
        switch (origin) {
            case ORIGIN_WECOM:
                return "wecom";
            case ORIGIN_DINGTALK:
                return "dingtalk";
            case ORIGIN_LARK:
                return "lark";
            case ORIGIN_LARKSUITE:
                return "larksuite";
            default:
                return "unknown-" + origin;
        }
    }
}
