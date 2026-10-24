package com.xiongx9527.askdata.ai.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * langchain4j 在线向量库后端的共用逻辑。
 *
 * <p>各类后端（Qdrant / Milvus / ...）都实现 langchain4j 的
 * {@link EmbeddingStore}{@code <TextSegment>}，因此 upsert / query / delete / 命中 id 解析等
 * 与后端无关的逻辑在本抽象类统一实现，子类只需提供：</p>
 * <ul>
 *   <li>{@link #createStore(String, int)}：按集合名与维度构造底层存储（各后端客户端不同）；</li>
 *   <li>{@link #health()}：连通性探测（各后端端口/协议不同）；</li>
 *   <li>可选 {@link #ensureCollection}：需要显式建集合的后端（如 Qdrant）覆写，其余依赖首次写入自动创建。</li>
 * </ul>
 *
 * <p>向量读写、候选 id 过滤（metadata {@code row_id in [...]}）、命中 id 解析（优先 embeddingId，
 * 回退 metadata row_id）均在此处理，新增后端时子类不需要重复这些代码。</p>
 */
public abstract class AbstractLangchain4jVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(AbstractLangchain4jVectorStore.class);

    /** 各集合 -> 底层 langchain4j 存储（按集合缓存，维度取自首次调用）。 */
    private final Map<String, EmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();
    /** 各集合最近一次使用的向量维度，供 delete 在无维度信息时回退。 */
    private final Map<String, Integer> knownDim = new ConcurrentHashMap<>();

    private final String collectionPrefix;

    protected AbstractLangchain4jVectorStore(String collectionPrefix) {
        this.collectionPrefix = (collectionPrefix == null) ? "" : collectionPrefix;
    }

    @Override
    public String collectionPrefix() {
        return collectionPrefix;
    }

    @Override
    public boolean isOnline() {
        return true;
    }

    /** 子类构造底层存储：按集合名 + 向量维度创建（各后端客户端不同）。 */
    protected abstract EmbeddingStore<TextSegment> createStore(String collection, int dimension);

    /** 连通性探测：各后端端口/协议不同，由子类实现。 */
    @Override
    public abstract boolean health();

    /** 返回（并缓存）集合对应的底层存储；首次按维度创建。 */
    protected EmbeddingStore<TextSegment> storeFor(String collection, int dimension) {
        if (dimension > 0) {
            knownDim.put(collection, dimension);
        }
        final int dim = dimension > 0 ? dimension : knownDim.getOrDefault(collection, 0);
        return stores.computeIfAbsent(collection, c -> createStore(c, dim));
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        // 默认：确保存储实例可用即可；需要显式建集合的后端（Qdrant）应覆写此方法。
        storeFor(collection, dimension);
    }

    @Override
    public void upsert(String collection, long id, double[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        try {
            EmbeddingStore<TextSegment> store = storeFor(collection, vector.length);
            String idStr = String.valueOf(id);
            // TextSegment 文本不能为空；用 id 字符串占位，真实语义信息存于向量与 metadata。
            TextSegment segment = TextSegment.from(idStr, Metadata.from(Map.of("row_id", idStr)));
            store.addAll(java.util.List.of(idStr), java.util.List.of(toEmbedding(vector)), java.util.List.of(segment));
        } catch (Exception e) {
            log.info("在线向量 upsert 失败 {}/{}（回落主链路）: {}", collection, id, e.getMessage());
        }
    }

    @Override
    public void delete(String collection, long id) {
        try {
            EmbeddingStore<TextSegment> store = storeFor(collection, 0);
            store.remove(String.valueOf(id));
        } catch (Exception e) {
            log.info("在线向量 delete 失败 {}/{}（回落主链路）: {}", collection, id, e.getMessage());
        }
    }

    @Override
    public List<IdScore> query(String collection, double[] vector, int topK, List<Long> candidateIds) {
        List<IdScore> hits = new ArrayList<>();
        if (vector == null) {
            return hits;
        }
        try {
            EmbeddingStore<TextSegment> store = storeFor(collection, vector.length);
            EmbeddingSearchRequest.EmbeddingSearchRequestBuilder builder = EmbeddingSearchRequest.builder()
                    .queryEmbedding(toEmbedding(vector))
                    .maxResults(topK > 0 ? topK : 10)
                    // 显式 0.0：EmbeddingSearchRequest 缺省 minScore=-1.0 会被 Qdrant 拒绝。
                    .minScore(0.0);
            if (candidateIds != null && !candidateIds.isEmpty()) {
                List<String> strs = new ArrayList<>();
                for (Long c : candidateIds) {
                    strs.add(String.valueOf(c));
                }
                // 服务端按 metadata.row_id 过滤候选，避免跨工作空间/跨对象类型泄漏。
                builder.filter(new IsIn("row_id", strs));
            }
            EmbeddingSearchResult<TextSegment> result = store.search(builder.build());
            for (EmbeddingMatch<TextSegment> m : result.matches()) {
                Long id = resolveId(m);
                if (id == null) {
                    continue;
                }
                Double score = m.score();
                hits.add(new IdScore(id, score == null ? 0d : score));
            }
        } catch (Exception e) {
            log.info("在线向量 query 失败 {}/{}（回落主链路）: {}", collection, vector.length, e.getMessage());
        }
        return hits;
    }

    /** 从命中解析记录 id：优先用 embeddingId（即写入时的 point id），回退 metadata.row_id。 */
    private static Long resolveId(EmbeddingMatch<TextSegment> m) {
        String idStr = m.embeddingId();
        if (idStr != null) {
            try {
                return Long.parseLong(idStr);
            } catch (NumberFormatException ignore) {
                // 部分后端可能返回非数值 id，继续尝试 metadata
            }
        }
        TextSegment seg = m.embedded();
        if (seg != null && seg.metadata() != null) {
            String rid = seg.metadata().getString("row_id");
            if (rid != null) {
                try {
                    return Long.parseLong(rid);
                } catch (NumberFormatException ignore) {
                    // ignore
                }
            }
        }
        return null;
    }

    /** double[] -> langchain4j Embedding（内部以 float 存储）。 */
    private static Embedding toEmbedding(double[] vector) {
        float[] f = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            f[i] = (float) vector[i];
        }
        return Embedding.from(f);
    }
}
