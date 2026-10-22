package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.embedding.DatasourceEmbeddingService;
import com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.training.TrainingRecallService;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.engine.ColumnSchema;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.engine.TableSchema;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;

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

    private final TableEmbeddingService tableEmbeddingService;

    private final DatasourceEmbeddingService datasourceEmbeddingService;

    private final TrainingRecallService trainingRecallService;

    @Autowired
    public SchemaContextBuilder(DatasourceRepository datasourceRepository, TableRepository tableRepository,
                                FieldRepository fieldRepository, DatasourceService datasourceService,
                                ChatModelClient modelClient, ChatModelRouter modelRouter, JdbcTemplate jdbc,
                                ObjectMapper objectMapper, TableEmbeddingService tableEmbeddingService,
                                DatasourceEmbeddingService datasourceEmbeddingService,
                                TrainingRecallService trainingRecallService) {
        this.datasourceRepository = datasourceRepository;
        this.tableRepository = tableRepository;
        this.fieldRepository = fieldRepository;
        this.datasourceService = datasourceService;
        this.modelClient = modelClient;
        this.modelRouter = modelRouter;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tableEmbeddingService = tableEmbeddingService;
        this.datasourceEmbeddingService = datasourceEmbeddingService;
        this.trainingRecallService = trainingRecallService;
    }

    /** 构造（保留旧签名；未注入向量召回服务时走词法降级路径）。 */
    public SchemaContextBuilder(DatasourceRepository datasourceRepository, TableRepository tableRepository,
                                FieldRepository fieldRepository, DatasourceService datasourceService,
                                ChatModelClient modelClient, ChatModelRouter modelRouter, JdbcTemplate jdbc,
                                ObjectMapper objectMapper, TableEmbeddingService tableEmbeddingService) {
        this(datasourceRepository, tableRepository, fieldRepository, datasourceService, modelClient, modelRouter,
                jdbc, objectMapper, tableEmbeddingService, null, null);
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
        // 优先用<b>已落库的数据源向量</b>（{@code core_datasource.embedding}，
        // 对齐 SQLBot apps/datasource/embedding/ds_embedding.py）；
        // 无向量时回落「实时 embedding」，再回落词法（bigram Jaccard）——三者对调用方透明。
        List<Long> vectorRanked = datasourceEmbeddingService == null
                ? null : datasourceEmbeddingService.rankByVector(question, dsIds);
        if (vectorRanked == null) {
            vectorRanked = java.util.Collections.emptyList();
        }
        double[] questionVector = null;
        if (vectorRanked.size() != dsIds.size() || !new java.util.HashSet<>(vectorRanked).equals(
                new java.util.HashSet<>(dsIds))) {
            // 向量未覆盖全部候选 → 仍走实时/词法打分
            vectorRanked = null;
        }
        List<Double> scores = new ArrayList<>();
        if (vectorRanked == null) {
            List<double[]> vectors = embedAll(question, docs);
            if (vectors != null && vectors.size() == docs.size() + 1) {
                questionVector = vectors.get(0);
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
        // 已落库向量覆盖全部候选 → 按向量排名取第一个
        long bestId = vectorRanked.get(0);
        log.info("数据源 RAG 选库（向量）：候选 {}，命中 ds={}", dsIds, bestId);
        return bestId;
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
            // 优先用已落库的样例向量（data_training.embedding）做向量召回；
            // 无向量时回落「实时 embedding 全部行」，再回落词法（对齐 SQLBot 语义）。
            List<Map<String, Object>> vectorHits = trainingRecallService == null
                    ? java.util.Collections.emptyList()
                    : trainingRecallService.recallByDatasource(question, dsId);
            if (!vectorHits.isEmpty()) {
                List<String> hits = new ArrayList<>();
                for (Map<String, Object> hit : vectorHits) {
                    String q = Rows.str(hit, "question");
                    String sql = Rows.str(hit, "description");
                    hits.add(String.valueOf(q == null ? "" : q) + "\n" + (sql == null ? "" : sql));
                }
                return hits;
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
        return buildSqlMessages(question, dsId, "", "", null);
    }

    /**
     * 构建 SQL 生成的 system + user 消息，含 A2 知识增强上下文：
     * 业务术语（释义 + 同义词扩展）、自定义提示词与训练样例。
     *
     * @param terminologyText  术语模板文本（XML 形态；空串时省略该节）
     * @param customPromptText 自定义提示词（空串时省略该节）
     * @param sqlExamples      SQL 示例（null 时本方法自行召回；调用方已召回时传入以避免重复查询）
     */
    public List<ModelMessage> buildSqlMessages(String question, Long dsId, String terminologyText,
                                               String customPromptText, List<String> sqlExamples) {
        List<ModelMessage> messages = new ArrayList<>();
        List<String> samples = sqlExamples != null ? sqlExamples
                : (dsId == null ? Collections.<String>emptyList() : trainingSamples(dsId, question));
        messages.add(new ModelMessage("system", sqlSystemPrompt(!samples.isEmpty(), resolveType(dsId))));
        StringBuilder user = new StringBuilder();
        user.append("【数据库表结构】\n").append(dsId == null ? "（未选择数据源）" : buildSchemaText(dsId, question));
        if (terminologyText != null && !terminologyText.isEmpty()) {
            user.append("\n\n【业务术语】\n").append(terminologyText);
        }
        if (customPromptText != null && !customPromptText.isEmpty()) {
            user.append("\n\n【自定义提示词】\n").append(customPromptText);
        }
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
        messages.add(new ModelMessage("system", sqlSystemPrompt(false, resolveType(dsId))));
        messages.add(new ModelMessage("user",
                "【数据库表结构】\n" + (dsId == null ? "（未选择数据源）" : buildSchemaText(dsId, question))
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

    /** 表结构文本（表注释 + 勾选字段 + 样例行），供 schema 提示词使用；不按问题排序。 */
    public String buildSchemaText(long dsId) {
        return buildSchemaText(dsId, null);
    }

    /**
     * 表结构文本（按问题召回排序取 TOP-N）：问题点名或语义相关的表排在前面，
     * 使进入提示词的 {@value #MAX_TABLES} 张表是最相关的表（验收 A1）。
     *
     * <p>question 为空、召回不可用或失败时退化为既有表次序，不抛错。</p>
     */
    public String buildSchemaText(long dsId, String question) {
        List<Map<String, Object>> tables = tableRepository.listByDsIdChecked(dsId);
        if (tables.isEmpty()) {
            return "（该数据源未勾选任何表）";
        }
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> table : tables) {
            byId.put(Rows.lng(table, "id", 0L), table);
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Long tableId : topTableIds(dsId, question, new ArrayList<>(byId.keySet()))) {
            if (count >= MAX_TABLES) {
                break;
            }
            Map<String, Object> table = byId.get(tableId);
            if (table == null) {
                continue;
            }
            sb.append(tableBlock(dsId, table));
            count++;
        }
        return sb.toString();
    }

    /**
     * 表结构文本（动态数据源版，A8）：直接从连接配置实时读取表/字段，不依赖
     * core_datasource 的勾选记录；与静态数据源共用同一提示词模板与方言提示。
     */
    public String buildSchemaTextForConf(DatasourceType type, DatasourceConf conf, String question) {
        List<TableSchema> tables;
        try {
            tables = datasourceService.tablesTransient(type, conf);
        } catch (Exception e) {
            log.info("动态数据源表结构读取失败: {}", e.getMessage());
            return "（动态数据源表结构不可用）";
        }
        if (tables == null || tables.isEmpty()) {
            return "（该数据源未发现任何表）";
        }
        List<TableSchema> ordered = orderTablesByQuestion(tables, question);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (TableSchema table : ordered) {
            if (count >= MAX_TABLES) {
                break;
            }
            sb.append(tableBlockForConf(type, conf, table));
            count++;
        }
        return sb.toString();
    }

    private List<TableSchema> orderTablesByQuestion(List<TableSchema> tables, String question) {
        if (question == null || question.trim().isEmpty()) {
            return tables;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        List<TableSchema> hit = new ArrayList<>();
        List<TableSchema> rest = new ArrayList<>();
        for (TableSchema table : tables) {
            String name = table.getTableName();
            if (name != null && lower.contains(name.toLowerCase(Locale.ROOT))) {
                hit.add(table);
            } else {
                rest.add(table);
            }
        }
        hit.addAll(rest);
        return hit;
    }

    private String tableBlockForConf(DatasourceType type, DatasourceConf conf, TableSchema table) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table ").append(table.getTableName());
        if (table.getTableComment() != null && !table.getTableComment().trim().isEmpty()) {
            sb.append(" (").append(table.getTableComment()).append(")");
        }
        sb.append(":\n");
        List<ColumnSchema> fields;
        try {
            fields = datasourceService.fieldsTransient(type, conf, table.getTableName());
        } catch (Exception e) {
            fields = new ArrayList<>();
        }
        List<String> fieldNames = new ArrayList<>();
        for (ColumnSchema field : fields) {
            fieldNames.add(field.getFieldName());
            sb.append("  - ").append(field.getFieldName()).append(" ").append(field.getFieldType());
            if (field.getFieldComment() != null && !field.getFieldComment().trim().isEmpty()) {
                sb.append("  ").append(field.getFieldComment());
            }
            sb.append("\n");
        }
        sb.append(sampleRowsTextForConf(type, conf, table.getTableName(), fieldNames));
        return sb.toString();
    }

    private String sampleRowsTextForConf(DatasourceType type, DatasourceConf conf, String tableName,
                                         List<String> fieldNames) {
        if (fieldNames.isEmpty()) {
            return "";
        }
        try {
            String sql = type.previewSql(fieldNames, tableName, conf, null);
            Map<String, Object> preview = datasourceService.execSqlTransient(type, conf, sql);
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
            log.info("动态数据源样例行读取失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 构建 SQL 生成消息（动态数据源版，A8）：与静态数据源同一套 system 提示词（含方言提示）
     * 与 user 结构，仅表结构来源改为连接配置实时读取。
     */
    public List<ModelMessage> buildSqlMessagesForConf(String question, DatasourceType type, DatasourceConf conf,
                                                      String terminologyText, String customPromptText,
                                                      String historyText) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", sqlSystemPrompt(false, type)));
        StringBuilder user = new StringBuilder();
        user.append("【数据库表结构】\n").append(buildSchemaTextForConf(type, conf, question));
        if (terminologyText != null && !terminologyText.isEmpty()) {
            user.append("\n\n【业务术语】\n").append(terminologyText);
        }
        if (customPromptText != null && !customPromptText.isEmpty()) {
            user.append("\n\n【自定义提示词】\n").append(customPromptText);
        }
        if (historyText != null && !historyText.isEmpty()) {
            user.append("\n\n【前文问答（追问请优先引用其中已出现的表名、列名与实体）】\n").append(historyText);
        }
        user.append("\n\n【用户问题】\n").append(question)
                .append("\n\n请只输出 SQL，使用 ```sql 代码块包裹。");
        messages.add(new ModelMessage("user", user.toString()));
        return messages;
    }

    /** 构建 SQL 修复消息（动态数据源版，A8）。 */
    public List<ModelMessage> buildSqlFixMessagesForConf(String question, DatasourceType type, DatasourceConf conf,
                                                         String brokenSql, String error) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", sqlSystemPrompt(false, type)));
        messages.add(new ModelMessage("user",
                "【数据库表结构】\n" + buildSchemaTextForConf(type, conf, question)
                        + "\n\n【用户问题】\n" + question
                        + "\n\n【上次生成的 SQL】\n" + brokenSql
                        + "\n\n【错误】\n" + error
                        + "\n\n请修正后只输出一条正确的 SQL，使用 ```sql 代码块包裹。"));
        return messages;
    }

    /** TOP-N 表 id：有问题时走表级向量召回排序（已按 MAX_TABLES 截断），否则保持既有次序。 */
    private List<Long> topTableIds(long dsId, String question, List<Long> orderedIds) {
        if (question == null || question.trim().isEmpty()) {
            return orderedIds;
        }
        try {
            List<Long> ranked = tableEmbeddingService.rankTables(dsId, question, MAX_TABLES);
            return ranked.isEmpty() ? orderedIds : ranked;
        } catch (Exception e) {
            // 召回异常（含向量读取失败）不影响 schema 提示词主链路，退化为既有次序
            log.info("表级召回失败（退化为既有表次序）: {}", e.getMessage());
            return orderedIds;
        }
    }

    /** 单表提示词块（表注释 + 勾选字段 + 样例行）。 */
    private String tableBlock(long dsId, Map<String, Object> table) {
        StringBuilder sb = new StringBuilder();
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

    private static String sqlSystemPrompt(boolean withTraining, DatasourceType dialect) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个严谨的数据库 SQL 专家，服务于一个中文问数（NL2SQL）系统。\n")
                .append("规则：\n")
                .append("1. 只能使用用户提供的表结构中的表与字段；\n")
                .append("2. 只生成一条只读 SELECT 语句，不要写 INSERT/UPDATE/DELETE/DDL；\n")
                .append("3. 结果用 ```sql 代码块包裹，不要解释；\n")
                .append("4. 需要聚合/排序/分页时合理处理，结果行数控制在 1000 行以内。\n");
        if (dialect != null) {
            // 验收 A1：注入方言特定的标识符引用与分页约定，避免模型默认按 MySQL 生成错误语法
            sb.append(dialect.sqlDialectHint()).append("\n");
        }
        if (withTraining) {
            sb.append("已提供 SQL 样例作为参考，优先对齐其风格。\n");
        }
        return sb.toString();
    }

    /**
     * 由数据源 id 解析方言（验收 A1）；解析失败或未知类型时返回 null（退化为通用提示，不抛错）。
     */
    private DatasourceType resolveType(Long dsId) {
        if (dsId == null) {
            return null;
        }
        try {
            return datasourceRepository.findById(dsId)
                    .map(row -> DatasourceType.fromType(Rows.str(row, "type")))
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            log.info("未知数据源类型，退化为通用提示: dsId={}", dsId);
            return null;
        }
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
