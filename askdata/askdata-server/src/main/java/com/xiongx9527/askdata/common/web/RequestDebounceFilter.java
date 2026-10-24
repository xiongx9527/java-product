package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.common.debounce.DebounceProperties;
import com.xiongx9527.askdata.common.debounce.RequestDebouncer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 接口防抖过滤器：同一「调用方 + 接口」在防抖窗口（{@code askdata.debounce.window-ms}）内
 * 只放行一次，重复请求直接返回 429（{@code Retry-After} 提示）。
 *
 * <p><b>调用方识别</b>：优先取请求里的鉴权头（主通道 {@code X-SQLBOT-TOKEN}、
 * API Key 通道 {@code X-SQLBOT-ASK-TOKEN}、助手通道 {@code X-SQLBOT-ASSISTANT-TOKEN}），
 * 取其摘要作为键；无鉴权头时退回来源 IP。头值只存摘要，不落原文。</p>
 *
 * <p><b>顺序</b>：排在鉴权过滤器（{@code HIGHEST_PRECEDENCE + 10}）之后，
 * 以便主通道之外的通道已识别出用户；主通道的 Sa-Token 上下文在 DispatcherServlet 内才建立，
 * 故主通道按请求头摘要区分调用方。</p>
 *
 * <p>缺省关闭（{@code askdata.debounce.enabled=false}），启用后由
 * {@code askdata.debounce.paths} 限定生效范围。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestDebounceFilter extends OncePerRequestFilter {

    /** 参与调用方识别的鉴权头（按优先级）。 */
    private static final String[] CALLER_HEADERS = {
            "X-SQLBOT-TOKEN", "X-SQLBOT-ASK-TOKEN", "X-SQLBOT-ASSISTANT-TOKEN"};

    private final RequestDebouncer debouncer;
    private final DebounceProperties properties;
    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;
    private final com.xiongx9527.askdata.common.i18n.AskdataLocaleResolver localeResolver;

    public RequestDebounceFilter(RequestDebouncer debouncer,
                                 DebounceProperties properties,
                                 com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver,
                                 com.xiongx9527.askdata.common.i18n.AskdataLocaleResolver localeResolver) {
        this.debouncer = debouncer;
        this.properties = properties;
        this.messageResolver = messageResolver;
        this.localeResolver = localeResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (!properties.matches(path)) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getMethod() + "|" + path + "|" + callerKey(request);
        if (!debouncer.allow(key)) {
            writeTooManyRequests(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 调用方标识：鉴权头摘要优先，其次来源 IP。 */
    private String callerKey(HttpServletRequest request) {
        for (String header : CALLER_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.trim().isEmpty()) {
                return "h:" + digest(value.trim());
            }
        }
        String ip = request.getRemoteAddr();
        return "ip:" + (ip == null ? "unknown" : ip);
    }

    /** 摘要（不落原文，避免把令牌写进缓存键）。 */
    private static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long windowMs = Math.max(1L, properties.getWindowMs());
        long retryAfter = Math.max(1L, (windowMs + 999L) / 1000L);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setContentType("application/json;charset=UTF-8");
        String detail = messageResolver.get("i18n_debounce.too_frequent",
                java.util.Map.of("seconds", String.valueOf(retryAfter)),
                localeResolver.resolveLocale(request));
        String body = "{\"code\":429,\"data\":null,\"msg\":\"" + escapeJson(detail) + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
