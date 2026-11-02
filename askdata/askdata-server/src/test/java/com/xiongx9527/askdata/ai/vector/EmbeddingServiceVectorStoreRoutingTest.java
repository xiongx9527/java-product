package com.xiongx9527.askdata.ai.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Lists;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.support.EmbeddingModelTestSupport;
import com.xiongx9527.askdata.support.MysqlTestSupport;
import com.xiongx9527.askdata.support.QdrantTestSupport;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 向量写入与召回端到端（<b>真实 MySQL + 真实 Qdrant + 真实 bge-m3</b>，无 mock / 无 HTTP 桩）：
 *
 * <ol>
 *   <li>写入委托给在线后端，集合名 = {prefix}-{object-type}，DB 向量列不再镜像；</li>
 *   <li>召回命中真实 ANN 结果，映射回候选行并按阈值 / topCount 截断；</li>
 *   <li>无可用向量模型时召回返回空（调用方降级为词法），不抛错；</li>
 *   <li>非白名单表 / 空向量被拒，不触达后端。</li>
 * </ol>
 */
class EmbeddingServiceVectorStoreRoutingTest {

    private static QdrantVectorStore store;

    private static JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EmbeddingService embeddingService;

    @BeforeAll
    static void connect() {
        QdrantTestSupport.requireAvailable();
        MysqlTestSupport.requireAvailable();
        EmbeddingModelTestSupport.requireAvailable();
        jdbc = MysqlTestSupport.createSchema();
        EmbeddingModelTestSupport.registerModel(jdbc);
        store = QdrantTestSupport.store(QdrantTestSupport.uniquePrefix());
    }

    @AfterAll
    static void cleanup() {
        if (store != null) {
            QdrantTestSupport.dropAll(store, "terminology", "core_table");
        }
        MysqlTestSupport.dropSchema(jdbc);
    }

    @BeforeEach
    void setUp() {
        store.dropCollection(store.collectionFor("terminology"));
        embeddingService = new EmbeddingService(
                new ChatModelRouter(new AiModelRepository(jdbc, new IdGenerator()), objectMapper),
                new ChatModelClient(objectMapper), objectMapper);
        embeddingService.setVectorStore(store);
    }

    @Test
    void writeEmbeddingUpsertsToRealBackendWithCollectionName() {
        double[] vector = {0.25D, 0.5D, 0.75D};
        assertTrue(embeddingService.writeEmbedding("terminology", 1L, vector));

        List<VectorStore.IdScore> hits = store.query(store.collectionFor("terminology"), vector, 5);
        assertEquals(1, hits.size(), "向量应写入 {prefix}-terminology 集合");
        assertEquals(1L, hits.get(0).id());
        // 在线库是唯一存储：DB 向量列不再镜像写入
        assertNull(jdbc.queryForObject("SELECT embedding FROM terminology WHERE id = 1", String.class));
    }

    @Test
    void writeEmbeddingRejectsNullVectorAndUnknownTable() {
        // 先建空集合，才能用「集合内无点」证明非法入参没触达后端（集合不存在时 Qdrant 查询返回 404）
        store.ensureCollection(store.collectionFor("terminology"), 2);

        assertFalse(embeddingService.writeEmbedding("terminology", 1L, null));
        assertFalse(embeddingService.writeEmbedding("sys_user", 1L, new double[] {1D, 0D}));

        assertTrue(store.query(store.collectionFor("terminology"), new double[] {1D, 0D}, 5).isEmpty(),
                "非法入参不得触达后端");
    }

    @Test
    void deleteEmbeddingRemovesPointFromRealBackend() {
        double[] vector = {0.1D, 0.2D, 0.3D};
        assertTrue(embeddingService.writeEmbedding("terminology", 42L, vector));
        assertEquals(1, store.query(store.collectionFor("terminology"), vector, 5).size());

        embeddingService.deleteEmbedding("terminology", 42L);

        assertTrue(store.query(store.collectionFor("terminology"), vector, 5).isEmpty(),
                "业务行删除后必须同步清理 point，避免孤儿点");
    }

    @Test
    void recallByVectorRanksRealEmbeddingHits() {
        insertTerminology(1L, "订单表：记录订单编号与金额");
        insertTerminology(2L, "用户表：记录用户昵称与手机号");
        insertTerminology(3L, "商品表：记录商品名称与价格");

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (long id = 1L; id <= 3L; id++) {
            double[] vector = embeddingService.embed(word(id));
            assertNotNull(vector, "真实 bge-m3 应返回向量");
            assertTrue(embeddingService.writeEmbedding("terminology", id, vector));
            candidates.add(row(id, word(id)));
        }

        List<Map<String, Object>> hits = embeddingService.recallByVector(
                "terminology", candidates, word(1L), 0D, 2);

        assertEquals(2, hits.size(), "topCount 截断生效");
        assertEquals(1L, Rows.lng(hits.get(0), "id", 0L), "与查询文本完全一致的行应排第一");
        assertTrue(((Number) hits.get(0).get("similarity")).doubleValue() > 0.99D, "同文本余弦应接近 1");
        assertEquals(word(1L), hits.get(0).get("word"), "候选行字段应保留");
    }

    @Test
    void recallByVectorReturnsEmptyWithoutEmbeddingModel() {
        JdbcTemplate emptySchema = MysqlTestSupport.createSchema();
        try {
            EmbeddingService noModel = new EmbeddingService(
                    new ChatModelRouter(new AiModelRepository(emptySchema, new IdGenerator()), objectMapper),
                    new ChatModelClient(objectMapper), objectMapper);
            noModel.setVectorStore(store);
            assertTrue(noModel.recallByVector("terminology", Lists.of(row(1L, "订单")), "查询", 0D, 5).isEmpty(),
                    "无可用 embedding 模型时应返回空（调用方降级为词法）");
        } finally {
            MysqlTestSupport.dropSchema(emptySchema);
        }
    }

    @Test
    void vectorScoresReturnsRealSimilarityByCandidateId() {
        insertTerminology(5L, "库存表：记录仓库与库存数量");
        insertTerminology(6L, "供应商表：记录供应商名称与联系方式");

        double[] five = embeddingService.embed(word(5L));
        double[] six = embeddingService.embed(word(6L));
        assertNotNull(five);
        assertNotNull(six);
        assertTrue(embeddingService.writeEmbedding("terminology", 5L, five));
        assertTrue(embeddingService.writeEmbedding("terminology", 6L, six));

        Map<Long, Double> scores = embeddingService.vectorScores("terminology", word(5L), Lists.of(5L, 6L, 7L));

        assertNotNull(scores.get(5L), "同文本应命中");
        assertTrue(scores.get(5L) > 0.99D, "同文本相似度应接近 1，实际 " + scores.get(5L));
        assertNull(scores.get(7L), "未写入向量的候选不应有分数");
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

    private static String word(long id) {
        if (id == 1L) {
            return "订单表：记录订单编号与金额";
        }
        if (id == 2L) {
            return "用户表：记录用户昵称与手机号";
        }
        if (id == 3L) {
            return "商品表：记录商品名称与价格";
        }
        if (id == 5L) {
            return "库存表：记录仓库与库存数量";
        }
        return "供应商表：记录供应商名称与联系方式";
    }

    private void insertTerminology(long id, String word) {
        jdbc.update("INSERT INTO terminology (id, oid, pid, create_time, word, description, embedding, enabled) "
                + "VALUES (?, 1, NULL, ?, ?, '', NULL, TRUE)", id, System.currentTimeMillis(), word);
    }
}
