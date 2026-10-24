package com.xiongx9527.askdata.chat.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §2.3 protocol 分发：端点形态（openai / vllm / azure）URL 构造单测。
 * openai 与 vllm 同构（OpenAI 兼容 /v1/chat/completions）；Azure 专有域名走
 * deployment URL + api-version（对齐 SQLBot LLMFactory azure 分支）。
 */
class ChatModelClientEndpointTest {

    @Test
    void openaiBareDomainAppendsV1() {
        assertEquals("http://host:9000/v1/chat/completions",
                ChatModelClient.chatCompletionsUrl("http://host:9000", "gpt-4o", null));
    }

    @Test
    void vllmDomainAlreadyCarryingV1StaysIntact() {
        // vllm serve 的 apiDomain 惯例自带 /v1，不应重复拼接
        assertEquals("http://127.0.0.1:8000/v1/chat/completions",
                ChatModelClient.chatCompletionsUrl("http://127.0.0.1:8000/v1", "qwen", null));
    }

    @Test
    void azureDomainBuildsDeploymentUrlWithApiVersion() {
        Map<String, Object> params = new HashMap<>();
        params.put("api_version", "2024-02-15-preview");
        params.put("deployment_name", "gpt4o-deploy");
        assertEquals("https://res.openai.azure.com/openai/deployments/gpt4o-deploy/chat/completions?api-version=2024-02-15-preview",
                ChatModelClient.chatCompletionsUrl("https://res.openai.azure.com/", "gpt-4o", params));
    }

    @Test
    void azureWithoutParamsFallsBackToModelNameAndDefaultVersion() {
        assertEquals("https://res.openai.azure.cn/openai/deployments/gpt-4o/chat/completions?api-version=2024-02-15-preview",
                ChatModelClient.chatCompletionsUrl("https://res.openai.azure.cn", "gpt-4o", null));
    }

    @Test
    void azureDetectionOnlyMatchesAzureHosts() {
        assertTrue(ChatModelClient.isAzureEndpoint("https://res.openai.azure.com"));
        assertTrue(ChatModelClient.isAzureEndpoint("https://my.res.openai.azure.cn/"));
        assertFalse(ChatModelClient.isAzureEndpoint("http://127.0.0.1:11434"));
        assertFalse(ChatModelClient.isAzureEndpoint("https://api.openai.com/v1"));
    }
}
