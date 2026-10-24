package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        DatasourceConf conf = parseConf(hostConfig.get("configuration"));
        // §2.4: 宿主可下发表级 SQL 模板（dynamic_sql：表名 → 子查询模板），用于二次 LLM 改写后作为子查询/CTE。
        Map<String, String> tableSqlTemplates = parseTableSqlTemplates(hostConfig.get("dynamic_sql"));
        if (tableSqlTemplates.isEmpty() && hostConfig.get("configuration") instanceof Map) {
            Object inner = ((Map<?, ?>) hostConfig.get("configuration")).get("dynamic_sql");
            tableSqlTemplates = parseTableSqlTemplates(inner);
        }
        return new DynamicDatasource(type, conf, Rows.str(hostConfig, "name"), tableSqlTemplates);
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

