package com.xiongx9527.askdata.ai.vector;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.schemafields.TagField;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线向量库实现：Redis（Redis Stack），基于 langchain4j 社区版
 * {@code langchain4j-community-redis} 的 {@link RedisEmbeddingStore}（Jedis 客户端）。
 *
 * <p>向量读写、候选过滤、命中 id 解析等公共逻辑见 {@link AbstractLangchain4jVectorStore}；
 * 本类处理 Redis 专属约束：</p>
 * <ul>
 *   <li><b>需要 Redis Stack</b>：向量检索依赖 RediSearch 的向量字段（KNN）与 RedisJSON，
 *       社区版 Redis（无向量模块）不可用。</li>
 *   <li><b>索引自动创建</b>：{@link RedisEmbeddingStore} 构造时即以 {@code FT.CREATE} 建索引
 *       （含向量字段维度），故维度必须已知——本类按集合记住实际维度，
 *       避免在维度未知时（如仅删除）建出无维度的索引。</li>
 *   <li><b>键前缀须以 {@code :} 结尾</b>：RedisSchema 对前缀有该约束。</li>
 *   <li><b>row_id 需声明为 TAG 字段</b>：元数据过滤在服务端按 {@code @row_id:} 精确匹配，
 *       故把 {@code $.row_id} 声明为 TAG 并别名为 {@code row_id}；即便服务端过滤未命中，
 *       基类也会在客户端按候选 id 兜底裁剪。</li>
 * </ul>
 */
public class Langchain4jRedisVectorStore extends AbstractLangchain4jVectorStore {

    private static final Logger log = LoggerFactory.getLogger(Langchain4jRedisVectorStore.class);

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final int database;

    /** 共享的 Jedis 客户端（连接池）。 */
    private final UnifiedJedis jedis;

    /** 各集合已知的向量维度（建索引需要；避免以 0 维度建索引）。 */
    private final Map<String, Integer> dims = new ConcurrentHashMap<>();

    public Langchain4jRedisVectorStore(String host, int port, String username, String password,
                                        boolean ssl, int database, String collectionPrefix) {
        super(collectionPrefix);
        this.host = (host == null ? "" : host.trim());
        this.port = port > 0 ? port : 6379;
        this.username = blankToNull(username);
        this.password = blankToNull(password);
        this.ssl = ssl;
        this.database = database > 0 ? database : 0;
        this.jedis = createJedis();
        log.info("Redis 后端初始化：{}:{}（db={}，ssl={}）", this.host, this.port, this.database, this.ssl);
    }

    private UnifiedJedis createJedis() {
        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                .ssl(ssl)
                .database(database);
        if (username != null) {
            cfg.user(username).password(password == null ? "" : password);
        }
        return new JedisPooled(new HostAndPort(host, port), cfg.build());
    }

    @Override
    protected EmbeddingStore<TextSegment> createStore(String collection, int dimension) {
        if (dimension > 0) {
            dims.put(collection, dimension);
        }
        int dim = dimension > 0 ? dimension : dims.getOrDefault(collection, 0);
        RedisEmbeddingStore.Builder builder = RedisEmbeddingStore.builder()
                .unifiedJedis(jedis)
                .indexName(indexNameFor(collection))
                .prefix(keyPrefixFor(collection))
                .metadataKeys(List.of("row_id"))
                // row_id 声明为 TAG（JSON 路径 $.row_id，别名 row_id），服务端才能按 @row_id: 精确过滤
                .metadataConfig(Map.of("row_id", TagField.of("$.row_id").as("row_id")));
        if (dim > 0) {
            builder.dimension(dim);
        }
        return builder.build();
    }

    /**
     * 删除向量：仅在该集合已写入过（索引已按正确维度建立）时执行，
     * 避免以未知维度建索引。
     */
    @Override
    public void delete(String collection, long id) {
        if (!dims.containsKey(collection)) {
            log.info("Redis 向量删除跳过 {}（该集合尚无写入记录，索引未建立）", collection);
            return;
        }
        super.delete(collection, id);
    }

    @Override
    public boolean health() {
        try {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            log.info("Redis 健康检查失败（{}:{}）: {}", host, port, e.getMessage());
            return false;
        }
    }

    /** RediSearch 索引名（按集合区分，须唯一）。 */
    private String indexNameFor(String collection) {
        return sanitize(collection) + "-idx";
    }

    /** 文档键前缀（须以 ':' 结尾）。 */
    private String keyPrefixFor(String collection) {
        return sanitize(collection).replace('-', ':') + ":";
    }

    private static String sanitize(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        s = s.replaceAll("^[-_+]+", "");
        return s.isEmpty() ? "askdata" : s;
    }

    private static String blankToNull(String v) {
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }
}
