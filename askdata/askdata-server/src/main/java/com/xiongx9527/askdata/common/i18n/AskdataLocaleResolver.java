package com.xiongx9527.askdata.common.i18n;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 多语言解析（A9：对齐 SQLBot 五语言 zh-CN/zh-TW/en/ko-KR/th-TH）。
 *
 * <p>解析优先级（对齐 SQLBot locale 中间件）：
 * <ol>
 *   <li>{@code ?lang=} 请求参数；</li>
 *   <li>已持久化的 {@code lang} Cookie（由 LocaleChangeInterceptor 写入）；</li>
 *   <li>{@code Accept-Language} 请求头（按 q 值择优）；</li>
 *   <li>默认语言 zh-CN。</li>
 * </ol>
 * 不支持的语言回退到默认语言，避免 SQLBot「不支持的语言」类错误。
 */
public class AskdataLocaleResolver implements LocaleResolver {

    private final List<Locale> supported = Arrays.asList(
            Locale.SIMPLIFIED_CHINESE, new Locale("zh", "TW"),
            Locale.ENGLISH, new Locale("ko", "KR"), new Locale("th", "TH"));

    private Locale defaultLocale = Locale.SIMPLIFIED_CHINESE;

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        String lang = request.getParameter("lang");
        Locale l = parse(lang);
        if (l != null) {
            return l;
        }
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("lang".equals(c.getName())) {
                    l = parse(c.getValue());
                    if (l != null) {
                        return l;
                    }
                }
            }
        }
        l = parseAcceptLanguage(request.getHeader("Accept-Language"));
        if (l != null) {
            return l;
        }
        return defaultLocale;
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        Cookie cookie = new Cookie("lang", locale == null ? "" : locale.toLanguageTag());
        cookie.setPath("/");
        if (locale == null) {
            cookie.setMaxAge(0);
        } else {
            cookie.setMaxAge(60 * 60 * 24 * 365);
        }
        response.addCookie(cookie);
    }

    private Locale parse(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Locale l = Locale.forLanguageTag(value.replace('_', '-'));
        if (isSupported(l)) {
            return l;
        }
        // 仅语言部分（如 zh）回退到受支持的首个同语言区域
        for (Locale s : supported) {
            if (s.getLanguage().equals(l.getLanguage())) {
                return s;
            }
        }
        return null;
    }

    private boolean isSupported(Locale l) {
        for (Locale s : supported) {
            if (s.equals(l)) {
                return true;
            }
        }
        return false;
    }

    private Locale parseAcceptLanguage(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        java.util.List<String> ranges = Arrays.asList(header.split(","));
        java.util.List<java.util.AbstractMap.SimpleEntry<Double, String>> scored = new java.util.ArrayList<>();
        for (String range : ranges) {
            String[] parts = range.split(";");
            double q = 1.0;
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(p.substring(2));
                    } catch (NumberFormatException ignored) {
                        q = 1.0;
                    }
                }
            }
            scored.add(new java.util.AbstractMap.SimpleEntry<>(q, parts[0].trim().split("-")[0].trim()));
        }
        scored.sort((a, b) -> Double.compare(b.getKey(), a.getKey()));
        for (java.util.AbstractMap.SimpleEntry<Double, String> e : scored) {
            Locale l = parse(e.getValue());
            if (l != null) {
                return l;
            }
        }
        return null;
    }
}
