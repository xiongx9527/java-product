package com.xiongx9527.askdata.ai.vector;

import co.elastic.clients.elasticsearch._types.Refresh;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线向量库实现：Elasticsearch，基于 langchain4j 的 {@link ElasticsearchEmbeddingStore}。
 *
 * <p>向量读写、候选过滤、命中 id 解析等公共逻辑见 {@link AbstractLangchain4jVectorStore}；
 * 本类处理 Elasticsearch 专属约束：</p>
 * <ul>
 *   <li><b>索引需显式创建</b>：langchain4j 的 ES store 既不会自动建索引，
 *       其 {@code dimension} 参数也已废弃（仅打 warn 日志）；若索引不存在，ES 动态映射会把向量
 *       退化成 float，kNN 检索直接不可用。故本类用 REST 建索引并显式声明
 *       {@code vector(dense_vector)} / {@code text} / {@code metadata} 三个字段。</li>
 *   <li><b>metadata 需 keyword 子字段</b>：langchain4j 的元数据过滤器实际查询
 *       {@code metadata.<key>} 与 {@code metadata.<key>.keyword}，故 row_id 同时建
 *       text + keyword 子字段，两种路径都可命中。</li>
 *   <li><b>索引名约束</b>：ES 索引名只能小写，故 {@link #collectionFor} 做小写化与字符清洗。</li>
 *   <li><b>写入可见性</b>：ES 默认近实时（refresh=false）写入后立即检索可能读不到，
 *       故默认使用 {@link Refresh#WaitFor}，保证「写后即查」。</li>
 * </ul>
 */
public class Langchain4jElasticsearchVectorStore extends AbstractLangchain4jVectorStore {

    private static final Logger log = LoggerFactory.getLogger(Langchain4jElasticsearchVectorStore.class);

    /** 索引映射模板：vector 为 dense_vector（kNN 必需），metadata.row_id 带 keyword 子字段。 */
    private static final String MAPPING_TEMPLATE = "{"
            + "\"mappings\":{\"properties\":{"
            + "\"vector\":{\"type\":\"dense_vector\",\"dims\":%d,\"index\":true,\"similarity\":\"cosine\"},"
            + "\"text\":{\"type\":\"text\"},"
            + "\"metadata\":{\"type\":\"object\",\"dynamic\":true,\"properties\":{"
            + "\"row_id\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}}}"
            + "}}}";

    private final String serverUrl;
    private final String apiKey;
    private final String userName;
    private final String password;
    private final Refresh refresh;

    /** 已确认存在（且映射正确）的索引，避免每次写入都探测。 */
    private final Set<String> ensuredIndexes = ConcurrentHashMap.newKeySet();

    public Langchain4jElasticsearchVectorStore(String serverUrl, String apiKey, String userName,
                                                String password, String collectionPrefix, String refresh) {
        super(collectionPrefix);
        this.serverUrl = trimTrailingSlash(serverUrl);
        this.apiKey = blankToNull(apiKey);
        this.userName = blankToNull(userName);
        this.password = blankToNull(password);
        this.refresh = parseRefresh(refresh);
        log.info("Elasticsearch 后端初始化：{}", this.serverUrl);
    }

    /** ES 索引名只能小写（且不能以 -/_/+ 开头）。 */
    @Override
    public String collectionFor(String objectType) {
        String raw = collectionPrefix() + "-" + objectType;
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        s = s.replaceAll("^[-_+]+", "");
        return s.isEmpty() ? "askdata" : s;
    }

    @Override
    protected EmbeddingStore<TextSegment> createStore(String collection, int dimension) {
        ElasticsearchEmbeddingStore.Builder builder = ElasticsearchEmbeddingStore.builder()
                .serverUrl(serverUrl)
                .indexName(collection)
                .refresh(refresh);
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }
        if (userName != null) {
            builder.userName(userName);
        }
        if (password != null) {
            builder.password(password);
        }
        return builder.build();
    }

    /**
     * 首次写入即建索引：ES 的向量维度由实际向量长度决定（langchain4j 的 dimension 参数已废弃），
     * 故在 upsert 时用 {@code vector.length} 建 dense_vector 映射。
     */
    @Override
    public void upsert(String collection, long id, double[] vector) {
        if (vector != null && vector.length > 0) {
            ensureIndex(collection, vector.length);
        }
        super.upsert(collection, id, vector);
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        super.ensureCollection(collection, dimension);
        if (dimension > 0) {
            ensureIndex(collection, dimension);
        }
    }

    @Override
    public boolean health() {
        try {
            HttpResponse<String> resp = send("GET", "/", null);
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.info("Elasticsearch 健康检查失败（{}）: {}", serverUrl, e.getMessage());
            return false;
        }
    }

    /** 确保索引存在且映射正确（幂等）。 */
    private void ensureIndex(String collection, int dimension) {
        if (ensuredIndexes.contains(collection)) {
            return;
        }
        synchronized (ensuredIndexes) {
            if (ensuredIndexes.contains(collection)) {
                return;
            }
            if (createIndexIfAbsent(collection, dimension)) {
                ensuredIndexes.add(collection);
            }
        }
    }

    private boolean createIndexIfAbsent(String index, int dimension) {
        try {
            HttpResponse<String> exists = send("GET", "/" + index, null);
            if (exists.statusCode() >= 200 && exists.statusCode() < 300) {
                return true;
            }
            HttpResponse<String> created = send("PUT", "/" + index, mappingJson(dimension));
            if (created.statusCode() >= 200 && created.statusCode() < 300) {
                log.info("Elasticsearch 索引已创建：{} (dim={})", index, dimension);
                return true;
            }
            // 并发场景下可能已被其它实例抢先创建
            if (created.body() != null && created.body().contains("resource_already_exists_exception")) {
                return true;
            }
            log.info("Elasticsearch 索引创建失败 {}: {}", index, created.body());
            return false;
        } catch (Exception e) {
            log.info("Elasticsearch 索引创建异常 {}: {}", index, e.getMessage());
            return false;
        }
    }

    /** 生成索引映射 JSON（包可见，便于单测校验字段与维度）。 */
    static String mappingJson(int dimension) {
        return String.format(MAPPING_TEMPLATE, dimension);
    }

    /** 发起 ES REST 请求（带可选鉴权）。 */
    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpClient hc = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .timeout(Duration.ofSeconds(10));
        if (apiKey != null) {
            rb.header("Authorization", "ApiKey " + apiKey);
        } else if (userName != null && password != null) {
            String raw = userName + ":" + password;
            rb.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        if (body == null) {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return hc.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String blankToNull(String v) {
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }

    private static Refresh parseRefresh(String v) {
        if (v == null || v.trim().isEmpty()) {
            return Refresh.WaitFor;
        }
        try {
            return Refresh.valueOf(v.trim());
        } catch (Exception e) {
            return Refresh.WaitFor;
        }
    }
}
