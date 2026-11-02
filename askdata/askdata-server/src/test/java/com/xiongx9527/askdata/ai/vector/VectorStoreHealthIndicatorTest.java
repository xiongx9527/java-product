package com.xiongx9527.askdata.ai.vector;

import com.xiongx9527.askdata.support.QdrantTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 向量存储健康指示器（<b>真实 Qdrant</b>）：
 * 可达 → UP；未配置 url 的占位实现 → UNKNOWN（区别于「配置了但不可达」的 DOWN）。
 */
class VectorStoreHealthIndicatorTest {

    private static QdrantVectorStore store;

    @BeforeAll
    static void connect() {
        QdrantTestSupport.requireAvailable();
        store = QdrantTestSupport.store(QdrantTestSupport.uniquePrefix());
    }

    @AfterAll
    static void cleanup() {
        if (store != null) {
            QdrantTestSupport.dropAll(store);
        }
    }

    @Test
    void upWhenRealBackendReachable() {
        Health health = new VectorStoreHealthIndicator(store).health();

        assertEquals(Status.UP, health.getStatus(), health.toString());
        assertEquals("qdrant", health.getDetails().get("backend"));
    }

    @Test
    void unknownWhenNotConfigured() {
        VectorStore unconfigured = new VectorStoreAutoConfiguration.UnconfiguredVectorStore("askdata");

        Health health = new VectorStoreHealthIndicator(unconfigured).health();

        assertEquals(Status.UNKNOWN, health.getStatus(), "未配置 url 不应报 DOWN（那不是故障）");
        assertFalse(unconfigured.configured());
    }
}
