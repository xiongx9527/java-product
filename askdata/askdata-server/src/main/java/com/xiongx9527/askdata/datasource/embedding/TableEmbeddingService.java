package com.xiongx9527.askdata.datasource.embedding;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** 提及分级权重（对齐 SQLBot calc_keyword_score 多层权重）：表名完整命中 > 表注释子串命中。 */
    private static final double NAME_MENTION_SCORE = 1.0D;

    private static final double COMMENT_MENTION_SCORE = 0.6D;

    // ------------------------------------------------------------------
    // A15：表级关键词打分权重（移植 SQLBot calc_keyword_score）
    // ------------------------------------------------------------------

    /** 表名完整匹配：1.0。 */
    private static final double KW_TABLE_NAME_EXACT = 1.0D;

    /** 表注释完整匹配：0.9。 */
    private static final double KW_TABLE_COMMENT_EXACT = 0.9D;

    /** 表名子串匹配（关键词是表名的一部分，如 用户 命中 用户表）：0.7。 */
    private static final double KW_TABLE_NAME_SUBSTRING = 0.7D;

    /** 字段注释完整匹配：0.5。 */
    private static final double KW_FIELD_COMMENT_EXACT = 0.5D;

    /** 字段注释子串匹配：0.4。 */
    private static final double KW_FIELD_COMMENT_SUBSTRING = 0.4D;

    /** 字段名匹配（按下划线/空白切分后的词元命中）：0.3。 */
    private static final double KW_FIELD_NAME = 0.3D;

    /** 表名切分后的部分匹配基准分 0.25，按覆盖率再加 0.25 乘 coverage。 */
    private static final double KW_TABLE_PART_BASE = 0.25D;

    private static final double KW_TABLE_PART_COVERAGE = 0.25D;

    /** 表名/字段名切分分隔符（SQLBot _RE_UNDERSCORE_SPACE）。 */
    private static final Pattern NAME_PART_SPLIT = Pattern.compile("[_\\s]+");

    /**
     * 显式提及加成：问题中以引号/反引号/方括号/中文引号包裹的表名（对齐 SQLBot {@code _select_tables}
     * 的引号标识符提取）视为显式引用，优先进入候选。加成大到保证显式提及表排在其余（含向量高分）之前。
     */
    private static final double EXPLICIT_MENTION_BONUS = 10D;

    /** 引号标识符："x" / `x` / [x] / “x”（捕获组 1-4 对应四种引号内容）。 */
    private static final Pattern QUOTED_TOKEN = Pattern.compile(
            "\"([^\"]+)\"|`([^`]+)`|\\[([^\\]]+)\\]|\u201c([^\u201d]+)\u201d");

    private final TableRepository tableRepository;

    private final FieldRepository fieldRepository;

    private final ChatModelRouter modelRouter;

    private final ChatModelClient modelClient;

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    /**
     * 向量存储后端（可插拔）。构造期默认 {@link com.xiongx9527.askdata.ai.vector.DbVectorStore}
     * 保留既有行为；Spring 装配时由 {@link #setVectorStore} 覆盖为配置指定的后端（none=DB / qdrant=在线）。
     */
    private com.xiongx9527.askdata.ai.vector.VectorStore vectorStore;

    public TableEmbeddingService(TableRepository tableRepository, FieldRepository fieldRepository,
                                 ChatModelRouter modelRouter, ChatModelClient modelClient,
                                 JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.tableRepository = tableRepository;
        this.fieldRepository = fieldRepository;
        this.modelRouter = modelRouter;
        this.modelClient = modelClient;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.vectorStore = new com.xiongx9527.askdata.ai.vector.DbVectorStore(jdbc, objectMapper, "askdata");
    }

    /** Spring 装配：注入配置指定的向量存储后端（覆盖构造期默认）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVectorStore(com.xiongx9527.askdata.ai.vector.VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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
        // 向量相似度经统一向量存储后端获取（在线库走 ANN，type=none 走 DB 列 + 应用内余弦）：
        // 表级排序要把向量分与显式提及分混合，故取「不截断、不设阈值」的逐表相似度。
        List<Long> candidateIds = new ArrayList<>(tables.size());
        for (Map<String, Object> table : tables) {
            candidateIds.add(Rows.lng(table, "id", 0L));
        }
        Map<Long, Double> vectorScores = questionVector != null
                ? vectorStoreScores(questionVector, candidateIds) : new HashMap<>();
        double[] mention = mentionScores(tables, q);
        // A15：SQLBot calc_keyword_score 加权关键词分（表名 1.0 / 表注释 0.9 / 表名子串 0.7 /
        // 字段注释 0.5 / 字段名 0.3，按覆盖率加权）。与边界感知提及分取较大者作为词法分量：
        // 前者给出细粒度权重，后者保留 ASCII 标识符边界与最长重叠消歧。
        double[] keyword = keywordScores(tables, q);
        Set<String> quoted = quotedTokens(q);
        List<Long> ids = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            long tableId = Rows.lng(tables.get(i), "id", 0L);
            ids.add(tableId);
            Double scored = vectorScores.get(tableId);
            double vector = scored != null ? scored : -1D;
            double score = hybrid(Math.max(mention[i], keyword[i]), vector);
            // A1 显式提及：引号包裹的表名/注释优先进入候选（对齐 SQLBot _select_tables）
            if (!quoted.isEmpty() && explicitMention(tables.get(i), quoted)) {
                score += EXPLICIT_MENTION_BONUS;
            }
            scores.add(score);
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
                    // 在线库启用时写入向量库；随后镜像 DB 列（保持回落与既有回填写入点可用）
                    try {
                        vectorStore.upsert(vectorStore.collectionFor("core_table"), tableId, vector);
                    } catch (Exception e) {
                        log.warn("表级向量写入在线库失败 ds={} tbl={}: {}", dsId, tableId, e.getMessage());
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
        if (question == null || question.isEmpty()) {
            return scores;
        }
        // 命中项 {表序, 起, 止}；tier 0=表名(NAME_MENTION_SCORE)、1=表注释 custom/table(COMMENT_MENTION_SCORE)
        List<int[]> hits = new ArrayList<>();
        List<Integer> tiers = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            Map<String, Object> table = tables.get(i);
            String name = Rows.str(table, "table_name");
            if (name != null && !name.trim().isEmpty()) {
                for (int[] span : occurrences(question, name.trim())) {
                    hits.add(new int[] {i, span[0], span[1]});
                    tiers.add(0);
                }
            }
            for (String comment : new String[] {Rows.str(table, "custom_comment"), Rows.str(table, "table_comment")}) {
                if (comment != null && !comment.trim().isEmpty()) {
                    for (int[] span : occurrences(question, comment.trim())) {
                        hits.add(new int[] {i, span[0], span[1]});
                        tiers.add(1);
                    }
                }
            }
        }
        if (hits.isEmpty()) {
            return scores;
        }
        // 最长重叠消歧：长度降序；同长优先表名(tier 0)再注释；再按出现位置、表序升序（保证确定性）
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < hits.size(); k++) {
            order.add(k);
        }
        order.sort(Comparator
                .<Integer>comparingInt(k -> hits.get(k)[1] - hits.get(k)[2])
                .thenComparingInt(tiers::get)
                .thenComparingInt(k -> hits.get(k)[1])
                .thenComparingInt(k -> hits.get(k)[0]));
        List<int[]> taken = new ArrayList<>();
        for (Integer k : order) {
            int[] hit = hits.get(k);
            if (overlaps(taken, hit)) {
                continue;
            }
            taken.add(hit);
            double tierScore = tiers.get(k) == 0 ? NAME_MENTION_SCORE : COMMENT_MENTION_SCORE;
            scores[hit[0]] = Math.max(scores[hit[0]], tierScore);
        }
        return scores;
    }

    // ------------------------------------------------------------------
    // A15：关键词打分（移植 SQLBot calc_keyword_score）
    // ------------------------------------------------------------------

    /**
     * 表级关键词匹配分（验收 A15，移植 SQLBot {@code calc_keyword_score}）。
     *
     * <p>权重口径与 SQLBot 完全一致：</p>
     * <ul>
     *   <li>表名完整匹配 1.0；表注释完整匹配 0.9；表名子串匹配 0.7；</li>
     *   <li>字段注释完整匹配 0.5、子串 0.4；字段名（按 {@code _}/空白切分词元）匹配 0.3；</li>
     *   <li>表名被切分为多个词元时，按 {@code 0.25 + 0.25 × 命中词元覆盖率} 计分；</li>
     *   <li>最终分 = 各关键词最佳分的最大值 × 关键词覆盖率（命中关键词数 / 关键词总数），
     *       即「匹配质量 × 覆盖率」。</li>
     * </ul>
     *
     * @param keywords    逗号分隔的关键词（已由调用方提取/扩展；为空时返回 0）
     * @param tableName   表名
     * @param tableComment 表注释（custom_comment 优先，调用方已合并）
     * @param fields      字段列表：{@code field_name}/{@code field_comment}/{@code custom_comment}
     * @return 0.0 ~ 1.0 的匹配分
     */
    public static double keywordScore(String keywords, String tableName, String tableComment,
                                      List<Map<String, Object>> fields) {
        if (keywords == null || keywords.trim().isEmpty()) {
            return 0D;
        }
        List<String> kws = new ArrayList<>();
        for (String part : keywords.split(",")) {
            String kw = part.trim().toLowerCase(Locale.ROOT);
            if (!kw.isEmpty()) {
                kws.add(kw);
            }
        }
        if (kws.isEmpty()) {
            return 0D;
        }
        String tn = tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
        String tc = tableComment == null ? "" : tableComment.trim().toLowerCase(Locale.ROOT);
        int totalKw = kws.size();

        Set<String> tnParts = new LinkedHashSet<>();
        for (String part : NAME_PART_SPLIT.split(tn)) {
            if (!part.isEmpty()) {
                tnParts.add(part);
            }
        }

        List<String> fieldComments = new ArrayList<>();
        List<Set<String>> fieldNameParts = new ArrayList<>();
        if (fields != null) {
            for (Map<String, Object> field : fields) {
                String fc = firstNonEmpty(Rows.str(field, "custom_comment"),
                        Rows.str(field, "field_comment"));
                fieldComments.add(fc == null ? "" : fc.trim().toLowerCase(Locale.ROOT));
                String fname = Rows.str(field, "field_name");
                Set<String> parts = new LinkedHashSet<>();
                if (fname != null && !fname.trim().isEmpty()) {
                    for (String part : NAME_PART_SPLIT.split(fname.trim().toLowerCase(Locale.ROOT))) {
                        if (!part.isEmpty()) {
                            parts.add(part);
                        }
                    }
                }
                fieldNameParts.add(parts);
            }
        }

        Map<String, Double> kwScores = new HashMap<>();
        for (String kw : kws) {
            // 表名切分后的词元命中（如 user_profile 命中 user）：先标记，
            // 分数由末尾的覆盖率分支统一计算，避免与子串分支重复计分
            boolean namePart = tnParts.contains(kw);
            // 1. 表名完整匹配：1.0
            if (kw.equals(tn)) {
                kwScores.put(kw, KW_TABLE_NAME_EXACT);
                continue;
            }
            // 2. 表注释完整匹配：0.9
            if (!tc.isEmpty() && kw.equals(tc)) {
                kwScores.put(kw, Math.max(kwScores.getOrDefault(kw, 0D), KW_TABLE_COMMENT_EXACT));
                continue;
            }
            // 3. 表名子串匹配：0.7（关键词是表名的一部分且未被切分命中，如 用户 命中 用户表）
            if (!namePart && !tn.isEmpty() && tn.contains(kw)) {
                kwScores.put(kw, Math.max(kwScores.getOrDefault(kw, 0D), KW_TABLE_NAME_SUBSTRING));
                continue;
            }
            // 4. 表注释子串匹配：0.7（关键词是注释的一部分）
            if (!tc.isEmpty() && tc.contains(kw)) {
                kwScores.put(kw, Math.max(kwScores.getOrDefault(kw, 0D), KW_TABLE_NAME_SUBSTRING));
                continue;
            }
            // 5. 字段注释匹配：0.5（完整）/ 0.4（子串）
            for (String fc : fieldComments) {
                if (fc.isEmpty()) {
                    continue;
                }
                if (kw.equals(fc)) {
                    kwScores.put(kw, Math.max(kwScores.getOrDefault(kw, 0D), KW_FIELD_COMMENT_EXACT));
                    break;
                }
                if (fc.contains(kw)) {
                    kwScores.put(kw, Math.max(kwScores.getOrDefault(kw, 0D), KW_FIELD_COMMENT_SUBSTRING));
                }
            }
            // 6. 字段名匹配：0.3
            if (kwScores.getOrDefault(kw, 0D) < KW_FIELD_NAME) {
                for (Set<String> parts : fieldNameParts) {
                    if (parts.contains(kw)) {
                        kwScores.put(kw, Math.max(kwScores.getOrDefault(kw, 0D), KW_FIELD_NAME));
                        break;
                    }
                }
            }
        }

        // 表名完整匹配直接返回 1.0；表注释完整匹配直接返回 0.9（对齐 SQLBot 的短路）
        if (kwScores.getOrDefault(tn, 0D) >= KW_TABLE_NAME_EXACT) {
            return KW_TABLE_NAME_EXACT;
        }
        if (!tc.isEmpty() && kwScores.getOrDefault(tc, 0D) >= KW_TABLE_COMMENT_EXACT) {
            return KW_TABLE_COMMENT_EXACT;
        }
        // 表名被切分为多个词元时按覆盖率加权（SQLBot 的 tn_parts 分支）
        if (!tnParts.isEmpty()) {
            int matched = 0;
            for (String kw : kws) {
                if (tnParts.contains(kw)) {
                    matched++;
                }
            }
            if (matched > 0) {
                double coverage = (double) matched / tnParts.size();
                double tnScore = Math.round((KW_TABLE_PART_BASE + KW_TABLE_PART_COVERAGE * coverage) * 100D) / 100D;
                for (String kw : kws) {
                    if (tnParts.contains(kw)) {
                        kwScores.put(kw, Math.max(kwScores.getOrDefault(kw, 0D), tnScore));
                    }
                }
            }
        }

        if (kwScores.isEmpty()) {
            return 0D;
        }
        // 综合评分：最佳匹配分数 × 关键词覆盖率
        double best = 0D;
        for (double value : kwScores.values()) {
            best = Math.max(best, value);
        }
        double matchRate = (double) kwScores.size() / totalKw;
        return Math.round(best * matchRate * 100D) / 100D;
    }
    /**
     * A15：逐表关键词分（{@link #keywordScore} 的批量形态；字段经 {@link FieldRepository} 读取）。
     */
    double[] keywordScores(List<Map<String, Object>> tables, String question) {
        double[] scores = new double[tables.size()];
        if (question == null || question.trim().isEmpty()) {
            return scores;
        }
        for (int i = 0; i < tables.size(); i++) {
            Map<String, Object> table = tables.get(i);
            long tableId = Rows.lng(table, "id", 0L);
            List<Map<String, Object>> fields;
            try {
                fields = fieldRepository.listByTableId(tableId);
            } catch (Exception e) {
                log.info("关键词打分读取字段失败 table={}: {}", tableId, e.getMessage());
                fields = new ArrayList<>();
            }
            scores[i] = keywordScore(question, Rows.str(table, "table_name"),
                    firstNonEmpty(Rows.str(table, "custom_comment"), Rows.str(table, "table_comment")),
                    fields);
        }
        return scores;
    }
    /** 提及候选：表名 + 表注释（custom_comment 优先，再 table_comment），去空白。 */
    /** 提取问题中被引号/反引号/方括号/中文引号包裹的 token（小写），用于显式提及判定。 */
    static Set<String> quotedTokens(String question) {
        Set<String> tokens = new LinkedHashSet<>();
        if (question == null || question.isEmpty()) {
            return tokens;
        }
        java.util.regex.Matcher m = QUOTED_TOKEN.matcher(question);
        while (m.find()) {
            for (int g = 1; g <= 4; g++) {
                String v = m.group(g);
                if (v != null) {
                    String t = v.trim();
                    if (!t.isEmpty()) {
                        tokens.add(t.toLowerCase(Locale.ROOT));
                    }
                    break;
                }
            }
        }
        return tokens;
    }

    /** 表名或表注释（custom/table）被引号显式包裹即为显式提及（大小写不敏感）。 */
    private static boolean explicitMention(Map<String, Object> table, Set<String> quoted) {
        for (String candidate : new String[] {Rows.str(table, "table_name"),
                Rows.str(table, "custom_comment"), Rows.str(table, "table_comment")}) {
            if (candidate != null && !candidate.trim().isEmpty()
                    && quoted.contains(candidate.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

    /**
     * 经向量存储后端取各勾选表的相似度（不截断、不设阈值）。
     *
     * <p>在线库走 ANN；{@code type=none} 回落 DB {@code table_embedding} 列 + 应用内余弦
     * （{@link com.xiongx9527.askdata.ai.vector.DbVectorStore} 自持读取与余弦，语义不变）。
     * 后端不可用/查询失败时返回空表，调用方降级为纯提及排序，<b>不抛错</b>。</p>
     */
    private Map<Long, Double> vectorStoreScores(double[] questionVector, List<Long> candidateIds) {
        Map<Long, Double> result = new HashMap<>();
        try {
            int topK = Math.min(candidateIds.size(), 1000);
            for (com.xiongx9527.askdata.ai.vector.VectorStore.IdScore hit : vectorStore.query(
                    vectorStore.collectionFor("core_table"), questionVector, topK, candidateIds)) {
                result.put(hit.id(), hit.score());
            }
        } catch (Exception e) {
            log.info("表级向量查询失败（降级为提及召回）: {}", e.getMessage());
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
        return null;
    }
}
