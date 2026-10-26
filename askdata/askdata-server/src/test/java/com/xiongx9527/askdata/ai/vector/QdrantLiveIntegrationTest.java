package com.xiongx9527.askdata.ai.vector;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.ai.LocalEmbeddingModel;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实环境端到端验证：Qdrant(192.168.1.100:6333，gRPC 6334) + TEI bge-m3(9528)。
 *
 * <p>默认 <b>跳过</b>（CI / 无外网环境不可用）；需要验证在线向量库时显式开启：
 * {@code mvn test -Dqdrant.live=true}。地址 / Key / TEI 可由系统属性覆盖。</p>
 *
 * <p>覆盖：集合按向量维度自动创建、ANN 排序、候选 id 过滤、同 id upsert 覆盖、删除；
 * 以及 {@link EmbeddingService} → {@code TerminologyRecallService} 全链路走在线库。</p>
 */
class QdrantLiveIntegrationTest {

    private static final String QDRANT_URL = System.getProperty("qdrant.url", "http://192.168.1.100:6333");
    private static final String QDRANT_API_KEY = System.getProperty("qdrant.apiKey", "Qdrant@123456");
    private static final String TEI_URL = System.getProperty("qdrant.teiUrl", "http://192.168.1.100:9528");

    @org.junit.jupiter.api.BeforeAll
    static void requireLiveFlag() {
        assumeTrue("true".equals(System.getProperty("qdrant.live")),
                "跳过：未指定 -Dqdrant.live=true（需要可达的 Qdrant + TEI）");
    }

    @Test
    void liveStoreRoundTrip() throws Exception {
        Langchain4jQdrantVectorStore store = new Langchain4jQdrantVectorStore(QDRANT_URL, QDRANT_API_KEY, "askdata-it");
        assertTrue(store.health(), "Qdrant should be reachable");

        String collection = store.collectionFor("core_table");
        store.ensureCollection(collection, 1024);

        long idStock = 910001L, idOrder = 910002L, idUser = 910003L;
        double[] vStock = embed("库存盘点表，记录各仓库商品的盘点差异数量");
        double[] vOrder = embed("订单表，保存客户下单的金额与状态");
        double[] vUser = embed("用户表，保存会员注册信息与城市");
        store.upsert(collection, idStock, vStock);
        store.upsert(collection, idOrder, vOrder);
        store.upsert(collection, idUser, vUser);

        double[] q = embed("盘点差异数量是多少");
        List<VectorStore.IdScore> hits = store.query(collection, q, 2, List.of(idStock, idOrder, idUser));
        assertEquals(2, hits.size());
        assertEquals(idStock, hits.get(0).id(), "semantically closest should rank first");
        assertTrue(hits.get(0).score() >= hits.get(1).score());

        List<VectorStore.IdScore> scoped = store.query(collection, q, 10, List.of(idOrder, idUser));
        assertEquals(2, scoped.size());
        assertTrue(scoped.stream().noneMatch(h -> h.id() == idStock), "out-of-candidate ids must not return");

        store.upsert(collection, idStock, vOrder);
        assertEquals(1, store.query(collection, embed("订单金额"), 10, List.of(idStock)).size(),
                "same-id upsert should overwrite, not duplicate");

        store.delete(collection, idStock);
        store.delete(collection, idOrder);
        store.delete(collection, idUser);
        assertTrue(store.query(collection, q, 10, List.of(idStock, idOrder, idUser)).isEmpty(),
                "nothing should hit after delete");
    }

    @Test
    void liveEmbeddingServiceRoutesThroughQdrant() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:qdrant_live_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection c = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(c);
        }
        IdGenerator ids = new IdGenerator();
        ObjectMapper om = new ObjectMapper();
        new AiModelRepository(jdbc, ids).insert(ids.nextId(), "", TEI_URL, 1, 0,
                "tei-bge-m3", 1, "bge-m3", false, "[]", 1, System.currentTimeMillis());

        EmbeddingService embeddingService = new EmbeddingService(
                new LocalEmbeddingModel(Files.createTempDirectory("no-model").toString(), 8),
                new ChatModelRouter(new AiModelRepository(jdbc, ids), om),
                new ChatModelClient(om), jdbc, om);
        assertTrue(embeddingService.available(), "TEI remote embedding should be usable");
        embeddingService.setVectorStore(new Langchain4jQdrantVectorStore(QDRANT_URL, QDRANT_API_KEY, "askdata-it"));
        assertTrue(embeddingService.isVectorStoreOnline(), "should switch to online store");

        long t1 = 920001L, t2 = 920002L, t3 = 920003L;
        insertTerm(jdbc, t1, "库存盘点表");
        insertTerm(jdbc, t2, "订单表");
        insertTerm(jdbc, t3, "用户表");

        TerminologyRecallService recallService = new TerminologyRecallService(embeddingService, jdbc);
        recallService.saveEmbeddings(List.of(t1, t2, t3));

        Map<Long, Double> scores =
                embeddingService.vectorScores("terminology", "盘点差异数量是多少", List.of(t1, t2, t3));
        assertFalse(scores.isEmpty(), "online store should return similarities");
        double best = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0D);
        assertEquals(best, scores.get(t1), 1e-6, "stock term should score highest");

        List<Long> hit = recallService.recall("盘点差异数量是多少", 1L, null, null);
        assertFalse(hit.isEmpty(), "business recall should hit");
        assertEquals(t1, hit.get(0), "business recall should rank the stock term first");

        Langchain4jQdrantVectorStore cleanup =
                new Langchain4jQdrantVectorStore(QDRANT_URL, QDRANT_API_KEY, "askdata-it");
        cleanup.delete("askdata-it-terminology", t1);
        cleanup.delete("askdata-it-terminology", t2);
        cleanup.delete("askdata-it-terminology", t3);
    }

    private static void insertTerm(JdbcTemplate jdbc, long id, String word) {
        jdbc.update("INSERT INTO terminology (id, oid, pid, create_time, word, description, embedding, enabled) "
                + "VALUES (?, 1, NULL, ?, ?, '', NULL, TRUE)", id, System.currentTimeMillis(), word);
    }

    private static double[] embed(String text) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"input\":[\"" + text + "\"],\"model\":\"bge-m3\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TEI_URL + "/v1/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        String resp = client.send(req, HttpResponse.BodyHandlers.ofString()).body();
        JsonNode arr = new ObjectMapper().readTree(resp).get("data").get(0).get("embedding");
        double[] v = new double[arr.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = arr.get(i).asDouble();
        }
        return v;
    }
}
