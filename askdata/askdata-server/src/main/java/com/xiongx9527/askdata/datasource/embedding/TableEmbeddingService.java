package com.xiongx9527.askdata.datasource.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 表级向量召回（table-rag-embedding）：为每张勾选表计算文档向量并回填
 * {@code core_table.table_embedding}，提问时按「显式提及 + 语义向量」混合分排序，
 * 使问题点名的表在 schema 上下文中排第一（对齐 SQLBot 的表级 RAG 思路）。
 *
 * <p><b>打分口径</b>：mentionScore 为标识符边界感知的提及命中（中文名/注释直接子串；
 * ASCII 标识符前后边界不能是 {@code [A-Za-z0-9_$]}，故 {@code `orders`}/{@code [orders]}
 * 命中而 {@code orders_ds} 不误命中 {@code orders}；多个候选命中同一片段时取最长，
 * 如 {@code user_profile} 命中时不把分记给 {@code user}）。vectorScore 为问题向量与
 * 表向量的余弦相似度。混合分 = 0.4×vector + 0.6×mention；某一路不可用时权重转移给另一路。</p>
 *
 * <p><b>降级（验收 A1）</b>：未配置 embedding 模型（{@link ChatModelRouter#embeddingModel()}
 * 为空）或端点不可用时，{@link #embed} 返回 {@code null}，排序退化为纯提及/词法相似度，
 * <b>不抛错</b>；回填失败仅 {@code log.warn}，不影响勾选/同步主链路。</p>
 */
@Service
public class TableEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(TableEmbeddingService.class);

    /** 混合分权重：向量 0.4 / 显式提及 0.6（提及优先，保证点名表排第一）。 */
    private static final double VECTOR_WEIGHT = 0.4D;

    private static final double MENTION_WEIGHT = 0.6D;

    private final TableRepository tableRepository;

    private final FieldRepository fieldRepository;

    private final ChatModelRouter modelRouter;

    private final ChatModelClient modelClient;

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    public TableEmbeddingService(TableRepository tableRepository, FieldRepository fieldRepository,
                                 ChatModelRouter modelRouter, ChatModelClient modelClient,
                                 JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.tableRepository = tableRepository;
        this.fieldRepository = fieldRepository;
        this.modelRouter = modelRouter;
        this.modelClient = modelClient;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 表文档与向量
    // ------------------------------------------------------------------

    /**
     * 表文档（表名 + 表注释 + 勾选字段名/类型/注释），口径与
     * {@code SchemaContextBuilder.tableDoc} 一致（不含样例行）。
     */
    public String tableDoc(long tableId) {
        Optional<Map<String, Object>> table = tableRepository.findById(tableId);
        if (!table.isPresent()) {
            return "";
        }
        Map<String, Object> row = table.get();
        StringBuilder sb = new StringBuilder();
        sb.append(nullToEmpty(Rows.str(row, "table_name"))).append(" ")
                .append(nullToEmpty(firstNonEmpty(Rows.str(row, "custom_comment"),
                        Rows.str(row, "table_comment")))).append(" ");
        for (Map<String, Object> field : fieldRepository.listByTableId(tableId)) {
            if (!Rows.bool(field, "checked", true)) {
                continue;
            }
            sb.append(nullToEmpty(Rows.str(field, "field_name"))).append(" ")
                    .append(nullToEmpty(Rows.str(field, "field_type"))).append(" ")
                    .append(nullToEmpty(firstNonEmpty(Rows.str(field, "custom_comment"),
                            Rows.str(field, "field_comment")))).append(" ");
        }
        return sb.toString();
    }

    /**
     * 单文本向量：经 {@link ChatModelRouter#embeddingModel()}（usable 检查）调
     * {@link ChatModelClient#embed} 取第 0 个。
     *
     * @return 向量；未配置 embedding 模型、模型不可用或调用失败时返回 {@code null}（不抛错）
     */
    public double[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        Optional<ModelRef> embedding = modelRouter.embeddingModel();
        if (!embedding.isPresent() || !embedding.get().usable()) {
            return null;
        }
        ModelRef model = embedding.get();
        try {
            List<double[]> vectors = modelClient.embed(model.apiDomain, model.apiKey, model.baseModel,
                    Collections.singletonList(text));
            if (vectors == null || vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) {
                return null;
            }
            return vectors.get(0);
        } catch (Exception e) {
            log.warn("embedding 调用失败（表级召回降级为词法）: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 排序
    // ------------------------------------------------------------------

    /**
     * 按混合分降序返回数据源下全部勾选表 id（同分保持 {@code listByDsIdChecked} 的稳定次序）。
     */
    public List<Long> rankTables(long dsId, String question) {
        return rankTables(dsId, question, question, Integer.MAX_VALUE);
    }

    /**
     * 按混合分降序返回数据源下勾选表 id，并截断到前 {@code limit} 个
     * （schema 上下文 TOP-N 截断用，N 由调用方的 {@code MAX_TABLES} 决定）。
     */
    public List<Long> rankTables(long dsId, String question, int limit) {
        return rankTables(dsId, question, question, limit);
    }

    /**
     * 按混合分降序返回数据源下勾选表 id，并截断到前 {@code limit} 个。
     * {@code matchText} 为表匹配用的查询文本（缺省为 {@code question}）；当知识增强链路给出
     * 扩展关键词（2.3，对齐 SQLBot 用 expanded_keywords 做表匹配）时，传扩展关键词可让语义相关的
     * 表排在前面，提升「同义表述」下的选表命中率。
     */
    public List<Long> rankTables(long dsId, String question, String matchText, int limit) {
        List<Map<String, Object>> tables = tableRepository.listByDsIdChecked(dsId);
        if (tables.isEmpty()) {
            return Collections.emptyList();
        }
        String q = (matchText == null || matchText.trim().isEmpty())
                ? (question == null ? "" : question) : matchText;
        double[] questionVector = embed(q);
        Map<Long, String> stored = storedEmbeddings(dsId);
        double[] mention = mentionScores(tables, q);
        List<Long> ids = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            long tableId = Rows.lng(tables.get(i), "id", 0L);
            ids.add(tableId);
            double[] tableVector = parseVector(stored.get(tableId));
            double vector = questionVector != null && tableVector != null
                    ? ChatModelClient.cosine(questionVector, tableVector) : -1D;
            scores.add(hybrid(mention[i], vector));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            order.add(i);
        }
        // 稳定排序：同分时保持 listByDsIdChecked（ORDER BY table_name）的既有次序
        order.sort(Comparator.comparingDouble(scores::get).reversed());
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < order.size() && i < limit; i++) {
            result.add(ids.get(order.get(i)));
        }
        return result;
    }

    /**
     * 异步回填写入点：对勾选表计算并写回 {@code table_embedding}。
     * 独立守护线程执行，失败仅 {@code log.warn}（吞异常），不影响调用方主链路。
     */
    public void backfill(long dsId) {
        Thread worker = new Thread(() -> {
            try {
                for (Map<String, Object> table : tableRepository.listByDsIdChecked(dsId)) {
                    long tableId = Rows.lng(table, "id", 0L);
                    double[] vector = embed(tableDoc(tableId));
                    if (vector == null) {
                        continue;
                    }
                    tableRepository.updateEmbedding(tableId, objectMapper.writeValueAsString(vector));
                }
            } catch (Exception e) {
                log.warn("表级向量回填失败 ds={}（不影响勾选/同步主链路）: {}", dsId, e.getMessage());
            }
        }, "table-embedding-backfill-" + dsId);
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------
    // 内部：提及打分
    // ------------------------------------------------------------------

    /**
     * 标识符边界感知提及打分：命中记 1.0，未命中 0.0。
     *
     * <ul>
     *   <li>中文表名/表注释：直接子串匹配；</li>
     *   <li>ASCII 标识符：前后边界不是 {@code [A-Za-z0-9_$]}（反引号/方括号/双引号/空白均可命中）；</li>
     *   <li>重叠取最长：{@code user} 与 {@code user_profile} 同现时只记最长者。</li>
     * </ul>
     */
    double[] mentionScores(List<Map<String, Object>> tables, String question) {
        double[] scores = new double[tables.size()];
        List<int[]> hits = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            for (String target : mentionTargets(tables.get(i))) {
                for (int[] span : occurrences(question, target)) {
                    hits.add(new int[] {i, span[0], span[1]});
                }
            }
        }
        if (hits.isEmpty()) {
            return scores;
        }
        // 取最长：长度降序，同长按出现位置、表序升序（保证确定性）
        hits.sort(Comparator.comparingInt((int[] hit) -> hit[1] - hit[2])
                .thenComparingInt(hit -> hit[1])
                .thenComparingInt(hit -> hit[0]));
        List<int[]> taken = new ArrayList<>();
        for (int[] hit : hits) {
            if (overlaps(taken, hit)) {
                continue;
            }
            taken.add(hit);
            scores[hit[0]] = 1D;
        }
        return scores;
    }

    /** 提及候选：表名 + 表注释（custom_comment 优先，再 table_comment），去空白。 */
    private static List<String> mentionTargets(Map<String, Object> table) {
        List<String> targets = new ArrayList<>();
        for (String candidate : new String[] {Rows.str(table, "table_name"),
                Rows.str(table, "custom_comment"), Rows.str(table, "table_comment")}) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                targets.add(candidate.trim());
            }
        }
        return targets;
    }

    /** 目标串在问题中的全部有效出现片段 {@code [start, end)}。 */
    private static List<int[]> occurrences(String question, String target) {
        List<int[]> spans = new ArrayList<>();
        if (question.isEmpty() || target.isEmpty()) {
            return spans;
        }
        boolean ascii = isAsciiIdentifier(target);
        String haystack = ascii ? question.toLowerCase(Locale.ROOT) : question;
        String needle = ascii ? target.toLowerCase(Locale.ROOT) : target;
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                break;
            }
            int end = index + needle.length();
            if (!ascii || boundaryOk(question, index, end)) {
                spans.add(new int[] {index, end});
            }
            from = index + 1;
        }
        return spans;
    }

    /** ASCII 标识符边界：前后字符不能是 {@code [A-Za-z0-9_$]}。 */
    private static boolean boundaryOk(String question, int start, int end) {
        if (start > 0 && isIdentifierChar(question.charAt(start - 1))) {
            return false;
        }
        return end >= question.length() || !isIdentifierChar(question.charAt(end));
    }

    private static boolean isAsciiIdentifier(String target) {
        if (target.isEmpty()) {
            return false;
        }
        for (int i = 0; i < target.length(); i++) {
            if (!isIdentifierChar(target.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }

    private static boolean overlaps(List<int[]> taken, int[] hit) {
        for (int[] span : taken) {
            if (hit[1] < span[2] && span[1] < hit[2]) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 内部：混合分与向量读写
    // ------------------------------------------------------------------

    /** 混合分：向量不可用（{@code vector < 0}）时权重转移给提及，否则 0.4×向量 + 0.6×提及。 */
    private static double hybrid(double mention, double vector) {
        return vector < 0D ? mention : VECTOR_WEIGHT * vector + MENTION_WEIGHT * mention;
    }

    /** 批量读取勾选表的 {@code table_embedding} JSON 文本（一次查询，失败视为全部缺失）。 */
    private Map<Long, String> storedEmbeddings(long dsId) {
        Map<Long, String> result = new HashMap<>();
        try {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT id, table_embedding FROM core_table WHERE ds_id = ? AND checked = TRUE", dsId)) {
                result.put(Rows.lng(row, "id", 0L), Rows.str(row, "table_embedding"));
            }
        } catch (Exception e) {
            log.info("表级向量读取失败（降级为提及召回）: {}", e.getMessage());
        }
        return result;
    }

    /** 解析 {@code table_embedding} JSON 数组；缺失/非法返回 {@code null}（降级，不抛错）。 */
    private double[] parseVector(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
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
        return null;
    }
}
