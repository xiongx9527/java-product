package com.xiongx9527.askdata.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * NL2SQL 接线验证：stub {@link ChatModel} 生成合法 SELECT，经框架 SqlDatabaseContentRetriever
 * 生成 SQL 并在元数据库（H2）上只读执行，断言返回 sql 与结果行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:nl2sql;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "askdata.assistant.enabled=true"
})
class AskdataNl2SqlWiringTest {

    @MockBean
    private ChatModel stubChatModel;

    @MockBean
    private StreamingChatModel stubStreamingChatModel;

    @BeforeEach
    void stub() {
        ChatResponse ok = ChatResponse.builder().aiMessage(AiMessage.from("SELECT 1 AS ok")).build();
        when(stubChatModel.chat(any(ChatRequest.class))).thenReturn(ok);
        when(stubChatModel.chat(any(java.util.List.class))).thenReturn(ok);
    }

    @Autowired
    private AskdataNl2Sql nl2Sql;

    @Test
    void generatesSqlAndExecutes() throws Exception {
        Map<String, Object> result = nl2Sql.ask("随便问一个问题");
        assertNotNull(result.get("sql"), "未生成 SQL");
        assertEquals("SELECT 1 AS ok", result.get("sql"));
        Object rows = result.get("rows");
        assertNotNull(rows);
        List<?> rowList = (List<?>) rows;
        assertFalse(rowList.isEmpty(), "执行结果为空");
        Map<?, ?> first = (Map<?, ?>) rowList.get(0);
        assertFalse(first.isEmpty(), "结果行解析失败");
        // 值为 1（列名大小写由 H2 决定）
        Object value = first.values().iterator().next();
        assertEquals(1, ((Number) value).intValue());
    }

    @Test
    void customModelOverrideTakesPrecedence() throws Exception {
        ChatModel override = org.mockito.Mockito.mock(ChatModel.class);
        ChatResponse ok = ChatResponse.builder().aiMessage(AiMessage.from("SELECT 2 AS custom")).build();
        when(override.chat(any(ChatRequest.class))).thenReturn(ok);
        when(override.chat(any(java.util.List.class))).thenReturn(ok);

        Map<String, Object> result = nl2Sql.ask("随便问一个问题", (Long) null, override);

        assertEquals("SELECT 2 AS custom", result.get("sql"));
        Object value = ((Map<?, ?>) ((List<?>) result.get("rows")).get(0)).values().iterator().next();
        assertEquals(2, ((Number) value).intValue());
    }
}
