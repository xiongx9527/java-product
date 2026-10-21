package com.xiongx9527.askdata.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
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
 * <p>向量以 JSON 数组文本存于各表的 {@code embedding} 列（SQLBot 的 pgvector 列在
 * askdata 的 H2 形态下为 JSON 文本，语义等价）。</p>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final LocalEmbeddingModel localEmbeddingModel;

    private final ChatModelRouter modelRouter;

    private final ChatModelClient modelClient;

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    public EmbeddingService(LocalEmbeddingModel localEmbeddingModel, ChatModelRouter modelRouter,
                            ChatModelClient modelClient, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.localEmbeddingModel = localEmbeddingModel;
        this.modelRouter = modelRouter;
        this.modelClient = modelClient;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
     * 写入向量到指定表的 {@code embedding} 列。
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
        try {
            jdbc.update("UPDATE " + table + " SET embedding = ? WHERE id = ?", toJson(vector), id);
            return true;
        } catch (Exception e) {
            log.info("写入向量失败 {}/{}: {}", table, id, e.getMessage());
            return false;
        }
    }

    /** 可写入向量的表白名单（防 SQL 注入）。 */
    private static boolean isWritableTable(String table) {
        if (table == null) {
            return false;
        }
        return "terminology".equals(table) || "data_training".equals(table)
                || "core_table".equals(table) || "core_datasource".equals(table);
    }

    // ------------------------------------------------------------------
    // 余弦相似度召回
    // ------------------------------------------------------------------

    /**
     * 按余弦相似度召回向量记录。
     *
     * <p>对齐 SQLBot 的 {@code embedding <=> :embedding} 语义（余弦距离取反得相似度），
     * 但在应用侧计算（askdata 用 JSON 文本存向量，无 pgvector 算子）。</p>
     *
     * @param candidates 候选记录（每条含 {@code embedding} 文本）
     * @param question   查询文本（先算向量）
     * @param threshold  相似度阈值（低于则不返回）
     * @param topCount   返回条数上限
     * @return 命中的记录（按相似度降序）
     */
    public List<java.util.Map<String, Object>> recallByVector(List<java.util.Map<String, Object>> candidates,
                                                             String question, double threshold, int topCount) {
        double[] queryVector = embed(question);
        if (queryVector == null || candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        List<java.util.Map<String, Object>> hits = new ArrayList<>();
        for (java.util.Map<String, Object> candidate : candidates) {
            double[] stored = parseVector(com.xiongx9527.askdata.common.util.Rows.str(candidate, "embedding"));
            if (stored == null || stored.length != queryVector.length) {
                continue;
            }
            double similarity = cosine(queryVector, stored);
            if (similarity > threshold) {
                java.util.Map<String, Object> hit = new java.util.LinkedHashMap<>(candidate);
                hit.put("similarity", similarity);
                hits.add(hit);
            }
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
