package com.xiongx9527.askdata.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 框架 MCP server 真实端到端验证（回应"不要忽悠"）：启动 askdata，
 * 用真实 HTTP 客户端经 SSE 传输完成 MCP 握手与工具调用。
 *
 * <p>流程：GET /mcp/sse 建流并取消息端点 → POST initialize → POST tools/list
 * → POST tools/call(server_info) / tools/call(datasource_list)，从 SSE 流读回
 * JSON-RPC 响应并断言。测试用 permit-all 放行鉴权，模型/LLM 不参与。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "fw-plus.ai.mcp.enabled=true",
        "fw-plus.ai.mcp.transport=http-sse",
        "fw-plus.ai.mcp.endpoint=/mcp",
        "fw-plus.ai.mcp.auth.provider=permit-all"
})
class McpFrameworkE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private static HttpURLConnection sseConnection;

    @AfterAll
    static void tearDown() {
        if (sseConnection != null) {
            sseConnection.disconnect();
        }
    }

    @Test
    void mcpHandshakeAndToolCallOverHttpSse() throws Exception {
        String base = "http://localhost:" + port;
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        AtomicReference<String> messagesUrl = new AtomicReference<>();
        CountDownLatch endpointReady = new CountDownLatch(1);

        sseConnection = (HttpURLConnection) new URL(base + "/mcp/sse").openConnection();
        sseConnection.setRequestMethod("GET");
        sseConnection.setRequestProperty("Accept", "text/event-stream");
        sseConnection.setReadTimeout(60000);

        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(sseConnection.getInputStream(), StandardCharsets.UTF_8))) {
                String event = null;
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring("data:".length()).trim();
                        if ("endpoint".equals(event)) {
                            messagesUrl.set(data);
                            endpointReady.countDown();
                        } else if ("message".equals(event)) {
                            messages.offer(objectMapper.readTree(data));
                        }
                    } else if (line.isEmpty()) {
                        event = null;
                    }
                }
            } catch (Exception e) {
                // 流关闭时正常结束
            }
        });
        reader.setDaemon(true);
        reader.start();

        assertTrue(endpointReady.await(15, TimeUnit.SECONDS),
                "未在 15s 内收到 SSE endpoint 事件（MCP SSE 建流失败）");
        String msgUrl = base + messagesUrl.get();

        // 1) initialize
        post(msgUrl, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{"
                + "\"name\":\"askdata-e2e\",\"version\":\"1.0.0\"}}}");
        JsonNode init = awaitId(messages, 1);
        assertNotNull(init.get("result"), "initialize 无 result: " + init);
        assertTrue(init.get("result").has("serverInfo"), "initialize 缺 serverInfo: " + init);

        // 2) initialized 通知 + tools/list
        post(msgUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        post(msgUrl, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonNode tools = awaitId(messages, 2);
        JsonNode toolNames = tools.get("result").get("tools");
        assertNotNull(toolNames, "tools/list 缺 tools: " + tools);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode t : toolNames) {
            names.add(t.get("name").asText());
        }
        assertTrue(names.contains("server_info"), "tools/list 未暴露 server_info: " + tools);
        assertTrue(names.contains("datasource_list"), "tools/list 未暴露 datasource_list: " + tools);
        // MCP parity（对齐 SQLBot 7 operations）：新增工具须被注册
        assertTrue(names.contains("workspace_list"), "tools/list 未暴露 workspace_list: " + tools);
        assertTrue(names.contains("model_list"), "tools/list 未暴露 model_list: " + tools);
        assertTrue(names.contains("chat_start"), "tools/list 未暴露 chat_start: " + tools);
        assertTrue(names.contains("access_token"), "tools/list 未暴露 access_token: " + tools);
        assertTrue(names.contains("question_answer"), "tools/list 未暴露 question_answer: " + tools);
        assertTrue(names.contains("assistant"), "tools/list 未暴露 assistant: " + tools);

        // 3) tools/call server_info（确定性，无 DB/LLM 依赖）
        post(msgUrl, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"server_info\",\"arguments\":{}}}");
        JsonNode call = awaitId(messages, 3);
        JsonNode content = call.get("result").get("content");
        assertNotNull(content, "tools/call 无 content: " + call);
        String text = content.toString();
        assertTrue(text.contains("askdata"), "server_info 结果不含 askdata: " + call);

        // 4) tools/call datasource_list（DB 后端，证明工具真正触达 askdata 业务层）
        post(msgUrl, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"datasource_list\",\"arguments\":{}}}");
        JsonNode dsCall = awaitId(messages, 4);
        assertNotNull(dsCall.get("result"), "datasource_list 无 result: " + dsCall);

        // 5) tools/call access_token（错误口令 → 可读 error，验证工具可调用且不崩溃）
        post(msgUrl, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"access_token\",\"arguments\":{\"username\":\"__nobody__\",\"password\":\"x\"}}}");
        JsonNode tokenCall = awaitId(messages, 5);
        assertNotNull(tokenCall.get("result"), "access_token 无 result: " + tokenCall);
        assertTrue(tokenCall.get("result").get("content").toString().contains("error"),
                "access_token 错误口令应返回 error: " + tokenCall);
    }

    private static void post(String url, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 300) {
            fail("POST " + url + " 返回 HTTP " + code);
        }
    }

    private static JsonNode awaitId(BlockingQueue<JsonNode> queue, int id) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode node = queue.poll(2, TimeUnit.SECONDS);
            if (node != null && node.has("id") && node.get("id").asInt() == id) {
                return node;
            }
        }
        fail("15s 内未收到 id=" + id + " 的 MCP 响应");
        return null;
    }
}
