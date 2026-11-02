package com.xiongx9527.askdata.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.ai.vector.QdrantVectorStore;
import com.xiongx9527.askdata.ai.vector.VectorStore;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.support.MysqlTestSupport;
import com.xiongx9527.askdata.support.QdrantTestSupport;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度变化语义（<b>真实 MySQL 元数据库 + 真实 Qdrant</b>，无 mock / 无内存替身）：
 *
 * <ol>
 *   <li>写入维度与既有集合不一致时，写入失败且既有向量保留（不静默清空集合）；</li>
 *   <li>显式重建（{@link EmbeddingService#resetCollections()}）后可按新维度写入，旧维度数据作废；</li>
 *   <li>非法入参（空向量 / 非白名单对象类型）不触达后端。</li>
 * </ol>
 */
class EmbeddingDimensionChangeTest {

    private static QdrantVectorStore store;

    private static JdbcTemplate jdbc;

    private EmbeddingService service;

    @BeforeAll
    static void connect() {
        QdrantTestSupport.requireAvailable();
        MysqlTestSupport.requireAvailable();
        jdbc = MysqlTestSupport.createSchema();
        store = QdrantTestSupport.store(QdrantTestSupport.uniquePrefix());
    }

    @AfterAll
    static void cleanup() {
        if (store != null) {
            QdrantTestSupport.dropAll(store, "terminology");
        }
        MysqlTestSupport.dropSchema(jdbc);
    }

    @BeforeEach
    void setUp() {
        store.dropCollection(store.collectionFor("terminology"));
        service = new EmbeddingService(
                new ChatModelRouter(new AiModelRepository(jdbc, new IdGenerator()), new ObjectMapper()),
                new ChatModelClient(new ObjectMapper()), new ObjectMapper());
        service.setVectorStore(store);
    }

    @Test
    void dimensionMismatchRejectsWriteAndKeepsExistingVectors() {
        assertTrue(service.writeEmbedding("terminology", 1L, vector(4)), "首次写入应建立集合");

        assertFalse(service.writeEmbedding("terminology", 2L, vector(8)),
                "维度不一致必须拒绝写入，而不是删集合重建");

        List<VectorStore.IdScore> hits = store.query(store.collectionFor("terminology"), vector(4), 5);
        assertEquals(1, hits.size(), "原维度向量必须保留");
        assertEquals(1L, hits.get(0).id());
    }

    @Test
    void resetCollectionsAllowsRebuildWithNewDimension() {
        assertTrue(service.writeEmbedding("terminology", 1L, vector(4)));

        service.resetCollections();

        assertTrue(service.writeEmbedding("terminology", 2L, vector(8)), "重建后应按新维度写入");
        List<VectorStore.IdScore> hits = store.query(store.collectionFor("terminology"), vector(8), 5);
        assertEquals(1, hits.size(), "重建后旧维度数据作废");
        assertEquals(2L, hits.get(0).id());
    }

    @Test
    void rejectsNullVectorAndUnknownObjectType() {
        // 先建空集合，才能用「集合内无点」证明非法入参没写入（集合不存在时 Qdrant 查询返回 404）
        store.ensureCollection(store.collectionFor("terminology"), 4);

        assertFalse(service.writeEmbedding("terminology", 1L, null));
        assertFalse(service.writeEmbedding("sys_user", 1L, vector(4)));

        assertTrue(store.query(store.collectionFor("terminology"), vector(4), 5).isEmpty(),
                "非法入参不得写入向量");
    }

    private static double[] vector(int dimension) {
        double[] values = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            values[i] = 0.01D;
        }
        return values;
    }
}
