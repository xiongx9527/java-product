package com.xiongx9527.askdata.system.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CAS 2.0/3.0 认证源客户端。
 *
 * <p>CAS 协议两段：</p>
 * <ol>
 *   <li>授权跳转：{@code {idpUri}/login?service={casCallbackDomain}}；</li>
 *   <li>回调换属性：{@code {idpUri}/serviceValidate?service=...&ticket=...} 返回 XML，
 *       解析 {@code <cas:user>} 与 {@code <cas:attributes>}。</li>
 * </ol>
 *
 * <p>config 字段（对齐前端 {@code views/system/authentication/CasEditor.vue}）：
 * {@code idpUri} / {@code casCallbackDomain} / {@code mapping}。</p>
 */
public class CasSsoClient implements SsoClient {

    private final ObjectMapper objectMapper;

    public CasSsoClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String authorizeUrl(Map<String, Object> config, SsoCallback callback) {
        String idpUri = str(config, "idpUri");
        String service = str(config, "casCallbackDomain");
        if (idpUri == null || service == null) {
            throw new IllegalStateException("Missing idpUri or casCallbackDomain");
        }
        String base = idpUri.endsWith("/") ? idpUri.substring(0, idpUri.length() - 1) : idpUri;
        return base + "/login?service=" + urlEncode(service);
    }

    @Override
    public Map<String, Object> exchange(Map<String, Object> config, SsoCallback callback) {
        String idpUri = str(config, "idpUri");
        String service = str(config, "casCallbackDomain");
        if (idpUri == null || service == null) {
            throw new IllegalStateException("Missing idpUri or casCallbackDomain");
        }
        String ticket = callback.getTicket();
        if (ticket == null || ticket.isEmpty()) {
            throw new IllegalStateException("Missing CAS ticket");
        }
        String base = idpUri.endsWith("/") ? idpUri.substring(0, idpUri.length() - 1) : idpUri;
        String url = base + "/serviceValidate?service=" + urlEncode(service) + "&ticket=" + urlEncode(ticket);
        String xml = get(url);

        String user = userFrom(xml);
        if (user == null) {
            // CAS 校验失败时返回 <cas:authenticationFailure code="INVALID_TICKET">…</…>，
            // 把 code 透出来（票据过期/被重放/服务不匹配都能直接看出来），不要只说“没取到用户”。
            String failure = failureFrom(xml);
            throw new IllegalStateException("CAS validation failed: "
                    + (failure == null ? "no user in response" : failure));
        }
        Map<String, Object> attributes = parseAttributes(xml);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform_uid", user);
        // 候选属性名按优先级取，都取不到才回落到 <cas:user>（登录名）/ null（邮箱）。
        result.put("account", pick(attributes, config, "account", user, "username", "uid", "email"));
        result.put("name", pick(attributes, config, "name", user, "displayName", "cn", "realName", "username"));
        result.put("email", pick(attributes, config, "email", null, "email", "mail", "emailAddress"));
        return result;
    }

