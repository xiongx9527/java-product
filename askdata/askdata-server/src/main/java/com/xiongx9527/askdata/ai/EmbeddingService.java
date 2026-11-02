package com.xiongx9527.askdata.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.ai.vector.VectorStore;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 向量服务（统一入口）：术语 / SQL 示例 / 表 / 数据源 四类对象的向量写入与召回。
 *
 * <p><b>模型来源</b>：只使用在线向量模型（{@code ai_model.model_type=1} 且 {@code enable=1}、
 * {@code valid=1}）。本地 ONNX 模型已移除（D7）——未配置可用在线向量模型时
 * {@link #embed(String)} 返回 {@code null}，问答入口按统一错误阻断，不再静默降级为词法召回。</p>
 *
 * <p>向量只持久化在在线 Qdrant（{@link VectorStore} 唯一实现）：DB 的 embedding 列不再存向量，
 * 也不再回落应用内余弦。在线库不可达时向量写入/召回显式失败并由调用方降级为词法，不静默写本地。</p>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ChatModelRouter modelRouter;

    private final ChatModelClient modelClient;

    private final ObjectMapper objectMapper;

    /**
     * 向量存储后端（在线 Qdrant）。由 Spring 注入；直接构造的单元测试必须显式
     * {@link #setVectorStore} 传入替身，未设置时向量读写会失败而不是静默落本地。
     */
    private VectorStore vectorStore;

    /**
     * 已确认维度的集合（collection → dimension）。
     *
     * <p>在线向量库的集合维度是建集合时固定的：切换默认向量模型导致维度变化时，必须先按新维度
     * 重建集合（旧维度数据随之作废），否则后续 upsert 会因维度不匹配被拒。</p>
     */
    private final java.util.Map<String, Integer> ensuredDimensions = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 维度不一致的集合（collection → 期望维度）。
     *
     * <p>记录后同一维度不再反复打后端：一次维度误配会让每次写入都失败，回填上千张表时
     * 不应产生上千条重复告警；运维执行重建后由 {@link #resetCollections()} 清空。</p>
     */
    private final java.util.Map<String, Integer> brokenDimensions = new java.util.concurrent.ConcurrentHashMap<>();

    /** 已告警过的损坏集合（避免回填时同一原因重复刷屏）。 */
    private final java.util.Set<String> brokenLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public EmbeddingService(ChatModelRouter modelRouter,
                            ChatModelClient modelClient, ObjectMapper objectMapper) {
        this.modelRouter = modelRouter;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    /** Spring 装配：注入在线向量存储后端。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 当前默认向量模型 id；无可用模型返回 {@code null}。
     *
     * <p>向量模型是全局配置（不按工作空间选择），写入向量时把它记到业务行上，
     * 模型切换后即可判定该行向量是否失效。</p>
     */
    public Long currentEmbeddingModelId() {
        Optional<ModelRef> embedding = modelRouter.embeddingModel();
        return embedding.isPresent() && embedding.get().usable() ? embedding.get().id : null;
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
        List<double[]> vectors = embedRemote(Collections.singletonList(text));
        return vectors == null || vectors.isEmpty() ? null : vectors.get(0);
    }

    /** 批量文本向量（只用在线向量模型；不可用返回 null，由调用方按降级路径处理）。 */
    public List<double[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        return embedRemote(texts);
    }

    /**
     * 远程 embedding 模型批量向量（全项目唯一的「取模型 → usable 校验 → 调端点 → 失败返 null」实现）。
     *
     * <p>表级召回（{@code TableEmbeddingService}）与 schema 上下文（{@code SchemaContextBuilder}）
     * 都委托到这里，避免同一段降级逻辑抄三份、改一处漏两处。</p>
     */
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
            log.warn("远程 embedding 调用失败（降级为词法/空结果）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 是否存在可用向量模型。
     *
     * <p>只反映在线向量模型（{@code enable=1 && valid=1}）；本地 ONNX 已移除，
     * 不允许以本地模型掩盖「配置未生效」。</p>
     */
    public boolean available() {
        Optional<ModelRef> embedding = modelRouter.embeddingModel();
        return embedding.isPresent() && embedding.get().usable();
    }

    // ------------------------------------------------------------------
    // 向量读写
    // ------------------------------------------------------------------

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
     * 确认集合维度（同维度只确认一次，避免每次写入都远程调用）。
     *
     * <p>维度不一致时后端抛错，这里把「该集合 + 该维度」记为已知损坏并短路后续写入：
     * 回填上千行不会产生上千次远程探测，也不会刷屏。损坏状态只由显式重建清除。</p>
     */
    private void ensureCollection(String collection, int dimension) {
        Integer ensured = ensuredDimensions.get(collection);
        if (ensured != null && ensured == dimension) {
            return;
        }
        Integer broken = brokenDimensions.get(collection);
        if (broken != null && broken == dimension) {
            throw new IllegalStateException("集合 " + collection + " 维度未就绪（需 " + dimension
                    + "）：请执行向量索引重建");
        }
        try {
            vectorStore.ensureCollection(collection, dimension);
        } catch (VectorStore.DimensionMismatchException e) {
            // 只有「维度不对」才是需要重建的持久状态；后端不可达等临时故障不得短路后续写入
            brokenDimensions.put(collection, dimension);
            throw e;
        }
        brokenDimensions.remove(collection);
        ensuredDimensions.put(collection, dimension);
    }

    /**
     * 清空在线集合与维度缓存，供显式重建调用。
     *
     * <p>重建是全量重算，旧 point 一律作废；先删集合再回填，既保证维度按新模型生效，
     * 也顺带清理业务行删除后遗留的孤儿点。删除失败只记日志：集合可能本就不存在。</p>
     */
    public void resetCollections() {
        ensuredDimensions.clear();
        brokenDimensions.clear();
        brokenLogged.clear();
        if (vectorStore == null) {
            log.warn("未注入向量存储，跳过集合清理");
            return;
        }
        for (String objectType : WRITABLE_TABLES) {
            try {
                vectorStore.dropCollection(vectorStore.collectionFor(objectType));
            } catch (Exception e) {
                log.warn("清理集合 {} 失败（继续重建）: {}", objectType, e.getMessage());
            }
        }
    }

    /**
     * 写入向量：委托给在线向量库。
     *
     * <p>不镜像 DB 向量列：在线库是唯一存储，避免出现「配了在线库却实际存在本地」的隐性路径。</p>
     *
     * @param table  表名（白名单内的业务表）
     * @param id     记录 id
     * @param vector 向量
     * @return 是否写入成功（失败仅记日志，不影响调用方主链路）
     */
    public boolean writeEmbedding(String table, long id, double[] vector) {
        if (vector == null || !isWritableTable(table)) {
            return false;
        }
        if (vectorStore == null) {
            log.warn("未注入向量存储，跳过写入 {}/{}", table, id);
            return false;
        }
        try {
            ensureCollection(vectorStore.collectionFor(table), vector.length);
            vectorStore.upsert(vectorStore.collectionFor(table), id, vector);
        } catch (Exception e) {
            logWriteFailure(table, id, e);
            return false;
        }
        return true;
    }

    /** 写入失败日志：同一集合的「维度未就绪」只告警一次，避免回填上千行时刷屏。 */
    private void logWriteFailure(String table, long id, Exception e) {
        String collection = vectorStore.collectionFor(table);
        if (brokenDimensions.containsKey(collection) && !brokenLogged.add(collection)) {
            log.debug("写入向量库失败 {}/{}（同一集合已告警，等待重建）: {}", table, id, e.getMessage());
            return;
        }
        log.warn("写入向量库失败 {}/{}（不回落本地存储）: {}", table, id, e.getMessage());
    }

    /**
     * 删除向量：业务行删除后同步清理在线库中的 point，避免孤儿点。
     *
     * <p>失败只记日志：业务删除已提交，向量残留由后续重建（重建集合）兜底，不回滚业务。</p>
     *
     * @param table 对象类型（即表名）
     * @param id    记录 id
     */
    public void deleteEmbedding(String table, long id) {
        if (!isWritableTable(table)) {
            return;
        }
        if (vectorStore == null) {
            log.warn("未注入向量存储，跳过删除 {}/{}", table, id);
            return;
        }
        try {
            vectorStore.delete(vectorStore.collectionFor(table), id);
        } catch (Exception e) {
            log.warn("删除向量失败 {}/{}（孤儿点由重建清理）: {}", table, id, e.getMessage());
        }
    }

    /**
     * 查询各候选记录的向量相似度（不截断、不设阈值）。
     *
     * <p>供需要把向量分与其它信号（如显式提及分）混合排序的调用方使用。无可用 embedding 模型
     * 或向量库不可用时返回空表，调用方据此降级，<b>不抛异常</b>。</p>
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
        if (vectorStore == null) {
            log.warn("未注入向量存储，跳过向量相似度查询 {}", table);
            return scores;
        }
        try {
            int topK = Math.min(candidateIds.size(), 1000);
            for (VectorStore.IdScore hit : vectorStore.query(
                    vectorStore.collectionFor(table), queryVector, topK, candidateIds)) {
                scores.put(hit.id(), hit.score());
            }
        } catch (Exception e) {
            log.warn("向量相似度查询失败 {}（降级）: {}", table, e.getMessage());
        }
        return scores;
    }

    /** 可写入向量的对象类型白名单（同时是集合名的后缀）。 */
    private static final List<String> WRITABLE_TABLES = Collections.unmodifiableList(
            java.util.Arrays.asList("terminology", "data_training", "core_table", "core_datasource"));

    /** 是否是可写入向量的对象类型（白名单，防 SQL 注入 / 任意集合名）。 */
    private static boolean isWritableTable(String table) {
        return table != null && WRITABLE_TABLES.contains(table);
    }

    // ------------------------------------------------------------------
    // 余弦相似度召回
    // ------------------------------------------------------------------

    /**
     * 按相似度召回向量记录（委托当前 {@link VectorStore} 后端）。
     *
     * <p>走在线库 ANN 查询（对齐 SQLBot 的 {@code embedding <=> :embedding} 语义）；向量库不可用时
     * 返回空表，调用方降级，<b>不抛异常</b>。</p>
     *
     * <p>由于结果已按相似度降序，先取前 {@code topCount} 再按 {@code threshold} 过滤，
     * 与「先过滤再取前 N」等价（阈值过滤只会削掉降序尾部）。</p>
     *
     * @param table      对象类型（即表名，决定集合 / 向量列）
     * @param candidates 候选记录（至少含 {@code id}）
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
        if (vectorStore == null) {
            log.warn("未注入向量存储，跳过向量召回 {}", table);
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
            log.warn("向量召回失败 {}（降级为空）: {}", table, e.getMessage());
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
