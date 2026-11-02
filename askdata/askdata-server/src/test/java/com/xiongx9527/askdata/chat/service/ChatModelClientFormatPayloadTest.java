package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.chat.service.ChatModelClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5.x：Anthropic Messages 与 Responses 的请求体结构必须与各自上游契约一致。
 */
class ChatModelClientFormatPayloadTest {

    private static List<ChatModelClient.ModelMessage> messages() {
        List<ChatModelClient.ModelMessage> list = new ArrayList<>();
        list.add(new ChatModelClient.ModelMessage("system", "你是 SQL 助手"));
        list.add(new ChatModelClient.ModelMessage("user", "问题1"));
        list.add(new ChatModelClient.ModelMessage("assistant", "答案1"));
        list.add(new ChatModelClient.ModelMessage("user", "问题2"));
        return list;
    }

    @Test
    void anthropicLiftsSystemAndKeepsMaxTokens() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("temperature", 0.2);
        params.put("stream_options", "ignored");
        Map<String, Object> payload = ChatModelClient.buildAnthropicPayload("claude-3", messages(), params);

        assertEquals("claude-3", payload.get("model"));
        assertEquals("你是 SQL 助手", payload.get("system"));
        assertEquals(4096, payload.get("max_tokens"));
        assertEquals(Boolean.TRUE, payload.get("stream"));
        assertEquals(0.2, payload.get("temperature"));
        assertFalse(payload.containsKey("stream_options"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> msgs = (List<Map<String, String>>) payload.get("messages");
        assertEquals(3, msgs.size());
        assertEquals("user", msgs.get(0).get("role"));
        assertEquals("assistant", msgs.get(1).get("role"));
        assertEquals("user", msgs.get(2).get("role"));
    }

    @Test
    void anthropicMaxTokensCanBeOverridden() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("max_tokens", 1024);
        Map<String, Object> payload = ChatModelClient.buildAnthropicPayload("claude-3", messages(), params);
        assertEquals(1024, payload.get("max_tokens"));
    }

    @Test
    void responsesUsesInputArray() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("stream_options", "ignored");
        Map<String, Object> payload = ChatModelClient.buildResponsesPayload("gpt-4.1", messages(), params);

        assertEquals("gpt-4.1", payload.get("model"));
        assertEquals(Boolean.TRUE, payload.get("stream"));
        assertFalse(payload.containsKey("stream_options"));
        assertNull(payload.get("messages"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) payload.get("input");
        assertEquals(4, input.size());
        assertEquals("user", input.get(0).get("role"));
        assertEquals("你是 SQL 助手", input.get(0).get("content"));
        assertTrue(input.get(2).get("role").equals("assistant"));
    }
}
