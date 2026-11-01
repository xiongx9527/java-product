package com.xiongx9527.askdata.system.authentication;

import javax.servlet.http.HttpServletRequest;

/**
 * 从当前请求推导「浏览器实际访问的入口地址」，用于生成 SSO 回调地址与登出回跳地址。
 *
 * <p>优先级：</p>
 * <ol>
 *   <li>反向代理透传的 {@code X-Forwarded-Proto} + {@code X-Forwarded-Host}（花生壳等隧道场景）；</li>
 *   <li>浏览器同源请求自带的 {@code Origin} 头（最可靠，等于用户实际访问的公网/内网入口）；</li>
 *   <li>{@code Referer} 兜底；</li>
 *   <li>最后回退到请求自身的 scheme/host/port。</li>
 * </ol>
 *
 * <p>地址因此不必写死在配置里：访问域名变化（如 localhost ↔ 隧道域名）时自动适配，
 * 且与前端 {@code getSQLBotAddr()}（origin + pathname）保持一致。</p>
 */
public final class RequestBaseUrl {

    private RequestBaseUrl() {
    }

    /**
     * @return 形如 {@code https://host[:port][contextPath]/} 的基础地址；请求为空时返回 {@code "/"}
     */
    public static String resolve(HttpServletRequest request) {
        String base = null;
        if (request != null) {
            String proto = request.getHeader("X-Forwarded-Proto");
            String fhost = request.getHeader("X-Forwarded-Host");
            if (isNotBlank(proto) && isNotBlank(fhost)) {
                base = buildBase(proto, fhost, request.getHeader("X-Forwarded-Port"));
            }
            if (base == null) {
                String origin = request.getHeader("Origin");
                if (isNotBlank(origin)) {
                    base = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
                }
            }
            if (base == null) {
                String referer = request.getHeader("Referer");
                if (isNotBlank(referer)) {
                    try {
                        java.net.URI uri = java.net.URI.create(referer);
                        base = buildBase(uri.getScheme(), uri.getHost(),
                                uri.getPort() > 0 ? String.valueOf(uri.getPort()) : null);
                    } catch (Exception ignored) {
                        base = null;
                    }
                }
            }
            if (base == null) {
                base = buildBase(request.getScheme(), request.getServerName(),
                        request.getServerPort() > 0 ? String.valueOf(request.getServerPort()) : null);
            }
        }
        if (base == null) {
            base = "";
        }
        return withContext(base, request);
    }

    /**
     * 只按「请求实际到达的主机」推导基础地址，忽略 {@code Origin} / {@code Referer}。
     *
     * <p>SAML2 的 ACS 是 IdP 用表单 POST 过来的跨站请求，浏览器会带上 IdP 自己的
     * {@code Origin}；此时用 {@link #resolve} 会把回跳地址推导成 IdP 域名，
     * 因此这类端点必须走本方法。</p>
     */
    public static String ofHost(HttpServletRequest request) {
        if (request == null) {
            return "/";
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        String fhost = request.getHeader("X-Forwarded-Host");
        String base = isNotBlank(proto) && isNotBlank(fhost)
                ? buildBase(proto, fhost, request.getHeader("X-Forwarded-Port")) : null;
        if (base == null) {
            base = buildBase(request.getScheme(), request.getServerName(),
                    request.getServerPort() > 0 ? String.valueOf(request.getServerPort()) : null);
        }
        return withContext(base == null ? "" : base, request);
    }

    private static String withContext(String base, HttpServletRequest request) {
        String ctx = request != null ? request.getContextPath() : "";
        if (ctx != null && !ctx.isEmpty() && !base.endsWith(ctx)) {
            base = base + ctx;
        }
        return base + "/";
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String buildBase(String scheme, String host, String port) {
        if (!isNotBlank(scheme) || !isNotBlank(host)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        int p = -1;
        if (isNotBlank(port)) {
            try {
                p = Integer.parseInt(port.trim());
            } catch (NumberFormatException ignored) {
                p = -1;
            }
        }
        if (p > 0) {
            boolean standard = ("https".equalsIgnoreCase(scheme) && p == 443)
                    || ("http".equalsIgnoreCase(scheme) && p == 80);
            if (!standard) {
                sb.append(":").append(p);
            }
        }
        return sb.toString();
    }
}
