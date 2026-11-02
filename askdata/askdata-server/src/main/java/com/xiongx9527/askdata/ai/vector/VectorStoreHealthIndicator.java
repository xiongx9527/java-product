package com.xiongx9527.askdata.ai.vector;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 向量库健康检查：把 Qdrant 的连通性暴露到 {@code /health}。
 *
 * <p>三种状态：未配置（{@link Health#unknown()}，不拖累整体 UP）、连通（UP）、配置了但不可达（DOWN）。
 * 之前 Qdrant 挂了只有一条 info 日志，运维侧没有任何信号。</p>
 */
@Component
public class VectorStoreHealthIndicator implements HealthIndicator {

    private final VectorStore vectorStore;

    public VectorStoreHealthIndicator(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public Health health() {
        if (!vectorStore.configured()) {
            return Health.unknown()
                    .withDetail("backend", "qdrant")
                    .withDetail("reason", "askdata.vector-store.qdrant.url 未配置")
                    .build();
        }
        if (vectorStore.health()) {
            return Health.up()
                    .withDetail("backend", "qdrant")
                    .withDetail("collectionPrefix", vectorStore.collectionPrefix())
                    .build();
        }
        return Health.down()
                .withDetail("backend", "qdrant")
                .withDetail("collectionPrefix", vectorStore.collectionPrefix())
                .build();
    }
}
