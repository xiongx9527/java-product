package com.xiongx9527.askdata.ai.vector;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.ai.LocalEmbeddingModel;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EmbeddingService} 与可插拔 {@link VectorStore} 的路由测试（纯 JUnit + H2 + 本地 HTTP 桩）：
 * <ol>
 *   <li>写入委托给当前后端，集合名 = {prefix}-{object-type}；在线库启用时镜像 DB 向量列；</li>
 *   <li>召回委托给当前后端，命中 id 映射回候选行、按阈值过滤、降序、截断 topCount；</li>
 *   <li>无可用 embedding 模型时召回返回空（调用方据此降级为词法），不抛错；</li>
 *   <li>非白名单表 / 空向量被拒，不触达后端。</li>
 * </ol>
 */
class EmbeddingServiceVectorStoreRoutingTest {

    private JdbcTemplate appJdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final IdGenerator ids = new IdGenerator();

    private ChatModelRouter modelRouter;

    private ChatModelClient modelClient;

    private EmbeddingService embeddingService;

    private RecordingVectorStore recording;

    private HttpServer stubServer;

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:vector_routing_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (java.sql.Connection connection = appJdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        java.nio.file.Path modelDir = Files.createTempDirectory("no-model");
        modelRouter = new ChatModelRouter(new AiModelRepository(appJdbc, ids), objectMapper);
        modelClient = new ChatModelClient(objectMapper);
        embeddingService = new EmbeddingService(
                new LocalEmbeddingModel(modelDir.toString(), 8), modelRouter, modelClient, appJdbc, objectMapper);
        recording = new RecordingVectorStore();
        embeddingService.setVectorStore(recording);
    }

    @AfterEach
    void tearDown() {
        if (stubServer != null) {
            stubServer.stop(0);
            stubServer = null;
        }
    }

    @Test
    void writeEmbeddingUpsertsToBackendWithCollectionName() {
        insertTerminology(1L, "订单");
        double[] vector = {0.25D, 0.5D};
        assertTrue(embeddingService.writeEmbedding("terminology", 1L, vector));
        assertEquals(1, recording.upserts.size());
        assertEquals("askdata-terminology", recording.lastUpsertCollection);
        assertEquals(1L, recording.lastUpsertId);
        assertArrayEquals(vector, recording.lastUpsertVector);
        // 在线库启用时同步镜像 DB 向量列（保持启动回填幂等）
        assertNotNull(appJdbc.queryForObject("SELECT embedding FROM terminology WHERE id = 1", String.class));
    }

    @Test
    void writeEmbeddingRejectsNullVectorAndUnknownTable() {
        insertTerminology(1L, "订单");
        assertFalse(embeddingService.writeEmbedding("terminology", 1L, null));
        assertFalse(embeddingService.writeEmbedding("sys_user", 1L, new double[] {1D}));
        assertTrue(recording.upserts.isEmpty(), "非法入参不得触达后端");
    }

    @Test
    void recallByVectorMapsBackendHitsToCandidateRows() throws Exception {
        startEmbeddingStub();
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(row(1L, "订单"));
        candidates.add(row(2L, "用户"));
        candidates.add(row(3L, "商品"));
        recording.queryResult = List.of(
                new VectorStore.IdScore(3L, 0.9D),
                new VectorStore.IdScore(1L, 0.6D),
                new VectorStore.IdScore(2L, 0.2D));

        List<Map<String, Object>> hits =
                embeddingService.recallByVector("terminology", candidates, "查一下订单", 0.4D, 2);

        assertEquals("askdata-terminology", recording.lastQueryCollection);
        assertEquals(List.of(1L, 2L, 3L), recording.lastQueryCandidateIds);
        assertEquals(2, hits.size(), "topCount 截断生效");
        assertEquals(3L, Rows.lng(hits.get(0), "id", 0L));
        assertEquals(1L, Rows.lng(hits.get(1), "id", 0L));
        assertEquals(0.9D, ((Number) hits.get(0).get("similarity")).doubleValue(), 1e-9);
        assertEquals(0.6D, ((Number) hits.get(1).get("similarity")).doubleValue(), 1e-9);
        assertEquals("商品", hits.get(0).get("word"), "候选行字段应保留");
    }

