package com.xiongx9527.askdata.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.fw.plus.common.util.SpringUtils;
import com.xiongx9527.askdata.chat.service.ChatModelClient.FrameWriter;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.common.i18n.I18n;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
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
    private final Supplier<ChatQuestionService> streamer;
    private final Supplier<McpAssistantSupport> assistantSupport;

    @Autowired
    public SqlbotMcpCompatController(ObjectMapper mapper) {
        this(mapper, () -> SpringUtils.getBean(AskdataMcpTools.class),
                () -> SpringUtils.getBean(ChatQuestionService.class),
                () -> SpringUtils.getBean(McpAssistantSupport.class));
    }

    SqlbotMcpCompatController(ObjectMapper mapper, Supplier<AskdataMcpTools> tools,
                              Supplier<ChatQuestionService> streamer) {
        this(mapper, tools, streamer, () -> SpringUtils.getBean(McpAssistantSupport.class));
    }

    SqlbotMcpCompatController(ObjectMapper mapper, Supplier<AskdataMcpTools> tools,
                              Supplier<ChatQuestionService> streamer,
                              Supplier<McpAssistantSupport> assistantSupport) {
        this.mapper = mapper;
        this.tools = tools;
        this.streamer = streamer;
        this.assistantSupport = assistantSupport;
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

    /** POST /mcp_model_list：body WsMcp{token?,oid} → 模型数组；token 非法时按 A8 返回 403。 */
    @PostMapping("/mcp_model_list")
    public ResponseEntity<String> modelList(@RequestBody(required = false) JsonNode body) throws Exception {
        return respond(tools.get().mcp_model_list(text(body, "token"), longOrNull(body, "oid")));
    }

    /**
     * POST /mcp_question：body McpQuestion{token, chat_id?, question, datasource_id?, custom_model?, lang?, stream?, return_img?}。
     *
     * <p>{@code stream} 缺省/true 返回 {@code text/event-stream}（A2）；显式 false 返回 SQLBot JSON，
     * 运行期错误映射为 500 {@code {success:false,message}}（A8）。{@code custom_model}/{@code return_img}
     * 在流式路径同样生效（A3）。</p>
     */
    @PostMapping("/mcp_question")
    public void mcpQuestion(@RequestBody(required = false) JsonNode body, HttpServletResponse response)
            throws Exception {
        if (!Boolean.FALSE.equals(boolOrNull(body, "stream"))) {
            try {
                streamQuestion(body, response);
            } catch (BadRequestException e) {
                write(response, detail(HttpStatus.BAD_REQUEST, e.getMessage()));
            }
            return;
        }
        String json = tools.get().mcp_question(text(body, "token"),
                intStrArg(body, "chat_id"), text(body, "question"),
                intStrArg(body, "datasource_id"), intStrArg(body, "custom_model"),
                text(body, "lang"), Boolean.FALSE, boolOrNull(body, "return_img"));
        write(response, respondQuestion(json));
    }

    /** 流式问数：身份绑定、会话创建等前置动作全部在打开 SSE 之前完成。 */
    private void streamQuestion(JsonNode body, HttpServletResponse response) throws Exception {
        Long chatId = longOrNull(body, "chat_id");
        Long customModel = longOrNull(body, "custom_model");
        Long datasourceId = longOrNull(body, "datasource_id");
        String question = text(body, "question");
        if (question == null || question.trim().isEmpty()) {
            throw new BadRequestException(I18n.msg("i18n_miss_args", "question"));
        }
        // 身份绑定必须在打开 SSE 之前完成，否则异常发生在响应提交后无法回写状态码
        tools.get().bindTokenStrict(text(body, "token"));
        // A1/D2：无 chat_id 时创建 MCP 会话（不建 first_chat 空记录）
        chatId = tools.get().resolveChatForQuestion(chatId, question);
        String lang = text(body, "lang");
        Locale previous = LocaleContextHolder.getLocale();
        try {
            if (lang != null && !lang.trim().isEmpty()) {
                LocaleContextHolder.setLocale(Locale.forLanguageTag(lang.trim()));
            }
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("chat_id", chatId);
            request.put("question", question);
            if (datasourceId != null) {
                request.put("datasource_id", datasourceId);
            }
            if (customModel != null) {
                request.put("custom_model", customModel);
            }
            Boolean returnImg = boolOrNull(body, "return_img");
            if (returnImg != null) {
                request.put("return_img", returnImg);
            }
            try {
                streamer.get().streamQuestion(request, response);
            } catch (BadRequestException e) {
                // A8：SSE 已建立后的运行期业务错误（如会话不存在）不改成 4xx/5xx，
                // 仍以 error + finish 帧结束；参数/身份校验仍在上方以 400/403 返回。
                writeStreamError(response, e.getMessage());
            }
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }

    /** 流式问数的运行期业务错误帧，格式与 {@code ChatQuestionService} 的 error 帧一致。 */
    private void writeStreamError(HttpServletResponse response, String message) throws IOException {
        try (FrameWriter writer = new FrameWriter(response, mapper)) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("message", message == null ? "" : message);
            payload.put("traceback", "");
            payload.put("type", "exec-sql");
            writer.write(Rows.of("type", "error", "content", mapper.writeValueAsString(payload)));
            writer.write(Rows.of("type", "finish"));
        }
    }

    /** ResponseEntity 落到原生响应（mcp_question 需在流式/同步两种形态间切换，故不再直接返回）。 */
    private void write(HttpServletResponse response, ResponseEntity<String> entity) throws IOException {
        response.setStatus(entity.getStatusCode().value());
        MediaType contentType = entity.getHeaders().getContentType();
        response.setContentType(contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType.toString());
        response.setCharacterEncoding("UTF-8");
        String body = entity.getBody();
        if (body != null) {
            response.getWriter().write(body);
        }
    }

    /**
     * POST /mcp_assistant：body McpAssistant{question, url, authorization, stream?, datasourceId?}。
     *
     * <p>{@code stream} 缺省/true 返回 SSE（A2），false 返回 JSON。url/authorization 必填，
     * 以合成身份创建会话与真实记录（A4）。</p>
     */
    @PostMapping("/mcp_assistant")
    public void mcpAssistant(@RequestBody(required = false) JsonNode body, HttpServletResponse response)
            throws Exception {
        if (!Boolean.FALSE.equals(boolOrNull(body, "stream"))) {
            assistantSupport.get().stream(text(body, "question"), text(body, "url"),
                    text(body, "authorization"), longOrNull(body, "datasourceId"), response);
            return;
        }
        String json = tools.get().mcp_assistant(text(body, "question"), text(body, "url"),
                text(body, "authorization"), Boolean.FALSE, text(body, "message"),
                longOrNull(body, "datasourceId"));
        write(response, respond(json));
    }

    /** 工具 JSON → HTTP 响应：含 error 键转 400 {"detail"}（镜像 SQLBot HTTPException），否则 200 原样。 */
    private ResponseEntity<String> respond(String toolJson) {
        try {
            JsonNode node = mapper.readTree(toolJson);
            JsonNode err = node == null ? null : node.get("error");
            if (err != null && !err.isNull() && !err.asText().trim().isEmpty()) {
                return detail(HttpStatus.BAD_REQUEST, err.asText());
            }
        } catch (Exception ignored) {
            // 非 JSON 工具输出：按 200 原样返回
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(toolJson);
    }

    /**
     * 非流式 mcp_question 响应（A8）：{@code success=false} 的运行期错误返回 500，
     * 保持工具层的 {@code {success:false,message,record_id?}} 结构。
     */
    private ResponseEntity<String> respondQuestion(String toolJson) {
        try {
            JsonNode node = mapper.readTree(toolJson);
            if (node != null && node.has("success") && !node.get("success").asBoolean()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(toolJson);
            }
        } catch (Exception ignored) {
            // 非 JSON 输出按常规错误映射处理
        }
        return respond(toolJson);
    }

    /** MCP 错误体：{@code {"detail":...}}。 */
    private ResponseEntity<String> detail(HttpStatus status, String message) {
        Map<String, Object> detail = new HashMap<String, Object>();
        detail.put("detail", message == null ? "" : message);
        try {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(detail));
        } catch (Exception e) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"detail\":\"\"}");
        }
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
        if (v.isContainerNode()) {
            // SQLBot 的 int|str 联合类型不接受对象/数组：不能静默当作缺省值，按格式非法 400
            throw new BadRequestException(I18n.msg("mcp.error.invalid.id"));
        }
        if (v.isNumber()) {
            return v.longValue();
        }
        String s = v.asText().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            // A8：字段格式非法按 SQLBot 校验口径 400，不进入问数执行
            throw new BadRequestException(I18n.msg("mcp.error.invalid.id"));
        }
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
        if (v.isContainerNode()) {
            // 对象/数组不满足 int|str 联合类型：直接 400，避免 asText() 退化为空串后当作缺省值
            throw new BadRequestException(I18n.msg("mcp.error.invalid.id"));
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

