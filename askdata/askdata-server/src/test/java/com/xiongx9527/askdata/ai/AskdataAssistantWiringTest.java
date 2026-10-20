package com.xiongx9527.askdata.ai;

import com.xiongx9527.fw.plus.ai.autoconfigure.langchain4j.support.assistant.SimpleAssistant;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 框架 Assistant 接线验证：开启 askdata.assistant.enabled 并以 {@link MockBean} 提供模型（覆盖真实模型 Bean），
 * 触发 {@link AskdataAssistantConfig} 装配 {@link SimpleAssistant}（经框架 AssistantBuilder），并验证 chat() 返回模型回答。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:asst_wire;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "askdata.assistant.enabled=true"
})
class AskdataAssistantWiringTest {

    @MockBean
    private ChatModel stubChatModel;

    @MockBean
    private StreamingChatModel stubStreamingChatModel;

    @BeforeEach
    void stub() {
        when(stubChatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("stub-assistant-ok")).build());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SimpleAssistant assistant;

    @Test
    void assistantIsWiredAndAnswers() {
        assertNotNull(assistant, "SimpleAssistant 未装配（AssistantBuilder + stub ChatModel 应触发）");
        String answer = assistant.chat("wiring-test", null, "你好");
        assertNotNull(answer);
        assertTrue(answer.contains("stub-assistant-ok"), "Assistant 未返回 stub 模型回答: " + answer);
    }
}
