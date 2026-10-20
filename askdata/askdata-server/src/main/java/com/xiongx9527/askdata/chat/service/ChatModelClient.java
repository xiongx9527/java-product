package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型接入：自包含的 OpenAI 兼容客户端（不再依赖 fw-plus-ai-model 契约）。
 *
 * <p>提供流式 {@link #streamCompletion}（{@code /v1/chat/completions} SSE，含
 * {@code reasoning_content} 思考流）、一次性 {@link #generate} 与向量 {@link #embed}
 * （{@code /v1/embeddings}）三条通路（D7：Ollama 与 OpenAI 兼容两种都支持）。
 * 保留自研 SSE 客户端而非笼统替换为 langchain4j 模型：问数主链路要求逐 token 推送
 * 且需透传 {@code reasoning_content}（协议见 {@code views/chat/answer/ChartAnswer.vue}），
 * 现有实现已验证可用。</p>
 *
 * <p><b>离线降级</b>：模型未配置或端点不可达时，{@link #streamCompletion} 通过
 * {@link TokenHandler#onError} 回调上报，<b>不抛异常</b>；调用方据此发出 {@code error} 帧并
 * 正常结束，应用本身不依赖真实 LLM 启动（与 {@code system/aimodel/status} 的连通检查路径同构）。</p>
 */
@Service
public class ChatModelClient {

    private static final Logger log = LoggerFactory.getLogger(ChatModelClient.class);

    private static final int CONNECT_TIMEOUT_MS = 8000;

    private static final int READ_TIMEOUT_MS = 120000;

    private final ObjectMapper objectMapper;

    public ChatModelClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 流式补全
    // ------------------------------------------------------------------

    /** 对话消息（role ∈ {system, user, assistant}）。 */
    public static class ModelMessage {

        public final String role;

        public final String content;

        public ModelMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** 流式 token 回调（每收到一段 content/reasoning_content 触发一次）。 */
    public interface TokenHandler {

        void onToken(String content, String reasoningContent);

        void onComplete();

        void onError(String message);

        /** 流末 usage 上报（A6 token 用量）；端点未上报时不触发。 */
        default void onUsage(long totalTokens) {
            // 默认忽略：仅关心文本流的调用方无需实现
        }
    }

    /**
     * 流式补全：按 SSE 逐段回调。
     *
     * @param apiDomain 模型域名（如 http://127.0.0.1:11434）；空则直接回调 onError
     * @param apiKey    访问密钥（可为空，本地 Ollama 通常不需要）
     * @param baseModel 模型名（如 gemma3 / deepseek-r1）
     */
    public void streamCompletion(String apiDomain, String apiKey, String baseModel,
                                 List<ModelMessage> messages, TokenHandler handler) {
        if (apiDomain == null || apiDomain.trim().isEmpty() || baseModel == null || baseModel.trim().isEmpty()) {
            handler.onError("模型未配置：缺少 api_domain 或 base_model（请先到系统-AI 模型配置并设置默认模型）");
            return;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(chatCompletionsUrl(apiDomain)).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", baseModel);
            List<Map<String, String>> msgs = new ArrayList<>();
            for (ModelMessage message : messages) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("role", message.role);
                item.put("content", message.content);
                msgs.add(item);
            }
            payload.put("messages", msgs);
            payload.put("stream", true);
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));
            connection.getOutputStream().flush();

            int code = connection.getResponseCode();
            if (code >= 400) {
                String detail = readAll(connection.getErrorStream());
                handler.onError("模型调用失败 HTTP " + code + (detail.isEmpty() ? "" : ": " + detail));
                return;
            }
            readSse(connection.getInputStream(), handler);
            handler.onComplete();
        } catch (Exception e) {
            log.warn("模型调用异常（已降级）: {}", e.getMessage());
            handler.onError("模型调用失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 非流式一次性补全（用于少量补充推理，如图表类型判定）。失败时返回 null。 */
    public String generate(String apiDomain, String apiKey, String baseModel, List<ModelMessage> messages) {
        final StringBuilder sb = new StringBuilder();
        final boolean[] failed = {false};
        streamCompletion(apiDomain, apiKey, baseModel, messages, new TokenHandler() {
            @Override
            public void onToken(String content, String reasoningContent) {
                if (content != null) {
                    sb.append(content);
                }
            }

            @Override
            public void onComplete() {
                // no-op
            }

            @Override
            public void onError(String message) {
                failed[0] = true;
            }
        });
        return failed[0] || sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------
    // 向量（D5 RAG：embedding + cosine）
    // ------------------------------------------------------------------

    /**
     * 批量取文本向量（OpenAI 兼容 {@code /v1/embeddings}，Ollama 同构）。
     *
     * <p>失败返回 {@code null}（调用方据此降级为词法召回），不抛异常。</p>
     */
    public List<double[]> embed(String apiDomain, String apiKey, String baseModel, List<String> inputs) {
        if (apiDomain == null || apiDomain.trim().isEmpty() || baseModel == null || baseModel.trim().isEmpty()
                || inputs == null || inputs.isEmpty()) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(embeddingsUrl(apiDomain)).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", baseModel);
            payload.put("input", inputs);
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));
            connection.getOutputStream().flush();
            int code = connection.getResponseCode();
            if (code >= 400) {
                log.info("embedding 调用失败 HTTP {}（降级为词法召回）", code);
                return null;
            }
            String body = readAll(connection.getInputStream());
            JsonNode node = objectMapper.readTree(body);
            JsonNode data = node.get("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            List<double[]> vectors = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JsonNode embedding = data.get(i).get("embedding");
                if (embedding == null || !embedding.isArray()) {
                    return null;
                }
                double[] vector = new double[embedding.size()];
                for (int j = 0; j < vector.length; j++) {
                    vector[j] = embedding.get(j).asDouble();
                }
                vectors.add(vector);
            }
            return vectors.size() == inputs.size() ? vectors : null;
        } catch (Exception e) {
            log.info("embedding 调用异常（降级为词法召回）: {}", e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 余弦相似度（两向量等长）。 */
    public static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm <= 0D || rightNorm <= 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    // ------------------------------------------------------------------
    // 流式帧输出（协议：data:{json}\n\n）
    // ------------------------------------------------------------------

    /** 把流式响应写进 HttpServletResponse（text/event-stream），逐帧 flush。 */
    public static class FrameWriter implements AutoCloseable {

        private final PrintWriter writer;

        private final ObjectMapper objectMapper;

        public FrameWriter(HttpServletResponse response, ObjectMapper objectMapper) throws IOException {
            this.objectMapper = objectMapper;
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            this.writer = response.getWriter();
        }

        /** 写出一帧 {@code data:{json}\n\n}，与 SQLBot 前端 answer/*.vue 解析逐字兼容。 */
        public void write(Map<String, Object> frame) throws IOException {
            writer.write("data:" + objectMapper.writeValueAsString(frame) + "\n\n");
            writer.flush();
        }

        @Override
        public void close() {
            if (writer != null) {
                writer.flush();
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void readSse(InputStream inputStream, TokenHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                try {
                    JsonNode node = objectMapper.readTree(data);
                    JsonNode usage = node.get("usage");
                    if (usage != null && usage.get("total_tokens") != null) {
                        handler.onUsage(usage.get("total_tokens").asLong());
                    }
                    JsonNode choices = node.get("choices");
                    if (choices != null && choices.isArray() && choices.size() > 0) {
                        JsonNode choice = choices.get(0);
                        JsonNode delta = choice.get("delta");
                        if (delta == null) {
                            delta = choice.get("message");
                        }
                        if (delta != null) {
                            JsonNode content = delta.get("content");
                            JsonNode reasoning = delta.get("reasoning_content");
                            String c = content != null && !content.isNull() ? content.asText() : null;
                            String r = reasoning != null && !reasoning.isNull() ? reasoning.asText() : null;
                            if ((c != null && !c.isEmpty()) || (r != null && !r.isEmpty())) {
                                handler.onToken(c, r);
                            }
                        }
                    }
                } catch (Exception e) {
                    // 忽略非法 SSE 数据行（保持流稳定）
                    log.debug("忽略非法 SSE 数据行: {}", data);
                }
            }
        }
    }

    private static String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > 4000) {
                    break;
                }
            }
        }
        return sb.length() > 800 ? sb.substring(0, 800) : sb.toString();
    }

    /** 端点规范化：{@code http://host:11434} → {@code .../v1/chat/completions}（与 AiModelService 同语义）。 */
    static String chatCompletionsUrl(String apiDomain) {
        String base = trimBase(apiDomain);
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        String path;
        try {
            path = new URL(base).getPath();
        } catch (Exception e) {
            return base + "/chat/completions";
        }
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return base + "/v1/chat/completions";
        }
        return base + "/chat/completions";
    }

    /** 端点规范化：{@code http://host:11434} → {@code .../v1/embeddings}。 */
    static String embeddingsUrl(String apiDomain) {
        String base = trimBase(apiDomain);
        if (base.endsWith("/embeddings")) {
            return base;
        }
        String path;
        try {
            path = new URL(base).getPath();
        } catch (Exception e) {
            return base + "/embeddings";
        }
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return base + "/v1/embeddings";
        }
        return base + "/embeddings";
    }

    private static String trimBase(String apiDomain) {
        String base = apiDomain.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
