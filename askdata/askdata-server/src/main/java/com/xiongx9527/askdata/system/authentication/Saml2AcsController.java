package com.xiongx9527.askdata.system.authentication;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.util.Rows;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SAML2 断言消费端点（ACS）。
 *
 * <p>IdP 用 HTTP-POST 绑定把 {@code SAMLResponse} 表单 POST 到 {@code spAcs}
 * （前端配置页写入 {@code <访问地址>/saml/sso}）。SPA 是静态页面收不到 POST body，
 * 所以由本端点接收、换用户、签发 token，再 302 回前端登录页把 token 交给前端。</p>
 *
 * <p>token 放在 URL 的 hash 段（{@code #/login?saml_token=...}）：hash 不会发送到服务端，
 * 也不会出现在 Referer 里，比放在查询串更安全。前端 {@code Handler.vue} 读到后立即写入
 * 本地存储并把参数从地址栏清掉。</p>
 */
@RestController
public class Saml2AcsController {

    private final AuthenticationService authenticationService;

    public Saml2AcsController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * POST /saml/sso：SAML2 ACS。
     *
     * @param params 表单参数，含 {@code SAMLResponse} 与可选的 {@code RelayState}
     */
    @PostMapping({"/saml/sso"})
    public ResponseEntity<Void> acs(@RequestParam Map<String, String> params, HttpServletRequest request) {
        String base = RequestBaseUrl.ofHost(request);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("SAMLResponse", params.get("SAMLResponse"));
            body.put("state", params.get("RelayState"));
            Map<String, Object> user = authenticationService.ssoLogin(
                    SsoAuthenticator.TYPE_SAML2, body, request);
            StpUtil.login(Rows.lng(user, "id", 0L));
            // 记住 IdP 侧用户标识（NameID），登出时用它构造 SAML LogoutRequest 终止单点会话
            String nameId = Rows.str(user, "platform_uid");
            if (nameId != null && !nameId.isEmpty()) {
                StpUtil.getSession().set(Saml2SsoClient.SESSION_NAME_ID, nameId);
            }
            // exp 随跳回登录页一并带上（hash 段不会发到服务端，也不会进 Referer）；
            // 与第三方登录 sso 端点返回的 exp 同源，避免前端拿不到 exp 而误判 token 过期。
            long exp = StpUtil.getTokenTimeout();
            return redirect(base + "#/login?saml_token=" + urlEncode(StpUtil.getTokenValue())
                    + "&saml_exp=" + exp);
        } catch (RuntimeException e) {
            // 断言无效/验签失败/账号被禁用等：带上可读原因回登录页，而不是给 IdP 一个 500 白页
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return redirect(base + "#/login?saml_error=" + urlEncode(message));
        }
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(302).location(URI.create(location)).build();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
