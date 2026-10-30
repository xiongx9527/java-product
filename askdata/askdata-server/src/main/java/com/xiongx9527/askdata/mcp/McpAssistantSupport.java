package com.xiongx9527.askdata.mcp;

import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.chat.service.ChatService;
import com.xiongx9527.askdata.chat.service.ChatModelClient.FrameWriter;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.embedded.DynamicDatasource;
import com.xiongx9527.askdata.embedded.DynamicDatasourceResolver;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLBot {@code mcp_assistant} 共用链路（A4/D3/D4）：
 * 以合成身份 {@code id=-1 / account=sqlbot-mcp-assistant / oid=1}（不写 {@code sys_user}）
 * 创建会话与真实记录，按 {@code url}+{@code authorization} 拉取宿主数据源，
 * 并以 {@code finish_step=QUERY_DATA} 执行问数（只出 SQL 与数据，不生成图表/图片）。
 *
 * <p>控制器负责 SSE / JSON 分流；MCP 工具层固定走 {@link #ask}（单次 JSON）。</p>
 */
@Component
public class McpAssistantSupport {

    public static final long SYNTHETIC_USER_ID = -1L;

    public static final String SYNTHETIC_ACCOUNT = CurrentUserService.MCP_ASSISTANT_ACCOUNT;

    public static final long SYNTHETIC_WORKSPACE_ID = 1L;

    private final ChatQuestionService chatQuestionService;

    private final ChatService chatService;

    private final DynamicDatasourceResolver datasourceResolver;

    private final MessageResolver messageResolver;

    private final ObjectMapper objectMapper;

    public McpAssistantSupport(ChatQuestionService chatQuestionService, ChatService chatService,
                               DynamicDatasourceResolver datasourceResolver, MessageResolver messageResolver,
                               ObjectMapper objectMapper) {
        this.chatQuestionService = chatQuestionService;
        this.chatService = chatService;
        this.datasourceResolver = datasourceResolver;
        this.messageResolver = messageResolver;
        this.objectMapper = objectMapper;
    }

    /** 非流式 mcp_assistant：返回 {@code {success,record_id,sql,data}}（QUERY_DATA 语义）。 */
    public Map<String, Object> ask(String question, String url, String authorization, Long datasourceId)
            throws IOException {
        AuthContext previous = AuthContext.current();
        try {
            Map<String, Object> body = prepare(question, url, authorization, datasourceId);
            return chatQuestionService.ask(body);
        } finally {
            restore(previous);
        }
    }

    /** 流式 mcp_assistant：校验/建会话/宿主数据源解析全部在打开 SSE 之前完成。 */
    public void stream(String question, String url, String authorization, Long datasourceId,
                       HttpServletResponse response) throws IOException {
        AuthContext previous = AuthContext.current();
        try {
            Map<String, Object> body = prepare(question, url, authorization, datasourceId);
            try (FrameWriter writer = new FrameWriter(response, objectMapper)) {
                chatQuestionService.streamQuestion(body, writer);
            }
        } finally {
            restore(previous);
        }
    }

    /** 校验参数、拉取宿主数据源并创建合成助手会话；调用方负责恢复 {@link AuthContext}。 */
    private Map<String, Object> prepare(String question, String url, String authorization, Long datasourceId) {
        requireArg("question", question);
        requireArg("url", url);
        requireArg("authorization", authorization);

        Map<String, Object> hostEndpoint = datasourceResolver.hostEndpointFromMcp(url, authorization);
        DynamicDatasource dynamic = datasourceId != null
                ? datasourceResolver.resolveFromHostList(DynamicDatasourceResolver.TYPE_ADVANCED_APP,
                hostEndpoint, datasourceId)
                : datasourceResolver.resolveFirstFromHostList(DynamicDatasourceResolver.TYPE_ADVANCED_APP,
                hostEndpoint);

        enterSyntheticUser();
        Map<String, Object> chat = chatService.startForMcpAssistant(question);
        long chatId = Rows.lng(chat, "id", 0L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("question", question);
        body.put("dynamic_datasource", dynamic);
        body.put("finish_step", "QUERY_DATA");
        body.put("return_img", false);
        body.put("first_chat", false);
        return body;
    }

    private void requireArg(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", name)));
        }
    }

    /** 请求级合成用户上下文：不落 sys_user，仅承载本次问数身份。 */
    private void enterSyntheticUser() {
        AuthContext existing = AuthContext.current();
        AuthContext temp = new AuthContext();
        if (existing != null) {
            temp.setChannel(existing.getChannel());
            temp.setAssistant(existing.getAssistant());
            temp.setRequestOrigin(existing.getRequestOrigin());
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", SYNTHETIC_USER_ID);
        user.put("account", SYNTHETIC_ACCOUNT);
        user.put("name", SYNTHETIC_ACCOUNT);
        user.put("email", SYNTHETIC_ACCOUNT + "@sqlbot.com");
        user.put("oid", SYNTHETIC_WORKSPACE_ID);
        user.put("status", 1);
        user.put("origin", 0);
        user.put("language", "zh-CN");
        temp.setCurrentUser(user);
        AuthContext.set(temp);
    }

    private void restore(AuthContext previous) {
        if (previous != null) {
            AuthContext.set(previous);
        } else {
            AuthContext.clear();
        }
    }
}
