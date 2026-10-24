package com.xiongx9527.askdata.ai.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 在线向量库实现：Milvus，基于 langchain4j 的 {@link MilvusEmbeddingStore}。
 *
 * <p>向量读写、候选过滤、命中 id 解析等公共逻辑见 {@link AbstractLangchain4jVectorStore}；
 * 本类只负责 Milvus 专属的客户端构造。</p>
 *
 * <ul>
 *   <li><b>集合自动创建</b>：{@link MilvusEmbeddingStore} 在首次写入时若集合不存在，
 *       会按 builder 传入的 {@code dimension} 自动创建（VarChar id + JSON metadata +
 *       FloatVector，余弦距离），故无需覆写 {@link #ensureCollection}。</li>
 *   <li><b>point id</b>：Milvus 的 id 字段为 VarChar，直接存记录 id 的字符串形式，
 *       与 Qdrant 一致，命中时可由 {@code embeddingId} 直接解析回记录 id。</li>
 *   <li><b>候选过滤</b>：metadata 为 JSON 字段，{@code row_id in [...]} 由
 *       langchain4j 的 MilvusMetadataFilterMapper 转成 Milvus 布尔表达式，服务端过滤。</li>
 * </ul>
 */
public class Langchain4jMilvusVectorStore extends AbstractLangchain4jVectorStore {

    private static final Logger log = LoggerFactory.getLogger(Langchain4jMilvusVectorStore.class);

    private final String host;
    private final int port;
    private final boolean tls;
    private final String token;
    private final String databaseName;
    private final MetricType metricType;
    private final IndexType indexType;
    private final ConsistencyLevelEnum consistencyLevel;
    /** REST/健康检查基址（Milvus 的 RESTful v2 根，如 {@code http://milvus:9091}）。 */
    private final String healthUrl;

    /**
     * @param host             Milvus 主机（gRPC 服务地址）
     * @param port             gRPC 端口（Milvus 缺省 19530）
     * @param tls              是否走 TLS
     * @param token            API Key / {@code user:password}（开启鉴权时必填）
     * @param databaseName     数据库名（缺省 default）
     * @param collectionPrefix 集合名前缀
     * @param metricType       相似度度量（缺省 COSINE）
     * @param indexType        索引类型（缺省 AUTO_INDEX）
     * @param consistencyLevel 一致性级别（缺省 STRONG）
     * @param healthUrl        RESTful 健康检查基址（如 {@code http://milvus:9091}）；为空则不探测
     */
    public Langchain4jMilvusVectorStore(String host, int port, boolean tls, String token,
                                        String databaseName, String collectionPrefix,
                                        MetricType metricType, IndexType indexType,
                                        ConsistencyLevelEnum consistencyLevel, String healthUrl) {
        super(collectionPrefix);
        this.host = (host == null ? "" : host.trim());
        this.port = port > 0 ? port : 19530;
        this.tls = tls;
        this.token = (token == null || token.trim().isEmpty()) ? null : token.trim();
        this.databaseName = (databaseName == null || databaseName.trim().isEmpty())
                ? "default" : databaseName.trim();
        this.metricType = metricType != null ? metricType : MetricType.COSINE;
        this.indexType = indexType != null ? indexType : IndexType.AUTOINDEX;
        this.consistencyLevel = consistencyLevel != null ? consistencyLevel : ConsistencyLevelEnum.STRONG;
        this.healthUrl = (healthUrl == null ? "" : healthUrl.trim().replaceAll("/+$", ""));
        log.info("Milvus 后端初始化：{}:{} (tls={}, db={})", this.host, this.port, tls, this.databaseName);
    }

    @Override
    public boolean health() {
        if (healthUrl.isEmpty()) {
            // 未提供 REST 基址时乐观视为可用：实际失败由各读写操作捕获并回落，不阻断主链路
            return true;
        }
        try {
            HttpClient hc = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl + "/healthz"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = hc.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.info("Milvus 健康检查失败（{}）: {}", healthUrl, e.getMessage());
            return false;
        }
    }

    @Override
    protected EmbeddingStore<TextSegment> createStore(String collection, int dimension) {
        MilvusEmbeddingStore.Builder builder = MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collection)
                .dimension(dimension)
                .metricType(metricType)
                .indexType(indexType)
                .consistencyLevel(consistencyLevel)
                .databaseName(databaseName);
        if (token != null) {
            builder.token(token);
        }
        return builder.build();
    }
}
