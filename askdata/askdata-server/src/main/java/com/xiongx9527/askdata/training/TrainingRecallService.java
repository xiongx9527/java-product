package com.xiongx9527.askdata.training;

import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.common.util.Rows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
                            + "FROM data_training WHERE oid = ? AND enabled = TRUE "
                            + "AND embedding IS NOT NULL");
            List<Object> args = new ArrayList<>();
            args.add(oid);
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
            return embeddingService.recallByVector(candidates, question, SIMILARITY_THRESHOLD, TOP_COUNT);
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
        if (question == null || question.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> candidates = jdbc.queryForList(
                    "SELECT id, datasource, advanced_application, question, description, embedding "
                            + "FROM data_training WHERE datasource = ? AND enabled = TRUE "
                            + "AND embedding IS NOT NULL", datasource);
            if (candidates.isEmpty()) {
                return new ArrayList<>();
            }
            return embeddingService.recallByVector(candidates, question, SIMILARITY_THRESHOLD, TOP_COUNT);
        } catch (Exception e) {
            log.info("SQL 示例向量召回失败（降级为词法）: {}", e.getMessage());
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
