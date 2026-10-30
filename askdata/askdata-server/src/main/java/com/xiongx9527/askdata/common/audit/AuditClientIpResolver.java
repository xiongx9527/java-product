package com.xiongx9527.askdata.common.audit;

import com.xiongx9527.askdata.common.util.Lists;

import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 IP 解析链（对齐 SQLBot {@code common/audit/schemas/logger_decorator.py:get_client_info}
 * 的六级优先级）：逐级回退至真实客户端 IP。
 *
 * <ol>
 *   <li>{@code X-Forwarded-For}（取首个，逗号分隔）</li>
 *   <li>{@code X-Real-IP}</li>
 *   <li>{@code CF-Connecting-IP}（Cloudflare）</li>
 *   <li>{@code X-Client-IP}</li>
 *   <li>{@code X-Forwarded}（单值）</li>
 *   <li>RFC 7239 {@code Forwarded} 头（解析 {@code for=} 段）</li>
 *   <li>Socket 对端地址 {@code request.getRemoteAddr()}</li>
 * </ol>
 */
@Component
public class AuditClientIpResolver {

    private static final List<String> SINGLE_HEADERS = Lists.of(
            "X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP", "X-Client-IP", "X-Forwarded");

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        for (String header : SINGLE_HEADERS) {
            String value = request.getHeader(header);
            if (value == null || value.isEmpty()) {
                continue;
            }
            value = value.trim();
            if ("X-Forwarded-For".equals(header)) {
                int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value;
            }
            if ("X-Forwarded".equals(header)) {
                String ip = parseForwarded(value);
                if (ip != null) {
                    return ip;
                }
                continue;
            }
            return value;
        }
        String forwarded = request.getHeader("Forwarded");
        if (forwarded != null && !forwarded.isEmpty()) {
            String ip = parseForwarded(forwarded.trim());
            if (ip != null) {
                return ip;
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 解析 RFC 7239 {@code Forwarded} 头，提取首个 {@code for=<ip>} 中的 IP（去除方括号/端口/引号）。
     */
    private String parseForwarded(String value) {
        String upper = value.toUpperCase();
        int idx = upper.indexOf("FOR=");
        if (idx < 0) {
            return null;
        }
        int start = idx + 4;
        // 跳过可选引号
        boolean quoted = start < value.length() && value.charAt(start) == '"';
        int end = quoted ? value.indexOf('"', start + 1) : value.indexOf(';', start);
        if (end < 0) {
            end = value.length();
        }
        String token = value.substring(start, end).trim();
        if (token.isEmpty()) {
            return null;
        }
        if (token.charAt(0) == '"' && token.length() > 1) {
            token = token.substring(1);
        }
        // 去除方括号（IPv6）与端口
        if (token.startsWith("[")) {
            int close = token.indexOf(']');
            if (close > 0) {
                token = token.substring(1, close);
            }
        } else {
            int colon = token.indexOf(':');
            if (colon > 0 && !token.contains(".")) {
                token = token.substring(0, colon);
            } else if (colon > 0) {
                // IPv4:port
                token = token.substring(0, colon);
            }
        }
        return token.isEmpty() ? null : token;
    }
}
