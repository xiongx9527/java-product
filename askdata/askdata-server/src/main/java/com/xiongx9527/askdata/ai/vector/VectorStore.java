package com.xiongx9527.askdata.ai.vector;

import java.util.List;

/**
 * 向量存储抽象（可插拔后端）。
 *
 * <p>askdata 的向量由在线 embedding 模型产出；
 * 本接口只负责向量的<b>持久化</b>与<b>最近邻（ANN）检索</b>，不关心向量如何生成。</p>
 *
 * <p>唯一实现：{@link QdrantVectorStore}（在线向量库，REST）。向量<b>只</b>持久化在在线库，
 * 不再有「DB embedding JSON 列」的本地存储路径——本地存储会在维度切换、集合迁移时静默丢召回，
 * 且与在线库形成两份真相。</p>
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

    /**
     * 集合维度与待写入向量不一致：写入被拒绝，集合与既有向量保持不变。
     *
     * <p>单独成类型而不是靠消息文本判断：调用方要能区分「维度不对，需重建」与
     * 「后端不可达，稍后重试」——后者不能被当成永久损坏而短路后续写入。</p>
     */
    class DimensionMismatchException extends IllegalStateException {

        public DimensionMismatchException(String message) {
            super(message);
        }
    }

    /** 集合名前缀（配置 askdata.vector-store.collection-prefix，缺省 askdata）。 */
    String collectionPrefix();

    /** 由对象类型（即表名）得到集合名：{prefix}-{object-type}。 */
    default String collectionFor(String objectType) {
        return collectionPrefix() + "-" + objectType;
    }

    /** 是否已完成配置（未配置 qdrant.url 时为 false，用于健康检查区分「未配置」与「不可达」）。 */
    default boolean configured() {
        return true;
    }

    /** 连通性探测；不可用时返回 false。 */
    boolean health();

    /**
     * 确保集合存在且维度一致（不存在则创建，余弦距离）。
     *
     * <p><b>不删数据</b>：集合已存在但维度不同时直接抛错，由运维执行显式重建
     * （{@link #dropCollection} + 重新回填）。写入路径绝不隐式删除整个集合——
     * 一次维度误配不应变成无人值守的数据清空。</p>
     *
     * @throws IllegalStateException 集合维度与 {@code dimension} 不一致，或后端不可用
     */
    void ensureCollection(String collection, int dimension);

    /**
     * 删除集合（不存在视为成功）。<b>会丢弃集合内全部向量</b>，
     * 只允许由显式重建链路（{@code EmbeddingService#resetCollections}）调用。
     */
    void dropCollection(String collection);

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
     * @param candidateIds 候选 id 限定（为空表示不限定）；在线库据此过滤
     * @return 命中（id + score，按 score 降序）
     */
    List<IdScore> query(String collection, double[] vector, int topK, List<Long> candidateIds);

    /** 不限定候选的召回。 */
    default List<IdScore> query(String collection, double[] vector, int topK) {
        return query(collection, vector, topK, null);
    }
}
