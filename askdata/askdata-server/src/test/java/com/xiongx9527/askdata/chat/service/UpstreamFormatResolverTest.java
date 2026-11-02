package com.xiongx9527.askdata.chat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A4.x：上游格式判定与 URL 规范化必须收敛在单一解析器，供模型列表、连通校验与运行期共用。
 */
class UpstreamFormatResolverTest {

    @Test
    void detectsAzureByHost() {
        assertEquals(UpstreamFormatResolver.AZURE,
                UpstreamFormatResolver.resolve("https://demo.openai.azure.com", UpstreamFormatResolver.AUTO));
        assertEquals(UpstreamFormatResolver.AZURE,
                UpstreamFormatResolver.resolve("https://demo.openai.azure.cn/", UpstreamFormatResolver.AUTO));
    }

    @Test
    void detectsAnthropicByHostOrPath() {
        assertEquals(UpstreamFormatResolver.ANTHROPIC,
                UpstreamFormatResolver.resolve("https://api.anthropic.com", UpstreamFormatResolver.AUTO));
        assertEquals(UpstreamFormatResolver.ANTHROPIC,
                UpstreamFormatResolver.resolve("https://gateway.example.com/anthropic", UpstreamFormatResolver.AUTO));
    }

    @Test
    void defaultsToChatCompletions() {
        assertEquals(UpstreamFormatResolver.CHAT_COMPLETIONS,
                UpstreamFormatResolver.resolve("https://api.deepseek.com", UpstreamFormatResolver.AUTO));
        assertEquals(UpstreamFormatResolver.CHAT_COMPLETIONS,
                UpstreamFormatResolver.resolve(null, UpstreamFormatResolver.AUTO));
    }

    @Test
    void configuredFormatWinsOverAutoDetection() {
        assertEquals(UpstreamFormatResolver.RESPONSES,
                UpstreamFormatResolver.resolve("https://api.openai.com", UpstreamFormatResolver.RESPONSES));
        assertEquals(UpstreamFormatResolver.CHAT_COMPLETIONS,
                UpstreamFormatResolver.resolve("https://api.anthropic.com", UpstreamFormatResolver.CHAT_COMPLETIONS));
    }

    @Test
    void normalizeAppendsV1WhenPathIsEmpty() {
        assertEquals("https://api.deepseek.com/v1",
                UpstreamFormatResolver.normalizeBase("https://api.deepseek.com/", false));
    }

    @Test
    void normalizeKeepsExistingVersionPath() {
        assertEquals("https://api.openai.com/v1",
                UpstreamFormatResolver.normalizeBase("https://api.openai.com/v1", false));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                UpstreamFormatResolver.normalizeBase("https://dashscope.aliyuncs.com/compatible-mode/v1/", false));
    }

    @Test
    void normalizeStripsChatCompletionsSuffix() {
        assertEquals("https://api.deepseek.com/v1",
                UpstreamFormatResolver.normalizeBase("https://api.deepseek.com/v1/chat/completions", false));
        assertEquals("https://gw.example.com/v1",
                UpstreamFormatResolver.normalizeBase("https://gw.example.com/v1/messages", false));
    }

    @Test
    void fullUrlIsReturnedAsIs() {
        String full = "https://gw.example.com/custom/endpoint?x=1";
        assertEquals(full, UpstreamFormatResolver.normalizeBase(full, true));
    }

    @Test
    void chatUrlFollowsFormat() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                UpstreamFormatResolver.chatUrl("https://api.deepseek.com", "deepseek-chat",
                        UpstreamFormatResolver.CHAT_COMPLETIONS, false));
        assertEquals("https://api.anthropic.com/v1/messages",
                UpstreamFormatResolver.chatUrl("https://api.anthropic.com", "claude-3",
                        UpstreamFormatResolver.ANTHROPIC, false));
        assertEquals("https://api.openai.com/v1/responses",
                UpstreamFormatResolver.chatUrl("https://api.openai.com", "gpt-4.1",
                        UpstreamFormatResolver.RESPONSES, false));
    }

    @Test
    void modelsAndEmbeddingsUrl() {
        assertEquals("https://api.deepseek.com/v1/models",
                UpstreamFormatResolver.modelsUrl("https://api.deepseek.com", false));
        assertEquals("https://api.deepseek.com/v1/embeddings",
                UpstreamFormatResolver.embeddingsUrl("https://api.deepseek.com", false));
    }

    @Test
    void embeddingUnsupportedForAnthropicAndResponses() {
        assertEquals(false, UpstreamFormatResolver.supportsEmbedding(UpstreamFormatResolver.ANTHROPIC));
        assertEquals(false, UpstreamFormatResolver.supportsEmbedding(UpstreamFormatResolver.RESPONSES));
        assertEquals(true, UpstreamFormatResolver.supportsEmbedding(UpstreamFormatResolver.CHAT_COMPLETIONS));
        assertEquals(true, UpstreamFormatResolver.supportsEmbedding(UpstreamFormatResolver.AZURE));
    }
}
