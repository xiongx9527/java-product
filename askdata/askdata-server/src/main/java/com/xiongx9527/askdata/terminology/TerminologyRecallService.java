package com.xiongx9527.askdata.terminology;

import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.common.util.Rows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 术语向量写入与召回（对齐 SQLBot {@code apps/terminology/curd/terminology.py}）。
 *
 * <p><b>写入</b>：父术语与同义词（子记录）各自的 {@code embedding} 列都写入向量
 * （SQLBot 的 {@code save_embeddings} 对 {@code id IN ids OR pid IN ids} 的记录统一 embed）。</p>
 *
 * <p><b>召回</b>：向量召回（余弦相似度 > 阈值，取前 N 条）+ <b>词法降级</b>
 * （子串/ILIKE 匹配）。向量不可用时只返回词法命中，问数主链路不报错。</p>
 */
@Service
public class TerminologyRecallService {

    private static final Logger log = LoggerFactory.getLogger(TerminologyRecallService.class);

    /** 术语向量相似度阈值缺省值（对齐 SQLBot EMBEDDING_TERMINOLOGY_SIMILARITY=0.4）。 */
    public static final double SIMILARITY_THRESHOLD = 0.4D;

    /**
     * A27：术语向量召回阈值（{@code askdata.vector.recall-threshold}，缺省 0.4）。
     *
     * <p>缺省值与 {@link #SIMILARITY_THRESHOLD} 一致，未配置时行为不变；调高更严格、调低更宽松。</p>
     */
    @Value("${askdata.vector.recall-threshold:0.4}")
    private double similarityThreshold = SIMILARITY_THRESHOLD;

    /** 术语向量召回条数（对齐 SQLBot EMBEDDING_TERMINOLOGY_TOP_COUNT=5）。 */
    public static final int TOP_COUNT = 5;

    private final EmbeddingService embeddingService;

    private final JdbcTemplate jdbc;

