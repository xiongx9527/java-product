package com.xiongx9527.askdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Properties;

/**
 * OpenAPI 文档元数据配置（{@code askdata.openapi.*}）。
 *
 * <p>此前标题 / 版本 / 说明是硬编码常量（版本号还写死在代码里，升级版本后必然失真），
 * 现改为可配置：</p>
 * <ul>
 *   <li>{@code title}：文档标题，缺省 {@code askdata API}；</li>
 *   <li>{@code version}：文档版本，缺省取构建版本（{@code askdata-build.properties}，
 *       由 Maven 资源过滤注入 {@code project.version}），随构建自动更新；</li>
 *   <li>{@code description}：文档说明，缺省用默认文案，其中的 {@code {tokenHeader}} 占位符
 *       会被替换为实际 token 头（取自 {@code sa-token.token-name}）。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "askdata.openapi")
public class OpenApiProperties {

    /** 构建版本文件（Maven 资源过滤生成，见 pom 的 resources 配置）。 */
    private static final String BUILD_PROPERTIES = "askdata-build.properties";

    /** 文档标题。 */
    private String title = "askdata API";

    /** 文档版本；为空时取构建版本。 */
    private String version;

    /** 文档说明；为空时用默认文案（{tokenHeader} 会被替换为实际 token 头）。 */
    private String description;

    public OpenApiProperties() {
        this.version = buildVersion();
    }

    /**
     * 读取构建版本（{@code askdata-build.properties}，由 Maven 过滤 {@code @project.version@} 生成）。
     *
     * @return 构建版本；读取不到时返回 {@code 0.0.0}
     */
    static String buildVersion() {
        try (InputStream in = OpenApiProperties.class.getClassLoader().getResourceAsStream(BUILD_PROPERTIES)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.trim().isEmpty()) {
                    return v.trim();
                }
            }
        } catch (Exception e) {
            // 忽略：回退 0.0.0
        }
        return "0.0.0";
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
