package com.xiongx9527.askdata.training;

import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.common.util.Rows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * SQL 示例（data_training）向量写入与召回
 * （对齐 SQLBot {@code apps/data_training/curd/data_training.py}）。
 *
 * <p><b>写入</b>：{@code data_training.embedding} 列（SQLBot 中该列从不写入，
 * askdata 补齐此缺口）。</p>
 *
 * <p><b>召回</b>：向量相似度 > {@link #SIMILARITY_THRESHOLD}，取前 {@link #TOP_COUNT} 条；
 * 过滤条件为 {@code oid + enabled + （datasource 命中 或 advanced_application 命中）}。</p>
 */
@Service
public class TrainingRecallService {

    private static final Logger log = LoggerFactory.getLogger(TrainingRecallService.class);

    /** SQL 示例向量相似度阈值（对齐 SQLBot EMBEDDING_DATA_TRAINING_SIMILARITY=0.4）。 */
    public static final double SIMILARITY_THRESHOLD = 0.4D;

    /**
     * A27：SQL 示例向量召回阈值（{@code askdata.vector.recall-threshold}，缺省 0.4）。
     *
     * <p>缺省值与 {@link #SIMILARITY_THRESHOLD} 一致，未配置时行为不变。</p>
     */
    @Value("${askdata.vector.recall-threshold:0.4}")
    private double similarityThreshold = SIMILARITY_THRESHOLD;

    /** SQL 示例向量召回条数（对齐 SQLBot EMBEDDING_DATA_TRAINING_TOP_COUNT=5）。 */
    public static final int TOP_COUNT = 5;

    private final EmbeddingService embeddingService;

    private final JdbcTemplate jdbc;

    public TrainingRecallService(EmbeddingService embeddingService, JdbcTemplate jdbc) {
        this.embeddingService = embeddingService;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 写入 SQL 示例向量。
     *
     * @param ids data_training id 列表
     */
    public void saveEmbeddings(List<Long> ids) {
        if (ids == null || ids.isEmpty() || !embeddingService.available()) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, question FROM data_training WHERE id IN (" + placeholders(ids.size()) + ")",
                    ids.toArray());
            if (rows.isEmpty()) {
                return;
            }
            List<String> questions = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                questions.add(Rows.str(row, "question"));
            }
            List<double[]> vectors = embeddingService.embedAll(questions);
            if (vectors == null || vectors.size() != rows.size()) {
                return;
            }
            for (int i = 0; i < rows.size(); i++) {
                embeddingService.writeEmbedding("data_training", Rows.lng(rows.get(i), "id", 0L), vectors.get(i));
            }
        } catch (Exception e) {
            log.info("SQL 示例向量写入失败（不影响主链路）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 召回
    // ------------------------------------------------------------------

    /**
     * SQL 示例向量召回。
     *
     * @param question      查询问题
     * @param oid           工作空间 id
     * @param datasource    数据源 id（可空）
     * @param advancedApp   高级应用 id（可空）
     * @return 命中的样例行（按相似度降序）
     */
    public List<Map<String, Object>> recall(String question, long oid, Long datasource, Long advancedApp) {
        if (question == null || question.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, datasource, advanced_application, question, description, embedding "
                            + "FROM data_training WHERE oid = ? AND enabled = TRUE");
            List<Object> args = new ArrayList<>();
            args.add(oid);
            // 在线库启用时向量不在 DB 列，不能再以 embedding IS NOT NULL 过滤候选
            if (embeddingService.candidateRequiresEmbeddingColumn()) {
                sql.append(" AND embedding IS NOT NULL");
            }
            if (advancedApp != null) {
                sql.append(" AND advanced_application = ?");
                args.add(advancedApp);
            } else if (datasource != null) {
                sql.append(" AND (datasource = ? OR advanced_application IS NOT NULL)");
                args.add(datasource);
            }
            List<Map<String, Object>> candidates = jdbc.queryForList(sql.toString(), args.toArray());
            if (candidates.isEmpty()) {
                return new ArrayList<>();
            }
            return embeddingService.recallByVector("data_training", candidates, question,
                    similarityThreshold, TOP_COUNT);
        } catch (Exception e) {
            log.info("SQL 示例向量召回失败（降级为词法）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * SQL 示例向量召回（按数据源过滤，不限工作空间）。
     *
     * <p>供问数主链路使用：数据源已由数据源服务的归属校验保证，
     * 故此处只按 {@code datasource + enabled} 过滤，避免在调用链上额外引入工作空间上下文。</p>
     *
     * @param question   查询问题
     * @param datasource 数据源 id
     * @return 命中的样例行（按相似度降序）
     */
    public List<Map<String, Object>> recallByDatasource(String question, long datasource) {
        return recallByDatasource(question, datasource, null);
    }

    /**
     * SQL 示例向量召回（按数据源 + 高级应用隔离）。
     *
     * <p>{@code advancedApp} 非空时只召回该高级应用的训练样例（A9 知识范围隔离），
     * 避免跨应用泄漏示例知识。</p>
     */
    /**
     * A14：SQL 示例双路召回（对齐 SQLBot {@code select_training_by_question}）。
     *
     * <p>两路并行、按 id 合并去重（先词法后向量）：</p>
     * <ol>
     *   <li><b>关键词命中（ILIKE 双向）</b>：存储问题包含用户句子，或用户句子包含存储问题
     *       （SQLBot: {@code :sentence ILIKE '%' || question || '%' OR question ILIKE '%' || :sentence || '%'}）；</li>
     *   <li><b>向量召回</b>：相似度 &gt; {@link #SIMILARITY_THRESHOLD} 取前 {@link #TOP_COUNT} 条。</li>
     * </ol>
     *
     * <p>任一路失败不影响另一路（与 SQLBot 的 try/except 语义一致），两路都不可用时返回空列表。</p>
     *
     * @param question    查询问题
     * @param datasource  数据源 id
     * @param advancedApp 高级应用 id（可空；非空时只召回该应用的样例）
     * @return 命中的样例行（词法命中在前，向量命中补后，按 id 去重）
     */
    public List<Map<String, Object>> recallByDatasource(String question, long datasource, Long advancedApp) {
        if (question == null || question.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String sentence = question.trim();
        // 第一路：关键词命中（ILIKE 双向）
        List<Map<String, Object>> keywordHits = keywordRecall(sentence, datasource, advancedApp);
        // 第二路：向量召回
        List<Map<String, Object>> vectorHits = vectorRecall(sentence, datasource, advancedApp);
        // 合并去重：先词法后向量（与 SQLBot _list 追加顺序一致）
        List<Long> seen = new ArrayList<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (List<Map<String, Object>> hits : Arrays.asList(keywordHits, vectorHits)) {
            for (Map<String, Object> hit : hits) {
                long id = Rows.lng(hit, "id", 0L);
                if (seen.contains(id)) {
                    continue;
                }
                seen.add(id);
                merged.add(hit);
            }
        }
        return merged;
    }

    /**
     * A14 第一路：关键词命中。ILIKE 双向匹配（含反方向：用户句子包含存储问题）。
     *
     * <p>SQLBot 用 PG 的 {@code ILIKE}；此处用 {@code LOWER(...) LIKE LOWER(...)}
     * 保持跨库可移植（MySQL / H2 / PG 均可执行）。</p>
     */
    private List<Map<String, Object>> keywordRecall(String sentence, long datasource, Long advancedApp) {
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, datasource, advanced_application, question, description, embedding "
                            + "FROM data_training WHERE datasource = ? AND enabled = TRUE "
                            + "AND (LOWER(question) LIKE LOWER(?) OR LOWER(?) LIKE LOWER(CONCAT('%', question, '%')))");
            List<Object> args = new ArrayList<>();
            args.add(datasource);
            args.add("%" + sentence + "%");
            args.add(sentence);
            if (advancedApp != null) {
                sql.append(" AND advanced_application = ?");
                args.add(advancedApp);
            }
            return jdbc.queryForList(sql.toString(), args.toArray());
        } catch (Exception e) {
            log.info("SQL 示例关键词召回失败（不影响向量召回）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** A14 第二路：向量召回（相似度阈值 + TOP-N）。 */
    private List<Map<String, Object>> vectorRecall(String sentence, long datasource, Long advancedApp) {
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, datasource, advanced_application, question, description, embedding "
                            + "FROM data_training WHERE datasource = ? AND enabled = TRUE");
            List<Object> args = new ArrayList<>();
            args.add(datasource);
            // 在线库启用时向量不在 DB 列，不能再以 embedding IS NOT NULL 过滤候选
            if (embeddingService.candidateRequiresEmbeddingColumn()) {
                sql.append(" AND embedding IS NOT NULL");
            }
            if (advancedApp != null) {
                sql.append(" AND advanced_application = ?");
                args.add(advancedApp);
            }
            List<Map<String, Object>> candidates = jdbc.queryForList(sql.toString(), args.toArray());
            if (candidates.isEmpty()) {
                return new ArrayList<>();
            }
            return embeddingService.recallByVector("data_training", candidates, sentence,
                    similarityThreshold, TOP_COUNT);
        } catch (Exception e) {
            log.info("SQL 示例向量召回失败（不影响关键词召回）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i > 0 ? ",?" : "?");
        }
        return sb.toString();
    }
}
