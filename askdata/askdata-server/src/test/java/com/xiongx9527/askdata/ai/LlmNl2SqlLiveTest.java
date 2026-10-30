package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.common.util.Lists;
import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 LLM 验证（integration）：直连 Ollama 的 OpenAI 兼容端点（/v1/chat/completions，
 * 对应 askdata ChatModelClient 自动补的路径），用非流式（stream=false）请求验证「给定 schema +
 * 自然语言 -> SQL」这一核心链路。仅当设置了 ASKDATA_LLM_DOMAIN 时运行，否则跳过。
 *
 * 默认模型 gpt-oss:120b-cloud（用户指定的生产模型，Ollama 云托管 120B，延迟高，故读超时
 * 放宽到 300s）；可用 ASKDATA_LLM_MODEL 覆盖为本地快速模型（如 qwen3:4b）。本测试与
 * ChatModelClient 共用同一 Ollama 端点约定，验证「端点 + 模型」本身可用。
 *
 * ASKDATA_LLM_DOMAIN=http://192.168.1.100:11434 ASKDATA_LLM_MODEL=gpt-oss:120b-cloud \
 * mvn -pl askdata/askdata-server test -Dmaven.test.skip=false -Dtest=LlmNl2SqlLiveTest
 */
class LlmNl2SqlLiveTest {

    private static final int READ_TIMEOUT_MS = 300_000;

    @Test
    void realLlmGeneratesSqlViaOllamaOpenAiEndpoint() throws Exception {
        String domain = System.getenv("ASKDATA_LLM_DOMAIN");
        assumeTrue(domain != null && !domain.trim().isEmpty(),
                "未设置 ASKDATA_LLM_DOMAIN，跳过真实 LLM 验证");
        String model = System.getenv("ASKDATA_LLM_MODEL");
        if (model == null || model.trim().isEmpty()) {
            model = "gpt-oss:120b-cloud";
        }
        String apiKey = System.getenv("ASKDATA_LLM_API_KEY");

        String base = domain.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URL url = new URL(base + "/v1/chat/completions");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", false);
        payload.put("messages", Lists.of(
                Maps.of("role", "system", "content",
                        "You are a text-to-SQL expert. Given a database schema and a question, "
                                + "return ONLY a valid SQL SELECT statement, no explanation."),
                Maps.of("role", "user", "content",
                        "Schema:\nTable ACCOUNT(id INTEGER, name VARCHAR(50), created_at TIMESTAMP)"
                                + "\nQuestion: 查询所有账户的名字和创建时间，按创建时间倒序")));

        ObjectMapper om = new ObjectMapper();
        byte[] body = om.writeValueAsBytes(payload);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.getOutputStream().write(body);
        conn.getOutputStream().flush();

        int code = conn.getResponseCode();
        String resp = new String(conn.getResponseCode() < 400
                ? readAll(conn.getInputStream()) : readAll(conn.getErrorStream()), StandardCharsets.UTF_8);
        assertTrue(code < 400, "Ollama 应返回 2xx，实际 HTTP " + code + " body=" + resp);

        JsonNode node = om.readTree(resp);
        JsonNode contentNode = node.path("choices").path(0).path("message").path("content");
        String sql = contentNode.isMissingNode() ? null : contentNode.asText();
        assertNotNull(sql, "模型应返回 content 文本，响应=" + resp);
        String upper = sql.toUpperCase();
        assertTrue(upper.contains("SELECT"), "模型返回应包含 SELECT 语句，实际: " + sql);
        assertTrue(upper.contains("ACCOUNT"), "模型返回应引用 ACCOUNT 表，实际: " + sql);
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
