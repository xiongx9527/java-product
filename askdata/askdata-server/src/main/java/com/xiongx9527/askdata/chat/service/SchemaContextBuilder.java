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
import com.xiongx9527.askdata.permission.DsPermissionQueryService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.repository.SysArgRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

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
    private static final int MAX_TABLES = 10;

    /** 每张表进入提示词的样例行数。 */
    private static final int SAMPLE_ROWS = 3;

    /**
     * A27：样例行开关（{@code askdata.prompt.sample-data-enabled}，缺省 true）。
     *
     * <p>关闭后 schema 提示词只含表/字段结构，不再查样例行（大库或敏感数据场景可减少
     * 数据外泄面与预览查询开销）；字段缺省值保持既有行为，单测手工构造实例时同样为 true。</p>
     */
    @Value("${askdata.prompt.sample-data-enabled:true}")
    private boolean sampleDataEnabled = true;

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

    /** §2.1: chat.* 运行参数来源（sqlbot_name 等）；单测环境可为 null。 */
    private final SysArgRepository sysArgRepository;

    /**
     * A9：SQLBot 提示词模板装载器（classpath prompt/template.yaml + sql_examples/&lt;方言&gt;.yaml）。
     *
     * <p>Setter 注入（required=false）：单测用旧构造器创建实例时退化为内置的精简提示词，
     * 不影响既有用例；Spring 环境下走移植模板。</p>
     */
    private SqlPromptTemplates promptTemplates;

    @Autowired(required = false)
    public void setPromptTemplates(SqlPromptTemplates promptTemplates) {
        this.promptTemplates = promptTemplates;
    }

    /**
     * A7：列权限查询服务（构造 schema/提示词前剔除无权限字段）。setter 注入（required=false）：
     * 未注入时退化为不裁剪，保持既有行为。
     */
    private DsPermissionQueryService permissionQuery;

    private CurrentUserService currentUserService;

    @Autowired(required = false)
    public void setPermissionQuery(DsPermissionQueryService permissionQuery) {
        this.permissionQuery = permissionQuery;
    }

    @Autowired(required = false)
    public void setCurrentUserService(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /** A7：当前用户在该表上无权限（enable=false）的列名集合；管理员/未注入时为空白。 */
    private Set<String> hiddenColumnsFor(long dsId, String tableName) {
        if (permissionQuery == null || currentUserService == null) {
            return Collections.emptySet();
        }
        Map<String, Object> user = currentUserService.currentUser();
        if (currentUserService.isAdmin(user)) {
            return Collections.emptySet();
        }
        return permissionQuery.hiddenColumns(currentUserService.oidOf(user), Rows.lng(user, "id", 0L), dsId,
                tableName);
    }

    @Autowired
    public SchemaContextBuilder(DatasourceRepository datasourceRepository, TableRepository tableRepository,
                                FieldRepository fieldRepository, DatasourceService datasourceService,
                                ChatModelClient modelClient, ChatModelRouter modelRouter, JdbcTemplate jdbc,
                                ObjectMapper objectMapper, TableEmbeddingService tableEmbeddingService,
                                DatasourceEmbeddingService datasourceEmbeddingService,
                                TrainingRecallService trainingRecallService,
                                SysArgRepository sysArgRepository) {
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
        this.sysArgRepository = sysArgRepository;
    }

    /** 构造（保留旧签名；未注入 sys_arg 时 chat 参数走默认值，未注入向量召回服务时走词法降级路径）。 */
    public SchemaContextBuilder(DatasourceRepository datasourceRepository, TableRepository tableRepository,
                                FieldRepository fieldRepository, DatasourceService datasourceService,
                                ChatModelClient modelClient, ChatModelRouter modelRouter, JdbcTemplate jdbc,
                                ObjectMapper objectMapper, TableEmbeddingService tableEmbeddingService,
                                DatasourceEmbeddingService datasourceEmbeddingService,
                                TrainingRecallService trainingRecallService) {
        this(datasourceRepository, tableRepository, fieldRepository, datasourceService, modelClient, modelRouter,
                jdbc, objectMapper, tableEmbeddingService, datasourceEmbeddingService, trainingRecallService, null);
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
        return trainingSamples(dsId, question, null);
    }

    /**
     * 训练样例召回（A9 高级应用知识范围隔离）：{@code advancedAppId} 非空时只召回该高级应用的
     * 训练样例，避免跨应用泄漏示例知识；为空时保持既有数据源级召回。
     */
    public List<String> trainingSamples(long dsId, String question, Long advancedAppId) {
        if (!trainingTableAvailable()) {
            return Collections.emptyList();
        }
        try {
            StringBuilder trainingSql = new StringBuilder(
                    "SELECT question, description, embedding FROM data_training "
                            + "WHERE datasource = ? AND enabled = TRUE");
            List<Object> trainingArgs = new ArrayList<>();
            trainingArgs.add(dsId);
            if (advancedAppId != null) {
                trainingSql.append(" AND advanced_application = ?");
                trainingArgs.add(advancedAppId);
            }
            List<Map<String, Object>> rows = jdbc.queryForList(trainingSql.toString(), trainingArgs.toArray());
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
                    : trainingRecallService.recallByDatasource(question, dsId, advancedAppId);
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

    /** SQL 生成 JSON 格式约束（多轮 ack 的最后一跳 user 消息统一复用）。 */
    private static final String SQL_JSON_FORMAT =
            "请只输出 SQL 对应的结构化 JSON 对象（不要使用 Markdown 代码块），字段如下：\n"
            + "{\n"
            + "  \"success\": true 或 false（无法生成 SQL 时为 false）,\n"
            + "  \"sql\": 生成的只读 SELECT 语句字符串（success 为 false 时为空字符串）,\n"
            + "  \"brief\": 一句话会话标题（中文，不超过 20 字，用于重命名本次会话）,\n"
            + "  \"chart-type\": 推荐图表类型，取值 table/column/bar/line/pie 之一,\n"
            + "  \"tables\": 本次查询实际用到的表名数组（必须与 FROM/JOIN 中的表一致）\n"
            + "}\n"
            + "注意：sql 字段必须是一条完整、可执行的 SQL，不得包含解释性文字。";

    /** 构建 SQL 生成的 system + user 消息（含训练样例时附加参考）。 */
    public List<ModelMessage> buildSqlMessages(String question, Long dsId) {
        return buildSqlMessages(question, dsId, "", "", trainingSamples(dsId, question), null, question);
    }

    /**
     * 构建 SQL 生成的消息序列（多轮 ack 结构，对齐 SQLBot llm.py init_messages）：
     * system(身份) → user(rules)+assistant(ack) → user(schema)+assistant(ack)
     * → 可选 user(custom_prompt)/user(terminologies)/user(data_training) 各配 assistant(ack)
     * → 历史轮次（priorContextMessages 产出）→ user(question)。
     *
     * @param terminologyText  术语模板文本（XML 形态；空串时省略该节）
     * @param customPromptText 自定义提示词（空串时省略该节）
     * @param sqlExamples      SQL 示例（训练样例；null 时本方法自行召回）
     * @param historyMessages  前序完整对话轮（已剔除 system），由 ChatQuestionService.priorContextMessages 产出
     */
    public List<ModelMessage> buildSqlMessages(String question, Long dsId, String terminologyText,
                                               String customPromptText, List<String> sqlExamples,
                                               List<ModelMessage> historyMessages, String matchText) {
        List<String> samples = sqlExamples != null ? sqlExamples
                : (dsId == null ? Collections.<String>emptyList() : trainingSamples(dsId, question));
        String identity = sqlSystemIdentity(resolveType(dsId));
        String rules = sqlSystemRules(resolveType(dsId));
        String schemaText = dsId == null ? "（未选择数据源）" : buildSchemaText(dsId, question, matchText);
        return buildSqlMessageSeq(identity, rules, schemaText, terminologyText, customPromptText,
                samples, historyMessages, question, SQL_JSON_FORMAT, null);
    }

    /**
     * 多轮 ack 公用骨架：system(identity) + 多组 user/assistant ack + 历史轮次 + 末跳 user(question)。
     *
     * @param schemaExtra 追加到 schema user 消息尾部的内容（动态数据源的模板/行规则段），可为 null
     */
    private List<ModelMessage> buildSqlMessageSeq(String identity, String rules, String schemaText,
                                                  String terminologyText, String customPromptText,
                                                  List<String> sqlExamples, List<ModelMessage> historyMessages,
                                                  String question, String jsonFormat, String schemaExtra) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", identity));
        messages.add(new ModelMessage("user", rules));
        messages.add(new ModelMessage("assistant",
                "我已掌握所有规则，包括表结构、SQL规范、安全限制和输出格式，我会严格遵守这些规则。"));
        String schemaUser = "【数据库表结构】\n" + schemaText + (schemaExtra == null ? "" : schemaExtra);
        messages.add(new ModelMessage("user", schemaUser));
        messages.add(new ModelMessage("assistant",
                "我已确认您提供的数据库信息与表结构schema，我生成的SQL不会超出您提供的范围。"));
        if (customPromptText != null && !customPromptText.isEmpty()) {
            messages.add(new ModelMessage("user", "【自定义提示词】\n" + customPromptText));
            messages.add(new ModelMessage("assistant", "我已确认您提供的额外信息，我会进行参考。"));
        }
        if (terminologyText != null && !terminologyText.isEmpty()) {
            messages.add(new ModelMessage("user", "【业务术语】\n" + terminologyText));
            messages.add(new ModelMessage("assistant", "我已确认您提供的术语信息，我会进行参考。"));
        }
        if (sqlExamples != null && !sqlExamples.isEmpty()) {
            StringBuilder sb = new StringBuilder("【可参考的 SQL 样例】\n");
            for (String s : sqlExamples) {
                sb.append(s).append("\n");
            }
            messages.add(new ModelMessage("user", sb.toString()));
            messages.add(new ModelMessage("assistant", "我已确认您提供的SQL示例，我会进行参考。"));
        }
        if (historyMessages != null) {
            messages.addAll(historyMessages);
        }
        messages.add(new ModelMessage("user", "【用户问题】\n" + question + "\n\n" + jsonFormat));
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
                        + "\n\n请修正后只输出一条正确的 SQL：以结构化 JSON 对象返回（字段与首次生成一致：success/sql/brief/chart-type/tables），不得包含解释性文字或 Markdown 代码块。"));
        return messages;
    }

    /** 构建图表生成的 system + user 消息（data 为已执行结果 {fields, data}）。 */
    /**
     * A10：静态数据源的 m-schema 文本（图表生成上下文用）。
     *
     * @return schema 文本；失败时返回空串（调用方省略该段）
     */
    public String mSchemaOf(long dsId) {
        try {
            return buildSchemaText(dsId);
        } catch (RuntimeException e) {
            log.info("m-schema 读取失败（图表上下文省略该段）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * A10：动态数据源的 m-schema 文本（图表生成上下文用）。
     *
     * @param tableSqlTemplates 宿主下发的表级 SQL 模板（占位符表 → 子查询）
     */
    public String mSchemaOfConf(DatasourceType type, DatasourceConf conf,
                                Map<String, String> tableSqlTemplates) {
        try {
            String text = buildSchemaTextForConf(type, conf, "");
            if (tableSqlTemplates != null && !tableSqlTemplates.isEmpty()) {
                StringBuilder sb = new StringBuilder(text);
                appendDynamicTemplateSection(sb, tableSqlTemplates, "查询这些受限表时");
                return sb.toString();
            }
            return text;
        } catch (RuntimeException e) {
            log.info("m-schema 读取失败（图表上下文省略该段）: {}", e.getMessage());
            return "";
        }
    }

    public List<ModelMessage> buildChartMessages(String question, String dataJson) {
        return buildChartMessages(question, dataJson, null, null, null);
    }

    /**
     * 构建图表生成的 system + user 消息（A10：对齐 SQLBot {@code llm.py:1085-1130}）。
     *
     * <p>用户消息除问题与查询结果外，还携带三段上下文：{@code <sql>}（生成该结果的 SQL）、
     * {@code <m-schema>}（数据源的 mini schema，供模型判断字段语义与多指标）、
     * {@code <chart-type>}（结果暗示的图表类型）。缺省传 null 时相应段省略，行为与旧版一致。</p>
     */
    public List<ModelMessage> buildChartMessages(String question, String dataJson, String sql, String mSchema,
                                                String chartType) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", chartSystemPrompt()));
        StringBuilder user = new StringBuilder();
        user.append("【用户问题】\n").append(question);
        if (sql != null && !sql.trim().isEmpty()) {
            user.append("\n\n<sql>\n").append(sql.trim()).append("\n</sql>");
        }
        if (mSchema != null && !mSchema.trim().isEmpty()) {
            user.append("\n\n<m-schema>\n").append(mSchema.trim()).append("\n</m-schema>");
        }
        if (chartType != null && !chartType.trim().isEmpty()) {
            user.append("\n\n<chart-type>\n").append(chartType.trim()).append("\n</chart-type>");
        }
        user.append("\n\n【查询结果（JSON，最多 20 行）】\n").append(dataJson);
        user.append("\n\n请只输出一个 G2 图表配置 JSON（不要 Markdown 代码块）。");
        messages.add(new ModelMessage("user", user.toString()));
        return messages;
    }

    /** 构建分析生成的 system + user 消息。 */
    public List<ModelMessage> buildAnalysisMessages(String question, String chartConfigJson, String dataJson) {
        return buildAnalysisMessages(question, chartConfigJson, dataJson, "");
    }

    /**
     * 构建分析生成的 system + user 消息。
     *
     * @param customPromptText 分析类自定义提示词（custom_prompt.type=ANALYSIS；空串时省略该节）
     */
    public List<ModelMessage> buildAnalysisMessages(String question, String chartConfigJson, String dataJson,
                                                    String customPromptText) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", analysisSystemPrompt()));
        StringBuilder user = new StringBuilder();
        user.append("【用户问题】\n").append(question).append("\n\n【图表配置】\n").append(chartConfigJson)
                .append("\n\n【查询结果】\n").append(dataJson);
        if (customPromptText != null && !customPromptText.isEmpty()) {
            user.append("\n\n【自定义提示词】\n").append(customPromptText);
        }
        user.append("\n\n请输出对结果的分析（中文 Markdown）。");
        messages.add(new ModelMessage("user", user.toString()));
        return messages;
    }

    /** 构建预测生成的 system + user 消息。 */
    public List<ModelMessage> buildPredictMessages(String question, String chartConfigJson, String dataJson) {
        return buildPredictMessages(question, chartConfigJson, dataJson, "");
    }

    /**
     * 构建预测生成的 system + user 消息。
     *
     * @param customPromptText 预测类自定义提示词（custom_prompt.type=PREDICT_DATA；空串时省略该节）
     */
    public List<ModelMessage> buildPredictMessages(String question, String chartConfigJson, String dataJson,
                                                   String customPromptText) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", predictSystemPrompt()));
        StringBuilder user = new StringBuilder();
        user.append("【用户问题】\n").append(question).append("\n\n【图表配置】\n").append(chartConfigJson)
                .append("\n\n【历史查询结果】\n").append(dataJson);
        if (customPromptText != null && !customPromptText.isEmpty()) {
            user.append("\n\n【自定义提示词】\n").append(customPromptText);
        }
        user.append("\n\n请输出一个 JSON 数组作为预测数据（字段与查询结果一致），数组前后可附中文结论说明。");
        messages.add(new ModelMessage("user", user.toString()));
        return messages;
    }

    /** 构建推荐问题生成的 system + user 消息（基础版，不注入图表/历史上下文）。 */
    public List<ModelMessage> buildRecommendMessages(String question, int articlesNumber) {
        return buildRecommendMessages(question, articlesNumber, null, null);
    }

    /**
     * 构建推荐问题生成的 system + user 消息（增强版，对齐 SQLBot §2.10 推荐问题模板增强）。
     * 当 {@code chartHint} 非空时，提示词携带当前图表/可视化类型，引导生成可用于深化分析的追问；
     * 当 {@code history} 非空时，注入历史提问，使新提问能承接既有对话上下文。
     */
    public List<ModelMessage> buildRecommendMessages(String question, int articlesNumber,
                                                     String chartHint, List<String> history) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", recommendSystemPrompt()));
        messages.add(new ModelMessage("user", buildRecommendUserPrompt(question, articlesNumber, chartHint, history)));
        return messages;
    }

    /** 组装推荐问题 user 提示词（包级可见，便于单测）。 */
    static String buildRecommendUserPrompt(String question, int articlesNumber,
                                           String chartHint, List<String> history) {
        StringBuilder user = new StringBuilder();
        user.append("【原问题】\n").append(question).append("\n");
        if (chartHint != null && !chartHint.trim().isEmpty()) {
            user.append("\n【当前图表/可视化类型】\n").append(chartHint.trim())
                    .append("\n（请结合该可视化类型，生成可用于深化分析的自然语言追问。）");
        }
        if (history != null && !history.isEmpty()) {
            user.append("\n【历史提问】\n");
            for (String h : history) {
                if (h != null && !h.trim().isEmpty()) {
                    user.append("- ").append(h.trim()).append("\n");
                }
            }
        }
        user.append("\n请生成 ").append(articlesNumber).append(" 个相关的追问问题，")
                .append("只输出一个 JSON 数组，例如 [\"问题1\",\"问题2\"]，不要编号、不要解释。");
        return user.toString();
    }

    // ------------------------------------------------------------------
    // 表结构
    // ------------------------------------------------------------------

    /** 表结构文本（表注释 + 勾选字段 + 样例行），供 schema 提示词使用；不按问题排序。 */
    public String buildSchemaText(long dsId) {
        return buildSchemaText(dsId, null, null);
    }

    /**
     * 表结构文本（按问题召回排序取 TOP-N）：问题点名或语义相关的表排在前面，
     * 使进入提示词的 {@value #MAX_TABLES} 张表是最相关的表（验收 A1）。
     *
     * <p>question 为空、召回不可用或失败时退化为既有表次序，不抛错。</p>
     */
    public String buildSchemaText(long dsId, String question) {
        return buildSchemaText(dsId, question, question);
    }

    /**
     * 表结构文本（按 matchText 召回排序取 TOP-N）：问题点名或语义相关的表排在前面。
     * {@code matchText} 缺省为 {@code question}；当知识增强链路给出 expanded_keywords（2.3，
     * 对齐 SQLBot 用扩展关键词做表匹配）时，传扩展关键词可显著提升「同义表述」下的选表命中率。
     */
    public String buildSchemaText(long dsId, String question, String matchText) {
        List<Map<String, Object>> tables = tableRepository.listByDsIdChecked(dsId);
        if (tables.isEmpty()) {
            return "（该数据源未勾选任何表）";
        }
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> table : tables) {
            byId.put(Rows.lng(table, "id", 0L), table);
        }
        StringBuilder sb = new StringBuilder();
        List<Long> included = new ArrayList<>();
        for (Long tableId : topTableIds(dsId, question, matchText, new ArrayList<>(byId.keySet()))) {
            if (included.size() >= MAX_TABLES) {
                break;
            }
            Map<String, Object> table = byId.get(tableId);
            if (table == null) {
                continue;
            }
            sb.append(tableBlock(dsId, table));
            included.add(tableId);
        }
        // A13：外键关系段（含未入选关联表的补全），对齐 SQLBot crud/datasource.py:604-666
        appendForeignKeys(sb, dsId, included);
        return sb.toString();
    }

    /**
     * A13：把数据源的表关系（{@code core_datasource.table_relation} 的 jointjs edge 列表）
     * 渲染为提示词的 {@code 【Foreign keys】} 段，形如 {@code a.x=b.y}。
     *
     * <p>语义对齐 SQLBot {@code apps/datasource/crud/datasource.py:604-666}：</p>
     * <ul>
     *   <li>只保留与已进入提示词的表相关（source/target 任一 cell 命中）的边；</li>
     *   <li>关系涉及但未入选提示词的表，按 SQLBot 的 "Complete the missing table" 补全其结构；</li>
     *   <li>无表关系、解析失败或表结构缺失时静默省略该段（不影响主链路）。</li>
     * </ul>
     */
    private void appendForeignKeys(StringBuilder sb, long dsId, List<Long> includedTableIds) {
        String relationJson;
        try {
            relationJson = datasourceRepository.findTableRelation(dsId);
        } catch (Exception e) {
            log.info("读取表关系失败（省略外键段）: {}", e.getMessage());
            return;
        }
        if (relationJson == null || relationJson.trim().isEmpty() || "null".equals(relationJson.trim())) {
            return;
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(relationJson);
            if (root == null || !root.isArray()) {
                return;
            }
            for (com.fasterxml.jackson.databind.JsonNode cell : root) {
                if (cell != null && "edge".equals(cell.path("shape").asText())) {
                    edges.add(objectMapper.convertValue(cell, Map.class));
                }
            }
        } catch (Exception e) {
            log.info("解析表关系失败（省略外键段）: {}", e.getMessage());
            return;
        }
        if (edges.isEmpty()) {
            return;
        }
        java.util.Set<Long> included = new java.util.HashSet<>(includedTableIds);
        List<Map<String, Object>> relevant = new ArrayList<>();
        java.util.Set<Long> tableIds = new java.util.LinkedHashSet<>();
        java.util.Set<Long> fieldIds = new java.util.LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            Map<String, Object> source = asMap(edge.get("source"));
            Map<String, Object> target = asMap(edge.get("target"));
            if (source == null || target == null) {
                continue;
            }
            long sourceCell = toLong(source.get("cell"));
            long targetCell = toLong(target.get("cell"));
            if (sourceCell <= 0 || targetCell <= 0) {
                continue;
            }
            if (!included.contains(sourceCell) && !included.contains(targetCell)) {
                continue;
            }
            relevant.add(edge);
            tableIds.add(sourceCell);
            tableIds.add(targetCell);
            fieldIds.add(toLong(source.get("port")));
            fieldIds.add(toLong(target.get("port")));
        }
        if (relevant.isEmpty()) {
            return;
        }
        Map<Long, String> tableNames = tableRepository.tableNamesByIds(new ArrayList<>(tableIds));
        Map<Long, String> fieldNames = fieldRepository.fieldNamesByIds(new ArrayList<>(fieldIds));
        // 补全关系涉及但未进入提示词的表（SQLBot: Complete the missing table）
        List<Long> lostIds = new ArrayList<>();
        for (Long tableId : tableIds) {
            if (!included.contains(tableId)) {
                lostIds.add(tableId);
            }
        }
        if (!lostIds.isEmpty()) {
            for (Map<String, Object> lost : tableRepository.listByIds(lostIds)) {
                sb.append(tableBlock(dsId, lost));
            }
        }
        sb.append("【Foreign keys】\n");
        for (Map<String, Object> edge : relevant) {
            Map<String, Object> source = asMap(edge.get("source"));
            Map<String, Object> target = asMap(edge.get("target"));
            String sourceTable = tableNames.get(toLong(source.get("cell")));
            String sourceField = fieldNames.get(toLong(source.get("port")));
            String targetTable = tableNames.get(toLong(target.get("cell")));
            String targetField = fieldNames.get(toLong(target.get("port")));
            if (sourceTable == null || sourceField == null || targetTable == null || targetField == null) {
                continue;
            }
            sb.append(sourceTable).append(".").append(sourceField)
                    .append("=").append(targetTable).append(".").append(targetField).append("\n");
        }
    }

    /** A13：jointjs 端点的 source/target 节点转为 Map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** A13：jointjs 节点的 cell/port id（可能为字符串数字）转为 long，非法值返回 0。 */
    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 表结构文本（动态数据源版，A8）：直接从连接配置实时读取表/字段，不依赖
     * core_datasource 的勾选记录；与静态数据源共用同一提示词模板与方言提示。
     */
    public String buildSchemaTextForConf(DatasourceType type, DatasourceConf conf, String question) {
        return buildSchemaTextForConf(type, conf, question, question);
    }

    /**
     * 动态数据源表结构文本（A8），按 matchText 相关性排序取 TOP-N。
     * {@code matchText} 缺省为 {@code question}；传扩展关键词可提升选表命中率（2.3）。
     */
    public String buildSchemaTextForConf(DatasourceType type, DatasourceConf conf, String question, String matchText) {
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
        String query = (matchText == null || matchText.trim().isEmpty()) ? question : matchText;
        List<TableSchema> ordered = orderTablesByQuestion(tables, query);
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

    /**
     * 表结构文本（应用库连接版，A2）：未登记为数据源、无勾选元数据的连接（如应用元数据库）
     * 不再每次问数全库 dump 完整 schema——只读取表名，按问题相关性排序后仅展开
     * {@value #MAX_TABLES} 张表的字段，与静态数据源的 top-N 选表语义一致。
     *
     * @param dataSource 目标连接（只读使用）
     * @param question   用户问题，用于表名相关性排序
     */
    public String buildSchemaTextForConnection(DataSource dataSource, String question) {
        try (Connection conn = dataSource.getConnection()) {
            List<String> tables = tableNamesOf(conn);
            if (tables.isEmpty()) {
                return "（数据库未发现任何表）";
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String table : orderNamesByQuestion(tables, question)) {
                if (count >= MAX_TABLES) {
                    break;
                }
                sb.append(connectionTableBlock(conn, table));
                count++;
            }
            log.info("应用库 schema 选表：候选 {}，进入提示词 {} 张", tables.size(), count);
            return sb.toString();
        } catch (Exception e) {
            log.info("应用库表结构读取失败: {}", e.getMessage());
            return "（数据库表结构不可用）";
        }
    }

    /** 连接内的用户表名（仅名称，不展开字段）。 */
    private static List<String> tableNamesOf(Connection conn) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet tables = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 按问题点名相关性排序表名（点名的表排前，其余保持元数据次序）。 */
    private static List<String> orderNamesByQuestion(List<String> names, String question) {
        if (question == null || question.trim().isEmpty()) {
            return names;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        List<String> hit = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String name : names) {
            if (lower.contains(name.toLowerCase(Locale.ROOT))) {
                hit.add(name);
            } else {
                rest.add(name);
            }
        }
        hit.addAll(rest);
        return hit;
    }

    /** 单表字段块（表名 + 字段名/类型）；字段行按 TABLE_NAME 精确匹配过滤，避免元数据通配符串表。 */
    private static String connectionTableBlock(Connection conn, String tableName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table ").append(tableName).append(":\n");
        try (ResultSet columns = conn.getMetaData().getColumns(null, null, tableName, "%")) {
            while (columns.next()) {
                String owner = columns.getString("TABLE_NAME");
                if (owner != null && !owner.equalsIgnoreCase(tableName)) {
                    continue;
                }
                sb.append("  - ").append(columns.getString("COLUMN_NAME")).append(" ")
                        .append(columns.getString("TYPE_NAME")).append("\n");
            }
        } catch (Exception e) {
            log.info("应用库字段读取失败 table={}: {}", tableName, e.getMessage());
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
        if (!sampleDataEnabled || fieldNames.isEmpty()) {
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
     * 构建 SQL 生成消息（动态数据源版，A8 / §2.4）。多轮 ack 结构对齐 SQLBot init_messages。
     *
     * @param historyMessages 前序完整对话轮（已剔除 system）；null 时省略
     */
    public List<ModelMessage> buildSqlMessagesForConf(String question, DatasourceType type, DatasourceConf conf,
                                                      String terminologyText, String customPromptText,
                                                      List<ModelMessage> historyMessages, String matchText,
                                                      Map<String, String> tableSqlTemplates) {
        return buildSqlMessagesForConf(question, type, conf, terminologyText, customPromptText, historyMessages,
                matchText, tableSqlTemplates, null);
    }

    /**
     * 构建 SQL 生成消息（动态数据源版，A8 / §2.4 / A18）。多轮 ack 结构对齐 SQLBot init_messages。
     *
     * @param tableSqlTemplates §2.4: 表级 SQL 模板（表名 -> 子查询模板）；非空时作为 CTE/子查询提示注入，
     *                          要求模型在最终 SQL 中优先复用这些已下发的子查询（对齐 SQLBot dynamic_sql）。
     * @param tableRules        A18: 宿主下发的表级行规则（表名 -> WHERE 条件片段）；非空时告知模型这些
     *                          数据边界，执行前还会由 ChatQuestionService 确定性注入。
     */
    public List<ModelMessage> buildSqlMessagesForConf(String question, DatasourceType type, DatasourceConf conf,
                                                      String terminologyText, String customPromptText,
                                                      List<ModelMessage> historyMessages, String matchText,
                                                      Map<String, String> tableSqlTemplates,
                                                      Map<String, String> tableRules) {
        String identity = sqlSystemIdentity(type);
        String rules = sqlSystemRules(type);
        String schemaText = buildSchemaTextForConf(type, conf, question, matchText);
        StringBuilder schemaExtra = new StringBuilder();
        if (tableSqlTemplates != null && !tableSqlTemplates.isEmpty()) {
            appendDynamicTemplateSection(schemaExtra, tableSqlTemplates, "查询这些受限表时");
        }
        if (tableRules != null && !tableRules.isEmpty()) {
            String drules = com.xiongx9527.askdata.embedded.DynamicDatasource.rowRulesPromptSection(tableRules);
            if (!drules.isEmpty()) {
                schemaExtra.append("\n\n【宿主数据边界（行规则，必须满足；系统执行前会强制注入）】\n").append(drules);
            }
        }
        String jsonFormat = "请只输出 SQL 对应的结构化 JSON 对象（不要使用 Markdown 代码块），字段同静态数据源："
                + "success/sql/brief/chart-type/tables，sql 为完整可执行的只读 SELECT。";
        return buildSqlMessageSeq(identity, rules, schemaText, terminologyText, customPromptText,
                null, historyMessages, question, jsonFormat, schemaExtra.toString());
    }

    public List<ModelMessage> buildSqlMessagesForConf(String question, DatasourceType type, DatasourceConf conf,
                                                      String terminologyText, String customPromptText,
                                                      List<ModelMessage> historyMessages, String matchText) {
        return buildSqlMessagesForConf(question, type, conf, terminologyText, customPromptText, historyMessages,
                matchText, Collections.<String, String>emptyMap());
    }

    /** 构建 SQL 修复消息（动态数据源版，A8）。 */
    /**
     * 构建 SQL 修复消息（动态数据源版，A8 / §2.4）。
     *
     * @param tableSqlTemplates §2.4: 表级 SQL 模板（表名 -> 子查询模板）；非空时注入修复提示，
     *                          要求修正 SQL 时继续复用已下发的子查询/CTE。
     */
    public List<ModelMessage> buildSqlFixMessagesForConf(String question, DatasourceType type, DatasourceConf conf,
                                                         String brokenSql, String error,
                                                         Map<String, String> tableSqlTemplates) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", sqlSystemPrompt(false, type)));
        StringBuilder user = new StringBuilder();
        user.append("【数据库表结构】\n").append(buildSchemaTextForConf(type, conf, question));
        if (tableSqlTemplates != null && !tableSqlTemplates.isEmpty()) {
            appendDynamicTemplateSection(user, tableSqlTemplates, "修正后的 SQL 仍引用这些受限表时");
        }
        user.append("\n\n【用户问题】\n").append(question)
                .append("\n\n【上次生成的 SQL】\n").append(brokenSql)
                .append("\n\n【错误】\n").append(error)
                .append("\n\n请修正后只输出一条正确的 SQL：以结构化 JSON 对象返回（字段与首次生成一致：success/sql/brief/chart-type/tables），不得包含解释性文字或 Markdown 代码块。");
        messages.add(new ModelMessage("user", user.toString()));
        return messages;
    }

    public List<ModelMessage> buildSqlFixMessagesForConf(String question, DatasourceType type, DatasourceConf conf,
                                                         String brokenSql, String error) {
        return buildSqlFixMessagesForConf(question, type, conf, brokenSql, error,
                Collections.<String, String>emptyMap());
    }

    /**
     * §2.4: 动态数据源宿主受限表段（对齐 SQLBot dynamic_subsql_prefix 形态）：
     * 每表声明一个占位符表名 {@code askdata_dynamic_temp_table_<表名>}，宿主子查询仅用于了解列结构；
     * 模型在最终 SQL 中只引用占位符表，执行前由 ChatQuestionService 确定性替换为宿主子查询，
     * 保证宿主下发的数据边界有程序性保证（brief D2）。
     */
    private void appendDynamicTemplateSection(StringBuilder user, Map<String, String> tableSqlTemplates,
                                              String whenClause) {
        user.append("\n\n【宿主下发的受限表（").append(whenClause)
                .append("，必须通过占位符表名引用，禁止直接访问同名物理表，也不要把定义子查询原样写进最终 SQL）】\n");
        for (Map.Entry<String, String> entry : tableSqlTemplates.entrySet()) {
            user.append("- 占位符表名: ").append(com.xiongx9527.askdata.embedded.DynamicDatasource.placeholderTable(entry.getKey()))
                    .append("\n  定义子查询（仅用于了解该表的列结构）: ").append(entry.getValue()).append("\n");
        }
        user.append("系统会在执行前把占位符表名替换为对应定义子查询；请像使用普通表一样引用占位符表名（可 JOIN/子查询/WITH）。\n");
    }

    /** TOP-N 表 id：有问题时走表级向量召回排序（已按 MAX_TABLES 截断），否则保持既有次序。 */
    private List<Long> topTableIds(long dsId, String question, String matchText, List<Long> orderedIds) {
        if ((question == null || question.trim().isEmpty())
                && (matchText == null || matchText.trim().isEmpty())) {
            return orderedIds;
        }
        try {
            List<Long> ranked = tableEmbeddingService.rankTables(dsId, question, matchText, MAX_TABLES);
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
        // A7：构造 schema 提示词前剔除当前用户无权限（列权限 enable=false）的字段
        Set<String> hidden = hiddenColumnsFor(dsId, tableName);
        for (Map<String, Object> field : fieldRepository.listByTableId(Rows.lng(table, "id", 0L))) {
            if (!Rows.bool(field, "checked", true)) {
                continue;
            }
            String fieldName = Rows.str(field, "field_name");
            if (hidden.contains(fieldName)) {
                continue;
            }
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

    /**
     * 本轮 {@link #buildSchemaText} 实际选中的表名（retrieved-only：按表级召回排序取 TOP-N）。
     *
     * <p>对齐 SQLBot {@code llm.py} 的 {@code table_name_list = choose_table_schema()}：
     * 仅「检索/召回进入 schema 提示词」的表才进入放行白名单（偏差④收敛）。与
     * {@link #checkedTableNames(long)}（全部勾选表）不同，未进入本轮 schema 的勾选表不会被放行，
     * 因此模型若引用了「勾选但未进入 schema」的表将被拒绝。</p>
     */
    public List<String> selectedTableNames(long dsId, String question, String matchText) {
        List<Map<String, Object>> tables = tableRepository.listByDsIdChecked(dsId);
        if (tables.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> table : tables) {
            byId.put(Rows.lng(table, "id", 0L), table);
        }
        List<String> names = new ArrayList<>();
        List<Long> included = new ArrayList<>();
        for (Long tableId : topTableIds(dsId, question, matchText, new ArrayList<>(byId.keySet()))) {
            if (included.size() >= MAX_TABLES) {
                break;
            }
            Map<String, Object> table = byId.get(tableId);
            if (table == null) {
                continue;
            }
            included.add(tableId);
            names.add(Rows.str(table, "table_name"));
        }
        return names;
    }

    /**
     * 选中数据源下「勾选表 → (勾选字段 → 字段类型)」的元数据快照（A10 SQL 语义校验用）。
     *
     * <p>与 {@link #checkedTableNames(long)} 同源（core_table / core_field 的 checked 标记），
     * 表名与字段名由调用方按大小写不敏感比对。未同步字段的表返回空列集合。</p>
     */
    public Map<String, Map<String, String>> checkedTableColumns(long dsId) {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        for (Map<String, Object> table : tableRepository.listByDsIdChecked(dsId)) {
            String tableName = Rows.str(table, "table_name");
            if (tableName == null || tableName.isEmpty()) {
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            for (Map<String, Object> field : fieldRepository.listByTableId(Rows.lng(table, "id", 0L))) {
                if (!Rows.bool(field, "checked", true)) {
                    continue;
                }
                String fieldName = Rows.str(field, "field_name");
                if (fieldName != null && !fieldName.isEmpty()) {
                    columns.put(fieldName, Rows.str(field, "field_type"));
                }
            }
            tables.put(tableName, columns);
        }
        return tables;
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
        if (!sampleDataEnabled) {
            return "";
        }
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

    /**
     * SQL 生成 system 提示词。
     *
     * @param withTraining 是否附带「参考 SQL 样例」约束
     * @param dialect      数据源方言（可空）
     *
     * <p>§2.1：助手名（{@code chat.sqlbot_name}）与数据量策略（{@code chat.limit_rows}）来自 sys_arg，
     * 与 SQLBot {@code apps/chat/task/llm.py:246-264} 的插值/二选一语义一致：
     * 开启时用 query_limit（强制最外层 1000 行、禁止在子查询/CTE 内加限制），
     * 关闭时用 no_query_limit（默认不加限制，用户明确指定时才加）。</p>
     */
    /**
     * 多轮 ack 结构的 system 身份消息（SQLBot init_messages 的 SystemPromptMessage(content=system)）。
     */
    private String sqlSystemIdentity(DatasourceType dialect) {
        String name = chatArg("chat.sqlbot_name", "SQLBot");
        boolean rowLimit = Boolean.parseBoolean(chatArg("chat.limit_rows", "true"));
        if (promptTemplates != null) {
            try {
                String ported = promptTemplates.sqlSystemIdentity(dialect, rowLimit, name);
                if (ported != null && !ported.trim().isEmpty()) {
                    return ported;
                }
            } catch (RuntimeException e) {
                log.info("提示词模板读取失败，退化为内置提示词: {}", e.getMessage());
            }
        }
        return "你是一个严谨的数据库 SQL 专家，服务于一个中文问数（NL2SQL）系统" + name + "。";
    }

    /**
     * 多轮 ack 结构的 rules 消息（SQLBot init_messages 的 HumanPromptMessage(content=rules)）。
     */
    private String sqlSystemRules(DatasourceType dialect) {
        String name = chatArg("chat.sqlbot_name", "SQLBot");
        boolean rowLimit = Boolean.parseBoolean(chatArg("chat.limit_rows", "true"));
        if (promptTemplates != null) {
            try {
                String ported = promptTemplates.sqlSystemRules(dialect, rowLimit, name);
                if (ported != null && !ported.trim().isEmpty()) {
                    return ported;
                }
            } catch (RuntimeException e) {
                log.info("提示词模板读取失败，退化为内置提示词: {}", e.getMessage());
            }
        }
        return fallbackRules(name, rowLimit, dialect);
    }

    /** 内置精简 rules（模板缺失/读取失败时的降级）。 */
    private String fallbackRules(String name, boolean rowLimit, DatasourceType dialect) {
        StringBuilder sb = new StringBuilder();
        sb.append("规则：\n")
                .append("1. 只能使用用户提供的表结构中的表与字段；\n")
                .append("2. 只生成一条只读 SELECT 语句，不要写 INSERT/UPDATE/DELETE/DDL；\n")
                .append("3. 结果用 ```sql 代码块包裹，不要解释；\n")
                .append("4. 需要聚合/排序/分页时合理处理。\n");
        if (rowLimit) {
            sb.append("5. 【数据量限制·强制】最外层查询必须包含行数限制，默认 1000 条"
                            + "（用户明确指定数量时按其数量；用户说「全部数据」时仍用 1000 条）；"
                            + "限制只能加在最外层，禁止出现在子查询、派生表、CTE 内部，"
                            + "聚合/统计/排序必须基于完整数据；「每组前 N」用窗口函数实现，不视为违反。\n")
                    .append("6. 生成后必须自检：行数限制存在且只在最外层，否则重新生成。\n");
        } else {
            sb.append("5. 默认不限制数据量，返回全部数据；仅在用户明确指定「前 N 条」时才加限制，"
                            + "且限制只能加在最外层查询；禁止在子查询、派生表、CTE 内部添加行数限制。\n");
        }
        if (dialect != null) {
            // 验收 A1：注入方言特定的标识符引用与分页约定，避免模型默认按 MySQL 生成错误语法
            sb.append(dialect.sqlDialectHint()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 合并版 system 提示词（兼容 buildSqlFixMessages / chart 等单条 system 调用方）。
     * 多轮 ack 主链路请直接用 {@link #sqlSystemIdentity} + {@link #sqlSystemRules}。
     */
    private String sqlSystemPrompt(boolean withTraining, DatasourceType dialect) {
        String base = sqlSystemIdentity(dialect) + "\n\n" + sqlSystemRules(dialect);
        if (withTraining) {
            base = base + "\n\n已提供 SQL 样例作为参考，优先对齐其风格。";
        }
        return base;
    }

    /** §2.1：读取 chat 组运行参数（sys_arg）；未注入 Repository / 未配置 / 值为空时返回默认值。 */
    private String chatArg(String pkey, String def) {
        if (sysArgRepository == null) {
            return def;
        }
        try {
            Optional<Map<String, Object>> row = sysArgRepository.findByPkey(pkey);
            if (!row.isPresent()) {
                return def;
            }
            String value = Rows.str(row.get(), "pval");
            return value == null || value.trim().isEmpty() ? def : value.trim();
        } catch (Exception e) {
            log.info("读取 sys_arg {} 失败（用默认值 {}）: {}", pkey, def, e.getMessage());
            return def;
        }
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

    /**
     * 图表生成 system 提示词（A9：优先使用移植的 SQLBot chart 模板；
     * 模板不可用时退化为内置精简说明）。
     */
    private String chartSystemPrompt() {
        if (promptTemplates != null) {
            try {
                String ported = promptTemplates.chartSystemPrompt(chatArg("chat.sqlbot_name", "SQLBot"));
                if (ported != null && !ported.trim().isEmpty()) {
                    return ported;
                }
            } catch (RuntimeException e) {
                log.info("图表提示词模板读取失败，退化为内置提示词: {}", e.getMessage());
            }
        }
        return "你是数据可视化专家。根据查询结果输出一个 G2 图表配置 JSON，字段说明：\n"
                + "type: column(柱状)/bar(条形)/line(折线)/pie(饼图)/area(面积)/table(表格)；\n"
                + "title: 标题字符串；\n"
                + "columns: [{name:'列名', value:'列名'}]（查询结果列）；\n"
                + "axis: {x:{name,value}, y:{name,value} 或 [{name,value}], series?:{name,value}}。\n"
                + "只输出 JSON，不要 Markdown 代码块。";
    }


    private String analysisSystemPrompt() {
        if (promptTemplates != null) {
            try {
                String ported = promptTemplates.analysisSystemPrompt(
                        chatArg("chat.sqlbot_name", "SQLBot"), chatArg("chat.lang", "zh-CN"), "");
                if (ported != null && !ported.trim().isEmpty()) {
                    return ported;
                }
            } catch (RuntimeException e) {
                log.info("分析提示词模板读取失败，退化为内置提示词: {}", e.getMessage());
            }
        }
        return "你是数据分析专家。基于查询结果与图表，输出简洁的分析结论（使用中文 Markdown，可含要点列表）。";
    }

    private String predictSystemPrompt() {
        if (promptTemplates != null) {
            try {
                String ported = promptTemplates.predictSystemPrompt(
                        chatArg("chat.sqlbot_name", "SQLBot"), chatArg("chat.lang", "zh-CN"), "");
                if (ported != null && !ported.trim().isEmpty()) {
                    return ported;
                }
            } catch (RuntimeException e) {
                log.info("预测提示词模板读取失败，退化为内置提示词: {}", e.getMessage());
            }
        }
        return "你是数据预测专家。基于历史数据输出合理的趋势预测："
                + "先给出中文结论，再输出一个 JSON 数组作为预测数据（字段与查询结果一致）。";
    }

    private String recommendSystemPrompt() {
        if (promptTemplates != null) {
            try {
                String ported = promptTemplates.guessSystemPrompt(
                        chatArg("chat.sqlbot_name", "SQLBot"), chatArg("chat.lang", "zh-CN"));
                if (ported != null && !ported.trim().isEmpty()) {
                    return ported;
                }
            } catch (RuntimeException e) {
                log.info("推荐问题提示词模板读取失败，退化为内置提示词: {}", e.getMessage());
            }
        }
        return "你是问答助手。基于用户原问题（及可选的图表类型与历史提问），生成若干相关的、"
                + "可继续追问的自然语言问题，尽量结合可视化分析角度；"
                + "只输出一个 JSON 数组，不要编号、不要解释。";
    }
}
