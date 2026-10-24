package com.xiongx9527.askdata.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.fw.plus.common.util.SpringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SQLBot MCP HTTP REST 兼容层：POST /api/v1/mcp/* 七端点镜像 SQLBot
 * {@code apps/mcp/mcp.py} 的路由路径与响应键（P-01）。
 *
 * <p>薄适配层：解析 SQLBot pydantic 模型形态的 JSON body，委托 {@link AskdataMcpTools}
 * 既有实现（token 身份绑定、snake_case 参数映射、响应键组装复用 MCP 工具层，不改业务逻辑）。
 * 工具返回 {"error": ...} 时转 HTTP 400 {"detail": ...}，镜像 SQLBot HTTPException 形态。</p>
 *
 * <p>鉴权：/api/v1/mcp/** 剥 /api/v1 前缀后命中 {@code AuthWhitelist} 既有 /mcp* 条目
 * （SQLBot whitelist.py 逐字对齐项），免会话头访问；身份以 body 内 token 为准。</p>
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class SqlbotMcpCompatController {

    private final ObjectMapper mapper;
    private final Supplier<AskdataMcpTools> tools;

    @Autowired
    public SqlbotMcpCompatController(ObjectMapper mapper) {
        this(mapper, () -> SpringUtils.getBean(AskdataMcpTools.class));
    }

    SqlbotMcpCompatController(ObjectMapper mapper, Supplier<AskdataMcpTools> tools) {
        this.mapper = mapper;
        this.tools = tools;
    }

    /** POST /access_token：body ChatToken{username,password} → {access_token, token, tokenName}。 */
    @PostMapping("/access_token")
    public ResponseEntity<String> accessToken(@RequestBody(required = false) JsonNode body) throws Exception {
        return respond(tools.get().access_token(text(body, "username"), text(body, "password")));
    }

    /** POST /mcp_start：body ChatStart{username?,password?,token?,oid?,datasource_id?,question?} → {access_token, chat_id, ...}。 */
    @PostMapping("/mcp_start")
    public ResponseEntity<String> mcpStart(@RequestBody(required = false) JsonNode body) throws Exception {
        return respond(tools.get().mcp_start(text(body, "username"), text(body, "password"), text(body, "token"),
                longOrNull(body, "oid"), intStrArg(body, "datasource_id"), text(body, "question")));
    }

    /** POST /mcp_ws_list：body/query token → 工作空间数组。 */
    @PostMapping("/mcp_ws_list")
    public ResponseEntity<String> wsList(@RequestBody(required = false) JsonNode body,
                                         @RequestParam(value = "token", required = false) String queryToken) throws Exception {
        return respond(tools.get().mcp_ws_list(queryToken != null ? queryToken : text(body, "token")));
    }

    /** POST /mcp_ds_list（SQLBot 路由路径）/mcp_datasource_list（operation_id 别名）：body McpDs{token, oid?} → 数据源数组。 */
    @PostMapping({"/mcp_ds_list", "/mcp_datasource_list"})
    public ResponseEntity<String> dsList(@RequestBody(required = false) JsonNode body) throws Exception {
        return respond(tools.get().mcp_datasource_list(text(body, "token"), longOrNull(body, "oid")));
    }

    /** POST /mcp_model_list：body WsMcp{oid} → 模型数组。 */
    @PostMapping("/mcp_model_list")
    public ResponseEntity<String> modelList(@RequestBody(required = false) JsonNode body) throws Exception {
        return respond(tools.get().mcp_model_list(longOrNull(body, "oid")));
    }

    /** POST /mcp_question：body McpQuestion{token, chat_id?, question, datasource_id?, custom_model?, lang?, stream?, return_img?} → 问数结果。 */
    @PostMapping("/mcp_question")
    public ResponseEntity<String> mcpQuestion(@RequestBody(required = false) JsonNode body) throws Exception {
        return respond(tools.get().mcp_question(text(body, "token"),
                intStrArg(body, "chat_id"), text(body, "question"),
                intStrArg(body, "datasource_id"), intStrArg(body, "custom_model"),
                text(body, "lang"), boolOrNull(body, "stream"), boolOrNull(body, "return_img")));
    }

    /** POST /mcp_assistant：body McpAssistant{url?, message} → 助手对话结果。 */
    @PostMapping("/mcp_assistant")
    public ResponseEntity<String> mcpAssistant(@RequestBody(required = false) JsonNode body) throws Exception {
        return respond(tools.get().mcp_assistant(text(body, "url"), text(body, "message")));
    }

    /** 工具 JSON → HTTP 响应：含 error 键转 400 {"detail"}（镜像 SQLBot HTTPException），否则 200 原样。 */
    private ResponseEntity<String> respond(String toolJson) {
        try {
            JsonNode node = mapper.readTree(toolJson);
            JsonNode err = node == null ? null : node.get("error");
            if (err != null && !err.isNull() && !err.asText().isBlank()) {
                Map<String, Object> detail = new HashMap<String, Object>();
                detail.put("detail", err.asText());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsString(detail));
            }
        } catch (Exception ignored) {
            // 非 JSON 工具输出：按 200 原样返回
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(toolJson);
    }

    private static String text(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode v = body.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long longOrNull(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode v = body.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.longValue();
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }

    /** SQLBot int|str 联合类型参数：数值转 Long、文本原样（空串由底层归一为 null），避免 JsonNode.toString 带引号。 */
    private static Object intStrArg(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode v = body.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.longValue();
        }
        return v.asText();
    }

    private static Boolean boolOrNull(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode v = body.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : Boolean.parseBoolean(s);
    }
}

