package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * embedding 大响应体回归测试。
 *
 * <p>1024 维向量（bge-m3）响应体约 13KB。旧实现把响应体截断到 800 字符，
 * 导致 JSON 解析失败、远程 embedding 静默降级为词法召回；此用例守住该行为。</p>
 */
class ChatModelClientEmbeddingResponseTest {

    private static final int DIM = 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatModelClient client;

    private HttpServer server;

    private int capturedBodies;

    @BeforeEach
    void setUp() throws Exception {
        client = new ChatModelClient(objectMapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", this::handleEmbeddings);
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

    @Test
    void largeEmbeddingResponseIsParsedWithoutTruncation() {
        List<double[]> vectors = client.embed(
                "http://127.0.0.1:" + server.getAddress().getPort(), "k", "bge-m3",
                List.of("库存盘点表", "订单表"));
        assertNotNull(vectors, "大响应体不得因截断而解析失败");
        assertEquals(2, vectors.size());
        for (double[] v : vectors) {
            assertEquals(DIM, v.length, "向量维度应完整保留");
            assertEquals(0.001D, v[1], 1e-9);
            assertEquals((DIM - 1) * 0.001D, v[DIM - 1], 1e-9);
        }
        assertEquals(1, capturedBodies);
    }

    private void handleEmbeddings(HttpExchange exchange) {
        try {
            capturedBodies++;
            StringBuilder data = new StringBuilder("[");
            for (int i = 0; i < 2; i++) {
                if (i > 0) {
                    data.append(",");
                }
                data.append("{\"embedding\":[");
                for (int d = 0; d < DIM; d++) {
                    if (d > 0) {
                        data.append(",");
                    }
                    data.append(String.format(java.util.Locale.ROOT, "%.6f", d * 0.001D));
                }
                data.append("]}");
            }
            data.append("]");
            byte[] body = ("{\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            assertTrue(body.length > 800, "桩响应体应超过旧的 800 字符截断上限");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } catch (Exception e) {
            // 桩异常不影响断言主体
        } finally {
            exchange.close();
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
