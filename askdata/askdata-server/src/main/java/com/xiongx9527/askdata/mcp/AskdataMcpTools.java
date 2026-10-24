package com.xiongx9527.askdata.mcp;

import cn.dev33.satoken.stp.StpUtil;
import com.ajaxjs.mcp.server.feature.annotation.McpService;
import com.ajaxjs.mcp.server.feature.annotation.Tool;
import com.ajaxjs.mcp.server.feature.annotation.ToolArg;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.fw.plus.ai.autoconfigure.langchain4j.support.assistant.SimpleAssistant;
import com.xiongx9527.fw.plus.common.util.SpringUtils;
import com.xiongx9527.askdata.ai.AiModelConfig;
import com.xiongx9527.askdata.ai.AskdataNl2Sql;
import com.xiongx9527.askdata.chart.server.ChartImageService;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.ChatService;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import dev.langchain4j.model.chat.ChatModel;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.AiModelService;
import com.xiongx9527.askdata.system.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * askdata 的 MCP 工具集（框架 fw-plus-ai-mcp-server 扫描注册）。
 *
 * <p>工具类由框架反射无参实例化，业务服务经框架的 {@link SpringUtils#getBean(Class)} 从容器获取。
 * 能力对齐 SQLBot MCP 7 operations：datasource_list、workspace_list、model_list、chat_start、
 * access_token、question_answer（returnImg 可选）、assistant；另有 server_info 连通探针。</p>
 */
@McpService
public class AskdataMcpTools {

    private static final Logger log = LoggerFactory.getLogger(AskdataMcpTools.class);

    /**
     * 列出 askdata 数据源（返回 JSON 数组）。
     */
    @Tool(value = "datasource_list", description = "列出当前用户/工作空间可用的数据源（JSON 数组）；管理员可用 workspaceId 指定工作空间")
    public String datasource_list(@ToolArg(value = "workspaceId", required = false) Long workspaceId) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        try {
            DatasourceService datasourceService = SpringUtils.getBean(DatasourceService.class);
            // 对齐 SQLBot mcp_ds_list：按当前用户 oid 过滤；管理员可显式指定 workspaceId
            List<Map<String, Object>> list = datasourceService.list(workspaceId);
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            return errorJson(mapper, "datasource_list", e);
        }
    }

    /**
     * 服务信息探针：用于 MCP client 验证 askdata MCP 链路连通。
     */
    @Tool(value = "server_info", description = "返回 askdata MCP 服务信息（连通性探针）")
    public String server_info() throws Exception {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("app", "askdata");
        info.put("mcp", "fw-plus-ai-mcp-server");
        info.put("transport", "http-sse");
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        return mapper.writeValueAsString(info);
    }

    /**
     * 工作空间列表（对齐 SQLBot mcp_ws_list）：当前登录用户可管理的工作空间。
     * 非管理员 / 未登录时返回可读错误（不抛异常）。
     */
    @Tool(value = "workspace_list", description = "列出当前用户可管理的工作空间（对齐 SQLBot mcp_ws_list）")
    public String workspace_list() throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        try {
            WorkspaceService service = SpringUtils.getBean(WorkspaceService.class);
            return mapper.writeValueAsString(service.list());
        } catch (Exception e) {
            return errorJson(mapper, "workspace_list", e);
        }
    }

    /**
     * 当前工作空间可用模型（对齐 SQLBot mcp_model_list）。
     */
    @Tool(value = "model_list", description = "列出当前工作空间可用的对话模型（对齐 SQLBot mcp_model_list）")
    public String model_list() throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        try {
            AiModelService service = SpringUtils.getBean(AiModelService.class);
            return mapper.writeValueAsString(service.listByWs());
        } catch (Exception e) {
            return errorJson(mapper, "model_list", e);
        }
    }

    /**
     * 开启一次问数会话（对齐 SQLBot mcp_start）：可指定数据源与初始问题，返回 chat_id 等会话信息。
     */
    @Tool(value = "chat_start", description = "开启一次问数会话（对齐 SQLBot mcp_start）：可带 datasourceId/question，返回 chat_id 等会话信息")
    public String chat_start(@ToolArg(value = "datasourceId", required = false) Long datasourceId,
                             @ToolArg(value = "question", required = false) String question) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        try {
            ChatService service = SpringUtils.getBean(ChatService.class);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            if (datasourceId != null) {
                body.put("datasource", datasourceId);
            }
            if (question != null) {
                body.put("question", question);
            }
            return mapper.writeValueAsString(service.start(body));
        } catch (Exception e) {
            return errorJson(mapper, "chat_start", e);
        }
    }

    /**
     * 用户名/口令换取访问 token（对齐 SQLBot mcp_access_token）。
     * 复用登录校验（MD5 比对 sys_user.password）并签发 Sa-Token；失败返回可读错误。
     */
    @Tool(value = "access_token", description = "用户名/口令换取访问 token（对齐 SQLBot mcp_access_token）")
    public String access_token(@ToolArg("username") String username,
                               @ToolArg("password") String password) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        UserRepository repository = SpringUtils.getBean(UserRepository.class);
        Optional<Map<String, Object>> userRow = username == null ? Optional.empty() : repository.findByAccount(username);
        if (!userRow.isPresent()
                || !CryptoUtil.md5Hex(password == null ? "" : password).equals(String.valueOf(userRow.get().get("password")))) {
            out.put("error", "账号或密码错误");
            return mapper.writeValueAsString(out);
        }
        Map<String, Object> user = userRow.get();
        long oid = toLong(user.get("oid"));
        long status = toLong(user.get("status"));
        long origin = toLong(user.get("origin"));
        if (oid == 0) {
            out.put("error", "用户未绑定工作空间");
            return mapper.writeValueAsString(out);
        }
        if (status != 1) {
            out.put("error", "用户已禁用");
            return mapper.writeValueAsString(out);
        }
        if (origin != 0) {
            out.put("error", "第三方用户不支持口令登录");
            return mapper.writeValueAsString(out);
        }
        try {
            StpUtil.login(toLong(user.get("id")));
            out.put("token", StpUtil.getTokenValue());
            out.put("tokenName", StpUtil.getTokenName());
        } catch (Exception e) {
            out.put("error", "签发 token 失败: " + e.getMessage());
        }
        return mapper.writeValueAsString(out);
    }

    private String errorJson(ObjectMapper mapper, String tool, Exception e) throws Exception {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("error", tool + " 失败: " + e.getMessage());
        return mapper.writeValueAsString(err);
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value).trim());
    }

    /**
     * 问数助手对话：走框架装配的 {@link SimpleAssistant}（多轮记忆 + thinking 透传能力）。
     * 未装配模型时返回可读错误（不抛异常），由调用方降级。
     *
     * <p>对齐 SQLBot {@code mcp_assistant}：多轮记忆按调用者身份隔离（原实现固定
     * {@code memoryId="mcp"}，所有调用者共享同一份上下文，存在跨用户串话）；
     * 对话以当前登录用户身份落 CHAT 审计（SQLBot 侧助手操作进入审计体系）。</p>
     */
    @Tool(value = "assistant", description = "问数助手对话（多轮记忆，基于默认对话模型）")
    public String assistant(@ToolArg("message") String message) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        Map<String, SimpleAssistant> beans = SpringUtils.getBeansOfType(SimpleAssistant.class);
        if (beans.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("error", "assistant 未装配：需配置默认对话模型并设置 askdata.assistant.enabled=true");
            return mapper.writeValueAsString(err);
        }
        // 记忆按调用者隔离：未登录（无 Sa-Token 会话）退回共享 id
        String memoryId = "mcp";
        try {
            memoryId = "mcp-" + StpUtil.getLoginIdAsLong();
        } catch (Exception ignored) {
            // 未登录：保持共享 memoryId
        }
        SimpleAssistant assistant = beans.values().iterator().next();
        AuditRecorder auditor = SpringUtils.getBean(AuditRecorder.class);
        String remark = "mcp-assistant: " + (message == null ? "" : message.substring(0, Math.min(message.length(), 80)));
        String answer;
        try {
            answer = assistant.chat(memoryId, null, message);
        } catch (Exception e) {
            auditor.failure(AuditOperationType.ANALYSIS, AuditModule.CHAT, null,
                    "mcp-assistant 失败: " + e.getMessage());
            throw e;
        }
        auditor.success(AuditOperationType.ANALYSIS, AuditModule.CHAT, null, remark);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("answer", answer);
        return mapper.writeValueAsString(out);
    }

    /**
     * 问数（NL2SQL）：自然语言 → SQL → 只读执行，返回 question/sql/rows。
     * 对齐 SQLBot {@code mcp_question}：支持 datasourceId（选定数据源）、chatId（续接会话并落库）、
     * customModel（按次选模型）、returnImg（是否出图）。未装配模型时返回可读错误。
     */
    @Tool(value = "question_answer", description = "问数：自然语言转SQL并只读执行，返回 SQL 与结果行；可指定 datasourceId、chatId、customModel、returnImg（对齐 SQLBot mcp_question）")
    public String question_answer(@ToolArg("question") String question,
                                 @ToolArg(value = "datasourceId", required = false) Long datasourceId,
                                 @ToolArg(value = "chatId", required = false) Long chatId,
                                 @ToolArg(value = "customModel", required = false) Long customModel,
                                 @ToolArg(value = "returnImg", required = false) Boolean returnImg) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        Map<String, AskdataNl2Sql> beans = SpringUtils.getBeansOfType(AskdataNl2Sql.class);
        if (beans.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("error", "question_answer 未装配：需配置默认对话模型并设置 askdata.assistant.enabled=true");
            return mapper.writeValueAsString(err);
        }
        AskdataNl2Sql nl2sql = beans.values().iterator().next();

        // customModel：按次选模型（对齐 SQLBot mcp_question.custom_model）
        ChatModel modelOverride = null;
        if (customModel != null) {
            ChatModelRouter router = SpringUtils.getBean(ChatModelRouter.class);
            Optional<ChatModelRouter.ModelRef> ref = router.chatModelById(customModel)
                    .filter(ChatModelRouter.ModelRef::usable);
            if (!ref.isPresent()) {
                Map<String, Object> err = new LinkedHashMap<String, Object>();
                err.put("error", "customModel 不存在或不可用: " + customModel);
                return mapper.writeValueAsString(err);
            }
            modelOverride = AiModelConfig.buildChatModel(ref.get());
        }

        // chatId：续接已有会话——校验归属、继承会话数据源、落一条记录（对齐 SQLBot mcp_question.chat_id）
        Long dsId = datasourceId;
        ChatRepository chatRepository = null;
        long recordId = 0L;
        long started = System.currentTimeMillis();
        if (chatId != null) {
            chatRepository = SpringUtils.getBean(ChatRepository.class);
            CurrentUserService currentUserService = SpringUtils.getBean(CurrentUserService.class);
            Map<String, Object> user = currentUserService.currentUser();
            Optional<Map<String, Object>> chatOpt = chatRepository.findChat(chatId);
            if (!chatOpt.isPresent()) {
                Map<String, Object> err = new LinkedHashMap<String, Object>();
                err.put("error", "chatId 不存在: " + chatId);
                return mapper.writeValueAsString(err);
            }
            Map<String, Object> chat = chatOpt.get();
            requireChatOwner(chat, user, currentUserService);
            if (dsId == null) {
                long chatDs = Rows.lng(chat, "datasource", 0L);
                if (chatDs > 0) {
                    dsId = chatDs;
                }
            }
            recordId = chatRepository.insertRecord(chatId, question,
                    Rows.lng(user, "id", 0L), currentUserService.oidOf(user), dsId, false, null);
        }

        // 验收 A2：datasourceId 非空时在该数据源（可能为 PostgreSQL/Oracle 等非 MySQL）上生成并执行正确方言的 SQL
        Map<String, Object> result;
        try {
            result = nl2sql.ask(question, dsId, modelOverride);
        } catch (Exception e) {
            if (chatId != null && recordId > 0) {
                chatRepository.saveError(recordId, e.getMessage());
                chatRepository.finishRecord(recordId, System.currentTimeMillis() - started, 0);
            }
            throw e;
        }

        if (chatId != null && recordId > 0) {
            Object sql = result.get("sql");
            chatRepository.saveSql(recordId, sql == null ? null : String.valueOf(sql), null);
            chatRepository.saveSqlData(recordId, mapper.writeValueAsString(result.get("rows")));
            chatRepository.finishRecord(recordId, System.currentTimeMillis() - started, 0);
            result.put("chatId", chatId);
            result.put("recordId", recordId);
        }

        // 验收 A4：服务端出图，非交互/MCP 场景返回图片 URL；returnImg=false 时不渲染（对齐 SQLBot mcp_question）
        try {
            if (shouldReturnImage(returnImg)) {
                ChartImageService imageService = SpringUtils.getBean(ChartImageService.class);
                ChatChartBuilder chartBuilder = SpringUtils.getBean(ChatChartBuilder.class);
                if (imageService != null && chartBuilder != null) {
                    String imageUrl = imageService.renderFromResult(result, question);
                    if (imageUrl != null) {
                        result.put("imageUrl", imageUrl);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MCP question_answer 出图失败（降级无图）: {}", e.getMessage());
        }
        return mapper.writeValueAsString(result);
    }

    /**
     * A7：{@code returnImg} 语义（对齐 SQLBot {@code McpQuestion.return_img}）——
     * 未传时默认 true（命中即返图），显式 false 时完全不渲染、响应不含 {@code imageUrl}。
     */
    static boolean shouldReturnImage(Boolean returnImg) {
        return returnImg == null || returnImg;
    }

    /** 会话归属校验（对齐 ChatQuestionService.requireOwner）：管理员放行；否则须同工作空间且为创建者。 */
    private void requireChatOwner(Map<String, Object> chat, Map<String, Object> user,
                                  CurrentUserService currentUserService) {
        if (currentUserService.isAdmin(user)) {
            return;
        }
        long id = Rows.lng(chat, "id", 0L);
        if (Rows.lng(chat, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new IllegalArgumentException("Chat with id " + id + " does not belong to current workspace");
        }
        if (Rows.lng(chat, "create_by", 0L) != Rows.lng(user, "id", 0L)) {
            throw new IllegalArgumentException("Chat with id " + id + " not owned by the current user");
        }
    }
}
