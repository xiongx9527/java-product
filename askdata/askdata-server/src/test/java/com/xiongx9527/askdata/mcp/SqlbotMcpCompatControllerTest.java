package com.xiongx9527.askdata.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.common.debounce.DebounceProperties;
import com.xiongx9527.askdata.common.i18n.TestMessageResolver;
import com.xiongx9527.askdata.common.web.ApiExceptionHandler;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.ForbiddenException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SQLBot MCP HTTP REST 兼容层：端点映射、SQLBot body 形态解析、委托既有 MCP 工具实现、
 * 错误 400 {"detail"} / 403 {"detail"} / 非流式问数 500 {success:false,message} 转换，
 * 以及 stream 缺省 true、mcp_assistant 流式分流。
 */
class SqlbotMcpCompatControllerTest {

    private AskdataMcpTools tools;
    private ChatQuestionService questionService;
    private McpAssistantSupport assistantSupport;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        tools = Mockito.mock(AskdataMcpTools.class);
        questionService = Mockito.mock(ChatQuestionService.class);
        assistantSupport = Mockito.mock(McpAssistantSupport.class);
        mvc = MockMvcBuilders.standaloneSetup(new SqlbotMcpCompatController(
                        new ObjectMapper(), () -> tools, () -> questionService, () -> assistantSupport))
                .setControllerAdvice(new ApiExceptionHandler(
                        TestMessageResolver.create(), new DebounceProperties()))
                .build();
    }

    @Test
    void accessTokenDelegatesAndReturnsSqlbotKey() throws Exception {
        when(tools.access_token("u", "p")).thenReturn(
                "{\"access_token\":\"t1\",\"token\":\"t1\",\"tokenName\":\"X\"}");
        mvc.perform(post("/api/v1/mcp/access_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("t1"));
    }

    @Test
    void toolErrorMappedTo400Detail() throws Exception {
        when(tools.access_token("u", "bad")).thenReturn("{\"error\":\"账号或密码错误\"}");
        mvc.perform(post("/api/v1/mcp/access_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("账号或密码错误"));
    }

    @Test
    void mcpStartMapsSnakeCaseBodyAndKeepsTwoKeys() throws Exception {
        when(tools.mcp_start(isNull(), isNull(), eq("tk"), eq(3L), eq("9"), eq("q")))
                .thenReturn("{\"access_token\":\"tk\",\"chat_id\":7}");
        mvc.perform(post("/api/v1/mcp/mcp_start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"oid\":3,\"datasource_id\":\"9\",\"question\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("tk"))
                .andExpect(jsonPath("$.chat_id").value(7))
                .andExpect(jsonPath("$.brief").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void mcpDsListRouteAndOperationIdAliasBothReachable() throws Exception {
        when(tools.mcp_datasource_list(eq("tk"), isNull())).thenReturn("[]");
        mvc.perform(post("/api/v1/mcp/mcp_ds_list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(post("/api/v1/mcp/mcp_datasource_list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\"}"))
                .andExpect(status().isOk());
        verify(tools, Mockito.times(2)).mcp_datasource_list("tk", null);
    }

    @Test
    void mcpQuestionNonStreamPassesIntStrUnionArgs() throws Exception {
        when(tools.mcp_question(eq("tk"), eq(5L), eq("q"), eq("7"), isNull(), isNull(), eq(false), eq(true)))
                .thenReturn("{\"success\":true,\"record_id\":1,\"sql\":\"SELECT 1\"}");
        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\",\"datasource_id\":\"7\",\"stream\":false,\"return_img\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sql").value("SELECT 1"));
    }

    @Test
    void mcpQuestionStreamsByDefaultWhenChatIdPresent() throws Exception {
        when(tools.resolveChatForQuestion(5L, "q")).thenReturn(5L);
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.getWriter().write("data:{\"type\":\"question\",\"question\":\"q\"}\n\n");
            return null;
        }).when(questionService).streamQuestion(anyMap(), any(HttpServletResponse.class));

        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("data:{\"type\":\"question\"")));

        verify(tools).bindTokenStrict("tk");
        verify(tools, never()).mcp_question(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mcpQuestionWithoutChatIdCreatesChatAndStreams() throws Exception {
        when(tools.resolveChatForQuestion(null, "q")).thenReturn(11L);
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.getWriter().write("data:{\"type\":\"id\",\"id\":11}\n\n");
            return null;
        }).when(questionService).streamQuestion(anyMap(), any(HttpServletResponse.class));

        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"question\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(questionService).streamQuestion(captor.capture(), any(HttpServletResponse.class));
        assertEquals(11L, captor.getValue().get("chat_id"));
        verify(tools, never()).mcp_question(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mcpQuestionCustomModelStillStreams() throws Exception {
        when(tools.resolveChatForQuestion(5L, "q")).thenReturn(5L);
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.getWriter().write("data:{\"type\":\"finish\"}\n\n");
            return null;
        }).when(questionService).streamQuestion(anyMap(), any(HttpServletResponse.class));

        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\",\"custom_model\":9}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(questionService).streamQuestion(captor.capture(), any(HttpServletResponse.class));
        assertEquals(9L, captor.getValue().get("custom_model"));
        verify(tools, never()).mcp_question(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mcpQuestionStreamRuntimeErrorStaysSse() throws Exception {
        when(tools.resolveChatForQuestion(5L, "q")).thenReturn(5L);
        doThrow(new BadRequestException("chat.not.found"))
                .when(questionService).streamQuestion(anyMap(), any(HttpServletResponse.class));

        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("\"type\":\"error\"")))
                .andExpect(content().string(containsString("chat.not.found")))
                .andExpect(content().string(containsString("\"type\":\"finish\"")));
    }

    @Test
    void mcpQuestionNonStreamFailureReturns500WithMessage() throws Exception {
        when(tools.mcp_question(eq("tk"), eq(5L), eq("q"), isNull(), isNull(), isNull(), eq(false), isNull()))
                .thenReturn("{\"success\":false,\"message\":\"boom\",\"record_id\":3}");
        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\",\"stream\":false}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("boom"))
                .andExpect(jsonPath("$.record_id").value(3));
    }

    @Test
    void mcpQuestionInvalidDatasourceIdReturns400Detail() throws Exception {
        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\",\"datasource_id\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());

        verify(tools, never()).bindTokenStrict(any());
        verify(questionService, never()).streamQuestion(anyMap(), any(HttpServletResponse.class));
    }

    /**
     * 风险修复：SQLBot 的 datasource_id/chat_id/custom_model 是 int|str 联合类型，
     * JSON 对象/数组不属于联合类型，必须返回 400 {"detail"}，不能 asText() 退化为空串后
     * 被当作「未传」静默走默认链路（流式与非流式两条路径都要拦住）。
     */
    @Test
    void mcpQuestionRejectsContainerIdsInsteadOfTreatingThemAsMissing() throws Exception {
        String[] bodies = {
                "{\"token\":\"tk\",\"chat_id\":{\"id\":5},\"question\":\"q\"}",
                "{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\",\"datasource_id\":[7]}",
                "{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\",\"custom_model\":{\"id\":9}}",
                "{\"token\":\"tk\",\"question\":\"q\",\"datasource_id\":{\"id\":7},\"stream\":false}",
                "{\"token\":\"tk\",\"chat_id\":[5],\"question\":\"q\",\"stream\":false}"
        };
        for (String body : bodies) {
            mvc.perform(post("/api/v1/mcp/mcp_question")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").exists());
        }

        verify(tools, never()).bindTokenStrict(any());
        verify(tools, never()).resolveChatForQuestion(any(), any());
        verify(tools, never()).mcp_question(any(), any(), any(), any(), any(), any(), any(), any());
        verify(questionService, never()).streamQuestion(anyMap(), any(HttpServletResponse.class));
    }

    @Test
    void mcpStartRejectsContainerOidAndDatasourceId() throws Exception {
        mvc.perform(post("/api/v1/mcp/mcp_start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"oid\":{\"id\":1},\"question\":\"q\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
        mvc.perform(post("/api/v1/mcp/mcp_start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"oid\":1,\"datasource_id\":[9],\"question\":\"q\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());

        verify(tools, never()).mcp_start(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mcpAssistantDefaultsToStream() throws Exception {
        mvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"url\":\"http://x\",\"authorization\":\"auth\"}"))
                .andExpect(status().isOk());

        verify(assistantSupport).stream(eq("hi"), eq("http://x"), eq("auth"), isNull(),
                any(HttpServletResponse.class));
        verify(tools, never()).mcp_assistant(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mcpAssistantNonStreamDelegatesToTool() throws Exception {
        when(tools.mcp_assistant("hi", "http://x", "auth", false, null, null))
                .thenReturn("{\"success\":true,\"sql\":\"SELECT 1\"}");
        mvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"url\":\"http://x\",\"authorization\":\"auth\",\"stream\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sql").value("SELECT 1"));

        verify(assistantSupport, never()).stream(any(), any(), any(), any(), any());
    }

    @Test
    void mcpAssistantToolErrorMappedTo400Detail() throws Exception {
        when(tools.mcp_assistant("hi", null, null, false, null, null))
                .thenReturn("{\"error\":\"缺失url参数\"}");
        mvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"stream\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("缺失url参数"));
    }

    @Test
    void mcpAssistantMissingUrlReturns400Detail() throws Exception {
        doThrow(new BadRequestException("missing url")).when(assistantSupport)
                .stream(eq("hi"), isNull(), eq("auth"), isNull(), any(HttpServletResponse.class));

        mvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"authorization\":\"auth\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("missing url"));

        verify(tools, never()).mcp_assistant(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mcpQuestionInvalidTokenReturns403Detail() throws Exception {
        doThrow(new ForbiddenException("invalid token")).when(tools).bindTokenStrict("bad");

        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bad\",\"chat_id\":5,\"question\":\"q\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("invalid token"));

        verify(questionService, never()).streamQuestion(anyMap(), any(HttpServletResponse.class));
    }

    @Test
    void mcpModelListAndAssistantDelegate() throws Exception {
        when(tools.mcp_model_list(null, 2L)).thenReturn("[]");
        when(tools.mcp_assistant("hi", "http://x", "auth", false, null, null))
                .thenReturn("{\"answer\":\"ok\"}");
        mvc.perform(post("/api/v1/mcp/mcp_model_list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oid\":2}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"url\":\"http://x\",\"authorization\":\"auth\",\"stream\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("ok"));
        verify(tools).mcp_model_list(null, 2L);
        verify(tools).mcp_assistant("hi", "http://x", "auth", false, null, null);
    }

    @Test
    void mcpModelListPassesBodyTokenForStrictBinding() throws Exception {
        when(tools.mcp_model_list("tk", 2L)).thenReturn("[]");

        mvc.perform(post("/api/v1/mcp/mcp_model_list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"oid\":2}"))
                .andExpect(status().isOk());

        verify(tools).mcp_model_list("tk", 2L);
    }

    @Test
    void wsListAcceptsBodyToken() throws Exception {
        when(tools.mcp_ws_list("tk")).thenReturn("[]");
        mvc.perform(post("/api/v1/mcp/mcp_ws_list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
