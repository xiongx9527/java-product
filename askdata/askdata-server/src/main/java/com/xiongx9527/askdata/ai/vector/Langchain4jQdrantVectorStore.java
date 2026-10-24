package com.xiongx9527.askdata.ai.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线向量库实现：基于 langchain4j 的 {@link QdrantEmbeddingStore}（复用 langchain4j 对 Qdrant 的封装，不重复造轮子）。
 *
 * <p>作为启用时的唯一存储后端，承担四类对象的向量持久化与 ANN 检索；向量由现有 embedding 模型产出。</p>
 *
 * <p><b>容错</b>：任何连接 / 解析失败均不抛异常，仅记日志并回落（调用方退化为词法召回），
 * 保证问数主链路可用（验收 A5）。集合在首次 {@link #ensureCollection} 时按向量维度 + 余弦距离创建。</p>
 *
 * <p>集合名约定 {@code {prefix}-{object-type}}；point id 取记录 id（数值串），payload 存 {@code row_id} 用于候选过滤。</p>
 */
public class Langchain4jQdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(Langchain4jQdrantVectorStore.class);

    /** Qdrant gRPC 默认端口（与 REST 端口 6333 相互独立）。 */
    private static final int DEFAULT_GRPC_PORT = 6334;

    private final String host;
    /** REST 端口（用于健康检查）。 */
    private final int port;
    /** gRPC 端口（用于向量读写；Qdrant 默认与 REST 分离）。 */
    private final int grpcPort;
    private final boolean tls;
    private final String apiKey;
    private final String collectionPrefix;

    private final QdrantClient adminClient;
    private final ConcurrentHashMap<String, QdrantEmbeddingStore> stores = new ConcurrentHashMap<>();

    /**
     * @param baseUrl           Qdrant REST 基址（如 {@code http://qdrant:6333}），用于健康检查
     * @param apiKey            API Key（Qdrant 开启鉴权时必填；REST 与 gRPC 同一把钥匙）
     * @param collectionPrefix  集合名前缀
     * @param grpcPort          gRPC 端口；{@code <= 0} 时用缺省 6334
     */
    public Langchain4jQdrantVectorStore(String baseUrl, String apiKey, String collectionPrefix, int grpcPort) {
        String u = (baseUrl == null ? "" : baseUrl.trim());
        this.tls = u.startsWith("https");
        String body = u.replaceFirst("^https?://", "");
        int slash = body.indexOf('/');
        if (slash >= 0) {
            body = body.substring(0, slash);
        }
        int colon = body.indexOf(':');
        if (colon >= 0) {
            this.host = body.substring(0, colon);
            int p;
            try {
                p = Integer.parseInt(body.substring(colon + 1));
            } catch (NumberFormatException e) {
                p = tls ? 443 : 6333;
            }
            this.port = p;
        } else {
            this.host = body;
            this.port = tls ? 443 : 6333;
        }
        this.grpcPort = grpcPort > 0 ? grpcPort : DEFAULT_GRPC_PORT;
        this.apiKey = (apiKey == null || apiKey.trim().isEmpty()) ? null : apiKey.trim();
        this.collectionPrefix = (collectionPrefix == null || collectionPrefix.trim().isEmpty())
                ? "askdata" : collectionPrefix.trim();
        this.adminClient = new QdrantClient(QdrantGrpcClient.newBuilder(host, this.grpcPort, tls)
                .withApiKey(this.apiKey).build());
        log.info("Qdrant 后端初始化：REST {}:{} / gRPC {}:{}", host, port, host, this.grpcPort);
    }

    /** 便捷构造：gRPC 端口用缺省 6334。 */
    public Langchain4jQdrantVectorStore(String baseUrl, String apiKey, String collectionPrefix) {
        this(baseUrl, apiKey, collectionPrefix, DEFAULT_GRPC_PORT);
    }

    private QdrantEmbeddingStore storeFor(String collection) {
        return stores.computeIfAbsent(collection,
                c -> new QdrantEmbeddingStore(c, host, grpcPort, tls, "text", apiKey));
    }

    @Override
    public String collectionPrefix() {
        return collectionPrefix;
    }

    @Override
    public boolean isOnline() {
        return true;
    }

    @Override
    public boolean health() {
        try {
            java.net.http.HttpClient hc = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create((tls ? "https://" : "http://") + host + ":" + port + "/collections"))
                    .timeout(java.time.Duration.ofSeconds(3));
            // 必须带上 API Key：Qdrant 开启鉴权时未授权请求返回 401，会被误判为「不可达」而回落 DB 路径
            if (apiKey != null) {
                builder.header("api-key", apiKey);
            }
            java.net.http.HttpResponse<String> resp =
                    hc.send(builder.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.info("Qdrant 健康检查失败（{}:{}）: {}", host, port, e.getMessage());
            return false;
        }
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        try {
            if (Boolean.TRUE.equals(adminClient.collectionExistsAsync(collection).get())) {
                return;
            }
            Collections.VectorParams params = Collections.VectorParams.newBuilder()
                    .setSize(dimension)
                    .setDistance(Collections.Distance.Cosine)
                    .build();
            adminClient.createCollectionAsync(collection, params).get();
            log.info("Qdrant 集合已就绪：{} (dim={})", collection, dimension);
        } catch (Exception e) {
            log.info("Qdrant 集合确保失败 {}（回落主链路）: {}", collection, e.getMessage());
        }
    }

    @Override
    public void upsert(String collection, long id, double[] vector) {
        if (vector == null) {
            return;
        }
        try {
            ensureCollection(collection, vector.length);
            float[] fv = new float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                fv[i] = (float) vector[i];
            }
            Embedding emb = Embedding.from(fv);
            Metadata md = new Metadata(Map.of("row_id", String.valueOf(id)));
            // langchain4j 要求 TextSegment 文本非空：文本本身不使用，占位即可
            TextSegment seg = TextSegment.from("id:" + id, md);
            storeFor(collection).addAll(
                    java.util.Collections.singletonList(String.valueOf(id)),
                    java.util.Collections.singletonList(emb),
                    java.util.Collections.singletonList(seg));
        } catch (Exception e) {
            log.info("Qdrant 向量写入失败 {}/{}（回落主链路）: {}", collection, id, e.getMessage());
        }
    }

    @Override
    public void delete(String collection, long id) {
        try {
            storeFor(collection).remove(String.valueOf(id));
        } catch (Exception e) {
            log.info("Qdrant 向量删除失败 {}/{}（回落主链路）: {}", collection, id, e.getMessage());
        }
    }

    @Override
    public List<IdScore> query(String collection, double[] vector, int topK, List<Long> candidateIds) {
        List<IdScore> hits = new ArrayList<>();
        if (vector == null) {
            return hits;
        }
        try {
            float[] fv = new float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                fv[i] = (float) vector[i];
            }
            Embedding emb = Embedding.from(fv);
            Filter filter = null;
            if (candidateIds != null && !candidateIds.isEmpty()) {
                Collection<String> ids = new ArrayList<>(candidateIds.size());
                for (Long cid : candidateIds) {
                    ids.add(String.valueOf(cid));
                }
                filter = MetadataFilterBuilder.metadataKey("row_id").isIn(ids);
            }
            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                    .queryEmbedding(emb)
                    .maxResults(topK > 0 ? topK : 10)
                    // langchain4j 约束 minScore ∈ [0,1]；取 0 表示不在存储层预过滤，
                    // 阈值统一交由调用方判定（余弦得分为负的记录本就低于任何非负阈值）
                    .minScore(0D)
                    .filter(filter)
                    .build();
            EmbeddingSearchResult<TextSegment> result = storeFor(collection).search(req);
            if (result != null && result.matches() != null) {
                for (var m : result.matches()) {
                    long parsed = resolveId(m);
                    if (parsed < 0) {
                        continue;
                    }
                    Double score = m.score();
                    hits.add(new IdScore(parsed, score == null ? 0D : score));
                }
            }
        } catch (Exception e) {
            log.info("Qdrant 向量查询失败 {}（回落主链路）: {}", collection, e.getMessage());
        }
        return hits;
    }

    private static long resolveId(dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> m) {
        String eid = m.embeddingId();
        if (eid != null && !eid.isEmpty()) {
            try {
                return Long.parseLong(eid);
            } catch (NumberFormatException ignored) {
                // fall through to metadata
            }
        }
        TextSegment seg = m.embedded();
        if (seg != null) {
            String rid = seg.metadata().getString("row_id");
            if (rid != null && !rid.isEmpty()) {
                try {
                    return Long.parseLong(rid);
                } catch (NumberFormatException ignored) {
                    return -1L;
                }
            }
        }
        return -1L;
    }
}