    public TerminologyRecallService(EmbeddingService embeddingService, JdbcTemplate jdbc) {
        this.embeddingService = embeddingService;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 为术语（及其同义词子记录）写入向量。
     *
     * @param ids 术语 id 列表（父记录 id；同义词子记录一并写入）
     */
    public void saveEmbeddings(List<Long> ids) {
        if (ids == null || ids.isEmpty() || !embeddingService.available()) {
            return;
        }
        try {
            // 两个 IN 各需 ids.size() 个参数：压平成单个数组。
            // （历史 bug：queryForList(sql, ids.toArray(), ids.toArray()) 被 varargs 当成 2 个参数，
            //  与 2N 个占位符不匹配，术语向量始终写不进去。）
            Object[] args = new Object[ids.size() * 2];
            for (int i = 0; i < ids.size(); i++) {
                args[i] = ids.get(i);
                args[ids.size() + i] = ids.get(i);
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, pid, word FROM terminology WHERE id IN (" + placeholders(ids.size()) + ")"
                            + " OR pid IN (" + placeholders(ids.size()) + ")",
                    args);
            if (rows.isEmpty()) {
                return;
            }
            List<String> words = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                words.add(Rows.str(row, "word"));
            }
            List<double[]> vectors = embeddingService.embedAll(words);
            if (vectors == null || vectors.size() != rows.size()) {
                return;
            }
            for (int i = 0; i < rows.size(); i++) {
                long id = Rows.lng(rows.get(i), "id", 0L);
                embeddingService.writeEmbedding("terminology", id, vectors.get(i));
            }
        } catch (Exception e) {
            log.info("术语向量写入失败（不影响主链路）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 召回
    // ------------------------------------------------------------------

    /**
     * 术语召回：向量 + 词法。
     *
     * @param sentence    查询句
     * @param oid         工作空间 id
     * @param datasource  数据源 id（可空；非空时按 specific_ds/datasource_ids 过滤）
     * @param advancedApp 高级应用 id（可空）
     * @return 命中的术语 id 列表（父术语优先）
     */
    public List<Long> recall(String sentence, long oid, Long datasource, Long advancedApp) {
        if (sentence == null || sentence.trim().isEmpty()) {
            return new ArrayList<>();
        }
        // §2.5：对齐 SQLBot get_terminology_template——含逗号时视为关键词列表，逐词匹配
        // （精度更高，避免整句 INSTR 被短词噪声命中）；否则整句作为一个 part。
        List<String> parts = new ArrayList<>();
        if (sentence.indexOf(',') >= 0) {
            for (String part : sentence.split(",")) {
                if (part != null && !part.trim().isEmpty()) {
                    parts.add(part.trim());
                }
            }
        }
        if (parts.isEmpty()) {
            parts.add(sentence);
        }
        List<Long> result = new ArrayList<>();
        for (String part : parts) {
            for (Long id : lexicalRecall(part, oid, datasource, advancedApp)) {
                if (!result.contains(id)) {
                    result.add(id);
                }
            }
            for (Long id : vectorRecall(part, oid, datasource, advancedApp)) {
                if (!result.contains(id)) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    /**
     * 词法召回（子串匹配，双向对齐 SQLBot {@code select_terminology_by_word} 的
     * {@code :sentence ILIKE '%' || word || '%'} 语义）：
     * <ul>
     *   <li>词是句子的子串（{@code INSTR(sentence, word) > 0}，SQLBot 语义：自然语言提问命中术语）；</li>
     *   <li>句子是词的子串（{@code word LIKE %sentence%}，既有语义：短提问/命令式整句命中）。</li>
     * </ul>
     * 向量不可用时本路径即唯一召回通道，问数主链路不报错。
     */
    private List<Long> lexicalRecall(String sentence, long oid, Long datasource, Long advancedApp) {
        List<Long> result = new ArrayList<>();
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT DISTINCT t.id FROM terminology t LEFT JOIN terminology c ON c.pid = t.id "
                            + "WHERE t.oid = ? AND t.enabled = TRUE AND ("
                            + "LOWER(t.word) LIKE ? OR LOWER(c.word) LIKE ?"
                            // 词为句子的子串（SQLBot 语义；NULL/空词不命中）
                            + " OR (LENGTH(t.word) > 0 AND INSTR(LOWER(?), LOWER(t.word)) > 0)"
                            + " OR (LENGTH(c.word) > 0 AND INSTR(LOWER(?), LOWER(c.word)) > 0))");
            String lower = sentence.toLowerCase(java.util.Locale.ROOT);
            String pattern = "%" + escapeLike(lower) + "%";
            List<Object> args = new ArrayList<>();
            args.add(oid);
            args.add(pattern);
            args.add(pattern);
            args.add(lower);
            args.add(lower);
            appendScope(sql, args, datasource, advancedApp);
            sql.append(" ORDER BY t.id LIMIT ").append(TOP_COUNT * 4);
            for (Map<String, Object> row : jdbc.queryForList(sql.toString(), args.toArray())) {
                result.add(Rows.lng(row, "id", 0L));
            }
        } catch (Exception e) {
            log.info("术语词法召回失败（降级为空）: {}", e.getMessage());
        }
        return result;
    }

    /** 向量召回（余弦相似度 > 阈值，取前 N）。 */
    private List<Long> vectorRecall(String sentence, long oid, Long datasource, Long advancedApp) {
        List<Long> result = new ArrayList<>();
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT t.id, t.embedding FROM terminology t WHERE t.oid = ? AND t.enabled = TRUE");
            List<Object> args = new ArrayList<>();
            args.add(oid);
            // 在线库启用时向量不在 DB 列，不能再以 embedding IS NOT NULL 过滤候选
            if (embeddingService.candidateRequiresEmbeddingColumn()) {
                sql.append(" AND t.embedding IS NOT NULL");
            }
            appendScope(sql, args, datasource, advancedApp);
            List<Map<String, Object>> candidates = jdbc.queryForList(sql.toString(), args.toArray());
            if (candidates.isEmpty()) {
                return result;
            }
            for (Map<String, Object> hit : embeddingService.recallByVector("terminology", candidates, sentence,
                    similarityThreshold, TOP_COUNT)) {
                result.add(Rows.lng(hit, "id", 0L));
            }
        } catch (Exception e) {
            log.info("术语向量召回失败（降级为词法）: {}", e.getMessage());
        }
        return result;
    }

    /** 作用域过滤（对齐 SQLBot 的 specific_ds / advanced_application 语义）。 */
    private static void appendScope(StringBuilder sql, List<Object> args, Long datasource, Long advancedApp) {
        if (advancedApp != null) {
            sql.append(" AND t.advanced_application = ?");
            args.add(advancedApp);
            return;
        }
        if (datasource != null) {
            sql.append(" AND (t.specific_ds = FALSE OR t.specific_ds IS NULL OR ("
                    + "INSTR(',' || COALESCE(REPLACE(REPLACE(t.datasource_ids, '[', ''), ']', ''), '') "
                    + "|| ',', ',' || ? || ',') > 0))");
            args.add(String.valueOf(datasource));
        } else {
            sql.append(" AND (t.specific_ds = FALSE OR t.specific_ds IS NULL)");
        }
    }

    /**
     * 召回术语的完整信息（主词 + 同义词 + 释义），供提示词组装使用。
     *
     * @return 形如 {@code [{words: [...], description: "..."}]} 的列表
     */
    public List<Map<String, Object>> recallGroups(String sentence, long oid, Long datasource, Long advancedApp) {
        List<Long> ids = recall(sentence, oid, datasource, advancedApp);
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, pid, word, description FROM terminology WHERE id IN ("
                            + placeholders(ids.size()) + ") OR pid IN (" + placeholders(ids.size()) + ")");
            List<Object> args = new ArrayList<>(ids);
            args.addAll(ids);
            List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
            Map<Long, Map<String, Object>> groups = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Long pid = Rows.lngOrNull(row, "pid");
                long key = pid == null ? Rows.lng(row, "id", 0L) : pid;
                Map<String, Object> group = groups.get(key);
                if (group == null) {
                    group = new LinkedHashMap<>();
                    group.put("words", new ArrayList<String>());
                    group.put("description", pid == null ? Rows.str(row, "description") : "");
                    groups.put(key, group);
                }
                @SuppressWarnings("unchecked")
                List<String> words = (List<String>) group.get("words");
                String word = Rows.str(row, "word");
                if (word != null && !words.contains(word)) {
                    words.add(word);
                }
            }
            result.addAll(groups.values());
        } catch (Exception e) {
            log.info("术语召回分组失败（降级为空）: {}", e.getMessage());
        }
        return result;
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i > 0 ? ",?" : "?");
        }
        return sb.toString();
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
    }
}
