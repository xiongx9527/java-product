package com.xiongx9527.askdata.datasource.embedding;

import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.common.util.Rows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源级向量（{@code core_datasource.embedding}）写入与召回
 * （对齐 SQLBot {@code apps/datasource/embedding/ds_embedding.py}）。
 *
 * <p><b>写入</b>：数据源文档 = {@code 名称, 描述} + 各表的 schema 文本
 * （与 SQLBot 的 {@code save_ds_embedding} 同构）；数据源新增、勾选表、字段同步后触发。</p>
 *
 * <p><b>召回</b>：问题向量与数据源向量的余弦相似度降序，取前 {@link #TOP_COUNT} 个。
 * 向量不可用时返回空列表，调用方（数据源选择）保持词法（bigram Jaccard）降级路径。</p>
 */
@Service
public class DatasourceEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceEmbeddingService.class);

    /** 数据源向量召回条数（对齐 SQLBot DS_EMBEDDING_COUNT=10）。 */
    public static final int TOP_COUNT = 10;

    private final EmbeddingService embeddingService;

    private final JdbcTemplate jdbc;

    public DatasourceEmbeddingService(EmbeddingService embeddingService, JdbcTemplate jdbc) {
        this.embeddingService = embeddingService;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 写入数据源向量。
     *
     * @param datasourceId 数据源 id
     */
    public void saveEmbedding(long datasourceId) {
        if (!embeddingService.available()) {
            return;
        }
        try {
            String doc = datasourceDoc(datasourceId);
            if (doc.isEmpty()) {
                return;
            }
            double[] vector = embeddingService.embed(doc);
            if (vector != null) {
                embeddingService.writeEmbedding("core_datasource", datasourceId, vector);
            }
        } catch (Exception e) {
            log.info("数据源向量写入失败 ds={}（不影响主链路）: {}", datasourceId, e.getMessage());
        }
    }

    /**
     * 数据源文档（名称 + 描述 + 各表 schema）。
     *
     * <p>与 SQLBot {@code save_ds_embedding} 的文档结构一致：
     * {@code "{name}, {description}\n# Table: {table}[, {comment}]\n(field:type[, comment])\n]"}。</p>
     */
    public String datasourceDoc(long datasourceId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name, description FROM core_datasource WHERE id = ?", datasourceId);
            if (rows.isEmpty()) {
                return "";
            }
            Map<String, Object> ds = rows.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append(nullToEmpty(Rows.str(ds, "name"))).append(", ")
                    .append(nullToEmpty(Rows.str(ds, "description"))).append("\n");
            List<Map<String, Object>> tables = jdbc.queryForList(
                    "SELECT id, table_name, custom_comment, table_comment FROM core_table WHERE ds_id = ? "
                            + "ORDER BY table_name", datasourceId);
            for (Map<String, Object> table : tables) {
                sb.append("# Table: ").append(nullToEmpty(Rows.str(table, "table_name")));
                String comment = firstNonEmpty(Rows.str(table, "custom_comment"), Rows.str(table, "table_comment"));
                if (!comment.isEmpty()) {
                    sb.append(", ").append(comment);
                }
                sb.append("\n[\n");
                List<Map<String, Object>> fields = jdbc.queryForList(
                        "SELECT field_name, field_type, custom_comment, field_comment FROM core_field "
                                + "WHERE table_id = ? ORDER BY field_index",
                        Rows.lng(table, "id", 0L));
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
            }
            return sb.toString();
        } catch (Exception e) {
            log.info("数据源文档构建失败 ds={}: {}", datasourceId, e.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 召回
    // ------------------------------------------------------------------

    /**
     * 数据源向量召回（按相似度降序，取前 {@link #TOP_COUNT}）。
     *
     * @param question 查询问题
     * @param dsIds    候选数据源 id
     * @return 命中的数据源 id 列表（按相似度降序）；向量不可用时返回空列表
     */
    public List<Long> recall(String question, List<Long> dsIds) {
        if (question == null || question.trim().isEmpty() || dsIds == null || dsIds.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, embedding FROM core_datasource WHERE embedding IS NOT NULL AND id IN (");
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < dsIds.size(); i++) {
                sql.append(i > 0 ? ",?" : "?");
            }
            sql.append(")");
            args.addAll(dsIds);
            List<Map<String, Object>> candidates = jdbc.queryForList(sql.toString(), args.toArray());
            if (candidates.isEmpty()) {
                return new ArrayList<>();
            }
            List<Long> result = new ArrayList<>();
            for (Map<String, Object> hit : embeddingService.recallByVector(candidates, question, 0D, TOP_COUNT)) {
                result.add(Rows.lng(hit, "id", 0L));
            }
            return result;
        } catch (Exception e) {
            log.info("数据源向量召回失败（降级为词法）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 按向量相似度给数据源排序（相似度高的在前）。
     *
     * <p>供数据源选择使用：返回按相似度降序排列的候选 id（未命中向量的候选保留在末尾）。</p>
     */
    public List<Long> rankByVector(String question, List<Long> dsIds) {
        List<Long> ranked = recall(question, dsIds);
        if (ranked.isEmpty()) {
            return dsIds;
        }
        List<Long> result = new ArrayList<>(ranked);
        for (Long id : dsIds) {
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return result;
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

    /** 便于单测/诊断的文档片段视图。 */
    public Map<String, Object> docPreview(long datasourceId) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("id", datasourceId);
        preview.put("doc", datasourceDoc(datasourceId));
        return preview;
    }
}
