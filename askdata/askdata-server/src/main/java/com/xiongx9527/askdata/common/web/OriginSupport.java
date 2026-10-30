package com.xiongx9527.askdata.common.web;

import javax.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Origin / 域名绑定工具（对齐 SQLBot {@code common/utils/utils.py:247-286} 的
 * {@code get_origin_from_referer} 与 {@code origin_match_domain}）。
 *
 * <p>嵌入助手的安全边界：助手的 {@code domain} 配置里列出的来源域名才允许访问该助手。
 * 判定时用请求的 {@code Origin} 头（缺失时回退 Referer 推导的 origin），
 * 与助手配置的 domain 做精确匹配（domain 可用 {@code ,} 或 {@code ;} 分隔多个）。</p>
 */
public final class OriginSupport {

    private OriginSupport() {
    }

    /**
     * 从 Referer 头推导 origin（scheme://host[:port]），默认端口不保留。
     * Referer 缺失或无法解析出 scheme/host 时返回 null（对齐 SQLBot 返回 None）。
     */
    public static String getOriginFromReferer(HttpServletRequest request) {
        String referer = request == null ? null : request.getHeader("Referer");
        if (referer == null || referer.isEmpty()) {
            return null;
        }
        try {
            URI parsed = new URI(referer);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null || scheme.isEmpty() || host.isEmpty()) {
                // SQLBot：urlparse 解析不出 scheme/hostname 时返回 None（不回落成原始 referer，
                // 否则 referer 内容会绕过 domain 匹配的精确比较前提）。
                return null;
            }
            int port = parsed.getPort();
            if (port > 0) {
                boolean defaultPort = ("http".equals(scheme) && port == 80)
                        || ("https".equals(scheme) && port == 443);
                if (!defaultPort) {
                    return scheme + "://" + host + ":" + port;
                }
            }
            return scheme + "://" + host;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 请求 origin：优先 {@code Origin} 头，其次 {@code X-SQLBOT-HOST-ORIGIN}（SQLBot 嵌入通道用），
     * 最后从 Referer 推导。均缺失时返回 null。
     */
    public static String requestOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.trim().isEmpty()) {
            return origin.trim();
        }
        String hostOrigin = request.getHeader("X-SQLBOT-HOST-ORIGIN");
        if (hostOrigin != null && !hostOrigin.trim().isEmpty()) {
            return hostOrigin.trim();
        }
        return getOriginFromReferer(request);
    }

    /**
     * origin 是否落在助手配置的 domain 列表内。
     * domain 可用 {@code ,} 或 {@code ;} 分隔多个来源，尾部 {@code /} 会被忽略。
     */
    public static boolean originMatchDomain(String origin, String domain) {
        if (origin == null || origin.isEmpty() || domain == null || domain.isEmpty()) {
            return false;
        }
        String normalized = stripTrailingSlash(origin);
        for (String item : domain.split("[,;]")) {
            String candidate = stripTrailingSlash(item.trim());
            if (!candidate.isEmpty() && candidate.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 拆出 domain 配置中的域名列表（对齐 SQLBot {@code get_domain_list}）。 */
    public static java.util.List<String> domainList(String domain) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (domain == null || domain.isEmpty()) {
            return result;
        }
        for (String item : domain.split("[,;]")) {
            String candidate = stripTrailingSlash(item.trim());
            if (!candidate.isEmpty()) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
