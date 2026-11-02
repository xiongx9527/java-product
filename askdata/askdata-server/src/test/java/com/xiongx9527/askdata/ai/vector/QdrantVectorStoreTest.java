package com.xiongx9527.askdata.ai.vector;

import com.xiongx9527.askdata.support.QdrantTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qdrant 后端契约（<b>打真实 Qdrant</b>，无 stub）：建集合 / 写入 / 候选过滤检索 / 删除 /
 * 维度不一致保护 / 集合删除。
 *
 * <p>桩实现测不出真实行为：{@code has_id} 过滤语法、响应字段结构、集合维度的持久化
 * 都只有真库能验证。后端不可达时整类跳过（见 {@link QdrantTestSupport}）。</p>
 */
class QdrantVectorStoreTest {

    private static QdrantVectorStore store;

    private static String prefix;

    private static final String COLLECTION_SUFFIX = "terminology";

    @BeforeAll
    static void connect() {
        QdrantTestSupport.requireAvailable();
        prefix = QdrantTestSupport.uniquePrefix();
        store = QdrantTestSupport.store(prefix);
    }

    @AfterAll
    static void cleanup() {
        if (store != null) {
            QdrantTestSupport.dropAll(store, COLLECTION_SUFFIX);
        }
    }

    @BeforeEach
    void freshCollection() {
        store.dropCollection(collection());
    }

    private static String collection() {
        return store.collectionFor(COLLECTION_SUFFIX);
    }

    @Test
    void healthReportsRealBackend() {
        assertTrue(store.health(), "真实 Qdrant 应可达");
    }

    @Test
    void ensureCollectionCreatesOnceAndIsIdempotent() {
        store.ensureCollection(collection(), 4);
        // 同维度重复确认不得报错，也不得重建（重建会清空数据）
        store.upsert(collection(), 1L, new double[] {1D, 0D, 0D, 0D});
        store.ensureCollection(collection(), 4);
        assertEquals(1, store.query(collection(), new double[] {1D, 0D, 0D, 0D}, 5).size(),
                "同维度确认后既有向量必须保留");
    }

    @Test
    void upsertThenQueryAppliesCandidateFilter() {
        store.ensureCollection(collection(), 3);
        store.upsert(collection(), 11L, new double[] {1D, 0D, 0D});
        store.upsert(collection(), 22L, new double[] {0D, 1D, 0D});
        store.upsert(collection(), 33L, new double[] {0D, 0D, 1D});

        List<VectorStore.IdScore> all = store.query(collection(), new double[] {1D, 0D, 0D}, 10);
        assertEquals(3, all.size(), "无限定时应召回全部点");
        assertEquals(11L, all.get(0).id(), "与查询向量同向的点应排第一");
        assertTrue(all.get(0).score() > 0.99D, "余弦相似度应接近 1，实际 " + all.get(0).score());

        List<Long> candidates = new ArrayList<>();
        candidates.add(22L);
        candidates.add(33L);
        List<VectorStore.IdScore> filtered = store.query(collection(), new double[] {1D, 0D, 0D}, 10, candidates);
        assertEquals(2, filtered.size(), "has_id 过滤后只剩候选点");
        for (VectorStore.IdScore hit : filtered) {
            assertTrue(hit.id() == 22L || hit.id() == 33L, "非候选点不得返回: " + hit.id());
        }
    }

    @Test
    void deleteRemovesPoint() {
        store.ensureCollection(collection(), 2);
        store.upsert(collection(), 7L, new double[] {1D, 0D});
        assertEquals(1, store.query(collection(), new double[] {1D, 0D}, 5).size());

        store.delete(collection(), 7L);
        assertTrue(store.query(collection(), new double[] {1D, 0D}, 5).isEmpty(), "删除后不得再召回");
    }

    @Test
    void dimensionMismatchIsRejectedWithoutDroppingData() {
        store.ensureCollection(collection(), 2);
        store.upsert(collection(), 9L, new double[] {1D, 0D});

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> store.ensureCollection(collection(), 3), "维度不一致必须显式失败，不能静默删集合");
        assertTrue(error.getMessage().contains("维度不一致"), error.getMessage());
        assertEquals(1, store.query(collection(), new double[] {1D, 0D}, 5).size(),
                "拒绝写入后原集合与向量必须保留");
    }

    @Test
    void dropCollectionAllowsRebuildWithNewDimension() {
        store.ensureCollection(collection(), 2);
        store.upsert(collection(), 5L, new double[] {1D, 0D});

        store.dropCollection(collection());
        store.ensureCollection(collection(), 3);
        assertTrue(store.query(collection(), new double[] {1D, 0D, 0D}, 5).isEmpty(),
                "重建后旧维度数据必须清空");
        store.upsert(collection(), 6L, new double[] {1D, 0D, 0D});
        assertEquals(6L, store.query(collection(), new double[] {1D, 0D, 0D}, 5).get(0).id());
        assertFalse(prefix.isEmpty());
    }
}
