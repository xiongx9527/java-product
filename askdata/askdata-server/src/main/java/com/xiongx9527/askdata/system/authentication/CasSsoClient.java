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

        String user = between(xml, "<cas:user>", "</cas:user>");
        if (user == null || user.isEmpty()) {
            throw new IllegalStateException("CAS validation failed: no user in response");
        }
        Map<String, Object> attributes = parseAttributes(xml);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform_uid", user);
        result.put("account", pick(attributes, config, "account", "username", "uid", "email", user));
        result.put("name", pick(attributes, config, "name", "cn", "displayName", "realName", user));
        result.put("email", pick(attributes, config, "email", "mail", "emailAddress", null));
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

    /** 解析 {@code <cas:attributes>} 下的简单 XML 属性。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAttributes(String xml) {
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
            String value = block.substring(close + 1, tagEnd);
            attributes.put(tag, value);
            cursor = tagEnd + tag.length() + 3;
        }
        return attributes;
    }

    private String pick(Map<String, Object> attributes, Map<String, Object> config, String localKey,
                        String... candidates) {
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
        return null;
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
