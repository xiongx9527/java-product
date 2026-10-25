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
import com.xiongx9527.askdata.common.i18n.I18n;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.embedded.DynamicDatasource;
import com.xiongx9527.askdata.embedded.DynamicDatasourceResolver;
import dev.langchain4j.model.chat.ChatModel;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.AiModelService;
import com.xiongx9527.askdata.system.service.WorkspaceService;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.common.web.BadRequestException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
            // 对齐 SQLBot mcp_model_list：按调用者当前工作空间取模型，不要求管理员（REST 的 by_ws 才限管理员）
            CurrentUserService users = currentUserService();
            long oid = users.oidOf(users.currentUser());
            AiModelService service = SpringUtils.getBean(AiModelService.class);
            return mapper.writeValueAsString(service.listByWs(oid));
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
            out.put("error", I18n.msg("login.account.pwd.error"));
            return mapper.writeValueAsString(out);
        }
        Map<String, Object> user = userRow.get();
        long oid = toLong(user.get("oid"));
        long status = toLong(user.get("status"));
        long origin = toLong(user.get("origin"));
        if (oid == 0) {
            out.put("error", I18n.msg("mcp.error.user.no.workspace"));
            return mapper.writeValueAsString(out);
        }
        if (status != 1) {
            out.put("error", I18n.msg("mcp.error.user.disabled"));
            return mapper.writeValueAsString(out);
        }
        if (origin != 0) {
            out.put("error", I18n.msg("mcp.error.third.party.pwd"));
            return mapper.writeValueAsString(out);
        }
        try {
            StpUtil.login(toLong(user.get("id")));
            String tokenValue = StpUtil.getTokenValue();
            out.put("token", tokenValue);
            out.put("tokenName", StpUtil.getTokenName());
            // SQLBot 兼容字段：预设客户端按 access_token 键读取令牌
            out.put("access_token", tokenValue);
        } catch (Exception e) {
            out.put("error", I18n.msg("mcp.error.token.sign.failed", e.getMessage()));
        }
        return mapper.writeValueAsString(out);
    }

    private String errorJson(ObjectMapper mapper, String tool, Exception e) throws Exception {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("error", I18n.msg("mcp.error.tool.failed", tool, e.getMessage()));
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
            err.put("error", I18n.msg("mcp.error.assistant.not.assembled"));
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
                    I18n.msg("mcp.error.assistant.failed", e.getMessage()));
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
            err.put("error", I18n.msg("mcp.error.question.answer.not.assembled"));
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
                err.put("error", I18n.msg("mcp.error.custom.model.unavailable", customModel));
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
                err.put("error", I18n.msg("mcp.error.chat.id.not.found", chatId));
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

    // ===== SQLBot 预设客户端兼容层：工具名别名（零改造接入）=====
    //
    // SQLBot MCP 的 7 个 operation_id（mcp.py）：access_token、mcp_start、mcp_ws_list、
    // mcp_datasource_list（注意路由 /mcp_ds_list 的 operation_id 是 mcp_datasource_list）、
    // mcp_model_list、mcp_question、mcp_assistant。以下别名委托既有实现，参数按 SQLBot
    // snake_case 签名声明；token 参数经 Sa-Token 反查绑定当前调用身份（复用 access_token
    // 签发的令牌，不签新 token、不做新鉴权机制）。

    /**
     * 身份绑定：token 非空且有效时把该令牌绑定为本次调用的登录身份（后续 CurrentUserService
     * 即以该用户执行）；token 非空但无效时抛出业务拒绝（对齐 SQLBot {@code get_user} 的 403 语义）；
     * token 为空时沿用现有会话（兼容既有调用）。
     */
    void bindTokenStrict(String token) {
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        String trimmed = token.trim();
        if (StpUtil.getLoginIdByToken(trimmed) == null) {
            throw new BadRequestException(I18n.msg("mcp.error.token.invalid"));
        }
        StpUtil.setTokenValue(trimmed);
    }

    /** 校验当前用户可访问指定工作空间：管理员放行，普通成员须存在于 sys_user_ws（对齐 SQLBot user_ws_options）。 */
    private void requireWorkspace(Map<String, Object> user, long oid) {
        if (oid <= 0) {
            return;
        }
        if (currentUserService().isAdmin(user)) {
            return;
        }
        UserWsRepository repo = SpringUtils.getBean(UserWsRepository.class);
        if (!repo.find(Rows.lng(user, "id", 0L), oid).isPresent()) {
            throw new BadRequestException(I18n.msg("mcp.error.user.not.in.workspace"));
        }
    }

    private CurrentUserService currentUserService() {
        return SpringUtils.getBean(CurrentUserService.class);
    }

    /**
     * 临时切换到指定工作空间（对齐 SQLBot mcp_start 的 {@code user.oid = oid}）：
     * 覆盖 {@link AuthContext} 的当前用户视图，不改变用户持久默认工作空间；返回原上下文以便恢复。
     */
    private AuthContext enterWorkspace(long oid) {
        CurrentUserService svc = currentUserService();
        AuthContext existing = AuthContext.current();
        AuthContext temp = new AuthContext();
        if (existing != null) {
            temp.setChannel(existing.getChannel());
            temp.setAssistant(existing.getAssistant());
            temp.setRequestOrigin(existing.getRequestOrigin());
        }
        Map<String, Object> user = new HashMap<String, Object>(svc.currentUser());
        user.put("oid", oid);
        temp.setCurrentUser(user);
        AuthContext.set(temp);
        return existing;
    }

    private void restoreWorkspace(AuthContext existing) {
        if (existing != null) {
            AuthContext.set(existing);
        } else {
            AuthContext.clear();
        }
    }

    /** SQLBot 侧 datasource_id/chat_id/custom_model 为 int|str 联合类型：Number/字符串统一转 Long。 */
    static Long longArgOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        return Long.parseLong(s);
    }

    /** mcp_start 响应组装：access_token + chat_id + 会话详情（对齐 SQLBot mcp_start 响应键）。 */
    static Map<String, Object> mcpStartResponse(String resToken, Map<String, Object> chatInfo) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (resToken != null) {
            out.put("access_token", resToken);
        }
        if (chatInfo != null) {
            out.put("chat_id", chatInfo.get("id"));
            out.putAll(chatInfo);
        }
        return out;
    }

    /** 兼容 SQLBot mcp_start：可带 username/password（现场登录）、token（身份绑定）、oid、
     *  datasource_id、question；响应含 access_token 与 chat_id。 */
    @Tool(value = "mcp_start", description = "兼容 SQLBot mcp_start：开启问数会话，响应含 access_token 与 chat_id")
    public String mcp_start(@ToolArg(value = "username", required = false) String username,
                            @ToolArg(value = "password", required = false) String password,
                            @ToolArg(value = "token", required = false) String token,
                            @ToolArg(value = "oid", required = false) Long oid,
                            @ToolArg(value = "datasource_id", required = false) Object datasourceId,
                            @ToolArg(value = "question", required = false) String question) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        AuthContext previous = null;
        try {
            bindTokenStrict(token);
            String resToken = token != null && !token.trim().isEmpty() && StpUtil.getLoginIdByToken(token.trim()) != null
                    ? token.trim() : null;
            if (resToken == null && username != null && password != null) {
                // SQLBot mcp_start 支持现场账号口令登录并返回新令牌
                String login = access_token(username, password);
                Map<String, Object> parsed = mapper.readValue(login, Map.class);
                Object issued = parsed.get("token");
                if (issued == null) {
                    return login; // 登录失败：透传可读错误
                }
                resToken = String.valueOf(issued);
                StpUtil.setTokenValue(resToken);
            }
            if (resToken == null) {
                try {
                    resToken = StpUtil.getTokenValue();
                } catch (Exception ignored) {
                    // 无会话：保持 null（SQLBot 对应 res_token=None）
                }
            }
            if (oid != null && oid > 0) {
                requireWorkspace(currentUserService().currentUser(), oid);
                previous = enterWorkspace(oid);
            }
            String started = chat_start(longArgOrNull(datasourceId), question);
            Map<String, Object> info = mapper.readValue(started, Map.class);
            if (info.containsKey("error")) {
                return started;
            }
            return mapper.writeValueAsString(mcpStartResponse(resToken, info));
        } catch (Exception e) {
            return errorJson(mapper, "mcp_start", e);
        } finally {
            restoreWorkspace(previous);
        }
    }

    /** 兼容 SQLBot mcp_ws_list → workspace_list。 */
    @Tool(value = "mcp_ws_list", description = "兼容 SQLBot mcp_ws_list：列出当前用户可管理的工作空间")
    public String mcp_ws_list(@ToolArg(value = "token", required = false) String token) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        try {
            bindTokenStrict(token);
            return workspace_list();
        } catch (Exception e) {
            return errorJson(mapper, "mcp_ws_list", e);
        }
    }

    /** 兼容 SQLBot mcp_datasource_list（路由 /mcp_ds_list 的 operation_id）→ datasource_list。 */
    @Tool(value = "mcp_datasource_list", description = "兼容 SQLBot mcp_datasource_list：列出当前用户可用的数据源")
    public String mcp_datasource_list(@ToolArg(value = "token", required = false) String token,
                                      @ToolArg(value = "oid", required = false) Long oid) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        try {
            bindTokenStrict(token);
            // 对齐 SQLBot mcp_ds_list：显式 oid 时校验成员关系（非成员 400，而非静默忽略）
            requireWorkspace(currentUserService().currentUser(), oid == null ? 0L : oid);
            return filterDatasourceFields(mapper, datasource_list(oid));
        } catch (Exception e) {
            return errorJson(mapper, "mcp_datasource_list", e);
        }
    }

    /** 对齐 SQLBot mcp_ds_list：移除内部字段（embedding/table_relation/recommended_config/configuration）。 */
    private String filterDatasourceFields(ObjectMapper mapper, String json) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
        if (node != null && node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                if (item.isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) item;
                    obj.remove("embedding");
                    obj.remove("table_relation");
                    obj.remove("recommended_config");
                    obj.remove("configuration");
                }
            }
        }
        return mapper.writeValueAsString(node);
    }

    /** 兼容 SQLBot mcp_model_list → model_list：oid 指定工作空间（非成员拒绝），缺省用当前工作空间。 */
    @Tool(value = "mcp_model_list", description = "兼容 SQLBot mcp_model_list：列出当前工作空间可用的对话模型")
    public String mcp_model_list(@ToolArg(value = "oid", required = false) Long oid) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        try {
            if (oid == null || oid <= 0) {
                return model_list();
            }
            requireWorkspace(currentUserService().currentUser(), oid);
            AiModelService service = SpringUtils.getBean(AiModelService.class);
            return mapper.writeValueAsString(service.listByWs(oid));
        } catch (Exception e) {
            return errorJson(mapper, "mcp_model_list", e);
        }
    }

    /**
     * 兼容 SQLBot mcp_question → question_answer。
     *
     * <p>{@code lang} 用于本次调用的错误文案语言；{@code stream} 在本工具层忽略（MCP 协议单次返回），
     * HTTP 兼容层 {@code SqlbotMcpCompatController} 在 {@code stream=true} 时改走 SSE 链路。</p>
     */
    @Tool(value = "mcp_question", description = "兼容 SQLBot mcp_question：自然语言转SQL并只读执行")
    public String mcp_question(@ToolArg(value = "token", required = false) String token,
                               @ToolArg(value = "chat_id", required = false) Object chatId,
                               @ToolArg("question") String question,
                               @ToolArg(value = "datasource_id", required = false) Object datasourceId,
                               @ToolArg(value = "custom_model", required = false) Object customModel,
                               @ToolArg(value = "lang", required = false) String lang,
                               @ToolArg(value = "stream", required = false) Boolean stream,
                               @ToolArg(value = "return_img", required = false) Boolean returnImg) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        Locale previous = LocaleContextHolder.getLocale();
        try {
            bindTokenStrict(token);
            if (lang != null && !lang.trim().isEmpty()) {
                LocaleContextHolder.setLocale(Locale.forLanguageTag(lang.trim()));
            }
            Long dsId;
            Long chatIdL;
            Long modelL;
            try {
                dsId = longArgOrNull(datasourceId);
                chatIdL = longArgOrNull(chatId);
                modelL = longArgOrNull(customModel);
            } catch (NumberFormatException nfe) {
                Map<String, Object> err = new LinkedHashMap<String, Object>();
                err.put("error", I18n.msg("mcp.error.invalid.id"));
                return mapper.writeValueAsString(err);
            }
            return question_answer(question, dsId, chatIdL, modelL, returnImg);
        } catch (Exception e) {
            return errorJson(mapper, "mcp_question", e);
        } finally {
            if (previous != null) {
                LocaleContextHolder.setLocale(previous);
            } else {
                LocaleContextHolder.resetLocaleContext();
            }
        }
    }

    /**
     * 兼容 SQLBot {@code mcp_assistant}（Cordys CRM 虚拟用户链路）。
     *
     * <p>下发 {@code url} + {@code authorization} 时：按 SQLBot 的虚拟助手形态
     * （{@code configuration={"endpoint": url}} + {@code certificate=authorization}）从第三方
     * 数据接口拉取数据源列表，并在该数据源上完成本次问数（生成 SQL → 只读执行 → 返回
     * {@code question/sql/dialect/rows}）。宿主返回多个数据源时取第一个，也可用
     * {@code datasourceId} 指定宿主侧数据源 id。未下发 {@code url} 时退化为本地
     * {@link #assistant(String)} 问答（askdata 早期行为）。</p>
     *
     * <p>{@code stream} 接受但忽略：MCP 工具层单次返回 JSON（对齐 SQLBot {@code stream=false}
     * 的 JSON 形态）。问题文本优先取 {@code question}，同时兼容早期调用方的 {@code message}。</p>
     */
    @Tool(value = "mcp_assistant", description = "兼容 SQLBot mcp_assistant：第三方数据接口问数（question/url/authorization/stream/datasourceId）")
    public String mcp_assistant(@ToolArg(value = "question", required = false) String question,
                                @ToolArg(value = "url", required = false) String url,
                                @ToolArg(value = "authorization", required = false) String authorization,
                                @ToolArg(value = "stream", required = false) Boolean stream,
                                @ToolArg(value = "message", required = false) String message,
                                @ToolArg(value = "datasourceId", required = false) Long datasourceId)
            throws Exception {
        String ask = question != null && !question.trim().isEmpty() ? question : message;
        // 未下发第三方数据接口：本地助手问答（askdata 早期行为）
        if (url == null || url.trim().isEmpty()) {
            return assistant(ask);
        }
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        try {
            Map<String, AskdataNl2Sql> beans = SpringUtils.getBeansOfType(AskdataNl2Sql.class);
            if (beans.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<String, Object>();
                err.put("error", I18n.msg("mcp.error.question.answer.not.assembled"));
                return mapper.writeValueAsString(err);
            }
            DynamicDatasourceResolver resolver = SpringUtils.getBean(DynamicDatasourceResolver.class);
            Map<String, Object> hostEndpoint = resolver.hostEndpointFromMcp(url, authorization);
            DynamicDatasource dynamic = datasourceId != null
                    ? resolver.resolveFromHostList(DynamicDatasourceResolver.TYPE_ADVANCED_APP,
                    hostEndpoint, datasourceId)
                    : resolver.resolveFirstFromHostList(DynamicDatasourceResolver.TYPE_ADVANCED_APP,
                    hostEndpoint);
            Map<String, Object> result = beans.values().iterator().next().ask(ask, dynamic, null);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return errorJson(mapper, "mcp_assistant", e);
        }
    }
}
