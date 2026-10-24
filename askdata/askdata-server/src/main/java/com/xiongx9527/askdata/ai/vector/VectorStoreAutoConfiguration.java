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

/**
 * 按 askdata.vector-store.type 装配向量存储后端：
 *
 * <ul>
 *   <li>qdrant 且 Qdrant 健康 -> Langchain4jQdrantVectorStore（基于 langchain4j 的 Qdrant 封装，在线唯一存储后端）；</li>
 *   <li>qdrant 但不可达，或 none/未配置 -> DbVectorStore（DB JSON 列 + 应用内余弦回退）；</li>
 * </ul>
 *
 * <p>默认 none，即沿用现有行为，保证既有测试与问数主链路不受影响。</p>
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
        String qdrantUrl = env.getProperty("askdata.vector-store.qdrant.url", "");
        String qdrantApiKey = env.getProperty("askdata.vector-store.qdrant.api-key", "");
        int qdrantGrpcPort = env.getProperty("askdata.vector-store.qdrant.grpc-port", Integer.class, 6334);
        if ("qdrant".equalsIgnoreCase(type)) {
            Langchain4jQdrantVectorStore qdrant =
                    new Langchain4jQdrantVectorStore(qdrantUrl, qdrantApiKey, prefix, qdrantGrpcPort);
            if (qdrant.health()) {
                log.info("向量存储后端：Qdrant（{}），集合前缀 '{}'", qdrantUrl, prefix);
                return qdrant;
            }
            log.warn("配置的 Qdrant 不可达（{}），回落到 DB JSON 列 + 应用内余弦", qdrantUrl);
            return new DbVectorStore(jdbc, objectMapper, prefix);
        }
        log.info("向量存储后端：DB JSON 列 + 应用内余弦（type={}）", type);
        return new DbVectorStore(jdbc, objectMapper, prefix);
    }
}
