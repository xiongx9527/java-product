package com.xiongx9527.askdata.ai.vector;

import java.util.List;

/**
 * 向量存储抽象（可插拔后端）。
 *
 * <p>askdata 的向量由现有 embedding 模型（远程 API / 本地 ONNX）产出；
 * 本接口只负责向量的<b>持久化</b>与<b>最近邻（ANN）检索</b>，不关心向量如何生成。</p>
 *
 * <p>当前实现：{@link DbVectorStore} —— 「DB embedding 列 + 应用内余弦」，不依赖外部向量库客户端，
 * 因此在 Java 8 / Spring Boot 2.7 平台同样可用。</p>
 *
 * <p>历史版本提供的在线后端（qdrant / milvus / weaviate / pgvector / elasticsearch / redis）基于
 * langchain4j 1.x 的 {@code EmbeddingStore} 写接口（需同时指定 point id 与 metadata），
 * langchain4j 0.35 不提供该接口，故 Java 8 版本不再内置；{@code askdata.vector-store.type}
 * 配为这些取值时回落 {@link DbVectorStore}。</p>
 */
public interface VectorStore {

    /** 命中点（id + 相似度，降序由调用方保证）。 */
    class IdScore {

        private final long id;

        private final double score;

        public IdScore(long id, double score) {
            this.id = id;
            this.score = score;
        }

        public long id() {
            return id;
        }

        public double score() {
            return score;
        }
    }

    /** 集合名前缀（配置 askdata.vector-store.collection-prefix，缺省 askdata）。 */
    String collectionPrefix();

    /** 由对象类型（即表名）得到集合名：{prefix}-{object-type}。 */
    default String collectionFor(String objectType) {
        return collectionPrefix() + "-" + objectType;
    }

    /** 是否为在线向量库（true=Qdrant；false=DB 回退）。 */
    boolean isOnline();

    /** 连通性探测；不可用时返回 false（调用方回落）。 */
    boolean health();

    /** 确保集合存在（不存在则创建，维度与向量一致，余弦距离）。 */
    void ensureCollection(String collection, int dimension);

    /** 写入 / 更新单条向量。 */
    void upsert(String collection, long id, double[] vector);

    /** 删除单条向量。 */
    void delete(String collection, long id);

    /**
     * 按向量召回 topK。
     *
     * @param collection    集合名
     * @param vector       查询向量
     * @param topK         返回条数上限
     * @param candidateIds 候选 id 限定（为空表示不限定）；在线库据此过滤，回退实现据此裁剪
     * @return 命中（id + score，按 score 降序）
     */
    List<IdScore> query(String collection, double[] vector, int topK, List<Long> candidateIds);

    /** 不限定候选的召回。 */
    default List<IdScore> query(String collection, double[] vector, int topK) {
        return query(collection, vector, topK, null);
    }
}
