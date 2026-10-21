package com.xiongx9527.askdata.common.web;

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
import java.util.regex.Pattern;

/**
 * Host 头校验（对齐 SQLBot {@code common/core/host_validation.py} 的 HostValidationMiddleware）。
 *
 * <p>为什么必须在最外层：Starlette 的 {@code URL(scope)} 会把 Host 头原始值拼进 URL，
 * Host 携带路径片段会污染 {@code request.url.path} 等派生属性，历史上被用于绕过认证白名单
 * （如 {@code Host: evil.com/api/v1/mcp}）。askdata 侧以 {@code request.getRequestURI()}
 * 做白名单判定同样不依赖 Host，但仍在入口处直接拒绝畸形 Host，避免任何下游组件再拼 URL。</p>
 *
 * <p>合法 Host：域名 / IPv4 / IPv6（含端口），排除 '/'、'@'、空白等非法字符，
 * 正则与 SQLBot 逐字一致：{@code ^[A-Za-z0-9.\-:\[\]]{1,255}$}。</p>
 *
 * <p>与 SQLBot 的一处<b>刻意</b>差异：SQLBot 对「Host 头缺失」也返回 400
 * （{@code if not host or not _HOST_RE.match(host)}）。askdata 侧在 Host 头缺失时回退到
 * Servlet 容器的 {@code getServerName():getServerPort()} 再校验，理由有二：</p>
 * <ul>
 *   <li>HTTP/1.0 客户端本就不发 Host 头，直接 400 会打断合法客户端；</li>
 *   <li>Host 头缺失时没有可污染 URL 路径的注入源，安全性等价——真正的攻击面
 *       （携带路径片段的 Host）依然被严格拒绝。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostValidationFilter implements Filter {

    /** 与 SQLBot {@code _HOST_RE} 逐字一致。 */
    public static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-:\\[\\]]{1,255}$");

    /** 不合法 Host 的响应体（与 SQLBot 的 JSONResponse 逐字一致）。 */
    public static final String INVALID_HOST_BODY =
            "{\"code\":400,\"data\":null,\"msg\":\"invalid host header\"}";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        if (!isValidHost(httpRequest)) {
            writeInvalidHost(httpResponse);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 校验 Host 头（或缺失时回退到容器 server name）。
     *
     * @return true 表示 Host 合法
     */
    public static boolean isValidHost(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null) {
            // Host 头缺失：回退到 Servlet 容器的 server name:port（HTTP/1.0 客户端场景）
            String serverName = request.getServerName();
            if (serverName == null || serverName.isEmpty()) {
                return false;
            }
            int port = request.getServerPort();
            host = port > 0 ? serverName + ":" + port : serverName;
        }
        return HOST_PATTERN.matcher(host).matches();
    }

    private void writeInvalidHost(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        byte[] body = INVALID_HOST_BODY.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }
}
