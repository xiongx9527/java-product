package com.xiongx9527.askdata.ai.vector;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线向量库实现：Qdrant，基于 langchain4j 的 {@link QdrantEmbeddingStore}（gRPC）。
 *
 * <p>向量读写、候选过滤、命中 id 解析等公共逻辑见 {@link AbstractLangchain4jVectorStore}；
 * 本类只负责 Qdrant 专属的部分：</p>
 * <ul>
 *   <li><b>端口分离</b>：REST（缺省 6333）仅用于健康检查；gRPC（缺省 6334）负责向量读写。
 *       两者是 Qdrant 的两个独立监听端口，langchain4j / io.qdrant 客户端走 gRPC。</li>
 *   <li><b>建集合</b>：{@link QdrantEmbeddingStore} 不负责创建集合，需自行用
 *       {@link QdrantClient} 按「向量维度 + 余弦距离」创建。</li>
 * </ul>
 */
public class Langchain4jQdrantVectorStore extends AbstractLangchain4jVectorStore {

    private static final Logger log = LoggerFactory.getLogger(Langchain4jQdrantVectorStore.class);

    /** Qdrant gRPC 默认端口（与 REST 端口 6333 相互独立）。 */
    private static final int DEFAULT_GRPC_PORT = 6334;

    private final String host;
    /** REST 端口（仅用于健康检查）。 */
    private final int restPort;
    /** gRPC 端口（向量读写）。 */
    private final int grpcPort;
    private final boolean tls;
    private final String apiKey;

    private final QdrantClient adminClient;

    /**
     * @param baseUrl          Qdrant REST 基址（如 {@code http://qdrant:6333}），用于健康检查
     * @param apiKey           API Key（Qdrant 开启鉴权时必填；REST 与 gRPC 共用同一把钥匙）
     * @param collectionPrefix 集合名前缀
     * @param grpcPort         gRPC 端口；{@code <= 0} 时用缺省 6334
     */
    public Langchain4jQdrantVectorStore(String baseUrl, String apiKey, String collectionPrefix, int grpcPort) {
        super(collectionPrefix);
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
            this.restPort = parsePort(body.substring(colon + 1), tls ? 443 : 6333);
        } else {
            this.host = body;
            this.restPort = tls ? 443 : 6333;
        }
        this.grpcPort = grpcPort > 0 ? grpcPort : DEFAULT_GRPC_PORT;
        this.apiKey = (apiKey == null || apiKey.trim().isEmpty()) ? null : apiKey.trim();
        this.adminClient = new QdrantClient(QdrantGrpcClient.newBuilder(host, this.grpcPort, tls)
                .withApiKey(this.apiKey).build());
        log.info("Qdrant 后端初始化：REST {}:{} / gRPC {}:{}", host, restPort, host, this.grpcPort);
    }

    /** 便捷构造：gRPC 端口用缺省 6334。 */
    public Langchain4jQdrantVectorStore(String baseUrl, String apiKey, String collectionPrefix) {
        this(baseUrl, apiKey, collectionPrefix, DEFAULT_GRPC_PORT);
    }

    @Override
    public boolean health() {
        try {
            java.net.http.HttpClient hc = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create((tls ? "https://" : "http://") + host + ":" + restPort + "/collections"))
                    .timeout(java.time.Duration.ofSeconds(3));
            // 必须带上 API Key：Qdrant 开启鉴权时未授权请求返回 401，会被误判为「不可达」而回落 DB 路径
            if (apiKey != null) {
                builder.header("api-key", apiKey);
            }
            java.net.http.HttpResponse<String> resp =
                    hc.send(builder.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.info("Qdrant 健康检查失败（{}:{}）: {}", host, restPort, e.getMessage());
            return false;
        }
    }

    @Override
    protected EmbeddingStore<TextSegment> createStore(String collection, int dimension) {
        return new QdrantEmbeddingStore(collection, host, grpcPort, tls, "text", apiKey);
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
        } finally {
            storeFor(collection, dimension);
        }
    }

    private static int parsePort(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
