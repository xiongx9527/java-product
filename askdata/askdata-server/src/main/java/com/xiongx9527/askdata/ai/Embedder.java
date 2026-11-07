package com.xiongx9527.askdata.ai;

/**
 * 文本向量化入口（产品侧自实现 embedding，不依赖框架 {@code EmbeddingPort}）。
 *
 * <p>用接口隔离，使检索召回器（{@code AskDataVectorRetriever}）只依赖「文本 → 向量」，
 * 不直接耦合 {@code EmbeddingService}，避免与框架检索链路形成循环依赖。</p>
 */
public interface Embedder {

    /** 单文本向量；无可用模型或失败时返回 {@code null}（降级）。 */
    double[] embed(String text);
}
