package com.xiongx9527.askdata.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.ai.vector.VectorStore;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 向量服务（统一入口）：术语 / SQL 示例 / 表 / 数据源 四类对象的向量写入与召回。
 *
 * <p><b>模型优先级</b>（对齐 spec「本地 embedding 模型」场景）：</p>
 * <ol>
 *   <li>远程 embedding 模型（{@code ai_model.model_type=1}）已配置且可用 → 用远程；</li>
 *   <li>否则用 {@link LocalEmbeddingModel}（本地 ONNX，768 维归一化）；</li>
 *   <li>两者都不可用 → {@link #embed(String)} 返回 {@code null}，调用方降级为词法召回，
 *       <b>不抛异常</b>（问数主链路必须仍能返回结果）。</li>
 * </ol>
 *
 * <p>向量经可插拔的 {@link VectorStore} 后端持久化与召回（{@code askdata.vector-store.type}）：
 * {@code none}（缺省）为 DB JSON 列 + 应用内余弦；{@code qdrant} 为基于 langchain4j 的在线向量库
 * （ANN 检索）。在线库不可达时回落 DB 路径，问数主链路不报错。</p>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** DB 向量列镜像的长度上限（列宽 VARCHAR(10000)，留出余量）。 */
    private static final int MIRROR_MAX_CHARS = 9000;

    private final LocalEmbeddingModel localEmbeddingModel;

    private final ChatModelRouter modelRouter;

    private final ChatModelClient modelClient;

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    /**
     * 向量存储后端（可插拔）。构造期默认用 {@link com.xiongx9527.askdata.ai.vector.DbVectorStore}
     * 保留既有行为；Spring 装配时由 {@link #setVectorStore} 覆盖为配置指定的后端（none=DB / qdrant=在线）。
     */
    private VectorStore vectorStore;

    public EmbeddingService(LocalEmbeddingModel localEmbeddingModel, ChatModelRouter modelRouter,
                            ChatModelClient modelClient, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.localEmbeddingModel = localEmbeddingModel;
        this.modelRouter = modelRouter;
        this.modelClient = modelClient;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.vectorStore = new com.xiongx9527.askdata.ai.vector.DbVectorStore(jdbc, objectMapper, "askdata");
    }

    /** Spring 装配：注入配置指定的向量存储后端（覆盖构造期默认）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 当前向量存储后端是否为在线库（供启动日志 / 诊断观测）。 */
    public boolean isVectorStoreOnline() {
        return vectorStore != null && vectorStore.isOnline();
    }

    /** 当前向量存储后端的集合名前缀（诊断用）。 */
    public String vectorStoreCollectionPrefix() {
        return vectorStore == null ? "askdata" : vectorStore.collectionPrefix();
    }

    // ------------------------------------------------------------------
    // 向量计算
    // ------------------------------------------------------------------

    /**
     * 单文本向量。
     *
     * @return 向量；无任何可用模型或调用失败时返回 {@code null}（降级，不抛错）
     */
    public double[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        double[] remote = embedRemote(text);
        if (remote != null) {
            return remote;
        }
        return embedLocal(text);
    }

    /** 批量文本向量（远程优先，其次本地）。 */
    public List<double[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        List<double[]> remote = embedRemote(texts);
        if (remote != null) {
            return remote;
        }
        List<double[]> local = localEmbeddingModel.embedAll(texts);
        return local == null ? null : local;
    }

    /** 远程 embedding 模型向量（未配置/不可用返回 null）。 */
    private double[] embedRemote(String text) {
        Optional<ModelRef> embedding = modelRouter.embeddingModel();
        if (!embedding.isPresent() || !embedding.get().usable()) {
            return null;
        }
        List<double[]> vectors = embedRemote(Collections.singletonList(text));
        if (vectors == null || vectors.isEmpty()) {
            return null;
        }
        return vectors.get(0);
    }

    /** 远程 embedding 模型批量向量。 */
    private List<double[]> embedRemote(List<String> texts) {
        Optional<ModelRef> embedding = modelRouter.embeddingModel();
        if (!embedding.isPresent() || !embedding.get().usable()) {
            return null;
        }
        ModelRef model = embedding.get();
        try {
            List<double[]> vectors = modelClient.embed(model.apiDomain, model.apiKey, model.baseModel, texts);
            if (vectors == null || vectors.isEmpty() || vectors.size() != texts.size()) {
                return null;
            }
            return vectors;
        } catch (Exception e) {
            log.info("远程 embedding 调用失败（回落到本地模型）: {}", e.getMessage());
            return null;
        }
    }

    /** 本地 ONNX 模型向量。 */
    private double[] embedLocal(String text) {
        try {
            return localEmbeddingModel.embed(text);
        } catch (Exception e) {
            log.info("本地 embedding 推理失败（降级为词法召回）: {}", e.getMessage());
            return null;
        }
    }

    /** 是否存在可用向量模型（远程或本地）。 */
    public boolean available() {
        Optional<ModelRef> embedding = modelRouter.embeddingModel();
        if (embedding.isPresent() && embedding.get().usable()) {
            return true;
        }
        return localEmbeddingModel.available();
    }

    // ------------------------------------------------------------------
    // 向量读写
    // ------------------------------------------------------------------

    /** 向量序列化为 JSON 数组文本。 */
    public String toJson(double[] vector) {
        if (vector == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 JSON 数组文本为向量；非法/缺失返回 {@code null}。 */
    public double[] parseVector(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray() || node.isEmpty()) {
                return null;
            }
            double[] vector = new double[node.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = node.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写入向量：委托给当前 {@link VectorStore} 后端（在线库或 DB 回退）。
     *
     * <p>在线库启用时同步镜像 DB 向量列，使启动回填的「{@code embedding IS NULL}」判定保持幂等，
     * 并支持切回 {@code type=none} 时直接回落，无需重建。</p>
     *
     * @param table    表名（白名单内的业务表）
     * @param id       记录 id
     * @param vector   向量
     * @return 是否写入成功（失败仅记日志，不影响调用方主链路）
     */
    public boolean writeEmbedding(String table, long id, double[] vector) {
        if (vector == null || !isWritableTable(table)) {
            return false;
        }
        boolean online = vectorStore.isOnline();
        try {
            vectorStore.upsert(vectorStore.collectionFor(table), id, vector);
        } catch (Exception e) {
            log.info("写入向量库失败 {}/{}（回落主链路）: {}", table, id, e.getMessage());
            return false;
        }
        if (online) {
            String json = toJson(vector);
            // DB 向量列宽度有限（VARCHAR(10000)）：768 维放得下，1024 维及以上放不下。
            // 镜像只为「切回 type=none 免重填」提供便利，放不下就跳过——在线库仍是唯一存储。
            if (json != null && json.length() <= MIRROR_MAX_CHARS) {
                try {
                    jdbc.update("UPDATE " + table + " SET " + embeddingColumn(table) + " = ? WHERE id = ?",
                            json, id);
                } catch (Exception e) {
                    log.info("镜像写入向量列失败 {}/{}（不影响在线库）: {}", table, id, e.getMessage());
                }
            }
        }
        return true;
    }

    /**
     * 召回候选是否仍要求 DB 向量列非空。
     *
     * <p>在线库启用时向量只存在于在线库（DB 列可能因宽度限制写不进而为空），
     * 若仍以 {@code embedding IS NOT NULL} 过滤候选，会把有向量的记录全部漏掉；
     * 仅 DB 回退路径才需要该门槛（用于把无向量记录排除在候选外）。</p>
     */
    public boolean candidateRequiresEmbeddingColumn() {
        return !isVectorStoreOnline();
    }

    /**
     * 查询各候选记录的向量相似度（不截断、不设阈值）。
     *
     * <p>供需要把向量分与其它信号（如显式提及分）混合排序的调用方使用；在线库走 ANN，
     * {@code type=none} 走 DB 向量列 + 应用内余弦。无可用 embedding 模型或后端不可用时返回空表，
     * 调用方据此降级，<b>不抛异常</b>。</p>
     *
     * @param table        对象类型（即表名）
     * @param question     查询文本
     * @param candidateIds 候选 id（为空表示不限定）
     * @return id -> 相似度；缺失的 id 表示无向量或维度不匹配
     */
    public java.util.Map<Long, Double> vectorScores(String table, String question,
                                                    java.util.List<Long> candidateIds) {
        java.util.Map<Long, Double> scores = new java.util.HashMap<>();
        if (question == null || question.trim().isEmpty() || candidateIds == null || candidateIds.isEmpty()) {
            return scores;
        }
        double[] queryVector = embed(question);
        if (queryVector == null) {
            return scores;
        }
        try {
            int topK = Math.min(candidateIds.size(), 1000);
            for (VectorStore.IdScore hit : vectorStore.query(
                    vectorStore.collectionFor(table), queryVector, topK, candidateIds)) {
                scores.put(hit.id(), hit.score());
            }
        } catch (Exception e) {
            log.info("向量相似度查询失败 {}（降级）: {}", table, e.getMessage());
        }
        return scores;
    }

    /** 可写入向量的表白名单（防 SQL 注入）。 */
    private static boolean isWritableTable(String table) {
        if (table == null) {
            return false;
        }
        return "terminology".equals(table) || "data_training".equals(table)
                || "core_table".equals(table) || "core_datasource".equals(table);
    }

    /** 各对象类型的向量列名：表级为 {@code table_embedding}，其余为 {@code embedding}。 */
    private static String embeddingColumn(String table) {
        return "core_table".equals(table) ? "table_embedding" : "embedding";
    }

    // ------------------------------------------------------------------
    // 余弦相似度召回
    // ------------------------------------------------------------------

    /**
     * 按相似度召回向量记录（委托当前 {@link VectorStore} 后端）。
     *
     * <p>在线库启用时走 ANN 查询；{@code type=none} 或在线库不可达时回落 DB JSON 列 + 应用内余弦
     * （与既有语义一致：对齐 SQLBot 的 {@code embedding <=> :embedding}）。</p>
     *
     * <p>由于结果已按相似度降序，先取前 {@code topCount} 再按 {@code threshold} 过滤，
     * 与「先过滤再取前 N」等价（阈值过滤只会削掉降序尾部）。</p>
     *
     * @param table      对象类型（即表名，决定集合 / 向量列）
     * @param candidates 候选记录（至少含 {@code id}；回退路径另含 {@code embedding} 文本）
     * @param question   查询文本（先算向量）
     * @param threshold  相似度阈值（低于则不返回）
     * @param topCount   返回条数上限
     * @return 命中的记录（按相似度降序，附带 {@code similarity}）
     */
    public List<java.util.Map<String, Object>> recallByVector(String table,
                                                             List<java.util.Map<String, Object>> candidates,
                                                             String question, double threshold, int topCount) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        double[] queryVector = embed(question);
        if (queryVector == null) {
            return new ArrayList<>();
        }
        java.util.Map<Long, java.util.Map<String, Object>> byId =
                new java.util.HashMap<>(candidates.size() * 2);
        List<Long> candidateIds = new ArrayList<>(candidates.size());
        for (java.util.Map<String, Object> candidate : candidates) {
            long id = com.xiongx9527.askdata.common.util.Rows.lng(candidate, "id", 0L);
            byId.putIfAbsent(id, candidate);
            if (!candidateIds.contains(id)) {
                candidateIds.add(id);
            }
        }
        List<java.util.Map<String, Object>> hits = new ArrayList<>();
        try {
            for (VectorStore.IdScore hit : vectorStore.query(
                    vectorStore.collectionFor(table), queryVector, topCount, candidateIds)) {
                if (hit.score() <= threshold) {
                    continue;
                }
                java.util.Map<String, Object> row = byId.get(hit.id());
                if (row == null) {
                    continue;
                }
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(row);
                out.put("similarity", hit.score());
                hits.add(out);
            }
        } catch (Exception e) {
            log.info("向量召回失败 {}（降级为空）: {}", table, e.getMessage());
            return new ArrayList<>();
        }
        hits.sort((a, b) -> Double.compare(
                ((Number) b.get("similarity")).doubleValue(), ((Number) a.get("similarity")).doubleValue()));
        return hits.size() > topCount ? new ArrayList<>(hits.subList(0, topCount)) : hits;
    }

    /** 余弦相似度（复用 ChatModelClient 的实现，语义一致）。 */
    public static double cosine(double[] a, double[] b) {
        return ChatModelClient.cosine(a, b);
    }
}
