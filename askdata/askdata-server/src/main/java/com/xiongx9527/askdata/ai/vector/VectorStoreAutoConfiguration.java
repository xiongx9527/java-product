package com.xiongx9527.askdata.ai.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 按 askdata.vector-store.type 装配向量存储后端：
 *
 * <ul>
 *   <li>qdrant：{@link Langchain4jQdrantVectorStore}（基于 langchain4j 的 Qdrant 封装，gRPC）；</li>
 *   <li>milvus：{@link Langchain4jMilvusVectorStore}（基于 langchain4j 的 Milvus 封装）；</li>
 *   <li>weaviate：{@link Langchain4jWeaviateVectorStore}（基于 langchain4j 的 Weaviate 封装）；</li>
 *   <li>pgvector：{@link Langchain4jPgVectorVectorStore}（基于 langchain4j 的 pgvector 封装，JDBC）；</li>
 *   <li>elasticsearch：{@link Langchain4jElasticsearchVectorStore}（基于 langchain4j 的 ES 封装，kNN 检索）；</li>
 *   <li>redis：{@link Langchain4jRedisVectorStore}（基于 langchain4j 社区版 Redis 封装，需 Redis Stack）；</li>
 *   <li>none / 未配置 / 在线库不可达：{@link DbVectorStore}（DB JSON 列 + 应用内余弦回退）。</li>
 * </ul>
 *
 * <p>默认 none，即沿用现有行为，保证既有测试与问数主链路不受影响。新增后端只需在
 * {@link #onlineStores()} 登记一行。</p>
 */
@Configuration
public class VectorStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreAutoConfiguration.class);

    private final Environment env;

    @Autowired
    public VectorStoreAutoConfiguration(Environment env) {
        this.env = env;
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        String type = env.getProperty("askdata.vector-store.type", "none");
        String prefix = env.getProperty("askdata.vector-store.collection-prefix", "askdata");
        Supplier<VectorStore> supplier = onlineStores().get(type.toLowerCase(Locale.ROOT));
        if (supplier == null) {
            log.info("向量存储后端：DB JSON 列 + 应用内余弦（type={}）", type);
            return new DbVectorStore(jdbc, objectMapper, prefix);
        }
        // 惰性创建：仅对选中的后端实例化，避免无关客户端初始化日志/连接
        VectorStore online = supplier.get();
        if (online.health()) {
            log.info("向量存储后端：{}（健康检查通过），集合前缀 '{}'", type, prefix);
            return online;
        }
        log.warn("配置的 {} 不可达（{}），回落到 DB JSON 列 + 应用内余弦", type, prefix);
        return new DbVectorStore(jdbc, objectMapper, prefix);
    }

    /** type（小写）-> 后端工厂。新增后端只需在此登记一行。 */
    private Map<String, Supplier<VectorStore>> onlineStores() {
        String prefix = env.getProperty("askdata.vector-store.collection-prefix", "askdata");
        Map<String, Supplier<VectorStore>> stores = new HashMap<>();
        stores.put("qdrant", () -> new Langchain4jQdrantVectorStore(
                env.getProperty("askdata.vector-store.qdrant.url", ""),
                env.getProperty("askdata.vector-store.qdrant.api-key", ""),
                prefix,
                env.getProperty("askdata.vector-store.qdrant.grpc-port", Integer.class, 6334)));
        stores.put("milvus", () -> new Langchain4jMilvusVectorStore(
                env.getProperty("askdata.vector-store.milvus.host", ""),
                env.getProperty("askdata.vector-store.milvus.port", Integer.class, 19530),
                env.getProperty("askdata.vector-store.milvus.tls", Boolean.class, false),
                env.getProperty("askdata.vector-store.milvus.token", ""),
                env.getProperty("askdata.vector-store.milvus.database", "default"),
                prefix,
                metricType(),
                indexType(),
                consistencyLevel(),
                env.getProperty("askdata.vector-store.milvus.health-url", "")));
        stores.put("weaviate", () -> new Langchain4jWeaviateVectorStore(
                env.getProperty("askdata.vector-store.weaviate.scheme", "http"),
                env.getProperty("askdata.vector-store.weaviate.host", ""),
                env.getProperty("askdata.vector-store.weaviate.port", Integer.class, 8080),
                env.getProperty("askdata.vector-store.weaviate.api-key", ""),
                prefix,
                env.getProperty("askdata.vector-store.weaviate.consistency-level", "QUORUM")));
        stores.put("pgvector", () -> new Langchain4jPgVectorVectorStore(
                env.getProperty("askdata.vector-store.pgvector.host", ""),
                env.getProperty("askdata.vector-store.pgvector.port", Integer.class, 5432),
                env.getProperty("askdata.vector-store.pgvector.database", ""),
                env.getProperty("askdata.vector-store.pgvector.user", ""),
                env.getProperty("askdata.vector-store.pgvector.password", ""),
                prefix,
                env.getProperty("askdata.vector-store.pgvector.use-index", Boolean.class, false)));
        stores.put("elasticsearch", () -> new Langchain4jElasticsearchVectorStore(
                env.getProperty("askdata.vector-store.elasticsearch.server-url", ""),
                env.getProperty("askdata.vector-store.elasticsearch.api-key", ""),
                env.getProperty("askdata.vector-store.elasticsearch.username", ""),
                env.getProperty("askdata.vector-store.elasticsearch.password", ""),
                prefix,
                env.getProperty("askdata.vector-store.elasticsearch.refresh", "WaitFor")));
        stores.put("redis", () -> new Langchain4jRedisVectorStore(
                env.getProperty("askdata.vector-store.redis.host", ""),
                env.getProperty("askdata.vector-store.redis.port", Integer.class, 6379),
                env.getProperty("askdata.vector-store.redis.username", ""),
                env.getProperty("askdata.vector-store.redis.password", ""),
                env.getProperty("askdata.vector-store.redis.ssl", Boolean.class, false),
                env.getProperty("askdata.vector-store.redis.database", Integer.class, 0),
                prefix));
        return stores;
    }

    private MetricType metricType() {
        String v = env.getProperty("askdata.vector-store.milvus.metric-type", "COSINE");
        try {
            return MetricType.valueOf(v);
        } catch (Exception e) {
            return MetricType.COSINE;
        }
    }

    private IndexType indexType() {
        String v = env.getProperty("askdata.vector-store.milvus.index-type", "AUTO_INDEX");
        try {
            return IndexType.valueOf(v);
        } catch (Exception e) {
            return IndexType.AUTOINDEX;
        }
    }

    private ConsistencyLevelEnum consistencyLevel() {
        String v = env.getProperty("askdata.vector-store.milvus.consistency-level", "STRONG");
        try {
            return ConsistencyLevelEnum.valueOf(v);
        } catch (Exception e) {
            return ConsistencyLevelEnum.STRONG;
        }
    }
}
