package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态数据源解析（A8）：把宿主按请求下发的连接配置解析为不落库的 {@link DynamicDatasource}。
 *
 * <p>仅高级应用（assistant type=1/3）允许使用动态数据源；普通小助手（type=0）与页面嵌入
 * （type=4）必须使用已入库的数据源，避免宿主绕过数据源治理。解析过程不读写 core_datasource，
 * 也不生成数据源 id。</p>
 */
@Service
public class DynamicDatasourceResolver {

    /** 高级应用类型（SQLBot：1=高级应用）。 */
    public static final int TYPE_ADVANCED_APP = 1;

    /** 高级应用变体类型（SQLBot：3）。 */
    public static final int TYPE_ADVANCED_APP_VARIANT = 3;

    private final ObjectMapper objectMapper;

    /** 错误文案统一走消息包（A4 / F-15）。 */
    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    /** 宿主数据源拉取超时（对齐 SQLBot AssistantOutDsFactory requests.get 默认 10s）。 */
    static final int HOST_FETCH_TIMEOUT_MS = 10_000;

    public DynamicDatasourceResolver(ObjectMapper objectMapper,
                                     com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver) {
        this.objectMapper = objectMapper;
        this.messageResolver = messageResolver;
    }

    /** 高级应用判定（type 1/3）。 */
    public static boolean isAdvancedApplication(int assistantType) {
        return assistantType == TYPE_ADVANCED_APP || assistantType == TYPE_ADVANCED_APP_VARIANT;
    }

