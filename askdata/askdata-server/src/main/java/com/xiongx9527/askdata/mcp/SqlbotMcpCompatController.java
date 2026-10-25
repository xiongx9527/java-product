package com.xiongx9527.askdata.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.fw.plus.common.util.SpringUtils;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import jakarta.servlet.http.HttpServletResponse;
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

    @Autowired
    public SqlbotMcpCompatController(ObjectMapper mapper) {
        this(mapper, () -> SpringUtils.getBean(AskdataMcpTools.class),
                () -> SpringUtils.getBean(ChatQuestionService.class));
    }

    SqlbotMcpCompatController(ObjectMapper mapper, Supplier<AskdataMcpTools> tools,
                              Supplier<ChatQuestionService> streamer) {
        this.mapper = mapper;
        this.tools = tools;
        this.streamer = streamer;
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

    /**
     * POST /mcp_question：body McpQuestion{token, chat_id?, question, datasource_id?, custom_model?, lang?, stream?, return_img?}。
     *
     * <p>{@code stream=true} 且带 {@code chat_id} 时按 SQLBot 语义返回 {@code text/event-stream}
     * （委托 {@link ChatQuestionService#streamQuestion}，帧格式与前端 ChartAnswer 一致）；
     * 缺少 {@code chat_id} 或指定了 {@code custom_model}（流式链路不支持按次选模型）时退回同步 JSON。</p>
     */
    @PostMapping("/mcp_question")
    public void mcpQuestion(@RequestBody(required = false) JsonNode body, HttpServletResponse response)
            throws Exception {
        if (Boolean.TRUE.equals(boolOrNull(body, "stream")) && streamQuestion(body, response)) {
            return;
        }
        write(response, respond(tools.get().mcp_question(text(body, "token"),
                intStrArg(body, "chat_id"), text(body, "question"),
                intStrArg(body, "datasource_id"), intStrArg(body, "custom_model"),
                text(body, "lang"), boolOrNull(body, "stream"), boolOrNull(body, "return_img"))));
    }

    /** 流式问数：返回 true 表示已写出 SSE 响应，false 表示条件不满足需退回同步 JSON。 */
    private boolean streamQuestion(JsonNode body, HttpServletResponse response) throws Exception {
        Long chatId = longOrNull(body, "chat_id");
        String question = text(body, "question");
        if (chatId == null || chatId <= 0L || question == null || question.trim().isEmpty()
                || longOrNull(body, "custom_model") != null) {
            return false;
        }
        // 身份绑定必须在打开 SSE 之前完成，否则异常发生在响应提交后无法回写状态码
        tools.get().bindTokenStrict(text(body, "token"));
        bindChatDatasource(chatId, longOrNull(body, "datasource_id"));
        String lang = text(body, "lang");
        Locale previous = LocaleContextHolder.getLocale();
        try {
            if (lang != null && !lang.trim().isEmpty()) {
                LocaleContextHolder.setLocale(Locale.forLanguageTag(lang.trim()));
            }
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("chat_id", chatId);
            request.put("question", question);
            streamer.get().streamQuestion(request, response);
            return true;
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }

    /**
     * SQLBot mcp_question.datasource_id：会话尚未绑定数据源时先绑定，供流式链路复用。
     *
     * <p>写库前先做会话归属校验（与 {@link ChatQuestionService} 的 requireOwner 同口径），
     * 归属不符时不落库，由随后的流式调用抛出业务拒绝，避免越权改写他人会话的数据源。</p>
     */
    private void bindChatDatasource(long chatId, Long datasourceId) {
        if (datasourceId == null || datasourceId <= 0L) {
            return;
        }
        ChatRepository chats = SpringUtils.getBean(ChatRepository.class);
        Map<String, Object> chat = chats.findChat(chatId).orElse(null);
        if (chat == null || Rows.lng(chat, "datasource", 0L) > 0L) {
            return;
        }
        CurrentUserService users = SpringUtils.getBean(CurrentUserService.class);
        Map<String, Object> user = users.currentUser();
        if (!users.isAdmin(user)
                && (Rows.lng(chat, "oid", 0L) != users.oidOf(user)
                || Rows.lng(chat, "create_by", 0L) != Rows.lng(user, "id", 0L))) {
            return;
        }
        Map<String, Object> ds = SpringUtils.getBean(DatasourceRepository.class).findById(datasourceId).orElse(null);
        if (ds != null) {
            chats.setChatDatasource(chatId, datasourceId, Rows.str(ds, "type_name"), Rows.str(ds, "type"));
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

    /** POST /mcp_assistant：body McpAssistant{question, url?, authorization?, stream?} → 助手对话结果。 */
    @PostMapping("/mcp_assistant")
    public ResponseEntity<String> mcpAssistant(@RequestBody(required = false) JsonNode body) throws Exception {
        return respond(tools.get().mcp_assistant(text(body, "question"), text(body, "url"),
                text(body, "authorization"), boolOrNull(body, "stream"), text(body, "message")));
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