    @Override
    public boolean validate(Map<String, Object> config) {
        String idpUri = str(config, "idpUri");
        if (idpUri == null || idpUri.isEmpty()) {
            throw new IllegalStateException("missing idpUri in CAS config");
        }
        try {
            // CAS 2.0 之前的探测端点；取不到不致命（3.0 移除了该端点），只要求主机可达。
            String base = idpUri.endsWith("/") ? idpUri.substring(0, idpUri.length() - 1) : idpUri;
            get(base + "/login");
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("CAS idp unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 {@code <cas:attributes>} 下的简单 XML 属性。
     *
     * <p>两个必须处理的细节（对接多个 IdP 实测）：</p>
     * <ol>
     *   <li>元素带命名空间前缀（{@code <cas:username>}），入库时去掉前缀存 {@code username}，
     *       否则按 {@code username} 取值永远取不到，表现为「missing account」；</li>
     *   <li>中文等非 ASCII 值被编码成 {@code base64:xxxx}，需要解码成原文。</li>
     * </ol>
     */
    Map<String, Object> parseAttributes(String xml) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        int start = xml.indexOf("<cas:attributes>");
        int end = xml.indexOf("</cas:attributes>");
        if (start < 0 || end < 0) {
            return attributes;
        }
        String block = xml.substring(start, end);
        int cursor = 0;
        while (true) {
            int open = block.indexOf('<', cursor);
            if (open < 0) {
                break;
            }
            int close = block.indexOf('>', open);
            if (close < 0) {
                break;
            }
            String tag = block.substring(open + 1, close).trim();
            if (tag.startsWith("/") || tag.startsWith("?") || tag.startsWith("!")) {
                cursor = close + 1;
                continue;
            }
            int tagEnd = block.indexOf("</" + tag + ">", close);
            if (tagEnd < 0) {
                cursor = close + 1;
                continue;
            }
            String value = decodeValue(block.substring(close + 1, tagEnd));
            if (value != null) {
                attributes.put(localName(tag), value);
            }
            cursor = tagEnd + tag.length() + 3;
        }
        return attributes;
    }

    /** {@code cas:username} → {@code username}；无前缀时原样返回。 */
    private static String localName(String tag) {
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }

    /** 解 {@code base64:} 编码；{@code null} 字面量视为空值。 */
    private static String decodeValue(String raw) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        if (value.startsWith("base64:")) {
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(value.substring("base64:".length()));
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return value;
            }
        }
        return value;
    }

    /**
     * 按「用户属性映射 → 候选属性名 → 默认值」的顺序取一个字段。
     *
     * @param fallback 所有候选都取不到时的默认值（账号默认登录名、邮箱默认空）
     */
    private String pick(Map<String, Object> attributes, Map<String, Object> config, String localKey,
                        String fallback, String... candidates) {
        Object mapping = config.get("mapping");
        if (mapping != null) {
            try {
                Map<?, ?> map = objectMapper.readValue(String.valueOf(mapping), Map.class);
                Object mapped = map.get(localKey);
                if (mapped != null) {
                    Object value = attributes.get(String.valueOf(mapped));
                    if (value != null) {
                        return String.valueOf(value);
                    }
                }
            } catch (Exception ignored) {
                // mapping 非合法 JSON 时按默认顺序取值
            }
        }
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Object value = attributes.get(candidate);
            if (value != null && !String.valueOf(value).isEmpty()) {
                return String.valueOf(value);
            }
        }
        return fallback;
    }

    /** 取 CAS 校验响应里的登录名（{@code <cas:user>}）；没有则返回 null。 */
    static String userFrom(String xml) {
        String user = between(xml, "<cas:user>", "</cas:user>");
        if (user == null) {
            return null;
        }
        user = user.trim();
        return user.isEmpty() ? null : user;
    }

    /** 取 CAS 校验失败原因（{@code code} + 文本）；没有失败节点时返回 null。 */
    static String failureFrom(String xml) {
        int start = xml.indexOf("<cas:authenticationFailure");
        if (start < 0) {
            return null;
        }
        int openEnd = xml.indexOf('>', start);
        int close = xml.indexOf("</cas:authenticationFailure>", start);
        if (openEnd < 0 || close < openEnd) {
            return null;
        }
        String code = between(xml.substring(start, openEnd + 1), "code=\"", "\"");
        String message = xml.substring(openEnd + 1, close).trim();
        // 部分 IdP 应用未配置适配器时返回 code="null"、内容 "null"：这时要把「没有失败原因」
        // 明确说出来，否则界面上只能看到一串 null，无法判断是应用侧配置问题。
        boolean noCode = code == null || "null".equalsIgnoreCase(code);
        boolean noMessage = message.isEmpty() || "null".equalsIgnoreCase(message);
        if (noCode && noMessage) {
            return "IdP returned no failure detail (check the CAS application registration, e.g. adapter)";
        }
        if (noCode) {
            return message;
        }
        return noMessage ? code : code + " " + message;
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return null;
        }
        start += open.length();
        int end = text.indexOf(close, start);
        if (end < 0) {
            return null;
        }
        return text.substring(start, end);
    }

    private static String get(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("CAS request failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String str(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