    /**
     * 从宿主 URL 实时拉取数据源配置并解析（对齐 SQLBot {@code AssistantOutDsFactory}：
     * 嵌入/高级应用场景 askdata 主动 GET 宿主 API，同步等待响应，10s 超时）。
     *
     * <p>与「宿主随请求下发」形态互补：宿主集成时既可以在请求体里带
     * {@code dynamic_datasource}，也可以只给 {@code host_url} 让 askdata 每次实时拉取
     * （宿主侧数据源动态变化无需改 askdata 配置）。拉取失败直接报错（宿主必须可达）。</p>
     *
     * @param assistantType 高级应用类型（必须为 1 或 3）
     * @param hostUrl       宿主数据源 API 地址（返回 {@code {type, name?, configuration, dynamic_sql?}} JSON）
     * @param headers       附加请求头（如宿主鉴权 token），可为 null
     * @return 动态数据源（仅本次请求有效）
     */
    public DynamicDatasource resolveFromHost(int assistantType, String hostUrl,
                                             Map<String, String> headers) {
        if (!isAdvancedApplication(assistantType)) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.dynamic_ds_advanced_only",
                    Map.of("type", assistantType)));
        }
        if (hostUrl == null || hostUrl.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Map.of("key", "host_url")));
        }
        String body = fetchFromHost(hostUrl.trim(), headers);
        Map<String, Object> hostConfig;
        try {
            hostConfig = objectMapper.readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.host_fetch_failed",
                    "invalid JSON: " + e.getMessage()));
        }
        return resolve(assistantType, hostConfig);
    }

    /**
     * A23：解析高级应用的宿主数据源端点配置（{@code sys_assistant.configuration}）。
     *
     * <p>对齐 SQLBot {@code AssistantOutDs.get_ds_from_api}：宿主在高级应用配置里给出
     * {@code endpoint}（数据源列表 API）、{@code certificate}（header/cookie/param 形式的凭证）、
     * {@code timeout}（秒，缺省 10）。endpoint 缺省时返回 null（该应用不走宿主下发）。</p>
     *
     * @param assistant sys_assistant 行（需含 configuration）
     * @return {@code {endpoint, headers, timeout}}；未配置 endpoint 时返回 null
     */
    public Map<String, Object> hostEndpointOf(Map<String, Object> assistant) {
        if (assistant == null) {
            return null;
        }
        Object raw = assistant.get("configuration");
        Map<String, Object> config;
        if (raw instanceof Map) {
            config = castMap(raw);
        } else if (raw == null || String.valueOf(raw).trim().isEmpty()) {
            return null;
        } else {
            try {
                config = objectMapper.readValue(String.valueOf(raw),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                return null;
            }
        }
        Object endpoint = config.get("endpoint");
        if (endpoint == null || String.valueOf(endpoint).trim().isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpoint", String.valueOf(endpoint).trim());
        result.put("headers", certificateHeaders(config.get("certificate")));
        int timeout = 10;
        Object timeoutRaw = config.get("timeout");
        if (timeoutRaw instanceof Number) {
            timeout = ((Number) timeoutRaw).intValue();
        } else if (timeoutRaw != null) {
            try {
                timeout = Integer.parseInt(String.valueOf(timeoutRaw).trim());
            } catch (NumberFormatException ignored) {
                timeout = 10;
            }
        }
        result.put("timeout", timeout);
        return result;
    }

    /** A23：把 certificate 列表中的 header 项收集为请求头（cookie/param 项本实现不使用）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> certificateHeaders(Object certificate) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!(certificate instanceof List)) {
            return headers;
        }
        for (Object item : (List<Object>) certificate) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) item;
            if (!"header".equals(String.valueOf(entry.get("target")))) {
                continue;
            }
            Object key = entry.get("key");
            Object value = entry.get("value");
            if (key != null && value != null) {
                headers.put(String.valueOf(key), String.valueOf(value));
            }
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    /**
     * A24：MCP {@code mcp_assistant} 的第三方数据接口端点（对齐 SQLBot {@code mcp_assistant}：
     * 以 {@code configuration={"endpoint": url}} + {@code certificate=authorization} 构造
     * 虚拟助手，再走 {@code AssistantOutDs} 拉取数据源列表）。
     *
     * <p>{@code authorization} 兼容两种形态：JSON 数组（SQLBot certificate 列表，取
     * {@code target==header} 项）或裸凭证串（按 {@code Authorization} 请求头发送）。</p>
     *
     * @param url           第三方数据接口地址（必填）
     * @param authorization 第三方接口凭证（可空）
     * @return {@code {endpoint, headers, timeout}}
     */
    public Map<String, Object> hostEndpointFromMcp(String url, String authorization) {
        if (url == null || url.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Map.of("key", "url")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpoint", url.trim());
        result.put("headers", authorizationHeaders(authorization));
        result.put("timeout", 10);
        return result;
    }

    /** authorization → 请求头：JSON certificate 列表取 header 项；裸凭证串按 Authorization 头发送。 */
    private Map<String, String> authorizationHeaders(String authorization) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (authorization == null || authorization.trim().isEmpty()) {
            return headers;
        }
        String text = authorization.trim();
        if (text.startsWith("[")) {
            try {
                List<Object> items = objectMapper.readValue(text,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {
                        });
                for (Object item : items) {
                    if (!(item instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> entry = castMap(item);
                    if (!"header".equals(String.valueOf(entry.get("target")))) {
                        continue;
                    }
                    Object key = entry.get("key");
                    Object value = entry.get("value");
                    if (key != null && value != null) {
                        headers.put(String.valueOf(key), String.valueOf(value));
                    }
                }
                return headers;
            } catch (Exception e) {
                // 非法 JSON 数组：退化为裸凭证串处理
            }
        }
        headers.put("Authorization", text);
        return headers;
    }

    /**
     * A24：从宿主 API 拉取数据源列表并取第一个（MCP {@code mcp_assistant} 未指定数据源时）。
     *
     * @param assistantType 高级应用类型（必须为 1 或 3）
     * @param hostEndpoint  {@link #hostEndpointFromMcp} 的返回
     * @return 动态数据源（仅本次请求有效）
     */
    public DynamicDatasource resolveFirstFromHostList(int assistantType, Map<String, Object> hostEndpoint) {
        List<Map<String, Object>> items = fetchHostDatasources(assistantType, hostEndpoint);
        if (items.isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.host_fetch_failed",
                    "host returned an empty datasource list"));
        }
        return resolve(assistantType, items.get(0));
    }

    /**
     * A23：从宿主 API 拉取数据源列表并取出指定数据源（对齐 SQLBot
     * {@code AssistantOutDs.get_ds}）。
     *
     * <p>宿主响应契约（SQLBot）：{@code {"code": 0|200, "data": [ {datasource}, ... ]}}。</p>
     *
     * @param assistantType 高级应用类型（必须为 1 或 3）
     * @param hostEndpoint  {@link #hostEndpointOf} 的返回
     * @param datasourceId  宿主侧数据源 id
     * @return 动态数据源（仅本次请求有效）
     */
    public DynamicDatasource resolveFromHostList(int assistantType, Map<String, Object> hostEndpoint,
                                                  long datasourceId) {
        List<Map<String, Object>> items = fetchHostDatasources(assistantType, hostEndpoint);
        for (Map<String, Object> item : items) {
            Long id = Rows.lngOrNull(item, "id");
            if (id != null && id == datasourceId) {
                return resolve(assistantType, item);
            }
        }
        throw new BadRequestException(messageResolver.get("i18n_embedded.host_fetch_failed",
                "datasource " + datasourceId + " is not found in host response"));
    }

    /** 拉取宿主数据源列表（高级应用类型与端点校验 + code/data 契约校验）。 */
    private List<Map<String, Object>> fetchHostDatasources(int assistantType, Map<String, Object> hostEndpoint) {
        if (!isAdvancedApplication(assistantType)) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.dynamic_ds_advanced_only",
                    Map.of("type", assistantType)));
        }
        if (hostEndpoint == null) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.host_fetch_failed",
                    "assistant endpoint is not configured"));
        }
        String endpoint = String.valueOf(hostEndpoint.get("endpoint"));
        Map<String, String> headers = castStringMap(hostEndpoint.get("headers"));
        String body = fetchFromHost(endpoint, headers);
        try {
            Map<String, Object> payload = objectMapper.readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Object code = payload.get("code");
            if (code != null && !"0".equals(String.valueOf(code)) && !"200".equals(String.valueOf(code))) {
                throw new IllegalStateException("host returned code " + code
                        + (payload.get("message") == null ? "" : ": " + payload.get("message")));
            }
            return castMapList(payload.get("data"));
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.host_fetch_failed",
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castStringMap(Object raw) {
        return raw instanceof Map ? (Map<String, String>) raw : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(Object raw) {
        return raw instanceof List ? (List<Map<String, Object>>) raw : new ArrayList<>();
    }

    /** GET 宿主 API（10s 连接/读取超时，对齐 SQLBot requests.get(..., timeout=10)）；非 2xx 或 IO 异常均报错。 */
    private String fetchFromHost(String hostUrl, Map<String, String> headers) {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(hostUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HOST_FETCH_TIMEOUT_MS);
            conn.setReadTimeout(HOST_FETCH_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (h.getKey() != null && h.getValue() != null) {
                        conn.setRequestProperty(h.getKey(), h.getValue());
                    }
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (java.io.InputStream in = conn.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                return out.toString("UTF-8");
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.host_fetch_failed",
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * 解析宿主下发的动态数据源配置（不持久化）。
     *
     * @param assistantType 高级应用类型（必须为 1 或 3）
     * @param hostConfig    宿主下发配置：{@code {type: "mysql", name?: "...", configuration: {...}|"{...}"}}
     * @return 动态数据源（仅本次请求有效）
     */
    public DynamicDatasource resolve(int assistantType, Map<String, Object> hostConfig) {
        if (!isAdvancedApplication(assistantType)) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.dynamic_ds_advanced_only",
                    java.util.Map.of("type", assistantType)));
        }
        if (hostConfig == null) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", java.util.Map.of("key", "configuration")));
        }
        String typeText = Rows.str(hostConfig, "type");
        if (typeText == null || typeText.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", java.util.Map.of("key", "type")));
        }
        DatasourceType type;
        try {
            type = DatasourceType.fromType(typeText.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(messageResolver.get("ds.type.not.support", typeText));
        }
        DatasourceConf conf = parseConf(configurationOf(hostConfig));
        // §2.4: 宿主可下发表级 SQL 模板（dynamic_sql：表名 → 子查询模板），用于二次 LLM 改写后作为子查询/CTE。
        Map<String, String> tableSqlTemplates = parseTableSqlTemplates(hostConfig.get("dynamic_sql"));
        // A18: 宿主可下发表级行规则（table_rule：表名 → WHERE 条件片段），执行前注入过滤 SQL
        Map<String, String> tableRules = parseTableSqlTemplates(hostConfig.get("table_rule"));
        if (hostConfig.get("configuration") instanceof Map) {
            Map<?, ?> inner = (Map<?, ?>) hostConfig.get("configuration");
            if (tableSqlTemplates.isEmpty()) {
                tableSqlTemplates = parseTableSqlTemplates(inner.get("dynamic_sql"));
            }
            if (tableRules.isEmpty()) {
                tableRules = parseTableSqlTemplates(inner.get("table_rule"));
            }
        }
        return new DynamicDatasource(type, conf, Rows.str(hostConfig, "name"), tableSqlTemplates, tableRules);
    }

    /** §2.4: 解析 dynamic_sql（表名 → 子查询模板）；非法/空返回空 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseTableSqlTemplates(Object raw) {
        if (raw == null) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Map<String, Object> map = raw instanceof Map ? (Map<String, Object>) raw
                    : objectMapper.readValue(String.valueOf(raw),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                            });
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        } catch (Exception e) {
            return Collections.emptyMap();
        }
        return result;
    }

    /**
     * SQLBot {@code AssistantOutDsSchema} 的扁平配置字段（宿主数据源列表项）→ askdata
     * {@link DatasourceConf} 属性名（MCP {@code mcp_assistant} 的第三方数据源列表即该形态）。
     */
    private static final Map<String, String> FLAT_CONF_KEYS = flatConfKeys();

    private static Map<String, String> flatConfKeys() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("host", "host");
        m.put("port", "port");
        m.put("user", "username");
        m.put("username", "username");
        m.put("password", "password");
        m.put("dataBase", "database");
        m.put("database", "database");
        m.put("extraParams", "extraJdbc");
        m.put("extraJdbc", "extraJdbc");
        m.put("schema", "dbSchema");
        m.put("db_schema", "dbSchema");
        m.put("dbSchema", "dbSchema");
        m.put("mode", "mode");
        m.put("lowVersion", "lowVersion");
        m.put("timeout", "timeout");
        return m;
    }

    /**
     * 宿主数据源连接配置：优先取嵌套 {@code configuration}（askdata {@code dynamic_datasource}
     * 形态）；缺失时按 SQLBot {@code AssistantOutDs.convert2schema} 的扁平字段
     * （{@code host/port/user/password/dataBase/schema/extraParams/mode/lowVersion}）合成。
     */
    private Object configurationOf(Map<String, Object> hostConfig) {
        Object nested = hostConfig.get("configuration");
        if (nested instanceof Map || (nested != null && !String.valueOf(nested).trim().isEmpty())) {
            return nested;
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : FLAT_CONF_KEYS.entrySet()) {
            Object value = hostConfig.get(entry.getKey());
            if (value != null) {
                flat.put(entry.getValue(), value);
            }
        }
        return flat.isEmpty() ? nested : flat;
    }

    private DatasourceConf parseConf(Object configuration) {
        if (configuration == null) {
            return new DatasourceConf();
        }
        if (configuration instanceof Map) {
            return objectMapper.convertValue(configuration, DatasourceConf.class);
        }
        String text = String.valueOf(configuration);
        if (text.trim().isEmpty()) {
            return new DatasourceConf();
        }
        try {
            return objectMapper.readValue(text, DatasourceConf.class);
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("ds.invalid.config", e.getMessage()));
        }
    }
}

