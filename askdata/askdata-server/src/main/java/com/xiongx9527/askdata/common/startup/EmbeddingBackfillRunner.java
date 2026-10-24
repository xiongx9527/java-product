package com.xiongx9527.askdata.common.startup;

import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.common.lock.SingleWorkerGuard;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.embedding.DatasourceEmbeddingService;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import com.xiongx9527.askdata.training.TrainingRecallService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动期向量回填（对齐 SQLBot {@code main.py} 的三个 {@code init_*_embedding_data}）。
 *
 * <p><b>回填对象</b>：术语（父术语与同义词）、SQL 示例、表、数据源——四类对象中
 * {@code embedding} 为空的记录。</p>
 *
 * <p><b>集群内只执行一次</b>：由 {@link SingleWorkerGuard} 保证（单次尝试、不重试；
 * 未获得守卫的实例跳过）。应用关闭时释放守卫。</p>
 *
 * <p><b>降级</b>：无任何可用 embedding 模型（远程未配置且本地模型缺失）时整体跳过；
 * 单类回填失败只记日志，不影响问数主链路可用性。</p>
 */
@Component
public class EmbeddingBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillRunner.class);

    private final EmbeddingService embeddingService;

    private final TerminologyRecallService terminologyRecallService;

    private final TrainingRecallService trainingRecallService;

    private final DatasourceEmbeddingService datasourceEmbeddingService;

    private final SingleWorkerGuard singleWorkerGuard;

    private final JdbcTemplate jdbc;

    /** 回填开关（{@code askdata.embedding.backfill-enabled}，缺省开启）。 */
    private final boolean enabled;

    public EmbeddingBackfillRunner(EmbeddingService embeddingService,
                                   TerminologyRecallService terminologyRecallService,
                                   TrainingRecallService trainingRecallService,
                                   DatasourceEmbeddingService datasourceEmbeddingService,
                                   SingleWorkerGuard singleWorkerGuard,
                                   JdbcTemplate jdbc,
                                   @Value("${askdata.embedding.backfill-enabled:true}") boolean enabled) {
        this.embeddingService = embeddingService;
        this.terminologyRecallService = terminologyRecallService;
        this.trainingRecallService = trainingRecallService;
        this.datasourceEmbeddingService = datasourceEmbeddingService;
        this.singleWorkerGuard = singleWorkerGuard;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /**
     * 应用就绪后执行回填（对齐 SQLBot lifespan 中的三次回填调用）。
     *
     * <p>用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}：此时 schema.sql
     * 已执行完毕（表已建好），且不影响应用启动耗时。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("向量回填已禁用（askdata.embedding.backfill-enabled=false）");
            return;
        }
        if (!embeddingService.available()) {
            log.info("无可用 embedding 模型（远程未配置且本地模型缺失），跳过启动期向量回填"
                    + "（向量召回降级为词法，问数主链路不受影响）");
            return;
        }
        if (!singleWorkerGuard.tryAcquire(SingleWorkerGuard.EMBEDDING_BACKFILL_KEY)) {
            log.info("本次启动未获得单 worker 守卫，跳过向量回填（不重试）");
            return;
        }
        log.info("启动期向量回填开始：存储后端={}（集合前缀 '{}'）",
                embeddingService.isVectorStoreOnline() ? "在线向量库" : "DB JSON 列",
                embeddingService.vectorStoreCollectionPrefix());
        try {
            backfillTerminology();
            backfillTraining();
            backfillTables();
            backfillDatasources();
        } catch (Exception e) {
            log.warn("启动期向量回填异常（不影响问数主链路）: {}", e.getMessage());
        }
    }

    /** 释放守卫（应用关闭时）。 */
    @jakarta.annotation.PreDestroy
    public void release() {
        singleWorkerGuard.release(SingleWorkerGuard.EMBEDDING_BACKFILL_KEY);
    }

    // ------------------------------------------------------------------
    // 四类回填
    // ------------------------------------------------------------------

    /** 术语回填：父术语（pid 为空）与同义词（pid 非空）各自的向量。 */
    public int backfillTerminology() {
        try {
            List<Map<String, Object>> parents = jdbc.queryForList(
                    "SELECT id FROM terminology" + pendingFilter("pid IS NULL"));
            List<Long> ids = idsOf(parents);
            terminologyRecallService.saveEmbeddings(ids);
            List<Map<String, Object>> children = jdbc.queryForList(
                    "SELECT DISTINCT pid AS id FROM terminology" + pendingFilter("pid IS NOT NULL"));
            List<Long> childParents = idsOf(children);
            terminologyRecallService.saveEmbeddings(childParents);
            int total = ids.size() + childParents.size();
            if (total > 0) {
                log.info("术语向量回填完成：{} 条", total);
            }
            return total;
        } catch (Exception e) {
            log.info("术语向量回填失败（降级为词法召回）: {}", e.getMessage());
            return 0;
        }
    }

    /** SQL 示例回填。 */
    public int backfillTraining() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM data_training" + pendingFilter(null));
            List<Long> ids = idsOf(rows);
            trainingRecallService.saveEmbeddings(ids);
            if (!ids.isEmpty()) {
                log.info("SQL 示例向量回填完成：{} 条", ids.size());
            }
            return ids.size();
        } catch (Exception e) {
            log.info("SQL 示例向量回填失败（降级为词法召回）: {}", e.getMessage());
            return 0;
        }
    }

    /** 表向量回填（{@code core_table.table_embedding}）。 */
    public int backfillTables() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM core_table" + pendingFilter(null));
            int count = 0;
            for (Map<String, Object> row : rows) {
                long id = Rows.lng(row, "id", 0L);
                double[] vector = embeddingService.embed(tableDoc(id));
                if (vector != null && embeddingService.writeEmbedding("core_table", id, vector)) {
                    count++;
                }
            }
            if (count > 0) {
                log.info("表向量回填完成：{} 条", count);
            }
            return count;
        } catch (Exception e) {
            log.info("表向量回填失败（降级为词法召回）: {}", e.getMessage());
            return 0;
        }
    }

    /** 数据源向量回填（{@code core_datasource.embedding}）。 */
    public int backfillDatasources() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM core_datasource" + pendingFilter(null));
            int count = 0;
            for (Map<String, Object> row : rows) {
                long id = Rows.lng(row, "id", 0L);
                datasourceEmbeddingService.saveEmbedding(id);
                count++;
            }
            if (count > 0) {
                log.info("数据源向量回填完成：{} 条", count);
            }
            return count;
        } catch (Exception e) {
            log.info("数据源向量回填失败（降级为词法召回）: {}", e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 表文档（与 SQLBot {@code save_table_embedding} 同构）。 */
    private String tableDoc(long tableId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT table_name, custom_comment, table_comment FROM core_table WHERE id = ?", tableId);
            if (rows.isEmpty()) {
                return "";
            }
            Map<String, Object> table = rows.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("# Table: ").append(nullToEmpty(Rows.str(table, "table_name")));
            String comment = firstNonEmpty(Rows.str(table, "custom_comment"), Rows.str(table, "table_comment"));
            if (!comment.isEmpty()) {
                sb.append(", ").append(comment);
            }
            sb.append("\n[\n");
            List<Map<String, Object>> fields = jdbc.queryForList(
                    "SELECT field_name, field_type, custom_comment, field_comment FROM core_field "
                            + "WHERE table_id = ? ORDER BY field_index", tableId);
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> field : fields) {
                String fieldComment = firstNonEmpty(Rows.str(field, "custom_comment"),
                        Rows.str(field, "field_comment"));
                if (fieldComment.isEmpty()) {
                    parts.add("(" + nullToEmpty(Rows.str(field, "field_name")) + ":"
                            + nullToEmpty(Rows.str(field, "field_type")) + ")");
                } else {
                    parts.add("(" + nullToEmpty(Rows.str(field, "field_name")) + ":"
                            + nullToEmpty(Rows.str(field, "field_type")) + ", " + fieldComment + ")");
                }
            }
            sb.append(String.join(",\n", parts));
            sb.append("\n]\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 待回填记录的 WHERE 子句（直接拼在 SELECT 之后）。
     *
     * <p>{@code type=none} 时按 DB 向量列为空筛选（既有行为，写库后自然收敛）；
     * 在线库启用时不再写 DB 向量列，无法据此判断是否已回填，故全量选取——
     * upsert 以记录 id 为 point id，重复写入即覆盖，保证幂等。</p>
     *
     * @param extra 附加条件（如 {@code "pid IS NULL"}），可为 {@code null}
     */
    private String pendingFilter(String extra) {
        if (embeddingService.isVectorStoreOnline()) {
            return extra == null ? "" : " WHERE " + extra;
        }
        String base = extra == null ? "embedding IS NULL" : "embedding IS NULL AND " + extra;
        return " WHERE " + base;
    }

    private static List<Long> idsOf(List<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ids.add(Rows.lng(row, "id", 0L));
        }
        return ids;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return "";
    }
}
