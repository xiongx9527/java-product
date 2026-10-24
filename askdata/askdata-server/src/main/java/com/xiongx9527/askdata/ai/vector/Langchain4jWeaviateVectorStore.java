package com.xiongx9527.askdata.ai.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.weaviate.WeaviateEmbeddingStore;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateAuthClient;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 在线向量库实现：Weaviate，基于 langchain4j 的 {@link io.weaviate.client.WeaviateClient} 封装。
 *
 * <p>向量读写、候选过滤、命中 id 解析等公共逻辑见 {@link AbstractLangchain4jVectorStore}；
 * 本类处理 Weaviate 专属约束：</p>
 * <ul>
 *   <li><b>id 必须是 UUID</b>：Weaviate 拒绝非 UUID 的 id，故 {@link #toPointId(long)} 由记录 id 派生稳定 UUID；
 *       真实记录 id 存于 metadata.row_id，命中时回退解析。</li>
 *   <li><b>search 忽略服务端 filter</b>：langchain4j 的 Weaviate store 不在 search 中应用 filter，
 *       候选 id 过滤由抽象基类在客户端兜底（见 {@link AbstractLangchain4jVectorStore#query}）。</li>
 *   <li><b>类需显式创建</b>：与 Qdrant 类似，{@link #ensureCollection} 显式建类（vectorizer=none，
 *       声明 text / row_id 属性）。</li>
 *   <li><b>类名约束</b>：Weaviate 类必须以大写字母开头且只含字母数字/下划线，
 *       故 {@link #collectionFor} 对集合名做清洗（如 askdata-terminology -> Askdata_terminology）。</li>
 * </ul>
 */
public class Langchain4jWeaviateVectorStore extends AbstractLangchain4jVectorStore {

    private static final Logger log = LoggerFactory.getLogger(Langchain4jWeaviateVectorStore.class);

    private final String scheme;
    private final String host;
    private final int port;
    private final String apiKey;
    private final String consistencyLevel;

    public Langchain4jWeaviateVectorStore(String scheme, String host, int port, String apiKey,
                                        String collectionPrefix, String consistencyLevel) {
        super(collectionPrefix);
        this.scheme = (scheme == null || scheme.trim().isEmpty()) ? "http" : scheme.trim();
        this.host = (host == null ? "" : host.trim());
        this.port = port > 0 ? port : 8080;
        this.apiKey = (apiKey == null || apiKey.trim().isEmpty()) ? null : apiKey.trim();
        this.consistencyLevel = (consistencyLevel == null || consistencyLevel.trim().isEmpty())
                ? "QUORUM" : consistencyLevel.trim();
        log.info("Weaviate 后端初始化：{}://{}:{}", this.scheme, this.host, this.port);
    }

    /** 记录 id -> 稳定 UUID（nameUUIDFromBytes，相同 id 始终派生同一 UUID，保证 upsert 覆盖）。 */
    @Override
    protected String toPointId(long id) {
        return UUID.nameUUIDFromBytes(("" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Weaviate 类命名约束：首字母大写，仅字母数字/下划线。 */
    @Override
    public String collectionFor(String objectType) {
        String raw = collectionPrefix() + "-" + objectType;
        if (raw.isEmpty()) {
            return "Askdata";
        }
        String s = Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
        return s.replaceAll("[^A-Za-z0-9_]", "_");
    }

    @Override
    public boolean health() {
        try {
            HttpClient hc = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(scheme + "://" + host + ":" + port + "/v1/meta"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = hc.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.info("Weaviate 健康检查失败（{}://{}:{}）: {}", scheme, host, port, e.getMessage());
            return false;
        }
    }

    @Override
    protected EmbeddingStore<TextSegment> createStore(String collection, int dimension) {
        WeaviateEmbeddingStore.WeaviateEmbeddingStoreBuilder builder =
                WeaviateEmbeddingStore.builder()
                        .scheme(scheme)
                        .host(host)
                        .port(port)
                        .objectClass(collection)
                        .metadataKeys(List.of("row_id"))
                        .metadataFieldName("")
                        .textFieldName("text")
                        .consistencyLevel(consistencyLevel);
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        try {
            WeaviateClient client = newClient();
            WeaviateClass clazz = WeaviateClass.builder()
                    .className(collection)
                    .vectorizer("none")
                    .properties(List.of(
                            Property.builder().name("text").dataType(List.of("text")).build(),
                            Property.builder().name("row_id").dataType(List.of("text")).build()))
                    .build();
            Result<Boolean> r = client.schema().classCreator().withClass(clazz).run();
            if (r.hasErrors()) {
                // 类已存在时 Weaviate 返回错误，属正常现象，不阻断。
                log.info("Weaviate 类 {} 创建结果（可能已存在）: {}", collection,
                        r.getError() == null ? "" : r.getError().getMessages());
            } else {
                log.info("Weaviate 类已就绪：{} (dim={})", collection, dimension);
            }
        } catch (Exception e) {
            log.info("Weaviate 类创建失败 {}（回落主链路）: {}", collection, e.getMessage());
        } finally {
            storeFor(collection, dimension);
        }
    }

    private WeaviateClient newClient() {
        Config config = new Config(scheme, host + ":" + port);
        if (apiKey == null) {
            return new WeaviateClient(config);
        }
        try {
            return WeaviateAuthClient.apiKey(config, apiKey);
        } catch (Exception e) {
            return new WeaviateClient(config);
        }
    }
}
