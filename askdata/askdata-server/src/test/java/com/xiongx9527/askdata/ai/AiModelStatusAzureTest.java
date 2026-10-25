package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.system.service.AiModelService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A23：AI 模型连通检查支持 Azure 端点与自定义参数（config_list）。
 */
class AiModelStatusAzureTest {

    private static Map<String, Object> configList(Object... kv) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", kv[i]);
            item.put("val", kv[i + 1]);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config_list", items);
        return body;
    }

    @Test
    void configListIsMergedIntoModelParams() {
        Map<String, Object> params = AiModelService.modelParams(configList(
                "temperature", 0.3, "top_p", 0.9, "max_tokens", 2048, "vendor_flag", true));
        assertEquals(0.3D, ((Number) params.get("temperature")).doubleValue());
        assertEquals(0.9D, ((Number) params.get("top_p")).doubleValue());
        assertEquals(2048, ((Number) params.get("max_tokens")).intValue());
        assertEquals(Boolean.TRUE, params.get("vendor_flag"));
    }

    @Test
    void incompleteConfigItemsAreIgnored() {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        Map<String, Object> noVal = new LinkedHashMap<>();
        noVal.put("key", "temperature");
        items.add(noVal);
        Map<String, Object> noKey = new LinkedHashMap<>();
        noKey.put("val", 0.5);
        items.add(noKey);
        Map<String, Object> nullVal = new LinkedHashMap<>();
        nullVal.put("key", "top_p");
        nullVal.put("val", null);
        items.add(nullVal);
        body.put("config_list", items);
        assertTrue(AiModelService.modelParams(body).isEmpty());
    }

    @Test
    void azureEndpointBuildsDeploymentUrlAndDropsUrlOnlyParams() {
        Map<String, Object> params = AiModelService.modelParams(configList(
                "temperature", 0.2, "api_version", "2024-06-01", "deployment_name", "my-deploy"));

        String url = com.xiongx9527.askdata.chat.service.ChatModelClient.chatCompletionsUrl(
                "https://myres.openai.azure.com", "gpt-4o", params);
        assertEquals(
                "https://myres.openai.azure.com/openai/deployments/my-deploy/chat/completions?api-version=2024-06-01",
                url);
        assertTrue(url.startsWith("https://myres.openai.azure.com/openai/deployments/my-deploy/"), url);
        assertTrue(url.contains("api-version=2024-06-01"), url);

        // api_version / deployment_name 只参与 URL，Azure 请求体中剔除
        Map<String, Object> payload = com.xiongx9527.askdata.chat.service.ChatModelClient.buildPayload(
                "gpt-4o",
                List.of(new com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage("user", "1+1=?")),
                params, true);
        assertFalse(payload.containsKey("api_version"));
        assertFalse(payload.containsKey("deployment_name"));
        assertEquals(0.2D, ((Number) payload.get("temperature")).doubleValue());
        // Azure 同样带流式 usage（A12）
        assertEquals(Boolean.TRUE, ((Map<?, ?>) payload.get("stream_options")).get("include_usage"));
    }

    @Test
    void azureDetectionCoversAzureChinaDomain() {
        assertTrue(com.xiongx9527.askdata.chat.service.ChatModelClient
                .isAzureEndpoint("https://myres.openai.azure.cn"));
        assertTrue(com.xiongx9527.askdata.chat.service.ChatModelClient
                .isAzureEndpoint("https://myres.openai.azure.com/"));
        assertFalse(com.xiongx9527.askdata.chat.service.ChatModelClient
                .isAzureEndpoint("https://api.openai.com/v1"));
    }

    @Test
    void customParamsReachNonAzurePayload() {
        Map<String, Object> params = AiModelService.modelParams(configList(
                "temperature", 0.7, "top_p", 0.95));
        Map<String, Object> payload = com.xiongx9527.askdata.chat.service.ChatModelClient.buildPayload(
                "qwen-max",
                Arrays.asList(new com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage("user", "hi")),
                params, false);
        assertEquals(0.7D, ((Number) payload.get("temperature")).doubleValue());
        assertEquals(0.95D, ((Number) payload.get("top_p")).doubleValue());
    }
}
