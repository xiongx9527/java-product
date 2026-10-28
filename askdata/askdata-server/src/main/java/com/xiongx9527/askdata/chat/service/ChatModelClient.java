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

    // gpt-oss:120b-cloud 等大模型首 token 时延可达数分钟（实测 ~6min），原 120s 读超时会在模型尚未
    // 返回任何分片时将其降级为「无 SQL」，导致问数/调度偶发失败；放宽到 600s 使慢模型得以完整返回。
    private static final int READ_TIMEOUT_MS = 600000;

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

        /**
         * A12：流末 usage 上报。默认实现只取 total（旧行为）；
         * 需要 input/output 明细时覆写 {@link #onUsageDetail(long, long, long)}。
         */
        default void onUsage(long totalTokens) {
            onUsageDetail(0L, 0L, totalTokens);
        }

        /** A12：usage 明细（input / output / total）；端点未上报分项时为 0。 */
        default void onUsageDetail(long inputTokens, long outputTokens, long totalTokens) {
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
    /**
     * 流式补全（含额外推理参数）。
     *
     * @param extraParams §2.3: 模型推理参数（temperature / top_p / max_tokens / enable_thinking 及厂商自定义
     *                    additional_params）；null 或空时忽略。这些参数原先只落库不下发，现透传到请求体。
     */
    public void streamCompletion(String apiDomain, String apiKey, String baseModel,
                                 List<ModelMessage> messages, Map<String, Object> extraParams,
                                 TokenHandler handler) {
        streamCompletion(apiDomain, apiKey, baseModel, messages, extraParams, 1, handler);
    }

    /**
     * §2.3: 带协议分发的流式补全。{@code protocol} 取 {@code ai_model.protocol}：
     * 1=openai 兼容、2=vllm（两者同构，URL 规范化一致）；请求形态由端点域名决定——
     * Azure 专有域名（{@code *.openai.azure.com} / {@code *.openai.azure.cn}）走
     * deployment URL + {@code api-key} 头（对齐 SQLBot LLMFactory 的 azure 分支）。
     */
    public void streamCompletion(String apiDomain, String apiKey, String baseModel,
                                 List<ModelMessage> messages, Map<String, Object> extraParams,
                                 int protocol, TokenHandler handler) {
        if (apiDomain == null || apiDomain.trim().isEmpty() || baseModel == null || baseModel.trim().isEmpty()) {
            handler.onError("模型未配置：缺少 api_domain 或 base_model（请先到系统-AI 模型配置并设置默认模型）");
            return;
        }
        streamCompletionImpl(apiDomain, apiKey, baseModel, messages, extraParams, protocol, handler);
    }

    /** 兼容旧调用方（不传额外参数）。 */
    public void streamCompletion(String apiDomain, String apiKey, String baseModel,
                                 List<ModelMessage> messages, TokenHandler handler) {
        streamCompletion(apiDomain, apiKey, baseModel, messages, null, 1, handler);
    }

    private void streamCompletionImpl(String apiDomain, String apiKey, String baseModel,
                                      List<ModelMessage> messages, Map<String, Object> extraParams,
                                      int protocol, TokenHandler handler) {
        HttpURLConnection connection = null;
        try {
            boolean azure = isAzureEndpoint(apiDomain);
            connection = (HttpURLConnection) new URL(chatCompletionsUrl(apiDomain, baseModel, extraParams)).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.isEmpty()) {
                if (azure) {
                    // Azure 不用 Bearer，改用 api-key 头（对齐 SQLBot AzureOpenAI 分支）
                    connection.setRequestProperty("api-key", apiKey);
                } else {
                    connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
            }
            Map<String, Object> payload = buildPayload(baseModel, messages, extraParams, azure);
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

    /**
     * 构造 chat/completions 请求体（§2.3 / A5）：
     * <ul>
     *   <li>模型推理参数整包平铺进顶层、跳过 null（temperature/top_p/max_tokens 等保持既有语义）；</li>
     *   <li>{@code extra_body} 键的值（Map 或 JSON 字符串）与单独出现的 {@code enable_thinking}
     *       归并进嵌套 {@code extra_body} 对象——严格 OpenAI 兼容端点不认顶层平铺键（对齐 SQLBot
     *       langchain 的 extra_body kwarg + llm.py:194-201 删 enable_thinking 语义）；</li>
     *   <li>Azure 端点：{@code api_version}/{@code deployment_name} 只参与 URL 构造（见
     *       {@link #chatCompletionsUrl}），从请求体剔除（对齐 SQLBot model_factory 的 pop 行为）；
     *       非 Azure 端点仍按普通参数透传。</li>
     * </ul>
     */
    public static Map<String, Object> buildPayload(String baseModel, List<ModelMessage> messages,
                                            Map<String, Object> extraParams, boolean azure) {
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
        Map<String, Object> extraBody = null;
        if (extraParams != null) {
            for (Map.Entry<String, Object> entry : extraParams.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value == null || key == null) {
                    continue;
                }
                if ("api_version".equals(key) || "deployment_name".equals(key)) {
                    if (!azure) {
                        payload.put(key, value);
                    }
                    continue;
                }
                if ("extra_body".equals(key)) {
                    extraBody = mergeExtraBody(extraBody, value);
                    continue;
                }
                if ("enable_thinking".equals(key)) {
                    if (extraBody == null) {
                        extraBody = new LinkedHashMap<>();
                    }
                    extraBody.put("enable_thinking", value);
                    continue;
                }
                payload.put(key, value);
            }
        }
        if (extraBody != null) {
            payload.put("extra_body", extraBody);
        }
        payload.put("stream", true);
        // A12：请求流式 usage（对齐 SQLBot stream_usage=True），使端点在上报 usage 时
        // 同时给出 input/output/total 三项（此前只取 total_tokens）
        Map<String, Object> streamOptions = new LinkedHashMap<>();
        streamOptions.put("include_usage", Boolean.TRUE);
        payload.put("stream_options", streamOptions);
        return payload;
    }

    /** 把 extra_body 配置值（Map / JSON 字符串）归并进已有的嵌套对象；非法 JSON 按原样字符串保留。 */
    private static Map<String, Object> mergeExtraBody(Map<String, Object> current, Object value) {
        Map<String, Object> merged = current == null ? new LinkedHashMap<>() : current;
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                merged.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.startsWith("{")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(text);
                    if (node.isObject()) {
                        merged.putAll(mapper.convertValue(node, Map.class));
                        return merged;
                    }
                } catch (Exception ignored) {
                    // 非 JSON 字符串按原样保留
                }
            }
            merged.put("extra_body", value);
        } else {
            merged.put("extra_body", value);
        }
        return merged;
    }

    /** 非流式一次性补全（用于少量补充推理，如图表类型判定）。失败时返回 null。
     * @param extraParams §2.3: 透传的模型推理参数（见 {@link #streamCompletion}）；可为 null。 */
    public String generate(String apiDomain, String apiKey, String baseModel, List<ModelMessage> messages,
                           Map<String, Object> extraParams) {
        return generate(apiDomain, apiKey, baseModel, messages, extraParams, null);
    }

    /**
     * 非流式一次性补全（A12：可回传 usage 明细）。
     *
     * @param usage 非 null 时写入本次调用的 input/output/total（端点未上报时为 0）
     */
    public String generate(String apiDomain, String apiKey, String baseModel, List<ModelMessage> messages,
                           Map<String, Object> extraParams, final Usage usage) {
        final StringBuilder sb = new StringBuilder();
        final boolean[] failed = {false};
        streamCompletion(apiDomain, apiKey, baseModel, messages, extraParams, new TokenHandler() {
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

            @Override
            public void onUsageDetail(long inputTokens, long outputTokens, long total) {
                if (usage != null) {
                    usage.input += inputTokens;
                    usage.output += outputTokens;
                    usage.total += total;
                }
            }
        });
        return failed[0] || sb.length() == 0 ? null : sb.toString();
    }

    /** A12：LLM 调用的 token 用量（可变 holder）。 */
    public static final class Usage {
        public long input;
        public long output;
        public long total;
    }

    /** 兼容旧调用方（不传额外参数）。 */
    public String generate(String apiDomain, String apiKey, String baseModel, List<ModelMessage> messages) {
        return generate(apiDomain, apiKey, baseModel, messages, null);
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
            String body = readAllFully(connection.getInputStream());
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

    /** 帧观察者：非流式 {@code ask} 用它在复用同一流式管线时捕获帧，不依赖 HttpServletResponse。 */
    public interface FrameListener {
        void onFrame(Map<String, Object> frame);
    }

    /** 把流式响应写进 HttpServletResponse（text/event-stream），逐帧 flush。 */
    public static class FrameWriter implements AutoCloseable {

        private final PrintWriter writer;

        private final ObjectMapper objectMapper;

        private final FrameListener listener;

        public FrameWriter(HttpServletResponse response, ObjectMapper objectMapper) throws IOException {
            this(response, objectMapper, null);
        }

        public FrameWriter(HttpServletResponse response, ObjectMapper objectMapper, FrameListener listener)
                throws IOException {
            this.objectMapper = objectMapper;
            this.listener = listener;
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            this.writer = response.getWriter();
        }

        private FrameWriter(PrintWriter writer, ObjectMapper objectMapper, FrameListener listener) {
            this.writer = writer;
            this.objectMapper = objectMapper;
            this.listener = listener;
        }

        /**
         * 捕获模式：帧只交给监听器，不写 HTTP 响应。
         *
         * <p>非流式 {@code mcp_question} 复用同一问数管线，通过捕获帧组装 SQLBot JSON，
         * 避免再维护一套简化链路。{@link #close()} 对捕获模式不产生副作用。</p>
         */
        public static FrameWriter capturing(ObjectMapper objectMapper, FrameListener listener) {
            return new FrameWriter(new PrintWriter(java.io.Writer.nullWriter()), objectMapper, listener);
        }

        /** 写出一帧 {@code data:{json}\n\n}，与 SQLBot 前端 answer/*.vue 解析逐字兼容。 */
        public void write(Map<String, Object> frame) throws IOException {
            if (listener != null) {
                listener.onFrame(frame);
            }
            try {
                writer.write("data:" + objectMapper.writeValueAsString(frame) + "\n\n");
                writer.flush();
            } catch (IOException e) {
                // A16：客户端已断开连接时静默忽略，后端继续完成 SQL 执行、图表生成与落库，
                // 不把断连当成业务错误（真实执行错误仍会由调用方以异常形式抛出并 saveError）
                log.info("流式写出失败（客户端可能已断开）: {}", e.getMessage());
            }
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
                    if (usage != null) {
                        long input = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : 0L;
                        long output = usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : 0L;
                        long total = usage.has("total_tokens")
                                ? usage.get("total_tokens").asLong() : input + output;
                        handler.onUsageDetail(input, output, total);
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

    /**
     * 读取响应体并截断到 800 字符——仅用于错误详情日志（避免把大段错误体刷进日志）。
     *
     * <p><b>不要用于业务响应体</b>：embedding 响应（1024 维约 13KB）远超该上限，
     * 截断会导致 JSON 解析失败；业务读取请用 {@link #readAllFully(InputStream)}。</p>
     */
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

    /** 完整读取响应体（不截断）：embedding 等大 JSON 业务响应必须全量读取。 */
    private static String readAllFully(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    /** Azure 专有端点判定：资源域名形如 {@code <res>.openai.azure.com} / {@code <res>.openai.azure.cn}。 */
    public static boolean isAzureEndpoint(String apiDomain) {
        String host;
        try {
            host = new URL(trimBase(apiDomain)).getHost();
        } catch (Exception e) {
            return false;
        }
        return host != null && (host.equals("openai.azure.com") || host.endsWith(".openai.azure.com")
                || host.equals("openai.azure.cn") || host.endsWith(".openai.azure.cn"));
    }

    /** 端点规范化：{@code http://host:11434} → {@code .../v1/chat/completions}（与 AiModelService 同语义）；
     *  Azure 专有域名改走 deployment 形态：{@code {base}/openai/deployments/{deployment}/chat/completions?api-version={v}}
     *  （deployment 取 additional_params 的 {@code deployment_name}，缺省模型名；api-version 取 {@code api_version}，
     *  缺省 2024-02-15-preview——与前端 supplier.ts Azure 预设一致）。 */
    public static String chatCompletionsUrl(String apiDomain, String baseModel, Map<String, Object> extraParams) {
        if (isAzureEndpoint(apiDomain)) {
            String base = trimBase(apiDomain);
            String deployment = baseModel;
            String apiVersion = "2024-02-15-preview";
            if (extraParams != null) {
                Object dn = extraParams.get("deployment_name");
                if (dn != null && !String.valueOf(dn).trim().isEmpty()) {
                    deployment = String.valueOf(dn).trim();
                }
                Object av = extraParams.get("api_version");
                if (av != null && !String.valueOf(av).trim().isEmpty()) {
                    apiVersion = String.valueOf(av).trim();
                }
            }
            return base + "/openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;
        }
        return openAiChatCompletionsUrl(apiDomain);
    }

    /** OpenAI 兼容端点规范化（openai / vllm 同构）。 */
    static String openAiChatCompletionsUrl(String apiDomain) {
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
