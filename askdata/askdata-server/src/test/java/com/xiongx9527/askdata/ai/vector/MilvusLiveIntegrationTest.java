package com.xiongx9527.askdata.ai.vector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实环境端到端验证：Milvus（gRPC host/port 由系统属性指定）+ 任意可用 embedding 模型。
 *
 * <p>默认 <b>跳过</b>（CI / 无 Milvus 环境）；需要验证在线向量库时显式开启：
 * {@code mvn test -Dmilvus.live=true}，并通过系统属性覆盖地址 / token / 维度：</p>
 * <ul>
 *   <li>{@code milvus.host} / {@code milvus.port}（缺省 127.0.0.1 / 19530）</li>
 *   <li>{@code milvus.token}（开启鉴权时必填）</li>
 *   <li>{@code milvus.dimension}（集合维度，需与 embedding 模型一致，缺省 1024）</li>
 *   <li>{@code milvus.healthUrl}（RESTful 健康检查基址，如 http://milvus:9091；留空则乐观视为可用）</li>
 * </ul>
 *
 * <p>覆盖：集合首次写入自动创建、ANN 排序、候选 id 过滤、同 id upsert 覆盖、删除。
 * 逻辑与 {@link QdrantLiveIntegrationTest} 共用 {@link AbstractLangchain4jVectorStore}，此处仅验证 Milvus 专属客户端。</p>
 */
class MilvusLiveIntegrationTest {

    private static final String HOST = System.getProperty("milvus.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("milvus.port", "19530"));
    private static final String TOKEN = System.getProperty("milvus.token", "");
    private static final int DIM = Integer.parseInt(System.getProperty("milvus.dimension", "1024"));
    private static final String HEALTH_URL = System.getProperty("milvus.healthUrl", "");

    @org.junit.jupiter.api.BeforeAll
    static void requireLiveFlag() {
        assumeTrue("true".equals(System.getProperty("milvus.live")),
                "跳过：未指定 -Dmilvus.live=true（需要可达的 Milvus）");
    }

    private Langchain4jMilvusVectorStore store() {
        Langchain4jMilvusVectorStore s = new Langchain4jMilvusVectorStore(
                HOST, PORT, false, TOKEN, "default", "askdata-it", null, null, null, HEALTH_URL);
        assertTrue(s.health(), "Milvus should be reachable");
        return s;
    }

    @Test
    void liveStoreRoundTrip() {
        Langchain4jMilvusVectorStore store = store();
        String collection = store.collectionFor("core_table");
        store.ensureCollection(collection, DIM);

        long idStock = 910001L, idOrder = 910002L, idUser = 910003L;
        double[] vStock = rand(DIM, 1L), vOrder = rand(DIM, 2L), vUser = rand(DIM, 3L);
        store.upsert(collection, idStock, vStock);
        store.upsert(collection, idOrder, vOrder);
        store.upsert(collection, idUser, vUser);

        double[] q = rand(DIM, 1L); // 与 vStock 同种子，应最相似
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
        for (int i = 0; i < dim; i++) {
            v[i] = r.nextGaussian();
            norm += v[i] * v[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < dim; i++) {
            v[i] /= norm;
        }
        return v;
    }
}
