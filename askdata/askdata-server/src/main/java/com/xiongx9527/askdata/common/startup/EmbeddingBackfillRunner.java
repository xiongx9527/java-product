package com.xiongx9527.askdata.common.startup;

import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.common.lock.SingleWorkerGuard;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.embedding.DatasourceEmbeddingService;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import com.xiongx9527.askdata.training.TrainingRecallService;
import javax.annotation.PostConstruct;
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
 * <p><b>回填对象</b>：术语（父术语与同义词）、SQL 示例、表、数据源。向量只存在在线库，
 * DB 的 embedding 列不再写入，因此不能靠「列值为空」挑选记录——启动回填按范围全量重算，
 * upsert 以记录 id 为 point id，重复写入即覆盖。</p>
 *
 * <p><b>集群内只执行一次</b>：由 {@link SingleWorkerGuard} 保证（单次尝试、不重试；
 * 未获得守卫的实例跳过）。应用关闭时释放守卫。</p>
 *
 * <p><b>降级</b>：无可用在线 embedding 模型时整体跳过；
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
     * <p>用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}：此时 Flyway 迁移
     * 已完成（表已建好），且不影响应用启动耗时。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("向量回填已禁用（askdata.embedding.backfill-enabled=false）");
            return;
        }
        if (!embeddingService.available()) {
            log.info("无可用在线 embedding 模型，跳过启动期向量回填"
                    + "（向量召回降级为词法，问数主链路不受影响）");
            return;
        }
        if (!singleWorkerGuard.tryAcquire(SingleWorkerGuard.EMBEDDING_BACKFILL_KEY)) {
            log.info("本次启动未获得单 worker 守卫，跳过向量回填（不重试）");
            return;
        }
        log.info("启动期向量回填开始：在线向量库（集合前缀 '{}'）",
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
    @javax.annotation.PreDestroy
    public void release() {
        singleWorkerGuard.release(SingleWorkerGuard.EMBEDDING_BACKFILL_KEY);
    }

    // ------------------------------------------------------------------
    // 四类回填
    // ------------------------------------------------------------------

    /** 重建入口：按工作空间对四类向量做全量强制重算（忽略「向量为空」过滤）。 */
    public int rebuildWorkspace(long oid) {
        int count = 0;
        count += backfillTerminology(oid);
        count += backfillTraining(oid);
        count += backfillTables(oid);
        count += backfillDatasources(oid);
        return count;
    }

    /** 术语回填：父术语（pid 为空）与同义词（pid 非空）各自的向量。 */
    public int backfillTerminology() {
        return backfillTerminology(null);
    }

    /**
     * 术语回填（可指定工作空间）。
     *
     * @param oid   为 null 时覆盖全部工作空间
     */
    public int backfillTerminology(Long oid) {
        try {
            Object[] args = oid == null ? new Object[0] : new Object[] {oid};
            List<Map<String, Object>> parents = jdbc.queryForList(
                    "SELECT id FROM terminology" + where("pid IS NULL", oid != null), args);
            List<Long> ids = idsOf(parents);
            terminologyRecallService.saveEmbeddings(ids);
            List<Map<String, Object>> children = jdbc.queryForList(
                    "SELECT DISTINCT pid AS id FROM terminology" + where("pid IS NOT NULL", oid != null),
                    args);
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
        return backfillTraining(null);
    }

    /** SQL 示例回填（可指定工作空间）。 */
    public int backfillTraining(Long oid) {
        try {
            Object[] args = oid == null ? new Object[0] : new Object[] {oid};
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM data_training" + where(null, oid != null), args);
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
        return backfillTables(null);
    }

    /** 表向量回填（按数据源所属工作空间过滤）。 */
    public int backfillTables(Long oid) {
        try {
            Object[] args = oid == null ? new Object[0] : new Object[] {oid};
            String dsFilter = oid == null ? null : "ds_id IN (SELECT id FROM core_datasource WHERE oid = ?)";
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM core_table" + where(dsFilter, false), args);
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
        return backfillDatasources(null);
    }

    /** 数据源向量回填（可指定工作空间）。 */
    public int backfillDatasources(Long oid) {
        try {
            Object[] args = oid == null ? new Object[0] : new Object[] {oid};
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM core_datasource" + where(null, oid != null), args);
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
     * 回填 / 重建的 WHERE 子句。
     *
     * <p>向量只存在在线库，DB 的 embedding 列不再写入，因此不能再用「{@code embedding IS NULL}」
     * 判断哪些记录缺向量——该列恒为空会把所有记录都当成待回填。这里只按业务条件筛选，
     * 是否重算由调用方（启动回填 / 重建任务）决定。</p>
     *
     * @param extra   附加条件（可为 null）
     * @param withOid 是否追加 {@code oid = ?}（参数由调用方按占位顺序提供）
     */
    private String where(String extra, boolean withOid) {
        List<String> conditions = new ArrayList<>();
        if (extra != null) {
            conditions.add(extra);
        }
        if (withOid) {
            conditions.add("oid = ?");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
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
