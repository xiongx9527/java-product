package com.xiongx9527.askdata.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档元数据（对齐 SQLBot 的 {@code /docs} + {@code /openapi.json} 对外能力）。
 *
 * <p>UI 由 Knife4j 提供：{@code /doc.html}；规范 JSON：{@code /v3/api-docs}。
 * 两者都在 {@code AuthWhitelist.API_DOCS_PATHS} 中放行，未登录也能打开（与 SQLBot 一致）。
 * 文档里声明的安全方案对应 Sa-Token 的 {@code token-name}，便于在 Knife4j 页面顶部
 * 「Authorize」里粘贴登录返回的 token 直接调试受保护接口。</p>
 *
 * <p>标题 / 版本 / 说明改为可配置（见 {@link OpenApiProperties}）；token 头名不再硬编码，
 * 直接取 {@code sa-token.token-name}，与鉴权实际使用的头保持一致（改配置即同步改文档）。</p>
 */
@Configuration
public class OpenApiConfig {

    /** 授权方案名。 */
    private static final String SCHEME_NAME = "AskToken";

    /** 默认说明文案；{@code {tokenHeader}} 会被替换为实际 token 头。 */
    private static final String DEFAULT_DESCRIPTION =
            "askdata 服务端接口（与 SQLBot 语义对齐）。"
                    + "认证方式：先 POST /api/v1/login/access-token 取得 token，"
                    + "再在请求头带 {tokenHeader}。";

    private final OpenApiProperties properties;

    /** Sa-Token 的 token 头名（与 sa-token.token-name 一致）。 */
    private final String tokenHeader;

    public OpenApiConfig(OpenApiProperties properties,
                          @Value("${sa-token.token-name}") String tokenHeader) {
        this.properties = properties;
        this.tokenHeader = tokenHeader;
    }

    @Bean
    public OpenAPI askdataOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(properties.getTitle())
                        .version(resolveVersion())
                        .description(resolveDescription()))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(tokenHeader)))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }

    /** 版本：优先取配置，缺省用构建版本。 */
    private String resolveVersion() {
        String v = properties.getVersion();
        return (v == null || v.trim().isEmpty()) ? OpenApiProperties.buildVersion() : v.trim();
    }

    /** 说明：优先取配置，缺省用默认文案（token 头占位符替换为实际值）。 */
    private String resolveDescription() {
        String desc = properties.getDescription();
        if (desc == null || desc.trim().isEmpty()) {
            desc = DEFAULT_DESCRIPTION;
        }
        return desc.replace("{tokenHeader}", tokenHeader);
    }
}
