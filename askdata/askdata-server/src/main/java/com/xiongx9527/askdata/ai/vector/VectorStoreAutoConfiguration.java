package com.xiongx9527.askdata.ai.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 向量存储后端装配。
 *
 * <p>后端由 {@code askdata.vector-store.type} 选择，缺省 {@code qdrant}（当前唯一实现，REST）。
 * 预留的在线后端取值（milvus / weaviate / pgvector / elasticsearch / redis）尚未实现：
 * 显式配置这些值会在启动期失败并给出明确提示，不会静默退回本地存储。</p>
 *
 * <p><b>没有本地后端</b>：向量不落 DB 的 embedding JSON 列，避免出现「看起来配置了在线库、
 * 实际却存在本地」的隐性路径。未配置 {@code qdrant.url} 时装配占位实现：应用仍可启动
 * （其它功能不受影响），但任何向量读写都会抛出带明确指引的异常。</p>
 *
 * <p>新增后端只需两步：实现 {@link VectorStore}，并在 {@link #vectorStore} 里加一个分支。</p>
 */
@Configuration
public class VectorStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreAutoConfiguration.class);

    /** 预留但尚未实现的在线后端：配置后启动期显式失败，避免误以为已生效。 */
    private static final Set<String> PLANNED_TYPES = new HashSet<>(Arrays.asList(
            "milvus", "weaviate", "pgvector", "elasticsearch", "redis"));

    private final Environment env;

    @Autowired
    public VectorStoreAutoConfiguration(Environment env) {
        this.env = env;
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(ObjectMapper objectMapper) {
        String prefix = env.getProperty("askdata.vector-store.collection-prefix", "askdata");
        String type = env.getProperty("askdata.vector-store.type", "qdrant");
        String normalized = type == null ? "qdrant" : type.trim().toLowerCase(Locale.ROOT);
        if ("qdrant".equals(normalized)) {
            return qdrant(prefix, objectMapper);
        }
        if (PLANNED_TYPES.contains(normalized)) {
            throw new IllegalStateException("向量存储后端 " + type + " 尚未实现；当前仅支持 qdrant");
        }
        if ("none".equals(normalized) || "db".equals(normalized)) {
            throw new IllegalStateException(
                    "本地向量存储已移除（askdata.vector-store.type=" + type + "）；请改用 qdrant");
        }
        throw new IllegalStateException("未知的向量存储后端：askdata.vector-store.type=" + type
                + "（当前仅支持 qdrant）");
    }

    private VectorStore qdrant(String prefix, ObjectMapper objectMapper) {
        String url = env.getProperty("askdata.vector-store.qdrant.url", "");
        if (url == null || url.trim().isEmpty()) {
            log.error("未配置在线向量存储（askdata.vector-store.qdrant.url），向量读写将不可用；"
                    + "本版本不再支持本地向量存储");
            return new UnconfiguredVectorStore(prefix);
        }
        String apiKey = env.getProperty("askdata.vector-store.qdrant.api-key", "");
        log.info("向量存储后端：Qdrant REST（{}，集合前缀 '{}'）", url, prefix);
        return new QdrantVectorStore(url, apiKey, prefix, objectMapper);
    }

    /** 未配置 Qdrant 时的占位实现：任何读写都显式失败，绝不静默降级到本地存储。 */
    static final class UnconfiguredVectorStore implements VectorStore {

        private final String prefix;

        UnconfiguredVectorStore(String prefix) {
            this.prefix = prefix == null ? "askdata" : prefix;
        }

        @Override
        public String collectionPrefix() {
            return prefix;
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public boolean health() {
            return false;
        }

        @Override
        public void ensureCollection(String collection, int dimension) {
            throw unavailable();
        }

        @Override
        public void dropCollection(String collection) {
            throw unavailable();
        }

        @Override
        public void upsert(String collection, long id, double[] vector) {
            throw unavailable();
        }

        @Override
        public void delete(String collection, long id) {
            throw unavailable();
        }

        @Override
        public void deleteByPayload(String collection, String key, Object value) {
            throw unavailable();
        }

        @Override
        public List<IdScore> query(String collection, double[] vector, int topK, List<Long> candidateIds) {
            throw unavailable();
        }

        private IllegalStateException unavailable() {
            return new IllegalStateException(
                    "未配置在线向量存储：请设置 askdata.vector-store.qdrant.url 指向 Qdrant 服务");
        }
    }
}
