package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.springframework.stereotype.Service;

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

    public DynamicDatasourceResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
            throw new BadRequestException("Dynamic datasource is only supported for advanced applications "
                    + "(type 1/3), got type " + assistantType);
        }
        if (hostConfig == null) {
            throw new BadRequestException("Dynamic datasource configuration is required");
        }
        String typeText = Rows.str(hostConfig, "type");
        if (typeText == null || typeText.trim().isEmpty()) {
            throw new BadRequestException("Dynamic datasource type is required");
        }
        DatasourceType type;
        try {
            type = DatasourceType.fromType(typeText.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unsupported dynamic datasource type: " + typeText);
        }
        DatasourceConf conf = parseConf(hostConfig.get("configuration"));
        return new DynamicDatasource(type, conf, Rows.str(hostConfig, "name"));
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
            throw new BadRequestException("Invalid dynamic datasource configuration: " + e.getMessage());
        }
    }
}

