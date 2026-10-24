package com.xiongx9527.askdata.ai.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证抽象基类在「后端忽略服务端 filter」场景下的行为（Weaviate / pgvector 这类后端的真实情形）：
 * 1) 强制 UUID 的 point id（toPointId 派生）；2) row_id 写入 metadata；3) 命中 id 由 metadata.row_id 解析；
 * 4) 候选 id 过滤在客户端兜底（即使底层 store 的 search 忽略 filter 也仅返回候选内结果）。
 */
class AbstractLangchain4jVectorStoreClientFilterTest {

    /** 故意忽略 request.filter() 的伪存储，模拟 Weaviate 的行为。 */
    static class IgnorantFakeStore implements EmbeddingStore<TextSegment> {
        final Map<String, FakeObj> data = new ConcurrentHashMap<>();
        @Override public String add(Embedding e) { return null; }
        @Override public void add(String id, Embedding e) {}
        @Override public String add(Embedding e, TextSegment t) { return null; }
        @Override public List<String> addAll(List<Embedding> e) { return null; }
        @Override public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
            for (int i = 0; i < ids.size(); i++) {
                FakeObj o = new FakeObj(embeddings.get(i), embedded == null ? null : embedded.get(i));
                // 真实后端会把写入时的 point id 作为 embeddingId 回填；这里同样存下，便于 search 回放。
                o.id = ids.get(i);
                data.put(ids.get(i), o);
            }
        }
        @Override public void remove(String id) { data.remove(id); }
        @Override public void removeAll(java.util.Collection<String> ids) { ids.forEach(data::remove); }
        @Override public void removeAll() { data.clear(); }
        @Override public void removeAll(dev.langchain4j.store.embedding.filter.Filter f) { data.clear(); }
        @Override public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest req) {
            // 忽略 req.filter()，把所有对象都返回（Weaviate 的真实行为）
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            for (FakeObj o : data.values()) {
                matches.add(new EmbeddingMatch<>(1.0, o.id, o.embedding, o.segment));
            }
            return new EmbeddingSearchResult<>(matches);
        }
        String lastUpsertedRowId; // 记录最近一次写入的 row_id metadata，供断言
        void captureRowId() {
            for (FakeObj o : data.values()) {
                if (o.segment != null && o.segment.metadata() != null) {
                    lastUpsertedRowId = o.segment.metadata().getString("row_id");
                }
            }
        }
    }
    static class FakeObj { final Embedding embedding; final TextSegment segment; String id;
        FakeObj(Embedding e, TextSegment s) { embedding = e; segment = s; } }

    static class UuidStore extends AbstractLangchain4jVectorStore {
        final IgnorantFakeStore fake = new IgnorantFakeStore();
        UuidStore() { super("askdata"); }
        @Override protected EmbeddingStore<TextSegment> createStore(String c, int d) { return fake; }
        @Override public boolean health() { return true; }
        @Override protected String toPointId(long id) {
            return UUID.nameUUIDFromBytes(("" + id).getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private static double[] v(int seed) { java.util.Random r = new java.util.Random(seed); double[] a = new double[8];
        for (int i = 0; i < 8; i++) a[i] = r.nextDouble(); return a; }

    @Test
    void upsertStoresRowIdAndUsesUuidPointId() {
        UuidStore store = new UuidStore();
        store.upsert(store.collectionFor("core_table"), 910001L, v(1));
        store.fake.captureRowId();
        assertEquals("910001", store.fake.lastUpsertedRowId, "row_id metadata 应等于记录 id");
        // point id 必须为 UUID（非数值串），且可由此回推稳定值
        String pid = store.toPointId(910001L);
        assertDoesNotThrow(() -> UUID.fromString(pid), "point id 必须是合法 UUID");
    }

    @Test
    void clientSideCandidateFilterEvenWhenStoreIgnoresFilter() {
        UuidStore store = new UuidStore();
        String col = store.collectionFor("core_table");
        long idIn = 910001L, idOut = 910002L;
        store.upsert(col, idIn, v(1));
        store.upsert(col, idOut, v(2));
        // 伪存储 search 忽略 filter，会返回两条；基类必须在客户端按候选裁剪
        List<VectorStore.IdScore> hits = store.query(col, v(1), 10, java.util.List.of(idIn));
        assertEquals(1, hits.size(), "仅应返回候选内的命中");
        assertEquals(idIn, hits.get(0).id());
        assertTrue(hits.stream().noneMatch(h -> h.id() == idOut), "候选外 id 必须被剔除");
    }
}
