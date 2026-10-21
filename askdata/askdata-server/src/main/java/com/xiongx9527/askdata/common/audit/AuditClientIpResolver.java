package com.xiongx9527.askdata.common.audit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析（对齐 SQLBot {@code common/audit/schemas/logger_decorator.py} 的
 * {@code SystemLogger.get_client_info}）。
 *
 * <p><b>解析链（按顺序，取第一个非空）</b>：</p>
 * <ol>
 *   <li>{@code X-Forwarded-For}（取第一个逗号分隔项）；</li>
 *   <li>{@code X-Real-IP}；</li>
 *   <li>{@code CF-Connecting-IP}；</li>
 *   <li>{@code X-Client-IP}；</li>
 *   <li>{@code X-Forwarded}；</li>
 *   <li>RFC 7239 {@code Forwarded}（取 {@code for=} 参数）；</li>
 *   <li>socket 对端地址（{@code request.getRemoteAddr()}）。</li>
 * </ol>
 */
public final class AuditClientIpResolver {

    /** 解析链（顺序敏感）。 */
    private static final String[] HEADERS = {
            "x-forwarded-for",
            "x-real-ip",
            "cf-connecting-ip",
            "x-client-ip",
            "x-forwarded",
            "forwarded"
    };

    private AuditClientIpResolver() {
    }

    /**
     * 解析客户端 IP。
     *
     * @param request 当前请求（可为 null）
     * @return 客户端 IP；无请求时返回 null
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        for (String header : HEADERS) {
            String value = request.getHeader(header);
            if (value == null || value.isEmpty()) {
                continue;
            }
            String ip;
            if ("x-forwarded-for".equals(header)) {
                int comma = value.indexOf(',');
                ip = comma > 0 ? value.substring(0, comma).trim() : value.trim();
            } else if ("forwarded".equals(header)) {
                ip = parseForwarded(value);
            } else {
                ip = value.trim();
            }
            if (ip != null && !ip.isEmpty()) {
                return ip;
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 解析 RFC 7239 {@code Forwarded} 头。
     *
     * <p>形如 {@code for=203.0.113.43;proto=https;by=203.0.113.1}，取 {@code for=} 的值
     * （去掉引号）。</p>
     */
    static String parseForwarded(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (String part : value.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("for=")) {
                String ip = trimmed.substring(4).trim();
                if (ip.length() >= 2 && ip.startsWith("\"") && ip.endsWith("\"")) {
                    ip = ip.substring(1, ip.length() - 1);
                }
                return ip;
            }
        }
        return null;
    }
}
