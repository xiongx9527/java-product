package com.xiongx9527.askdata.ai.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 向量存储后端装配。
 *
 * <p>当前实现只有 {@link DbVectorStore}（DB JSON 列 + 应用内余弦），不依赖任何外部向量库客户端，
 * 因此可运行在 Java 8 / Spring Boot 2.7 平台上。</p>
 *
 * <p>历史版本曾按 {@code askdata.vector-store.type} 装配 qdrant / milvus / weaviate / pgvector /
 * elasticsearch / redis 等在线后端（基于 langchain4j 1.x 的 {@code EmbeddingStore} 写接口，
 * 需要指定 point id 与 metadata 同时写入）。langchain4j 0.35（Java 8 线）不提供该写接口，
 * 故这些在线后端在 Java 8 版本中不提供；配置为这些取值时统一回落 {@link DbVectorStore} 并给出告警。</p>
 */
@Configuration
public class VectorStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreAutoConfiguration.class);

    /** 历史在线后端取值：Java 8 版本不再提供，配置后回落 DB 后端。 */
    private static final Set<String> LEGACY_ONLINE_TYPES = new HashSet<>(Arrays.asList(
            "qdrant", "milvus", "weaviate", "pgvector", "elasticsearch", "redis"));

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
        String normalized = type == null ? "none" : type.toLowerCase(Locale.ROOT);
        if (LEGACY_ONLINE_TYPES.contains(normalized)) {
            log.warn("向量存储后端 {} 在当前版本不可用（需 langchain4j 1.x 写接口），回落到 DB JSON 列 + 应用内余弦", type);
        } else {
            log.info("向量存储后端：DB JSON 列 + 应用内余弦（type={}）", type);
        }
        return new DbVectorStore(jdbc, objectMapper, prefix);
    }
}
