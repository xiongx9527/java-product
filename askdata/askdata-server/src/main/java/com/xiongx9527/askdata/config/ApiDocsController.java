package com.xiongx9527.askdata.config;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * SQLBot 文档站的两个历史路径，映射到本项目的 OpenAPI 3 实现（audit §1.3）。
 *
 * <ul>
 *   <li>{@code /openapi.json}：SQLBot {@code main.py:188} 的规范入口。用 servlet forward 转到
 *       springdoc 的 {@code /v3/api-docs}，状态码、Content-Type 与 JSON 内容都取自同一个处理器，
 *       不再自己序列化一遍（避免和 springdoc 内部 API 耦合）；</li>
 *   <li>{@code /docs}：SQLBot 的 Swagger UI 页面入口（前端「API KEY」页直接链接它），
 *       跳到 Knife4j 的 {@code /doc.html}。</li>
 * </ul>
 *
 * <p>两条路径都在 {@code AuthWhitelist.API_DOCS_PATHS} 免鉴权，与 SQLBot 公开文档站一致。</p>
 */
@RestController
public class ApiDocsController {

    /** springdoc 的规范端点，对应 {@code springdoc.api-docs.path}。 */
    static final String SPEC_PATH = "/v3/api-docs";

    @GetMapping("/openapi.json")
    public void openapiJson(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request.getRequestDispatcher(SPEC_PATH).forward(request, response);
    }

    @GetMapping("/docs")
    public RedirectView docs() {
        return new RedirectView("/doc.html");
    }
}
