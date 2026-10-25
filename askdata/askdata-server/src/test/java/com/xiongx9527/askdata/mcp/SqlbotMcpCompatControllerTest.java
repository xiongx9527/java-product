package com.xiongx9527.askdata.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SQLBot MCP HTTP REST 兼容层（P-01）：端点映射、SQLBot body 形态解析、
 * 委托既有 MCP 工具实现、错误 400 {"detail"} 转换。
 */
class SqlbotMcpCompatControllerTest {

    private AskdataMcpTools tools;
    private ChatQuestionService questionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        tools = Mockito.mock(AskdataMcpTools.class);
        questionService = Mockito.mock(ChatQuestionService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new SqlbotMcpCompatController(new ObjectMapper(), () -> tools, () -> questionService)).build();
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
    void mcpStartMapsSnakeCaseBody() throws Exception {
        when(tools.mcp_start(isNull(), isNull(), eq("tk"), eq(3L), eq("9"), eq("q")))
                .thenReturn("{\"access_token\":\"tk\",\"chat_id\":7,\"id\":7}");
        mvc.perform(post("/api/v1/mcp/mcp_start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"oid\":3,\"datasource_id\":\"9\",\"question\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat_id").value(7));
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
    void mcpQuestionPassesIntStrUnionArgs() throws Exception {
        when(tools.mcp_question(eq("tk"), eq(5L), eq("q"), eq("7"), isNull(), isNull(), eq(false), eq(true)))
                .thenReturn("{\"sql\":\"SELECT 1\"}");
        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\",\"datasource_id\":\"7\",\"stream\":false,\"return_img\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value("SELECT 1"));
    }

    @Test
    void mcpQuestionStreamsSseWhenStreamTrue() throws Exception {
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.getWriter().write("data:{\"type\":\"question\",\"question\":\"q\"}\n\n");
            return null;
        }).when(questionService).streamQuestion(anyMap(), any(HttpServletResponse.class));

        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"chat_id\":5,\"question\":\"q\",\"stream\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("data:{\"type\":\"question\"")));

        verify(tools).bindTokenStrict("tk");
        verify(tools, never()).mcp_question(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mcpQuestionFallsBackToSyncWithoutChatId() throws Exception {
        when(tools.mcp_question(eq("tk"), isNull(), eq("q"), isNull(), isNull(), isNull(), eq(true), isNull()))
                .thenReturn("{\"sql\":\"SELECT 1\"}");
        mvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tk\",\"question\":\"q\",\"stream\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value("SELECT 1"));
        verify(questionService, never()).streamQuestion(anyMap(), any(HttpServletResponse.class));
    }

    @Test
    void mcpModelListAndAssistantDelegate() throws Exception {
        when(tools.mcp_model_list(2L)).thenReturn("[]");
        when(tools.mcp_assistant("hi", "http://x", "auth", false, null))
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
        verify(tools).mcp_model_list(2L);
        verify(tools).mcp_assistant("hi", "http://x", "auth", false, null);
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

