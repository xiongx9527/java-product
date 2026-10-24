package com.xiongx9527.askdata.ai.vector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实环境端到端验证：Weaviate（scheme/host/port 由系统属性指定）+ 任意可用 embedding 模型。
 *
 * <p>默认 <b>跳过</b>；需要验证时显式开启：{@code mvn test -Dweaviate.live=true}，并可覆盖
 * {@code weaviate.scheme/host/port/apiKey/consistency/dimension}。</p>
 *
 * <p>覆盖：类自动创建、ANN 排序、候选 id 过滤（注意 Weaviate search 忽略服务端 filter，由抽象基类客户端兜底）、
 * 同 id upsert 覆盖、删除。逻辑与 {@link QdrantLiveIntegrationTest} 共用 {@link AbstractLangchain4jVectorStore}。</p>
 */
class WeaviateLiveIntegrationTest {

    private static final String SCHEME = System.getProperty("weaviate.scheme", "http");
    private static final String HOST = System.getProperty("weaviate.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("weaviate.port", "8080"));
    private static final String API_KEY = System.getProperty("weaviate.apiKey", "");
    private static final String CONSISTENCY = System.getProperty("weaviate.consistency", "QUORUM");
    private static final int DIM = Integer.parseInt(System.getProperty("weaviate.dimension", "1024"));

    @org.junit.jupiter.api.BeforeAll
    static void requireLiveFlag() {
        assumeTrue("true".equals(System.getProperty("weaviate.live")),
                "跳过：未指定 -Dweaviate.live=true（需要可达的 Weaviate）");
    }

    private Langchain4jWeaviateVectorStore store() {
        Langchain4jWeaviateVectorStore s = new Langchain4jWeaviateVectorStore(
                SCHEME, HOST, PORT, API_KEY, "askdata-it", CONSISTENCY);
        assertTrue(s.health(), "Weaviate should be reachable");
        return s;
    }

    @Test
    void liveStoreRoundTrip() {
        Langchain4jWeaviateVectorStore store = store();
        String collection = store.collectionFor("core_table");
        store.ensureCollection(collection, DIM);

        long idStock = 910001L, idOrder = 910002L, idUser = 910003L;
        double[] vStock = rand(DIM, 1L), vOrder = rand(DIM, 2L), vUser = rand(DIM, 3L);
        store.upsert(collection, idStock, vStock);
        store.upsert(collection, idOrder, vOrder);
        store.upsert(collection, idUser, vUser);

        double[] q = rand(DIM, 1L);
        List<VectorStore.IdScore> hits = store.query(collection, q, 2, List.of(idStock, idOrder, idUser));
        assertEquals(2, hits.size());
        assertEquals(idStock, hits.get(0).id(), "closest (same seed) should rank first");

        List<VectorStore.IdScore> scoped = store.query(collection, q, 10, List.of(idOrder, idUser));
        assertEquals(2, scoped.size());
        assertTrue(scoped.stream().noneMatch(h -> h.id() == idStock), "out-of-candidate ids must not return");

        store.upsert(collection, idStock, vOrder);
        assertEquals(1, store.query(collection, rand(DIM, 2L), 10, List.of(idStock)).size(),
                "same-id upsert should overwrite, not duplicate");

        store.delete(collection, idStock);
        store.delete(collection, idOrder);
        store.delete(collection, idUser);
        assertTrue(store.query(collection, q, 10, List.of(idStock, idOrder, idUser)).isEmpty(),
                "nothing should hit after delete");
    }

    private static double[] rand(int dim, long seed) {
        java.util.Random r = new java.util.Random(seed);
        double[] v = new double[dim];
        double norm = 0d;
        for (int i = 0; i < dim; i++) { v[i] = r.nextGaussian(); norm += v[i] * v[i]; }
        norm = Math.sqrt(norm);
        for (int i = 0; i < dim; i++) { v[i] /= norm; }
        return v;
    }
}
