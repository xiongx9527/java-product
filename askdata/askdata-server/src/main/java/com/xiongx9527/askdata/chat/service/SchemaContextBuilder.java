package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 对话上下文构建（对齐 SQLBot {@code apps/chat/task/llm.py} 的
 * {@code select_datasource / get_db_schema / init_messages / filter_training_template}）：
 * 数据源 RAG 选库 → 训练样例召回 → 表结构（DDL/注释/样例行）schema 提示词。
 *
 * <p><b>RAG 降级（依赖边界要求）</b>：本子任务基线未集成 value-add-modules，
 * {@code data_training} 表可能不存在；{@link #trainingTableAvailable} 探测存在性，
 * 存在则纳入训练样例，不存在则退化为「仅表结构 schema + 系统提示词」驱动
 * （SQLBot 在无训练样例时同样可工作）。<b>禁止因该表缺失导致启动失败或对话报错</b>。</p>
 *
 * <p><b>向量降级（D5）</b>：embedding 模型未配置（D7 的 bge-m3 不可用时同理）则
 * 一律用词法召回（分词 + 字符级 Jaccard 近似），保证离线可单测、可运行。</p>
 */
@Service
public class SchemaContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(SchemaContextBuilder.class);

    /** 参与提示词与向量召回的表数量上限（避免大库超长提示词）。 */
    private static final int MAX_TABLES = 8;

    /** 每张表进入提示词的样例行数。 */
    private static final int SAMPLE_ROWS = 3;

    private static final int TRAINING_LIMIT = 3;

    private final DatasourceRepository datasourceRepository;

    private final TableRepository tableRepository;

    private final FieldRepository fieldRepository;

    private final DatasourceService datasourceService;

    private final ChatModelClient modelClient;

    private final ChatModelRouter modelRouter;

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    public SchemaContextBuilder(DatasourceRepository datasourceRepository, TableRepository tableRepository,
                                FieldRepository fieldRepository, DatasourceService datasourceService,
                                ChatModelClient modelClient, ChatModelRouter modelRouter, JdbcTemplate jdbc,
                                ObjectMapper objectMapper) {
        this.datasourceRepository = datasourceRepository;
        this.tableRepository = tableRepository;
        this.fieldRepository = fieldRepository;
        this.datasourceService = datasourceService;
        this.modelClient = modelClient;
        this.modelRouter = modelRouter;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 数据源 RAG 选库（D5）
    // ------------------------------------------------------------------

    /**
     * 选择回答该问题的数据源：有向量能力时用「问题 ↔ 表文档」余弦相似度，否则用词法召回。
     *
     * @param oid      当前工作空间
     * @param question 用户问题
     * @return 数据源 id；无可用数据源时返回 null
     */
    public Long selectDatasource(long oid, String question) {
        List<Map<String, Object>> candidates = datasourceRepository.listByOid(oid);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return Rows.lng(candidates.get(0), "id", 0L);
        }
        List<String> docs = new ArrayList<>();
        List<Long> dsIds = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            long dsId = Rows.lng(candidate, "id", 0L);
            dsIds.add(dsId);
            docs.add(Rows.str(candidate, "name") + " " + tableDoc(dsId));
        }
        List<Double> scores = new ArrayList<>();
        List<double[]> vectors = embedAll(question, docs);
        if (vectors != null && vectors.size() == docs.size() + 1) {
            double[] questionVector = vectors.get(0);
            for (int i = 0; i < docs.size(); i++) {
                scores.add(ChatModelClient.cosine(questionVector, vectors.get(i + 1)));
            }
        } else {
            for (String doc : docs) {
                scores.add(lexicalScore(question, doc));
            }
        }
        int best = 0;
        for (int i = 1; i < scores.size(); i++) {
            if (scores.get(i) > scores.get(best)) {
                best = i;
            }
        }
        log.info("数据源 RAG 选库：候选 {}，命中 ds={}（score={}）", dsIds, dsIds.get(best), scores.get(best));
        return dsIds.get(best);
    }

    /**
     * 训练样例召回（对齐 SQLBot {@code filter_training_template}）。
     *
     * @return 样例文本列表；{@code data_training} 不存在或查询失败时返回空列表（降级）。
     */
    public List<String> trainingSamples(long dsId, String question) {
        if (!trainingTableAvailable()) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT question, description, embedding FROM data_training "
                            + "WHERE datasource = ? AND enabled = TRUE", dsId);
            if (rows.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> texts = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String q = Rows.str(row, "question");
                String sql = Rows.str(row, "description");
                texts.add(String.valueOf(q == null ? "" : q) + "\n" + (sql == null ? "" : sql));
            }
            return topK(question, rows, texts);
        } catch (Exception e) {
            // 表结构/列名与 value-add-modules 不一致时一律降级，不影响主链路
            log.info("data_training 召回失败（降级为仅 schema 驱动）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 探测 data_training 表是否存在（优雅降级判据，失败一律视为不存在）。
     *
     * <p>用 {@code queryForList} 而非 {@code queryForObject}：空表无行时
     * {@code queryForObject} 会抛 EmptyResultDataAccessException，导致表存在也被判为缺失。</p>
     */
    public boolean trainingTableAvailable() {
        try {
            jdbc.queryForList("SELECT 1 FROM data_training WHERE 1 = 0");
            return true;
        } catch (Exception e) {
            log.info("data_training 不可用（降级为仅 schema 驱动）：{}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    /** 构建 SQL 生成的 system + user 消息（含训练样例时附加参考）。 */
    public List<ModelMessage> buildSqlMessages(String question, Long dsId) {
        List<ModelMessage> messages = new ArrayList<>();
        List<String> samples = dsId == null ? Collections.<String>emptyList() : trainingSamples(dsId, question);
        messages.add(new ModelMessage("system", sqlSystemPrompt(!samples.isEmpty())));
        StringBuilder user = new StringBuilder();
        user.append("【数据库表结构】\n").append(dsId == null ? "（未选择数据源）" : buildSchemaText(dsId));
        if (!samples.isEmpty()) {
            user.append("\n\n【可参考的 SQL 样例】\n");
            for (String sample : samples) {
                user.append(sample).append("\n");
            }
        }
        user.append("\n\n【用户问题】\n").append(question)
                .append("\n\n请只输出 SQL，使用 ```sql 代码块包裹。");
        messages.add(new ModelMessage("user", user.toString()));
        return messages;
    }

    /** 构建 SQL 修复消息（把执行/校验错误回喂一轮）。 */
    public List<ModelMessage> buildSqlFixMessages(String question, Long dsId, String brokenSql, String error) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", sqlSystemPrompt(false)));
        messages.add(new ModelMessage("user",
                "【数据库表结构】\n" + (dsId == null ? "（未选择数据源）" : buildSchemaText(dsId))
                        + "\n\n【用户问题】\n" + question
                        + "\n\n【上次生成的 SQL】\n" + brokenSql
                        + "\n\n【错误】\n" + error
                        + "\n\n请修正后只输出一条正确的 SQL，使用 ```sql 代码块包裹。"));
        return messages;
    }

    /** 构建图表生成的 system + user 消息（data 为已执行结果 {fields, data}）。 */
    public List<ModelMessage> buildChartMessages(String question, String dataJson) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", chartSystemPrompt()));
        messages.add(new ModelMessage("user",
                "【用户问题】\n" + question + "\n\n【查询结果（JSON，最多 20 行）】\n" + dataJson
                        + "\n\n请只输出一个 G2 图表配置 JSON（不要 Markdown 代码块）。"));
        return messages;
    }

    /** 构建分析生成的 system + user 消息。 */
    public List<ModelMessage> buildAnalysisMessages(String question, String chartConfigJson, String dataJson) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", analysisSystemPrompt()));
        messages.add(new ModelMessage("user",
                "【用户问题】\n" + question + "\n\n【图表配置】\n" + chartConfigJson
                        + "\n\n【查询结果】\n" + dataJson + "\n\n请输出对结果的分析（中文 Markdown）。"));
        return messages;
    }

    /** 构建预测生成的 system + user 消息。 */
    public List<ModelMessage> buildPredictMessages(String question, String chartConfigJson, String dataJson) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", predictSystemPrompt()));
        messages.add(new ModelMessage("user",
                "【用户问题】\n" + question + "\n\n【图表配置】\n" + chartConfigJson
                        + "\n\n【历史查询结果】\n" + dataJson
                        + "\n\n请输出一个 JSON 数组作为预测数据（字段与查询结果一致），"
                        + "数组前后可附中文结论说明。"));
        return messages;
    }

    /** 构建推荐问题生成的 system + user 消息。 */
    public List<ModelMessage> buildRecommendMessages(String question, int articlesNumber) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", recommendSystemPrompt()));
        messages.add(new ModelMessage("user",
                "【原问题】\n" + question + "\n\n请生成 " + articlesNumber + " 个相关的追问问题，"
                        + "只输出一个 JSON 数组，例如 [\"问题1\",\"问题2\"]，不要编号、不要解释。"));
        return messages;
    }

    // ------------------------------------------------------------------
    // 表结构
    // ------------------------------------------------------------------

    /** 表结构文本（表注释 + 勾选字段 + 样例行），供 schema 提示词使用。 */
    public String buildSchemaText(long dsId) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> tables = tableRepository.listByDsIdChecked(dsId);
        if (tables.isEmpty()) {
            return "（该数据源未勾选任何表）";
        }
        int count = 0;
        for (Map<String, Object> table : tables) {
            if (count >= MAX_TABLES) {
                break;
            }
            String tableName = Rows.str(table, "table_name");
            String tableComment = firstNonEmpty(Rows.str(table, "custom_comment"), Rows.str(table, "table_comment"));
            sb.append("Table ").append(tableName);
            if (tableComment != null) {
                sb.append(" (").append(tableComment).append(")");
            }
            sb.append(":\n");
            for (Map<String, Object> field : fieldRepository.listByTableId(Rows.lng(table, "id", 0L))) {
                if (!Rows.bool(field, "checked", true)) {
                    continue;
                }
                String fieldName = Rows.str(field, "field_name");
                String fieldType = Rows.str(field, "field_type");
                String fieldComment = firstNonEmpty(Rows.str(field, "custom_comment"),
                        Rows.str(field, "field_comment"));
                sb.append("  - ").append(fieldName).append(" ").append(fieldType);
                if (fieldComment != null) {
                    sb.append("  ").append(fieldComment);
                }
                sb.append("\n");
            }
            sb.append(sampleRowsText(dsId, Rows.lng(table, "id", 0L)));
            count++;
        }
        return sb.toString();
    }

    /** 选中数据源下所有勾选表名（SQL 表名白名单，用于未授权表拦截）。 */
    public List<String> checkedTableNames(long dsId) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> table : tableRepository.listByDsIdChecked(dsId)) {
            names.add(Rows.str(table, "table_name"));
        }
        return names;
    }

    // ------------------------------------------------------------------
    // 内部：RAG 计算
    // ------------------------------------------------------------------

    /** 批量向量：[0]=问题，其后依次为各文档；不可用时返回 null。 */
    private List<double[]> embedAll(String question, List<String> docs) {
        if (docs.isEmpty()) {
            return null;
        }
        Optional<ModelRef> embedding = modelRouter.embeddingModel();
        if (!embedding.isPresent() || !embedding.get().usable()) {
            return null;
        }
        ModelRef model = embedding.get();
        List<String> inputs = new ArrayList<>();
        inputs.add(question);
        inputs.addAll(docs);
        return modelClient.embed(model.apiDomain, model.apiKey, model.baseModel, inputs);
    }

    private List<String> topK(String question, List<Map<String, Object>> rows, List<String> texts) {
        List<Double> scores = new ArrayList<>();
        List<double[]> vectors = embedAll(question, texts);
        if (vectors != null && vectors.size() == texts.size() + 1) {
            double[] questionVector = vectors.get(0);
            for (int i = 0; i < texts.size(); i++) {
                scores.add(ChatModelClient.cosine(questionVector, vectors.get(i + 1)));
            }
        } else {
            for (String text : texts) {
                scores.add(lexicalScore(question, text));
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            order.add(i);
        }
        order.sort((left, right) -> Double.compare(scores.get(right), scores.get(left)));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < order.size() && i < TRAINING_LIMIT; i++) {
            int index = order.get(i);
            if (scores.get(index) <= 0D) {
                continue;
            }
            Map<String, Object> row = rows.get(index);
            result.add("问：" + Rows.str(row, "question") + "\nSQL：" + Rows.str(row, "description"));
        }
        return result;
    }

    /** 表文档（表名 + 注释 + 字段名 + 字段注释），供选库召回。 */
    private String tableDoc(long dsId) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> table : tableRepository.listByDsIdChecked(dsId)) {
            sb.append(Rows.str(table, "table_name")).append(" ")
                    .append(firstNonEmpty(Rows.str(table, "custom_comment"), Rows.str(table, "table_comment")))
                    .append(" ");
            for (Map<String, Object> field : fieldRepository.listByTableId(Rows.lng(table, "id", 0L))) {
                sb.append(Rows.str(field, "field_name")).append(" ")
                        .append(firstNonEmpty(Rows.str(field, "custom_comment"),
                                Rows.str(field, "field_comment"))).append(" ");
            }
        }
        return sb.toString();
    }

    /**
     * 词法召回打分：问题分词在文档中的命中率 × IDF 近似，叠加中文二元组 Jaccard。
     * 用于无 embedding 模型时的等价召回（离线可测）。
     */
    double lexicalScore(String question, String doc) {
        if (question == null || doc == null || question.isEmpty() || doc.isEmpty()) {
            return 0D;
        }
        String q = question.toLowerCase(Locale.ROOT);
        String d = doc.toLowerCase(Locale.ROOT);
        List<String> tokens = tokenize(q);
        double hit = 0D;
        for (String token : tokens) {
            if (d.contains(token)) {
                hit += 1D;
            }
        }
        double coverage = tokens.isEmpty() ? 0D : hit / tokens.size();
        double jaccard = bigramJaccard(q, d);
        return coverage * 0.7D + jaccard * 0.3D;
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAscii(c) && (Character.isLetterOrDigit(c) || c == '_')) {
                latin.append(c);
                continue;
            }
            if (latin.length() > 0) {
                tokens.add(latin.toString().toLowerCase(Locale.ROOT));
                latin.setLength(0);
            }
            if (isCjk(c)) {
                tokens.add(String.valueOf(c));
                if (i + 1 < text.length() && isCjk(text.charAt(i + 1))) {
                    tokens.add(text.substring(i, i + 2));
                }
            }
        }
        if (latin.length() > 0) {
            tokens.add(latin.toString().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private static double bigramJaccard(String left, String right) {
        java.util.Set<String> a = new java.util.HashSet<>();
        java.util.Set<String> b = new java.util.HashSet<>();
        for (int i = 0; i + 1 < left.length(); i++) {
            a.add(left.substring(i, i + 2));
        }
        for (int i = 0; i + 1 < right.length(); i++) {
            b.add(right.substring(i, i + 2));
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0D;
        }
        java.util.Set<String> intersection = new java.util.HashSet<>(a);
        intersection.retainAll(b);
        java.util.Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static boolean isAscii(char c) {
        return c < 128;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    // ------------------------------------------------------------------
    // 内部：样例行
    // ------------------------------------------------------------------

    /** 表样例行文本（复用 datasource-engine 的 previewData，失败时静默跳过）。 */
    private String sampleRowsText(long dsId, long tableId) {
        try {
            Map<String, Object> preview = datasourceService.previewData(dsId,
                    Rows.of("table", Rows.of("id", tableId)));
            List<Map<String, Object>> data = Rows.mapList(preview.get("data"));
            if (data.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("  Sample rows:\n");
            for (int i = 0; i < data.size() && i < SAMPLE_ROWS; i++) {
                sb.append("    ").append(compactJson(data.get(i))).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            // 数据源不可达（离线演示场景）：仅影响样例行，不影响表结构提示词
            log.info("样例行预览失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    private String compactJson(Map<String, Object> row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (Exception e) {
            return String.valueOf(row);
        }
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

    // ------------------------------------------------------------------
    // 提示词文案
    // ------------------------------------------------------------------

    private static String sqlSystemPrompt(boolean withTraining) {
        return "你是一个严谨的数据库 SQL 专家，服务于一个中文问数（NL2SQL）系统。\n"
                + "规则：\n"
                + "1. 只能使用用户提供的表结构中的表与字段；\n"
                + "2. 只生成一条只读 SELECT 语句，不要写 INSERT/UPDATE/DELETE/DDL；\n"
                + "3. 结果用 ```sql 代码块包裹，不要解释；\n"
                + "4. 需要聚合/排序/分页时合理处理，结果行数控制在 1000 行以内。\n"
                + (withTraining ? "5. 已提供 SQL 样例作为参考，优先对齐其风格。\n" : "");
    }

    private static String chartSystemPrompt() {
        return "你是数据可视化专家。根据查询结果输出一个 G2 图表配置 JSON，字段说明：\n"
                + "type: column(柱状)/bar(条形)/line(折线)/pie(饼图)/area(面积)/table(表格)；\n"
                + "title: 标题字符串；\n"
                + "columns: [{name:'列名', value:'列名'}]（查询结果列）；\n"
                + "axis: {x:{name,value}, y:{name,value} 或 [{name,value}], series?:{name,value}}。\n"
                + "只输出 JSON，不要 Markdown 代码块。";
    }

    private static String analysisSystemPrompt() {
        return "你是数据分析专家。基于查询结果与图表，输出简洁的分析结论（使用中文 Markdown，可含要点列表）。";
    }

    private static String predictSystemPrompt() {
        return "你是数据预测专家。基于历史数据输出合理的趋势预测："
                + "先给出中文结论，再输出一个 JSON 数组作为预测数据（字段与查询结果一致）。";
    }

    private static String recommendSystemPrompt() {
        return "你是问答助手。基于用户原问题，生成若干相关的、可继续追问的自然语言问题，"
                + "只输出一个 JSON 数组，不要编号、不要解释。";
    }
}
