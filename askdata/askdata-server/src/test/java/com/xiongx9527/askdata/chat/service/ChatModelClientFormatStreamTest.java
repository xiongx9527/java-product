package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5.3–A5.6：三种上游格式的端点、请求头、请求体与流式解析。
 *
 * <p>每个用例都通过本地 stub 上游真实发一次请求，断言「实际命中的路径与请求头」，
 * 避免只测纯函数而漏掉接线问题。</p>
 */
class ChatModelClientFormatStreamTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatModelClient client;

    private HttpServer server;

    private final List<String> hitPaths = new ArrayList<>();

    private final List<String> authHeaders = new ArrayList<>();

    private final List<String> versionHeaders = new ArrayList<>();

    private final List<String> bodies = new ArrayList<>();

    private String sseBody;

    @BeforeEach
    void setUp() throws Exception {
        client = new ChatModelClient(objectMapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) {
        try {
            hitPaths.add(exchange.getRequestURI().getPath());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
            authHeaders.add(auth != null ? auth : apiKey);
            versionHeaders.add(exchange.getRequestHeaders().getFirst("anthropic-version"));
            try (InputStream in = exchange.getRequestBody()) {
                bodies.add(new String(readAll(in), StandardCharsets.UTF_8));
            }
            byte[] bytes = (sseBody == null ? "" : sseBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            exchange.close();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /** 记录 token、usage 与错误。 */
    private static final class Capture implements ChatModelClient.TokenHandler {

        final StringBuilder text = new StringBuilder();

        final StringBuilder errors = new StringBuilder();

        long input;

        long output;

        boolean completed;

        @Override
        public void onToken(String content, String reasoningContent) {
            if (content != null) {
                text.append(content);
            }
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(String message) {
            errors.append(message);
        }

        @Override
        public void onUsageDetail(long inputTokens, long outputTokens, long total) {
            input += inputTokens;
            output += outputTokens;
        }
    }

    private static List<ChatModelClient.ModelMessage> messages() {
        List<ChatModelClient.ModelMessage> list = new ArrayList<>();
        list.add(new ChatModelClient.ModelMessage("system", "你是 SQL 助手"));
        list.add(new ChatModelClient.ModelMessage("user", "问题"));
        return list;
    }

    // ------------------------------------------------------------------
    // A5.2 / A5.3 Anthropic Messages
    // ------------------------------------------------------------------

    @Test
    void anthropicHitsMessagesEndpointWithDedicatedHeaders() throws Exception {
        sseBody = "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n";
        Capture capture = new Capture();
        client.streamCompletion(base(), "sk-ant", "claude-3",
                messages(), null, 1, UpstreamFormatResolver.ANTHROPIC, capture);

        assertEquals(1, hitPaths.size());
        assertEquals("/v1/messages", hitPaths.get(0));
        assertEquals("sk-ant", authHeaders.get(0), "Anthropic 使用 x-api-key 而非 Bearer");
        assertEquals("2023-06-01", versionHeaders.get(0));

        JsonNode body = objectMapper.readTree(bodies.get(0));
        assertEquals("你是 SQL 助手", body.get("system").asText());
        assertEquals(4096, body.get("max_tokens").asInt());
        assertEquals(1, body.get("messages").size());
        assertEquals("user", body.get("messages").get(0).get("role").asText());
        assertFalse(body.has("stream_options"));
    }

    @Test
    void anthropicSseConcatenatesTextAndReadsUsage() {
        sseBody = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12}}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"你\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"好\"}}\n\n"
                + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":7}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        Capture capture = new Capture();
        client.streamCompletion(base(), "sk-ant", "claude-3",
                messages(), null, 1, UpstreamFormatResolver.ANTHROPIC, capture);

        assertEquals("你好", capture.text.toString());
        assertEquals(12L, capture.input);
        assertEquals(7L, capture.output);
        assertTrue(capture.completed);
        assertEquals("", capture.errors.toString());
    }

    // ------------------------------------------------------------------
    // A5.4 Responses
    // ------------------------------------------------------------------

    @Test
    void responsesHitsResponsesEndpointWithInputArray() throws Exception {
        sseBody = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n";
        Capture capture = new Capture();
        client.streamCompletion(base(), "sk-oai", "gpt-4.1",
                messages(), null, 1, UpstreamFormatResolver.RESPONSES, capture);

        assertEquals("/v1/responses", hitPaths.get(0));
        assertEquals("Bearer sk-oai", authHeaders.get(0));

        JsonNode body = objectMapper.readTree(bodies.get(0));
        assertTrue(body.get("input").isArray());
        assertEquals(2, body.get("input").size());
        assertFalse(body.has("messages"));
    }

    @Test
    void responsesSseConcatenatesDeltaAndReadsCompletedUsage() {
        sseBody = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"1+1\"}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"=2\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":9,\"output_tokens\":3}}}\n\n";
        Capture capture = new Capture();
        client.streamCompletion(base(), "sk-oai", "gpt-4.1",
                messages(), null, 1, UpstreamFormatResolver.RESPONSES, capture);

        assertEquals("1+1=2", capture.text.toString());
        assertEquals(9L, capture.input);
        assertEquals(3L, capture.output);
        assertTrue(capture.completed);
    }

    @Test
    void responsesFailedEventReportsError() {
        sseBody = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"boom\"}}}\n\n";
        Capture capture = new Capture();
        client.streamCompletion(base(), "sk-oai", "gpt-4.1",
                messages(), null, 1, UpstreamFormatResolver.RESPONSES, capture);

        assertFalse(capture.completed);
        assertTrue(capture.errors.toString().contains("boom"));
    }

    // ------------------------------------------------------------------
    // A5.5 Azure OpenAI
    // ------------------------------------------------------------------

    @Test
    void azureUsesDeploymentPathAndApiKeyHeader() {
        // Azure 域名无法离线直连，这里断言「会命中哪条路径、用哪个头、请求体剔除哪些键」
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("api_version", "2024-06-01");
        params.put("deployment_name", "my-deploy");
        String url = ChatModelClient.chatCompletionsUrl("https://res.openai.azure.com", "gpt-4o", params);
        assertEquals("https://res.openai.azure.com/openai/deployments/my-deploy/chat/completions?api-version=2024-06-01",
                url);
        assertTrue(ChatModelClient.isAzureEndpoint("https://res.openai.azure.com"),
                "Azure 判定为真时客户端使用 api-key 头而非 Bearer");

        JsonNode body = objectMapper.valueToTree(
                ChatModelClient.buildPayload("gpt-4o", messages(), params, true));
        assertFalse(body.has("api_version"), "api_version 只参与 URL 构造");
        assertFalse(body.has("deployment_name"), "deployment_name 只参与 URL 构造");
    }

    // ------------------------------------------------------------------
    // A5.6 Chat Completions 回归
    // ------------------------------------------------------------------

    @Test
    void chatCompletionsKeepsBearerAndFlatParams() throws Exception {
        sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("temperature", 0.2);
        Capture capture = new Capture();
        client.streamCompletion(base(), "sk-plain", "deepseek-chat",
                messages(), params, 1, UpstreamFormatResolver.CHAT_COMPLETIONS, capture);

        assertEquals("/v1/chat/completions", hitPaths.get(0));
        assertEquals("Bearer sk-plain", authHeaders.get(0));
        JsonNode body = objectMapper.readTree(bodies.get(0));
        assertEquals(0.2, body.get("temperature").asDouble(), 1e-9);
        assertEquals("ok", capture.text.toString());
        assertNull(versionHeaders.get(0));
        assertNotNull(capture);
    }

    @Test
    void autoDetectSendsAnthropicDomainToMessagesEndpoint() {
        // 未显式指定格式时，域名/路径决定分支：路径含 /anthropic 必须走 /messages
        sseBody = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"ok\"}}\n\n";
        Capture capture = new Capture();
        client.streamCompletion(base() + "/anthropic", "sk-ant", "claude-3",
                messages(), null, 1, UpstreamFormatResolver.AUTO, capture);
        assertEquals("/anthropic/messages", hitPaths.get(0));
        assertEquals("ok", capture.text.toString());
    }
}
