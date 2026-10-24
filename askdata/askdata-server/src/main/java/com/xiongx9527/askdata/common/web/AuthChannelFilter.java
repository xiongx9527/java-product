package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.embedded.AssistantRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 鉴权通道 Filter（对齐 SQLBot {@code apps/system/middleware/auth.py} 的 TokenMiddleware）。
 *
 * <p>判定顺序与 SQLBot <strong>逐字一致</strong>：</p>
 * <ol>
 *   <li>OPTIONS 预检 → 直接放行（但 {@code /system/assistant/info/{id}} 的预检要按 origin 匹配动态返回 CORS 头）；</li>
 *   <li>命中公共路径白名单 → 直接放行（白名单按<strong>请求行真实路径</strong>判定，不依赖 Host 头）；</li>
 *   <li>{@code X-SQLBOT-ASK-TOKEN}（API Key 通道）→ 校验；</li>
 *   <li>{@code X-SQLBOT-ASSISTANT-TOKEN}（嵌入助手通道）→ 校验；</li>
 *   <li>主通道 {@code X-SQLBOT-TOKEN}（Sa-Token）→ 由 {@code SaTokenConfigure} 的 Sa-Token 拦截器判定。</li>
 * </ol>
 *
 * <p>失败一律返回 401，且响应头带 {@code Access-Control-Allow-Origin: *}
 * （SQLBot 在每个鉴权失败响应上都加该头，嵌入 iframe 场景必需）。</p>
 *
 * <p>本 Filter 与 Sa-Token 拦截器的协作：白名单放行与两条支线通道校验成功后，
 * 会在 request 上打 {@link #ALLOWED_ATTRIBUTE} 标记；Sa-Token 拦截器看到该标记即放行
 * （支线通道的请求本来就没有 Sa-Token 登录态）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthChannelFilter implements Filter {

    /** request 属性标记：本 Filter 已完成放行判定（白名单或支线通道）。 */
    public static final String ALLOWED_ATTRIBUTE = "com.xiongx9527.askdata.auth.allowed";

    /** SQLBot 预检分支的正则（与 SQLBot {@code re.search(r'/system/assistant/info/(\d+)', ...)} 逐字一致）。 */
    private static final Pattern ASSISTANT_INFO_PATTERN = Pattern.compile("/system/assistant/info/(\\d+)");

    private final AuthWhitelist authWhitelist;

    private final ApiKeyTokenAuthenticator apiKeyTokenAuthenticator;

    private final EmbeddedTokenAuthenticator embeddedTokenAuthenticator;

    private final AssistantRepository assistantRepository;

    /** 错误文案统一走消息包（A4 / F-15）；过滤器早于 DispatcherServlet，需自行解析 locale。 */
    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    private final com.xiongx9527.askdata.common.i18n.AskdataLocaleResolver localeResolver;

    public AuthChannelFilter(AuthWhitelist authWhitelist,
                             ApiKeyTokenAuthenticator apiKeyTokenAuthenticator,
                             EmbeddedTokenAuthenticator embeddedTokenAuthenticator,
                             AssistantRepository assistantRepository,
                             com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver,
                             com.xiongx9527.askdata.common.i18n.AskdataLocaleResolver localeResolver) {
        this.authWhitelist = authWhitelist;
        this.apiKeyTokenAuthenticator = apiKeyTokenAuthenticator;
        this.embeddedTokenAuthenticator = embeddedTokenAuthenticator;
        this.assistantRepository = assistantRepository;
        this.messageResolver = messageResolver;
        this.localeResolver = localeResolver;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        AuthContext.clear();
        try {
            if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
                // SQLBot 对 /system/assistant/info/{id} 的预检按 origin 与助手 domain 匹配后
                // 直接 200 + CORS 头返回；其余 OPTIONS 一律放行。
                if (!handleAssistantInfoPreflight(httpRequest, httpResponse)) {
                    markAllowed(httpRequest);
                    chain.doFilter(request, response);
                }
                return;
            }
            if (authWhitelist.isWhitelisted(httpRequest)) {
                markAllowed(httpRequest);
                chain.doFilter(request, response);
                return;
            }

            String askToken = httpRequest.getHeader(ApiKeyTokenAuthenticator.HEADER);
            if (askToken != null && !askToken.trim().isEmpty()) {
                Map<String, Object> user = apiKeyTokenAuthenticator.authenticate(askToken);
                AuthContext context = new AuthContext();
                context.setChannel(AuthContext.Channel.ASK_TOKEN);
                context.setCurrentUser(user);
                AuthContext.set(context);
                markAllowed(httpRequest);
                chain.doFilter(request, response);
                return;
            }

            String assistantToken = httpRequest.getHeader(EmbeddedTokenAuthenticator.HEADER);
            if (assistantToken != null && !assistantToken.trim().isEmpty()) {
                EmbeddedTokenAuthenticator.AuthResult result =
                        embeddedTokenAuthenticator.authenticate(assistantToken);
                AuthContext context = new AuthContext();
                context.setChannel(AuthContext.Channel.ASSISTANT_TOKEN);
                context.setCurrentUser(result.getCurrentUser());
                context.setAssistant(result.getAssistant());
                context.setRequestOrigin(OriginSupport.requestOrigin(httpRequest));
                AuthContext.set(context);
                markAllowed(httpRequest);
                chain.doFilter(request, response);
                return;
            }

            // 主通道（Sa-Token）不在此判定：交给 SaTokenConfigure 的 Sa-Token 拦截器。
            chain.doFilter(request, response);
        } catch (AuthFailureException e) {
            writeUnauthorized(httpRequest, httpResponse, e.getMessage());
        } finally {
            AuthContext.clear();
        }
    }

    private void markAllowed(HttpServletRequest request) {
        request.setAttribute(ALLOWED_ATTRIBUTE, Boolean.TRUE);
    }

    /**
     * {@code /system/assistant/info/{id}} 的 OPTIONS 预检：origin 与助手 domain 匹配则
     * 直接 200 + CORS 头（对齐 SQLBot TokenMiddleware 的同名分支）。
     *
     * @return true 表示已处理（不再走 chain）
     */
    private boolean handleAssistantInfoPreflight(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = authWhitelist.rawPath(request);
        Matcher matcher = ASSISTANT_INFO_PATTERN.matcher(path);
        if (!matcher.find()) {
            return false;
        }
        String origin = request.getHeader("origin");
        if (origin == null || origin.trim().isEmpty()) {
            return false;
        }
        long assistantId;
        try {
            assistantId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return false;
        }
        Optional<Map<String, Object>> assistant = assistantRepository.findById(assistantId);
        if (!assistant.isPresent()) {
            return false;
        }
        String domain = Rows.str(assistant.get(), "domain");
        if (!OriginSupport.originMatchDomain(origin.trim(), domain)) {
            return false;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Access-Control-Allow-Origin", origin.trim());
        response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "*");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Max-Age", "600");
        return true;
    }

    /**
     * 鉴权失败：401 + {@code Access-Control-Allow-Origin: *}。
     *
     * <p>响应体与 SQLBot {@code apps/system/middleware/auth.py} 的
     * {@code JSONResponse(message, status_code=401, headers={"Access-Control-Allow-Origin": "*"})} 一致：
     * {@code {"code":401,"data":null,"msg":"Authentication invalid【<原因>】"}}。</p>
     */
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setContentType("application/json;charset=UTF-8");
        String detail = messageResolver.get("i18n_permission.authenticate_invalid",
                java.util.Map.of("msg", message == null ? "" : message), localeResolver.resolveLocale(request));
        String body = "{\"code\":401,\"data\":null,\"msg\":\"" + escapeJson(detail) + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
