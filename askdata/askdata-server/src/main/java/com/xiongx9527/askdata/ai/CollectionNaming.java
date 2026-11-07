package com.xiongx9527.askdata.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向量集合命名：{@code prefix + "-" + objectType}。
 *
 * <p>框架 {@code VectorStorePort} 不管理集合前缀，产品侧统一在此集中处理，
 * 供 {@code EmbeddingService} / {@code TableEmbeddingService} 等复用，避免前缀逻辑散落多处。</p>
 */
@Component
public class CollectionNaming {

    public static final String DEFAULT_PREFIX = "askdata";

    private final String prefix;

    public CollectionNaming(@Value("${askdata.vector-store.collection-prefix:askdata}") String prefix) {
        this.prefix = (prefix == null || prefix.trim().isEmpty()) ? DEFAULT_PREFIX : prefix.trim();
    }

    public String prefix() {
        return prefix;
    }

    public String collectionFor(String objectType) {
        return prefix + "-" + (objectType == null ? "" : objectType);
    }
}
