package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5：chat/completions 请求体构造——extra_body/enable_thinking 归并嵌套、
 * Azure api_version/deployment_name 只进 URL 不进 body、其余参数整包透传。
 */
class ChatModelClientPayloadTest {

    private static List<ModelMessage> messages() {
        return List.of(new ModelMessage("system", "sys"), new ModelMessage("user", "hi"));
    }

    @Test
    void extraBodyMapIsNestedNotFlattened() {
        Map<String, Object> extra = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("temperature", 0.7);
        extra.put("extra_body", body);
        extra.put("temperature", 0.5);
        Map<String, Object> payload = ChatModelClient.buildPayload(
                "gpt-x", messages(), extra, false);
        assertTrue(payload.get("extra_body") instanceof Map, String.valueOf(payload));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) payload.get("extra_body");
        assertEquals(0.7, nested.get("temperature"));
        // 顶层同名键保持整包透传语义（extra_body 内的值只进嵌套对象）
        assertEquals(0.5, payload.get("temperature"));
        assertFalse(payload.containsKey("enable_thinking"));
    }

    @Test
    void standaloneEnableThinkingGoesIntoExtraBody() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("enable_thinking", false);
        Map<String, Object> payload = ChatModelClient.buildPayload("m", messages(), extra, false);
        assertFalse(payload.containsKey("enable_thinking"), String.valueOf(payload));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) payload.get("extra_body");
        assertEquals(false, nested.get("enable_thinking"));
    }

    @Test
    void azureParamsOnlyInUrlNotInBody() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("api_version", "2024-06-01");
        extra.put("deployment_name", "my-deploy");
        extra.put("temperature", 0.2);
        Map<String, Object> azureBody = ChatModelClient.buildPayload("gpt", messages(), extra, true);
        assertFalse(azureBody.containsKey("api_version"), String.valueOf(azureBody));
        assertFalse(azureBody.containsKey("deployment_name"), String.valueOf(azureBody));
        assertEquals(0.2, azureBody.get("temperature"));
        // 非 Azure 端点维持普通参数透传
        Map<String, Object> normalBody = ChatModelClient.buildPayload("gpt", messages(), extra, false);
        assertEquals("2024-06-01", normalBody.get("api_version"));
        assertEquals("my-deploy", normalBody.get("deployment_name"));
        // Azure URL 仍包含二者
        String url = ChatModelClient.chatCompletionsUrl("https://res.openai.azure.com", "gpt", extra);
        assertTrue(url.contains("/openai/deployments/my-deploy/chat/completions?api-version=2024-06-01"), url);
    }

    @Test
    void extraBodyJsonStringIsMerged() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("extra_body", "{\"enable_thinking\":true,\"top_k\":40}");
        Map<String, Object> payload = ChatModelClient.buildPayload("m", messages(), extra, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) payload.get("extra_body");
        assertEquals(true, nested.get("enable_thinking"));
        assertEquals(40, ((Number) nested.get("top_k")).intValue());
    }

    @Test
    void nullValuesSkippedAndStreamAlwaysPresent() throws Exception {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("presence_penalty", null);
        extra.put("top_p", 0.9);
        Map<String, Object> payload = ChatModelClient.buildPayload("m", messages(), extra, false);
        assertFalse(payload.containsKey("presence_penalty"));
        assertEquals(0.9, payload.get("top_p"));
        assertEquals(Boolean.TRUE, payload.get("stream"));
        // 可被 Jackson 序列化
        new ObjectMapper().writeValueAsString(payload);
    }
}
