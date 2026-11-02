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
            // 钉钉扫码登录（DTFrameLogin）需要 client_id(=AppKey) 与 redirect_uri；corpid 供展示
            result.put("corpid", text(config, "corpid"));
            result.put("client_id", text(config, "client_id"));
            result.put("redirect_uri", text(config, "redirect_uri"));
        } else {
            // 飞书 / LarkSuite 扫码登录：返回 client_id 与固定 redirect_uri（避免按访问 origin 动态生成导致与后台登记不一致）
            result.put("client_id", text(config, "client_id"));
            result.put("redirect_uri", text(config, "redirect_uri"));
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
        // 直属成员：pid 指定时取该部门，pid 为空取根部门。作为叶子节点与子部门并列返回，
        // 使懒加载展开部门时能看到「本部门成员」，而不是只看到子部门。
        nodes.addAll(listUsers(origin, config, pid));
        List<Map<String, Object>> departments = listDepartments(origin, config, pid);
        for (Map<String, Object> dept : departments) {
            String deptId = String.valueOf(dept.get("id"));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", deptId);
            node.put("name", dept.get("name"));
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("is_user", false);
            node.put("options", options);
            if (!lazy) {
                List<Map<String, Object>> children = buildTree(origin, config, deptId, lazy, depth + 1);
                if (!children.isEmpty()) {
                    node.put("children", children);
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
                requireOk(res, "wecom department/simplelist");
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> item : mapList(res.get("department_id"))) {
                    out.add(node(item.get("id"), item.get("name")));
                }
                return out;
            }
            case ORIGIN_DINGTALK: {
                String token = dingtalkToken(config);
                String deptId = pid == null || pid.isEmpty() ? "1" : pid;
                // 钉钉 topapi 接口要求 access_token 作为 URL 查询参数（非 Authorization Bearer 头）
                Map<String, Object> res = postJson(dingtalkBase(config)
                                + "/topapi/v2/department/listsub?access_token=" + token,
                        Maps.of("dept_id", Long.parseLong(deptId), "language", "zh_CN"), null);
                requireOk(res, "dingtalk department/listsub");
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
                String dept = (deptId == null || deptId.isEmpty()) ? "1" : deptId;
                String token = wecomToken(config);
                Map<String, Object> res = get(wecomBase(config) + "/cgi-bin/user/simplelist"
                        + "?access_token=" + token + "&department_id=" + dept + "&fetch_child=0", null);
                requireOk(res, "wecom user/simplelist");
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> item : mapList(res.get("userlist"))) {
                    out.add(userNode(item.get("userid"), item.get("name"), item.get("email")));
                }
                return out;
            }
            case ORIGIN_DINGTALK: {
                String dept = (deptId == null || deptId.isEmpty()) ? "1" : deptId;
                String token = dingtalkToken(config);
                Map<String, Object> res = postJson(dingtalkBase(config)
                                + "/topapi/v2/user/list?access_token=" + token,
                        Maps.of("dept_id", Long.parseLong(dept), "cursor", 0L, "size", 100), null);
                requireOk(res, "dingtalk user/list");
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
                String dept = (deptId == null || deptId.isEmpty()) ? "0" : deptId;
                String token = larkToken(origin, config);
                Map<String, Object> res = get(larkBase(origin, config)
                        + "/open-apis/contact/v3/users/find_by_department?department_id=" + dept
                        + "&page_size=50", token);
                requireOk(res, "lark find_by_department");
                List<Map<String, Object>> out = new ArrayList<>();
                Map<String, Object> data = asMap(res.get("data"));
                if (data != null) {
                    for (Map<String, Object> item : mapList(data.get("items"))) {
                        // 节点 id 用可读的 user_id（回退 open_id），与登录账号对齐
                        out.add(userNode(firstNonEmpty(text(item, "user_id"), text(item, "open_id")),
                                item.get("name"), item.get("email")));
                    }
                }
                return out;
            }
            default:
                throw new IllegalStateException("unsupported platform: " + origin);
        }
    }

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
        // 钉钉 DTFrameLogin 扫码登录回调用 authCode（同时回传 code，二者等值）；其余平台仍用 code。
        String code = firstNonEmpty(text(body, "authCode"), text(body, "code"));
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
                // 企业微信 CorpApp 扫码登录：官方 gettoken -> user/getuserinfo -> user/get。
                // 配置了 api_base（私有化/代理/测试）时走本地可重定向的 REST 实现；
                // 否则交给 JustAuth（其域名写死 qyapi.weixin.qq.com）。
                if (apiBaseConfigured(config)) {
                    return wecomUserByCode(config, code);
                }
                return wecomUser(justAuthUser(new AuthWeChatEnterpriseQrcodeRequest(
                        toImAuthConfig(origin, config, redirectUri)), callback));
            case ORIGIN_LARK:
                // 飞书 OAuth2 扫码登录：官方 app_access_token -> oidc/access_token -> user_info。
                // 同企业微信：配置 api_base 时走 REST 实现（JustAuth 写死 open.feishu.cn）。
                if (apiBaseConfigured(config)) {
                    return feishuUserByCode(config, code);
                }
                return feishuUser(config, justAuthUser(new AuthFeishuRequest(
                        toImAuthConfig(origin, config, redirectUri)), callback));
            case ORIGIN_DINGTALK:
                // 钉钉扫码登录有两类来源：网页 DTFrameLogin(scope=openid) 回传 SNS 临时 code；
                // 钉钉客户端内 JSAPI 免登(dd.runtime.permission.requestAuthCode) 回传内部 code。
                // 二者共用 /system/platform/sso/7，靠 state 区分（client 内免登 state 含 "client"）。
                return dingtalkUserByCode(config, code, text(body, "state"));
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

    /**
     * 企业微信扫码 code 换用户（{@code api_base} 指向的 REST 实现）。
     *
     * <p>与企业微信官方 CorpApp 扫码登录同构：{@code gettoken} 取应用 token →
     * {@code user/getuserinfo} 用 code 换 UserId → {@code user/get} 补全姓名/邮箱。
     * 用于私有化部署、API 代理与测试（JustAuth 的域名写死，无法指向自建网关）。</p>
     */
    private Map<String, Object> wecomUserByCode(Map<String, Object> config, String code) {
        String token = wecomToken(config);
        Map<String, Object> info = get(wecomBase(config) + "/cgi-bin/user/getuserinfo?access_token="
                + urlEncode(token) + "&code=" + urlEncode(code), null);
        requireOk(info, "wecom user/getuserinfo");
        String userId = firstNonEmpty(text(info, "userid"), text(info, "UserId"));
        if (userId == null || userId.isEmpty()) {
            throw new IllegalStateException("wecom login failed: userid not found; wecom_resp=" + info);
        }
        Map<String, Object> detail = get(wecomBase(config) + "/cgi-bin/user/get?access_token="
                + urlEncode(token) + "&userid=" + urlEncode(userId), null);
        requireOk(detail, "wecom user/get");
        String name = firstNonEmpty(text(detail, "name"), text(detail, "alias"), userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform_uid", userId);
        out.put("account", userId);
        out.put("name", name);
        out.put("email", text(detail, "email"));
        return out;
    }

    /**
     * 飞书扫码 code 换用户（{@code api_base} 指向的 REST 实现）。
     *
     * <p>与飞书官方 OAuth2 同构：{@code app_access_token} 取应用 token →
     * {@code authen/v1/oidc/access_token} 用 code 换用户 token → {@code authen/v1/user_info} 取资料。</p>
     */
    private Map<String, Object> feishuUserByCode(Map<String, Object> config, String code) {
        String base = larkBase(ORIGIN_LARK, config);
        Map<String, Object> appRes = postJson(base + "/open-apis/auth/v3/app_access_token/internal",
                Maps.of("app_id", text(config, "client_id"), "app_secret", text(config, "client_secret")), null);
        String appToken = firstNonEmpty(text(appRes, "app_access_token"), text(appRes, "tenant_access_token"));
        if (appToken == null || appToken.isEmpty()) {
            throw new IllegalStateException("feishu login failed: app_access_token not found; resp=" + appRes);
        }
        Map<String, Object> tokenRes = postJson(base + "/open-apis/authen/v1/oidc/access_token",
                Maps.of("grant_type", "authorization_code", "code", code), appToken);
        Map<String, Object> tokenData = asMap(tokenRes.get("data"));
        String userToken = tokenData == null ? null : text(tokenData, "access_token");
        if (userToken == null || userToken.isEmpty()) {
            throw new IllegalStateException("feishu login failed: user access_token not found; resp=" + tokenRes);
        }
        Map<String, Object> infoRes = get(base + "/open-apis/authen/v1/user_info", userToken);
        Map<String, Object> u = asMap(infoRes.get("data"));
        if (u == null) {
            throw new IllegalStateException("feishu login failed: user_info not found; resp=" + infoRes);
        }
        String userId = text(u, "user_id");
        String unionId = text(u, "union_id");
        String openId = text(u, "open_id");
        String mobile = text(u, "mobile");
        String email = firstNonEmpty(text(u, "email"), text(u, "enterprise_email"));
        String account = firstNonEmpty(email, userId, mobile, openId, unionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform_uid", firstNonEmpty(userId, unionId, openId));
        out.put("account", account);
        out.put("name", firstNonEmpty(text(u, "name"), account));
        out.put("email", email);
        return out;
    }

    private Map<String, Object> feishuUser(Map<String, Object> config, AuthUser authUser) {
        // 登录 user_info 未必含 user_id/open_id/mobile，用 contact API 按 union_id 补全。
        // account 优先邮箱（可读唯一），无邮箱回退 user_id > mobile > open_id > union_id。
        // platform_uid 用 union_id（稳定，兼容历史绑定）。
        String unionId = firstNonEmpty(rawStr(authUser, "union_id"), authUser.getUuid());
        String openId = rawStr(authUser, "open_id");
        String userId = rawStr(authUser, "user_id");
        String mobile = rawStr(authUser, "mobile");
        String name = firstNonEmpty(rawStr(authUser, "name"), authUser.getUsername(), authUser.getNickname());
        String email = firstNonEmpty(rawStr(authUser, "email"), rawStr(authUser, "enterprise_email"),
                authUser.getEmail());
        if (unionId != null && !unionId.isEmpty()) {
            try {
                String tenantToken = feishuTenantToken(config);
                Map<String, Object> res = get(larkBase(ORIGIN_LARK, config)
                        + "/open-apis/contact/v3/users/" + unionId + "?user_id_type=union_id", tenantToken);
                Map<String, Object> data = asMap(res.get("data"));
                Map<String, Object> u = data == null ? null : asMap(data.get("user"));
                if (u != null) {
                    name = firstNonEmpty(text(u, "name"), name);
                    email = firstNonEmpty(text(u, "email"), text(u, "enterprise_email"), email);
                    userId = firstNonEmpty(text(u, "user_id"), userId);
                    mobile = firstNonEmpty(text(u, "mobile"), mobile);
                    openId = firstNonEmpty(text(u, "open_id"), openId);
                }
            } catch (Exception ignored) {
                // contact API 不可用时回退登录 user_info
            }
        }
        String account = firstNonEmpty(email, userId, mobile, openId, unionId);
        Map<String, Object> out = new LinkedHashMap<>();
        // platform_uid 用 user_id（与组织树节点 id、用户同步绑定一致），union_id/open_id 回退
        out.put("platform_uid", firstNonEmpty(userId, unionId, openId));
        out.put("account", account);
        out.put("name", name == null ? account : name);
        out.put("email", email);
        return out;
    }

    private String feishuTenantToken(Map<String, Object> config) {
        return cached(ORIGIN_LARK + "|tenant|" + larkBase(ORIGIN_LARK, config), () -> {
            Map<String, Object> res = postJson(larkBase(ORIGIN_LARK, config)
                            + "/open-apis/auth/v3/tenant_access_token/internal",
                    Maps.of("app_id", text(config, "client_id"), "app_secret", text(config, "client_secret")), null);
            return text(res, "tenant_access_token");
        });
    }

    private Map<String, Object> dingtalkUserByCode(Map<String, Object> config, String code, String state) {
        // 钉钉扫码登录两类来源共用本方法，靠 state 区分：
        //  - 钉钉客户端内 JSAPI 免登（dd.runtime.permission.requestAuthCode）：state 含 "client"，
        //    回传内部 code，须走 topapi/v2/user/getuserinfo（内部流程）。
        //  - 网页 DTFrameLogin 扫码（scope=openid）：state 为 fit2cloud-dingtalk-qr，
        //    回传 SNS 临时 code（authCode），须走 sns 流程（sns/getuserinfo_bycode）。
        if (state != null && state.contains("client")) {
            return dingtalkUserByInternalCode(config, code);
        }
        return dingtalkUserByOauthCode(config, code);
    }

    /** 钉钉客户端内 JSAPI 免登（内部 code）。 */
    private Map<String, Object> dingtalkUserByInternalCode(Map<String, Object> config, String code) {
        String token = dingtalkToken(config);
        Map<String, Object> info = postJson(dingtalkBase(config)
                        + "/topapi/v2/user/getuserinfo?access_token=" + token,
                Maps.of("code", code), null);
        Map<String, Object> result = asMap(info.get("result"));
        String userId = result == null ? null : text(result, "userid");
        if (userId == null) {
            throw new IllegalStateException("dingtalk login failed: userid not found; dingtalk_resp=" + info);
        }
        Map<String, Object> user = postJson(dingtalkBase(config)
                        + "/topapi/v2/user/get?access_token=" + token,
                Maps.of("userid", userId, "language", "zh_CN"), null);
        Map<String, Object> u = asMap(user.get("result"));
        if (u == null) {
            u = Maps.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform_uid", userId);
        out.put("account", firstNonEmpty(text(u, "email"), userId));
        out.put("name", text(u, "name"));
        out.put("email", text(u, "email"));
        return out;
    }

    /**
     * 钉钉企业内部应用网页扫码登录（DTFrameLogin, scope=openid）走新版 OAuth2：
     * 用 AppKey/AppSecret 换用户 accessToken，再查 contact/users/me 取 unionId，
     * 最后用内部 token 经 topapi/v2/user/getbyunionid 换 userid 并补全资料。
     * （旧版 sns/gettoken 仅适用于「网站应用」，企业内部应用会报 40001 Secret 错误。）
     */
    private Map<String, Object> dingtalkUserByOauthCode(Map<String, Object> config, String code) {
        String clientId = text(config, "client_id");
        String clientSecret = text(config, "client_secret");
        Map<String, Object> tokenBody = new LinkedHashMap<>();
        tokenBody.put("clientId", clientId);
        tokenBody.put("clientSecret", clientSecret);
        tokenBody.put("code", code);
        tokenBody.put("grantType", "authorization_code");
        Map<String, Object> tokenRes = postJson("https://api.dingtalk.com/v1.0/oauth2/userAccessToken",
                tokenBody, null);
        String userToken = text(tokenRes, "accessToken");
        if (userToken == null || userToken.isEmpty()) {
            throw new IllegalStateException("dingtalk oauth login failed: userAccessToken not found; resp=" + tokenRes);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-acs-dingtalk-access-token", userToken);
        Map<String, Object> me = getWithHeaders("https://api.dingtalk.com/v1.0/contact/users/me", headers);
        String unionId = text(me, "unionId");
        String nick = text(me, "nick");
        String email = text(me, "email");
        String userId = null;
        String name = nick;
        String detailEmail = email;
        if (unionId != null && !unionId.isEmpty()) {
            try {
                String token = dingtalkToken(config);
                Map<String, Object> byUnion = postJson(dingtalkBase(config)
                                + "/topapi/user/getbyunionid?access_token=" + token,
                        Maps.of("unionid", unionId), null);
                Map<String, Object> r = asMap(byUnion.get("result"));
                if (r != null) {
                    userId = text(r, "userid");
                }
                if (userId != null && !userId.isEmpty()) {
                    Map<String, Object> user = postJson(dingtalkBase(config)
                                    + "/topapi/v2/user/get?access_token=" + token,
                            Maps.of("userid", userId, "language", "zh_CN"), null);
                    Map<String, Object> u = asMap(user.get("result"));
                    if (u != null) {
                        name = firstNonEmpty(text(u, "name"), nick);
                        detailEmail = firstNonEmpty(text(u, "email"), email);
                    }
                }
            } catch (Exception ignored) {
                // 换不到 userid/明细时退化为 unionId + me 字段
            }
        }
        // platform_uid 用 userid（与组织树节点 id、用户同步绑定一致）；unionId 仅作回退，
        // 否则登录与同步会给同一个人写两条不同的平台绑定。
        String platformUid = firstNonEmpty(userId, unionId);
        String account = firstNonEmpty(detailEmail, userId, platformUid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform_uid", platformUid);
        out.put("account", account);
        out.put("name", name == null ? account : name);
        out.put("email", detailEmail);
        return out;
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
                case ORIGIN_WECOM: {
                    // 直接请求企业微信 gettoken 校验 corpid+corpsecret（不依赖扫码 code）。
                    // 原实现用假 code "__validate__" 走 login，必然在“换用户”步骤失败，
                    // 导致凭据正确也报“不存在的临时授权码”；改为校验 token 端点更准确。
                    Map<String, Object> res = get(wecomBase(config) + "/cgi-bin/gettoken?corpid="
                            + text(config, "corpid") + "&corpsecret=" + text(config, "corpsecret"), null);
                    String token = res == null ? null : text(res, "access_token");
                    if (token != null && !token.isEmpty()) {
                        result.put("valid", true);
                        result.put("reason", null);
                    } else {
                        String detail = firstPresent(res, "errmsg", "error", "message");
                        result.put("reason", detail != null ? detail
                                : ("errcode=" + (res == null ? null : res.get("errcode"))));
                    }
                    return result;
                }
                case ORIGIN_DINGTALK: {
                    Map<String, Object> res = get(dingtalkBase(config) + "/gettoken?appkey="
                            + text(config, "client_id") + "&appsecret=" + text(config, "client_secret"), null);
                    String token = res == null ? null : text(res, "access_token");
                    if (token != null && !token.isEmpty()) {
                        result.put("valid", true);
                        result.put("reason", null);
                    } else {
                        String detail = firstPresent(res, "errmsg", "error", "message");
                        result.put("reason", detail != null ? detail
                                : ("errcode=" + (res == null ? null : res.get("errcode"))));
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

    /** 是否显式配置了 API 基址（私有化/代理/测试场景）。 */
    private static boolean apiBaseConfigured(Map<String, Object> config) {
        String configured = text(config, "api_base");
        return configured != null && !configured.trim().isEmpty();
    }

    /** 查询参数编码（URL 里拼 code/userid 等外部值）。 */
    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
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
        Map<String, String> headers = new LinkedHashMap<>();
        if (bearer != null && !bearer.isEmpty()) {
            headers.put("Authorization", "Bearer " + bearer);
        }
        return requestWithHeaders("GET", url, null, headers);
    }

    private Map<String, Object> postJson(String url, Map<String, Object> body, String bearer) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body == null ? Maps.of() : body);
        } catch (Exception e) {
            throw new IllegalStateException("unable to serialize request body: " + e.getMessage());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (bearer != null && !bearer.isEmpty()) {
            headers.put("Authorization", "Bearer " + bearer);
        }
        return requestWithHeaders("POST", url, json, headers);
    }

    private Map<String, Object> getWithHeaders(String url, Map<String, String> headers) {
        return requestWithHeaders("GET", url, null, headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestWithHeaders(String method, String url, String jsonBody,
                                                   Map<String, String> headers) {
        try {
            HttpRequest req = "POST".equalsIgnoreCase(method) ? HttpRequest.post(url) : HttpRequest.get(url);
            req.timeout(10000);
            req.header("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        req.header(e.getKey(), e.getValue());
                    }
                }
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

    /**
     * 平台返回非 0 错误码时抛出可读异常。
     *
     * <p>企业微信/钉钉用 {@code errcode}，飞书用 {@code code}；不校验的话接口失败会退化成
     * “组织树为空”，看不出是 IP 白名单、权限或凭证问题。</p>
     */
    private static void requireOk(Map<String, Object> res, String api) {
        Object code = res.get("errcode") != null ? res.get("errcode") : res.get("code");
        if (code instanceof Number && ((Number) code).intValue() != 0) {
            String msg = firstNonEmpty(text(res, "errmsg"), text(res, "msg"));
            throw new IllegalStateException(api + " failed: errcode " + code
                    + (msg == null ? "" : " " + msg));
        }
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
        String openId = text(u, "open_id");
        String unionId = text(u, "union_id");
        String userId = text(u, "user_id");
        String name = text(u, "name");
        String email = firstNonEmpty(text(u, "email"), text(u, "enterprise_email"));
        String account = firstNonEmpty(email, userId, openId, unionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform_uid", firstNonEmpty(openId, unionId));
        out.put("account", account);
        out.put("name", name == null ? account : name);
        out.put("email", email);
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
            // 钉钉 oapi gettoken：与 topapi 同 host（oapi.dingtalk.com），返回 access_token。
            String url = dingtalkBase(config) + "/gettoken?appkey="
                    + text(config, "client_id") + "&appsecret=" + text(config, "client_secret");
            Map<String, Object> res = get(url, null);
            return text(res, "access_token");
        });
    }

}
