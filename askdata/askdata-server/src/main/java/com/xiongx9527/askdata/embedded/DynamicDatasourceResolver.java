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