    @Test
    void recallByVectorKeepsAllWhenThresholdIsZero() throws Exception {
        startEmbeddingStub();
        List<Map<String, Object>> candidates = List.of(row(1L, "a"), row(2L, "b"));
        recording.queryResult = List.of(new VectorStore.IdScore(2L, 0.1D), new VectorStore.IdScore(1L, 0.05D));
        List<Map<String, Object>> hits = embeddingService.recallByVector("terminology", candidates, "x", 0D, 10);
        assertEquals(2, hits.size(), "阈值为 0 时全部保留");
        assertEquals(2L, Rows.lng(hits.get(0), "id", 0L));
    }

    @Test
    void recallByVectorReturnsEmptyWithoutEmbeddingModel() {
        List<Map<String, Object>> candidates = List.of(row(1L, "a"));
        recording.queryResult = List.of(new VectorStore.IdScore(1L, 0.9D));
        assertTrue(embeddingService.recallByVector("terminology", candidates, "查询", 0D, 5).isEmpty(),
                "无可用 embedding 模型时应返回空（调用方降级为词法）");
    }

    @Test
    void vectorScoresReturnsIdToScoreMap() throws Exception {
        startEmbeddingStub();
        recording.queryResult = List.of(new VectorStore.IdScore(5L, 0.7D), new VectorStore.IdScore(6L, 0.3D));
        Map<Long, Double> scores = embeddingService.vectorScores("terminology", "查询", List.of(5L, 6L, 7L));
        assertEquals(2, scores.size());
        assertEquals(0.7D, scores.get(5L), 1e-9);
        assertEquals(0.3D, scores.get(6L), 1e-9);
        assertNull(scores.get(7L));
    }

    @Test
    void vectorScoresEmptyWithoutEmbeddingModel() {
        assertTrue(embeddingService.vectorScores("terminology", "查询", List.of(1L)).isEmpty());
    }

    // ------------------------------------------------------------------
    // 测试基建
    // ------------------------------------------------------------------

    private static Map<String, Object> row(long id, String word) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("word", word);
        return row;
    }

    private void insertTerminology(long id, String word) {
        appJdbc.update("INSERT INTO terminology (id, oid, pid, create_time, word, description, embedding, enabled) "
                + "VALUES (?, 1, NULL, ?, ?, '', NULL, TRUE)", id, System.currentTimeMillis(), word);
    }

    /** 本地 HTTP 桩：OpenAI 兼容 /v1/embeddings，返回固定 2 维向量（只需 embed() 非空）。 */
    private void startEmbeddingStub() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/v1/embeddings", this::handleEmbeddings);
        stubServer.setExecutor(null);
        stubServer.start();
        new AiModelRepository(appJdbc, ids).insert(ids.nextId(), "",
                "http://127.0.0.1:" + stubServer.getAddress().getPort(), 1, 0,
                "stub-bge-m3", 1, "bge-m3", false, "[]", 1, System.currentTimeMillis());
    }

    private void handleEmbeddings(HttpExchange exchange) {
        try {
            byte[] request = readAll(exchange.getRequestBody());
            JsonNode inputs = objectMapper.readTree(request).get("input");
            StringBuilder data = new StringBuilder("[");
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) {
                    data.append(",");
                }
                data.append("{\"embedding\":[1.0,0.0]}");
            }
            data.append("]");
            byte[] body = ("{\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } catch (Exception e) {
            // 桩异常不影响断言主体（向量调用方会降级）
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

    /** 记录型 VectorStore：捕获调用参数并返回预设查询结果（在线后端语义）。 */
    private static final class RecordingVectorStore implements VectorStore {

        private final List<String> upserts = new ArrayList<>();
        private String lastUpsertCollection;
        private long lastUpsertId;
        private double[] lastUpsertVector;
        private String lastQueryCollection;
        private List<Long> lastQueryCandidateIds;
        private int lastQueryTopK;
        private List<IdScore> queryResult = new ArrayList<>();

        @Override
        public String collectionPrefix() {
            return "askdata";
        }

        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public boolean health() {
            return true;
        }

        @Override
        public void ensureCollection(String collection, int dimension) {
            // 测试替身：无需真实集合
        }

        @Override
        public void upsert(String collection, long id, double[] vector) {
            lastUpsertCollection = collection;
            lastUpsertId = id;
            lastUpsertVector = vector;
            upserts.add(collection + "#" + id + "#" + Arrays.toString(vector));
        }

        @Override
        public void delete(String collection, long id) {
            // 测试替身：无需真实删除
        }

        @Override
        public List<IdScore> query(String collection, double[] vector, int topK, List<Long> candidateIds) {
            lastQueryCollection = collection;
            lastQueryCandidateIds = candidateIds;
            lastQueryTopK = topK;
            return queryResult;
        }
    }
}
