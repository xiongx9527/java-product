package com.xiongx9527.askdata.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档元数据（对齐 SQLBot 的 {@code /docs} + {@code /openapi.json} 对外能力）。
 *
 * <p>UI 由 Knife4j 提供：{@code /doc.html}；规范 JSON：{@code /v3/api-docs}。
 * 两者都在 {@code AuthWhitelist.API_DOCS_PATHS} 中放行，未登录也能打开（与 SQLBot 一致）。
 * 文档里声明的 {@code X-SQLBOT-TOKEN} 安全方案对应 Sa-Token 的 {@code token-name}，
 * 便于在 Knife4j 页面顶部「Authorize」里粘贴登录返回的 token 直接调试受保护接口。</p>
 */
@Configuration
public class OpenApiConfig {

    /** Sa-Token 的 token 头名，与 {@code sa-token.token-name} 保持一致。 */
    private static final String TOKEN_HEADER = "X-SQLBOT-TOKEN";

    private static final String SCHEME_NAME = "AskToken";

    @Bean
    public OpenAPI askdataOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("askdata API")
                        .version("0.3.0-SNAPSHOT")
                        .description("askdata 服务端接口（与 SQLBot 语义对齐）。"
                                + "认证方式：先 POST /api/v1/login/access-token 取得 token，"
                                + "再在请求头带 " + TOKEN_HEADER + "。"))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(TOKEN_HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }
}
