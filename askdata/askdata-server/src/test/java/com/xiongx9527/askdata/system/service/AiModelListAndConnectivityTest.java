package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.support.MysqlTestSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A6.x / A7.x：模型列表代理与按类型分流的连通校验。
 *
 * <p>全部用例都打到本地 stub 上游，断言「真实命中的路径 / 请求次数 / 落库结果」，
 * 而不是只测纯函数。</p>
 */
class AiModelListAndConnectivityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final IdGenerator ids = new IdGenerator();

    private JdbcTemplate jdbc;

    private AiModelRepository repository;

    private AiModelService service;

    private HttpServer server;

    private final List<String> hitPaths = new ArrayList<>();

    private final List<String> authHeaders = new ArrayList<>();

    private String modelsBody = "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"text-embedding-3-small\"}]}";

    private int modelsStatus = 200;

    private int embeddingDimension = 1024;

    private String chatSse = "data: {\"choices\":[{\"delta\":{\"content\":\"2\"}}]}\n\ndata: [DONE]\n\n";

    @BeforeEach
    void setUp() throws Exception {
        jdbc = MysqlTestSupport.createSchema();
        repository = new AiModelRepository(jdbc, ids);

        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages");
        source.setDefaultEncoding("UTF-8");
        MessageResolver resolver = new MessageResolver(source);

        CurrentUserService currentUser = mock(CurrentUserService.class);
        service = new AiModelService(repository, currentUser, ids, objectMapper, new ChatModelClient(objectMapper));
        ReflectionTestUtils.setField(service, "messageResolver", resolver);

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
            String path = exchange.getRequestURI().getPath();
            hitPaths.add(path);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth != null ? auth : exchange.getRequestHeaders().getFirst("api-key"));
            byte[] requestBody;
            try (InputStream in = exchange.getRequestBody()) {
                requestBody = readAll(in);
            }
            byte[] response;
            int status = 200;
            String contentType = "application/json";
            if (path.endsWith("/models")) {
                status = modelsStatus;
                response = modelsBody.getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/embeddings")) {
                StringBuilder sb = new StringBuilder("{\"data\":[{\"embedding\":[");
                for (int i = 0; i < embeddingDimension; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("0.01");
                }
                sb.append("]}]}");
                response = sb.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                contentType = "text/event-stream";
                response = chatSse.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
            assertNotNull(requestBody);
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

    private Map<String, Object> listBody(String domain, int supplier, boolean fullUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_domain", domain);
        body.put("api_key", "sk-secret-value");
        body.put("supplier", supplier);
        body.put("full_url", fullUrl);
        return body;
    }

    private long insertModel(int modelType, String baseModel, boolean enable) {
        long id = ids.nextId();
        repository.insert(id, "sk-secret-value", base(), 1, 1, "模型" + id, modelType, baseModel,
                false, "[]", 1, System.currentTimeMillis(), enable, 1, null);
        return id;
    }

    /** 采集 NDJSON 输出。 */
    private String streamStatus(Map<String, Object> body) throws Exception {
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(writer);
        service.streamStatus(body, response);
        writer.flush();
        return text.toString();
    }

    private Map<String, Object> statusBody(int modelType, String baseModel, Long id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_domain", base());
        body.put("api_key", "sk-secret-value");
        body.put("base_model", baseModel);
        body.put("model_type", modelType);
        if (id != null) {
            body.put("id", id);
        }
        return body;
    }

    // ------------------------------------------------------------------
    // A6.1 三种 source
    // ------------------------------------------------------------------

    @Test
    void remoteModelsAreReturnedWithTypeGuess() {
        Map<String, Object> result = service.listModels(listBody(base(), 1, false));
        assertEquals("remote", result.get("source"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
        assertEquals(2, models.size());
        assertEquals("gpt-4o", models.get(0).get("id"));
        assertEquals(AiModelService.CHAT_MODEL_TYPE, models.get(0).get("model_type"));
        assertEquals(AiModelService.EMBEDDING_MODEL_TYPE, models.get(1).get("model_type"));
        assertEquals("/v1/models", hitPaths.get(0));
    }

    @Test
    void upstreamFailureFallsBackToPreset() {
        modelsStatus = 401;
        Map<String, Object> result = service.listModels(listBody(base(), 1, false));
        assertEquals("preset", result.get("source"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
        assertTrue(models.isEmpty());
        assertNotNull(result.get("error"));
        assertFalse(String.valueOf(result.get("error")).contains("sk-secret-value"),
                "错误文案不得带出 Key");
    }

    @Test
    void customSupplierHasNoPresetFallback() {
        modelsStatus = 404;
        Map<String, Object> result = service.listModels(listBody(base(), AiModelService.CUSTOM_SUPPLIER, false));
        assertEquals("none", result.get("source"));
    }

    @Test
    void emptyRemoteListIsTreatedAsUnavailable() {
        modelsBody = "{\"data\":[]}";
        Map<String, Object> result = service.listModels(listBody(base(), 1, false));
        assertEquals("preset", result.get("source"));
        assertNotNull(result.get("error"));
    }

    // ------------------------------------------------------------------
    // A6.2 单次请求 + 条数上限
    // ------------------------------------------------------------------

    @Test
    void onlyOneRequestIsSentAndListIsTruncated() {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 250; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"m").append(i).append("\"}");
        }
        sb.append("]}");
        modelsBody = sb.toString();

        Map<String, Object> result = service.listModels(listBody(base(), 1, false));
        assertEquals(1, hitPaths.size(), "只发一次规范化请求，不做 404 路径探测");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
        assertEquals(200, models.size(), "返回条数受上限约束");
    }

    @Test
    void apiKeyIsSentAsBearer() {
        service.listModels(listBody(base(), 1, false));
        assertEquals("Bearer sk-secret-value", authHeaders.get(0));
    }

    // ------------------------------------------------------------------
    // A6.4 完整 URL 不可剥离
    // ------------------------------------------------------------------

    @Test
    void fullUrlWithoutChatEndpointIsRejectedWithoutRequest() {
        Map<String, Object> result = service.listModels(listBody(base() + "/v1/custom", 1, true));
        assertEquals("preset", result.get("source"));
        assertNotNull(result.get("error"));
        assertEquals(0, hitPaths.size(), "无法识别端点时不得发起请求");
    }

    @Test
    void fullUrlWithChatEndpointIsStripped() {
        Map<String, Object> result = service.listModels(listBody(base() + "/v1/chat/completions", 1, true));
        assertEquals("remote", result.get("source"));
        assertEquals("/v1/models", hitPaths.get(0));
    }

    // ------------------------------------------------------------------
    // A7.1 按类型分流
    // ------------------------------------------------------------------

    @Test
    void chatModelChecksChatCompletionsEndpoint() throws Exception {
        String output = streamStatus(statusBody(AiModelService.CHAT_MODEL_TYPE, "gpt-4o", null));
        assertEquals("/v1/chat/completions", hitPaths.get(0));
        assertTrue(output.contains("2"), output);
    }

    @Test
    void embeddingModelChecksEmbeddingsEndpoint() throws Exception {
        String output = streamStatus(statusBody(AiModelService.EMBEDDING_MODEL_TYPE, "bge-m3", null));
        assertEquals("/v1/embeddings", hitPaths.get(0));
        assertTrue(output.contains("dimension"), output);
    }

    // ------------------------------------------------------------------
    // A7.2 / A7.3 valid 与维度回写
    // ------------------------------------------------------------------

    @Test
    void embeddingCheckWritesDimensionAndValid() throws Exception {
        long id = insertModel(AiModelService.EMBEDDING_MODEL_TYPE, "bge-m3", false);
        String output = streamStatus(statusBody(AiModelService.EMBEDDING_MODEL_TYPE, "bge-m3", id));

        assertTrue(output.contains(String.valueOf(embeddingDimension)), output);
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT valid FROM ai_model WHERE id = ?", Boolean.class, id));
        assertEquals(embeddingDimension,
                jdbc.queryForObject("SELECT dimension FROM ai_model WHERE id = ?", Integer.class, id));
    }

    @Test
    void failedCheckMarksModelUnavailableAndDisablesIt() throws Exception {
        long id = insertModel(AiModelService.CHAT_MODEL_TYPE, "gpt-4o", true);
        chatSse = "{\"error\":\"boom\"}";
        // 非 SSE 响应体会被当作 JSON 解析，仍返回成功；改用 401 更贴近真实失败
        modelsStatus = 200;
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        Map<String, Object> body = statusBody(AiModelService.CHAT_MODEL_TYPE, "gpt-4o", id);
        body.put("api_domain", "http://127.0.0.1:1");
        service.streamStatus(body, response);

        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT valid FROM ai_model WHERE id = ?", Boolean.class, id));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT enable FROM ai_model WHERE id = ?", Boolean.class, id));
    }

    @Test
    void checkWithoutIdDoesNotTouchAnyRow() throws Exception {
        long id = insertModel(AiModelService.CHAT_MODEL_TYPE, "gpt-4o", false);
        streamStatus(statusBody(AiModelService.CHAT_MODEL_TYPE, "gpt-4o", null));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT valid FROM ai_model WHERE id = ?", Boolean.class, id),
                "不带 id 的校验只返回结论，不落库");
    }

    // ------------------------------------------------------------------
    // A7.4 保存不改写 valid
    // ------------------------------------------------------------------

    @Test
    void updateKeepsStoredValid() {
        long id = insertModel(AiModelService.CHAT_MODEL_TYPE, "gpt-4o", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "改名");
        body.put("api_domain", base());
        body.put("api_key", "sk-secret-value");
        body.put("base_model", "gpt-4o");
        body.put("model_type", AiModelService.CHAT_MODEL_TYPE);
        body.put("supplier", 1);
        body.put("enable", true);
        body.put("id", id);
        service.update(body);
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT valid FROM ai_model WHERE id = ?", Boolean.class, id));
        assertEquals("改名", jdbc.queryForObject("SELECT name FROM ai_model WHERE id = ?", String.class, id));
    }
}
