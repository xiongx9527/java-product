package com.xiongx9527.askdata.ai.vector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实环境端到端验证：Elasticsearch（url/apiKey/username/password 由系统属性指定）。
 *
 * <p>默认 <b>跳过</b>；需要验证时显式开启：{@code mvn test -Delasticsearch.live=true}，并可覆盖
 * {@code elasticsearch.url/apiKey/username/password/dimension}。</p>
 *
 * <p>覆盖：索引自动创建（dense_vector 映射）、kNN 排序、候选 id 过滤、同 id upsert 覆盖、删除。
 * 共用 {@link AbstractLangchain4jVectorStore}。</p>
 */
class ElasticsearchLiveIntegrationTest {

    private static final String URL = System.getProperty("elasticsearch.url", "http://127.0.0.1:9200");
    private static final String API_KEY = System.getProperty("elasticsearch.apiKey", "");
    private static final String USERNAME = System.getProperty("elasticsearch.username", "");
    private static final String PASSWORD = System.getProperty("elasticsearch.password", "");
    private static final int DIM = Integer.parseInt(System.getProperty("elasticsearch.dimension", "768"));

    @org.junit.jupiter.api.BeforeAll
    static void requireLiveFlag() {
        assumeTrue("true".equals(System.getProperty("elasticsearch.live")),
                "跳过：未指定 -Delasticsearch.live=true（需要可达的 Elasticsearch）");
    }

    private Langchain4jElasticsearchVectorStore store() {
        Langchain4jElasticsearchVectorStore s = new Langchain4jElasticsearchVectorStore(
                URL, API_KEY, USERNAME, PASSWORD, "askdata-it", "WaitFor");
        assertTrue(s.health(), "Elasticsearch should be reachable");
        return s;
    }

    @Test
    void liveStoreRoundTrip() {
        Langchain4jElasticsearchVectorStore store = store();
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
