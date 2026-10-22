package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.repository.RecommendedQuestionRepository;
import com.xiongx9527.askdata.chat.service.ChatModelClient.FrameWriter;
import com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage;
import com.xiongx9527.askdata.chat.service.ChatModelClient.TokenHandler;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.engine.SqlSafety;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.embedded.DynamicDatasource;
import com.xiongx9527.askdata.embedded.DynamicDatasourceResolver;
import com.xiongx9527.askdata.permission.PermissionEnforcer;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 问数主链路（对齐 SQLBot {@code apps/chat/task/llm.py} 的
 * {@code run_task / run_analysis_or_predict_task / run_recommend_questions_task}）：
 * 默认模型选定 → 数据源 RAG 选库 → schema 提示词 → LLM 生成 SQL → 只读 SELECT 校验与
 * 语义修复重试 → 执行 → 表格数据 → 图表生成 → 推荐问题。
 *
 * <p><b>流式协议：逐字兼容 SQLBot 前端</b>（{@code views/chat/answer/ChartAnswer.vue} 等
 * 的 {@code data:{json}\n\n} + {@code switch (data.type)}）：
 * id / regenerate_record_id / question / info / brief / model / datasource / error /
 * sql-result / sql / sql-data / chart-result / chart / analysis-result / analysis_finish /
 * predict-result / predict-success / predict-failed / predict_finish / recommended_question /
 * finish。分析/预测同时按 spec §2.3 的命名补发 analysis / analysis_thinking / predict /
 * predict_content / predict_data 帧（前端按自身 switch 忽略未知类型，互不影响）。</p>
 *
 * <p><b>离线降级</b>：模型未配置/不可达时，链路发出 {@code error} 帧并正常 {@code finish}，
 * 不抛异常、不影响应用启动。</p>
 */
@Service
public class ChatQuestionService {

    private static final Logger log = LoggerFactory.getLogger(ChatQuestionService.class);

    /** 模型单次生成的最大字符数（防止异常模型刷爆记录）。 */
    private static final int MAX_GENERATED_TEXT = 32000;

    /** 图表提示词携带的结果行数上限。 */
    private static final int CHART_SAMPLE_ROWS = 20;

    /** 推荐问题数量（对齐 SQLBot 默认 articles_number）。 */
    private static final int DEFAULT_ARTICLES_NUMBER = 4;

    private static final String OPERATE_GENERATE_SQL = "generate_sql";

    private static final String OPERATE_EXECUTE_SQL = "execute_sql";

    private static final String OPERATE_GENERATE_CHART = "generate_chart";

    private static final String OPERATE_ANALYSIS = "analysis";

    private static final String OPERATE_PREDICT = "predict";

    private static final String OPERATE_RECOMMEND_QUESTIONS = "recommend_questions";

    /** A2 知识增强链路操作（对齐 SQLBot OperationEnum：EXTRACT_KEYWORDS/FILTER_TERMS/FILTER_SQL_EXAMPLE/FILTER_CUSTOM_PROMPT）。 */
    private static final String OPERATE_EXTRACT_KEYWORDS = "extract_keywords";

    private static final String OPERATE_FILTER_TERMS = "filter_terms";

    private static final String OPERATE_FILTER_SQL_EXAMPLE = "filter_sql_example";

    private static final String OPERATE_FILTER_CUSTOM_PROMPT = "filter_custom_prompt";

    /**
     * {@code /regenerate} 后可跟记录 id；命令必须独占整个提问
     * （对齐 SQLBot {@code parse_quick_command}：命令不在串尾时按普通提问处理的语义）。
     */
    private static final java.util.regex.Pattern REGENERATE_COMMAND =
            java.util.regex.Pattern.compile("^/regenerate(?:\\s+(\\d+))?$");

    /** 重新生成链上溯的最大深度（防环）。 */
    private static final int MAX_REGENERATE_DEPTH = 8;

    private final ChatRepository chatRepository;

    private final RecommendedQuestionRepository recommendedQuestionRepository;

    private final DatasourceRepository datasourceRepository;

    private final DatasourceService datasourceService;

    private final CurrentUserService currentUserService;

    private final SchemaContextBuilder schemaContextBuilder;

    private final ChatModelClient modelClient;

    private final ChatModelRouter modelRouter;

    private final ChatChartBuilder chartBuilder;

    private final ChatKnowledgeService chatKnowledgeService;

    private final ObjectMapper objectMapper;

    private final PermissionEnforcer permissionEnforcer;

    private final DynamicDatasourceResolver dynamicDatasourceResolver;

    public ChatQuestionService(ChatRepository chatRepository,
                               RecommendedQuestionRepository recommendedQuestionRepository,
                               DatasourceRepository datasourceRepository, DatasourceService datasourceService,
                               CurrentUserService currentUserService, SchemaContextBuilder schemaContextBuilder,
                               ChatModelClient modelClient, ChatModelRouter modelRouter,
                               ChatChartBuilder chartBuilder, ObjectMapper objectMapper,
                               PermissionEnforcer permissionEnforcer, ChatKnowledgeService chatKnowledgeService,
                               DynamicDatasourceResolver dynamicDatasourceResolver) {
        this.chatRepository = chatRepository;
        this.recommendedQuestionRepository = recommendedQuestionRepository;
        this.datasourceRepository = datasourceRepository;
        this.datasourceService = datasourceService;
        this.currentUserService = currentUserService;
        this.schemaContextBuilder = schemaContextBuilder;
        this.modelClient = modelClient;
        this.modelRouter = modelRouter;
        this.chartBuilder = chartBuilder;
        this.objectMapper = objectMapper;
        this.permissionEnforcer = permissionEnforcer;
        this.chatKnowledgeService = chatKnowledgeService;
        this.dynamicDatasourceResolver = dynamicDatasourceResolver;
    }

    // ==================================================================
    // POST /chat/question
    // ==================================================================

    /**
     * 提问主链路（流式）。
     *
     * @param body     {@code {question, chat_id, regenerate_record_id?}}
     * @param response 原始响应（写 {@code text/event-stream}）
     */
    public void streamQuestion(Map<String, Object> body, HttpServletResponse response) throws IOException {
        Map<String, Object> user = currentUserService.currentUser();
        long uid = Rows.lng(user, "id", 0L);
        long oid = currentUserService.oidOf(user);
        long chatId = Rows.lng(body, "chat_id", 0L);
        String question = Rows.str(body, "question");
        long started = System.currentTimeMillis();

        Map<String, Object> chat = chatRepository.findChat(chatId)
                .orElseThrow(() -> new BadRequestException("Chat with id " + chatId + " not found"));
        requireOwner(chat, user, "Chat");

        // 快捷命令解析（对齐 SQLBot common/utils/command_utils.py + views/chat/index.vue 的
        // askAgain：前端把「/regenerate <record_id>」作为 question 文本发送，不传 id 参数）
        String rawQuestion = question == null ? "" : question;
        Long regenerateRecordId = Rows.lngOrNull(body, "regenerate_record_id");
        if (isRegenerateCommand(rawQuestion)) {
            Long target = extractCommandRecordId(rawQuestion);
            if (target == null) {
                target = chatRepository.lastUsableRecordId(chatId).orElse(null);
            }
            if (target == null) {
                throw new BadRequestException("You have not ask any question");
            }
            regenerateRecordId = target;
            question = findBaseQuestion(target);
        }

        try (FrameWriter writer = new FrameWriter(response, objectMapper)) {
            AtomicLong totalTokens = new AtomicLong(0L);
            AtomicLong currentRecordId = new AtomicLong(0L);
            try {
                Optional<ModelRef> model = modelRouter.defaultChatModel();
                if (!model.isPresent() || !model.get().usable()) {
                    emitError(writer, "默认大模型尚未配置（请到系统-AI 模型设置默认模型）");
                    return;
                }
                ModelRef aiModel = model.get();

                DynamicDatasource dynamic = resolveDynamicDatasource(body);
                Long dsId;
                Map<String, Object> ds = null;
                if (dynamic != null) {
                    // A8：高级应用按请求从宿主拉取动态数据源（不落库，dsId 为空）
                    dsId = null;
                } else {
                    dsId = resolveDatasource(chat, oid, question, writer);
                    if (dsId == null) {
                        emitError(writer, "当前工作空间没有可用的数据源");
                        return;
                    }
                    ds = datasourceRepository.findById(dsId).orElse(null);
                }
                String dsType = dynamic != null ? dynamic.typeName() : dsTypeOf(ds);
                String dsTypeName = dynamic != null ? dynamic.getType().getDbName()
                        : (ds == null ? null : Rows.str(ds, "type_name"));
                String dsName = dynamic != null ? dynamic.getName()
                        : (ds == null ? null : Rows.str(ds, "name"));
                emit(writer, Rows.of("type", "model", "id", aiModel.id, "name", aiModel.name));
                emit(writer, Rows.of("type", "datasource", "id", dsId,
                        "datasource_name", dsName,
                        "engine_type", dsTypeName));

                // first_chat 标记来自请求（ChatCreator 的快捷提问记录才置位，前端据此渲染首问引导）；
                // 会话摘要则在会话尚无记录时生成（对齐 SQLBot change_title 的首问语义）
                boolean isFirstRecord = chatRepository.listRecordsByChat(chatId).isEmpty();
                boolean firstChat = Rows.bool(body, "first_chat", false);
                long recordId = chatRepository.insertRecord(chatId, question, uid, oid, dsId, firstChat,
                        regenerateRecordId);
                chatRepository.touchChat(chatId);
                if (dsId != null && Rows.lng(chat, "datasource", 0L) <= 0L) {
                    chatRepository.setChatDatasource(chatId, dsId, dsTypeName, dsType);
                }
                currentRecordId.set(recordId);

                emit(writer, Rows.of("type", "id", "id", recordId));
                if (regenerateRecordId != null) {
                    emit(writer, Rows.of("type", "regenerate_record_id", "regenerate_record_id", regenerateRecordId));
                }
                emit(writer, Rows.of("type", "question", "question", question));
                emit(writer, Rows.of("type", "info", "msg", "sql generating"));

                // -- A2 知识增强：关键词提取 → 术语过滤 → SQL 示例/自定义提示词过滤
                // （未配置 embedding 模型时术语召回退化为词法匹配，不报错）
                long knowledgeStart = System.currentTimeMillis();
                // A9：高级应用知识范围隔离——请求携带的 assistant_id 作为知识作用域
                // （仅影响术语/训练样例/自定义提示词的召回范围，不会扩大权限）
                Long advancedAppId = Rows.lngOrNull(body, "assistant_id");
                ChatKnowledgeService.KnowledgeContext knowledge =
                        chatKnowledgeService.prepare(question, oid, dsId, advancedAppId);
                chatRepository.saveKeywords(recordId, knowledge.getExtractedKeywords(),
                        knowledge.getExpandedKeywords());
                logOperation(recordId, oid, uid, OPERATE_EXTRACT_KEYWORDS, knowledgeStart, 0L, false,
                        Rows.of("keywords", knowledge.getExtractedKeywords(),
                                "expanded_keywords", knowledge.getExpandedKeywords()));
                logOperation(recordId, oid, uid, OPERATE_FILTER_TERMS, knowledgeStart, 0L, false,
                        Rows.of("terms", knowledge.getTermGroups()));
                logOperation(recordId, oid, uid, OPERATE_FILTER_SQL_EXAMPLE, knowledgeStart, 0L, false,
                        Rows.of("examples", knowledge.getSqlExamples()));
                logOperation(recordId, oid, uid, OPERATE_FILTER_CUSTOM_PROMPT, knowledgeStart, 0L, false,
                        Rows.of("prompts", knowledge.getCustomPrompts()));

                // -- 生成 SQL（流式） + 只读校验 + 错误回喂修复一轮
                long sqlStart = System.currentTimeMillis();
                String customPromptText = String.join("\n", knowledge.getCustomPrompts());
                // 验收 A3：追问上下文（前序 Q&A）注入 SQL 生成，follow-up 可引用前文实体
                String historyText = priorContext(chatId, currentRecordId.get());
                String sqlText = generateText(writer, aiModel,
                        dynamic != null
                                ? schemaContextBuilder.buildSqlMessagesForConf(question, dynamic.getType(),
                                        dynamic.getConf(), knowledge.getTerminologyText(), customPromptText, historyText)
                                : schemaContextBuilder.buildSqlMessages(question, dsId, knowledge.getTerminologyText(),
                                        customPromptText, knowledge.getSqlExamples(), historyText),
                        "sql-result", totalTokens);
                String sql = sqlText == null ? null : extractSql(sqlText);
                if (sql == null) {
                    // 一次修复：把「未输出 SQL」的语义错误回喂
                    sqlText = regenerateSql(aiModel, question, dsId, dynamic, sqlText, "模型未输出可执行的 SQL", writer);
                    sql = sqlText == null ? null : extractSql(sqlText);
                }
                String validation = sql == null ? "模型未输出可执行的 SQL" : validateSql(sql, dsType, dsId);
                if (validation != null) {
                    String fixed = regenerateSql(aiModel, question, dsId, dynamic, sql == null ? "" : sql, validation, writer);
                    String fixedSql = fixed == null ? null : extractSql(fixed);
                    if (fixedSql != null && validateSql(fixedSql, dsType, dsId) == null) {
                        sqlText = fixed;
                        sql = fixedSql;
                        validation = null;
                    }
                }
                logOperation(currentRecordId.get(), oid, uid, OPERATE_GENERATE_SQL, sqlStart,
                        totalTokens.get(), validation != null, null);
                if (validation != null) {
                    emitError(writer, validation);
                    return;
                }
                // -- 行/列权限强制（A3）：普通用户注入行过滤、隐藏无权限列；管理员/白名单跳过
                PermissionEnforcer.EnforcedQuery enforced = (dynamic != null || dsId == null || permissionEnforcer == null)
                        ? null : permissionEnforcer.enforce(oid, uid, dsId, sql);
                if (enforced != null) {
                    sql = enforced.getSql();
                }
                chatRepository.saveSql(recordId, sql, sqlText);
                emit(writer, Rows.of("type", "sql", "content", formatSql(sql)));

                // -- 首问时生成会话摘要（对齐 SQLBot change_title 语义）
                if (isFirstRecord) {
                    String brief = briefOf(question);
                    chatRepository.renameChat(chatId, brief);
                    emit(writer, Rows.of("type", "brief", "brief", brief));
                }

                // -- 执行 SQL → 表格数据
                emit(writer, Rows.of("type", "info", "msg", "sql executing"));
                long execStart = System.currentTimeMillis();
                Map<String, Object> result = dynamic != null
                        ? datasourceService.execSqlTransient(dynamic.getType(), dynamic.getConf(), sql)
                        : datasourceService.execSql(dsId, Rows.of("sql", sql));
                if (enforced != null) {
                    result = permissionEnforcer.applyHiddenColumns(result, enforced.getHiddenColumns());
                }
                chatRepository.saveSqlData(recordId, chatRepository.writeJson(
                        Rows.of("fields", result.get("fields"), "data", result.get("data"))));
                logOperation(recordId, oid, uid, OPERATE_EXECUTE_SQL, execStart, 0L, false,
                        Rows.of("sql", sql, "count", sizeOf(result.get("data"))));
                emit(writer, Rows.of("type", "info", "msg", "sql executed"));
                emit(writer, Rows.of("type", "sql-data", "content", "execute-success"));

                // -- 图表生成
                emit(writer, Rows.of("type", "info", "msg", "chart generating"));
                long chartStart = System.currentTimeMillis();
                String chartJson = generateChart(writer, aiModel, question, result, totalTokens);
                logOperation(recordId, oid, uid, OPERATE_GENERATE_CHART, chartStart, totalTokens.get(), false, null);
                chatRepository.saveChart(recordId, chartJson, null);
                emit(writer, Rows.of("type", "info", "msg", "chart generated"));
                emit(writer, Rows.of("type", "chart", "content", chartJson));

                // -- 推荐问题（仅在真的产出时才发帧：前端空数组会渲染为「获取失败」）
                if (dynamic != null || recommendedEnabled(ds)) {
                    long recommendStart = System.currentTimeMillis();
                    List<String> recommended = recommendQuestions(aiModel, question, DEFAULT_ARTICLES_NUMBER);
                    logOperation(recordId, oid, uid, OPERATE_RECOMMEND_QUESTIONS, recommendStart, 0L, false, null);
                    if (!recommended.isEmpty()) {
                        String json = chatRepository.writeJson(recommended);
                        chatRepository.saveRecommendedQuestion(recordId, json);
                        recommendedQuestionRepository.replaceByChat(chatId, recommended);
                        emit(writer, Rows.of("type", "recommended_question", "content", json));
                    }
                }

                chatRepository.finishRecord(recordId, System.currentTimeMillis() - started, totalTokens.get());
                emit(writer, Rows.of("type", "finish"));
            } catch (Exception e) {
                log.warn("提问链路异常: {}", e.getMessage());
                emitError(writer, e.getMessage() == null ? e.toString() : e.getMessage());
                if (currentRecordId.get() > 0L) {
                    chatRepository.saveError(currentRecordId.get(), String.valueOf(e.getMessage()));
                }
            }
        }
    }

    // ==================================================================
    // POST /chat/record/{rid}/{analysis|predict}
    // ==================================================================

    /**
     * 记录级分析/预测（流式）。
     *
     * <p>对齐 SQLBot {@code analysis_or_predict}：基础记录必须先生成过图表，
     * 否则报 {@code has not generated chart}。</p>
     */
    public void streamAnalysisOrPredict(long baseRecordId, String actionType, HttpServletResponse response)
            throws IOException {
        if (!"analysis".equals(actionType) && !"predict".equals(actionType)) {
            throw new BadRequestException("Type " + actionType + " Not Found");
        }
        Map<String, Object> user = currentUserService.currentUser();
        long uid = Rows.lng(user, "id", 0L);
        long oid = currentUserService.oidOf(user);
        Map<String, Object> base = chatRepository.findRecord(baseRecordId)
                .orElseThrow(() -> new BadRequestException("Chat record with id " + baseRecordId + " not found"));
        Map<String, Object> chat = chatRepository.findChat(Rows.lng(base, "chat_id", 0L))
                .orElseThrow(() -> new BadRequestException("Chat record with id " + baseRecordId + " not found"));
        requireOwner(chat, user, "ChatRecord");
        long started = System.currentTimeMillis();

        try (FrameWriter writer = new FrameWriter(response, objectMapper)) {
            try {
                Optional<ModelRef> model = modelRouter.defaultChatModel();
                if (!model.isPresent() || !model.get().usable()) {
                    emitError(writer, "默认大模型尚未配置（请到系统-AI 模型设置默认模型）");
                    return;
                }
                ModelRef aiModel = model.get();
                if (Rows.str(base, "chart") == null || Rows.str(base, "chart").isEmpty()) {
                    emitError(writer, "Chat record with id " + baseRecordId
                            + " has not generated chart, do not support to analyze it");
                    return;
                }
                Long dsId = Rows.lngOrNull(base, "datasource");
                long recordId = chatRepository.insertSubRecord(Rows.lng(base, "chat_id", 0L),
                        Rows.str(base, "question"), uid, oid, dsId, actionType, baseRecordId);
                emit(writer, Rows.of("type", "id", "id", recordId));

                String question = Rows.str(base, "question");
                String dataJson = chatRepository.writeJson(
                        Rows.of("fields", fieldsOf(base), "data", dataOf(base)));
                AtomicLong totalTokens = new AtomicLong(0L);
                boolean analysis = "analysis".equals(actionType);
                String full = generateText(writer, aiModel,
                        analysis ? schemaContextBuilder.buildAnalysisMessages(question, Rows.str(base, "chart"),
                                dataJson)
                                : schemaContextBuilder.buildPredictMessages(question, Rows.str(base, "chart"),
                                dataJson),
                        analysis ? "analysis-result" : "predict-result", totalTokens);

                if (analysis) {
                    chatRepository.saveAnalysis(baseRecordId, full, "");
                    // spec §2.3 命名（analysis / analysis_thinking）
                    emit(writer, Rows.of("type", "analysis", "content", full));
                    emit(writer, Rows.of("type", "analysis_thinking", "content", ""));
                    emit(writer, Rows.of("type", "info", "msg", "analysis generated"));
                    emit(writer, Rows.of("type", "analysis_finish"));
                } else {
                    chatRepository.savePredictContent(baseRecordId, full, "");
                    List<Map<String, Object>> predictData = extractJsonArray(full);
                    boolean hasData = !predictData.isEmpty();
                    if (hasData) {
                        chatRepository.savePredictData(baseRecordId, chatRepository.writeJson(predictData));
                    }
                    emit(writer, Rows.of("type", "predict", "content", full));
                    emit(writer, Rows.of("type", "predict_content", "content", full));
                    emit(writer, Rows.of("type", "info", "msg", "predict generated"));
                    if (hasData) {
                        emit(writer, Rows.of("type", "predict_data", "content",
                                chatRepository.writeJson(predictData)));
                        emit(writer, Rows.of("type", "predict-success"));
                    } else {
                        emit(writer, Rows.of("type", "predict-failed"));
                    }
                    emit(writer, Rows.of("type", "predict_finish"));
                }
                long duration = System.currentTimeMillis() - started;
                logOperation(baseRecordId, oid, uid, analysis ? OPERATE_ANALYSIS : OPERATE_PREDICT,
                        started, totalTokens.get(), false, Rows.of("record_id", recordId));
                chatRepository.finishRecord(recordId, duration, totalTokens.get());
                chatRepository.finishRecord(baseRecordId, duration, totalTokens.get());
            } catch (Exception e) {
                log.warn("分析/预测链路异常: {}", e.getMessage());
                emitError(writer, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    // ==================================================================
    // POST /chat/recommend_questions/{rid}
    // ==================================================================

    /** 推荐问题生成（流式）。 */
    public void streamRecommendQuestions(long recordId, int articlesNumber, HttpServletResponse response)
            throws IOException {
        Map<String, Object> user = currentUserService.currentUser();
        long oid = currentUserService.oidOf(user);
        Map<String, Object> record = chatRepository.findRecord(recordId).orElse(null);
        try (FrameWriter writer = new FrameWriter(response, objectMapper)) {
            if (record == null) {
                emit(writer, Rows.of("type", "recommended_question", "content", "[]"));
                return;
            }
            Map<String, Object> chat = chatRepository.findChat(Rows.lng(record, "chat_id", 0L)).orElse(null);
            if (chat == null || (!currentUserService.isAdmin(user) && Rows.lng(chat, "oid", 0L) != oid)) {
                emit(writer, Rows.of("type", "recommended_question", "content", "[]"));
                return;
            }
            Optional<ModelRef> model = modelRouter.defaultChatModel();
            if (!model.isPresent() || !model.get().usable()) {
                emit(writer, Rows.of("type", "recommended_question", "content", "[]"));
                return;
            }
            long start = System.currentTimeMillis();
        List<String> questions = recommendQuestions(model.get(), Rows.str(record, "question"), articlesNumber);
        logOperation(recordId, oid, Rows.lng(user, "id", 0L), OPERATE_RECOMMEND_QUESTIONS, start, 0L, false, null);
            String json = chatRepository.writeJson(questions);
            if (!questions.isEmpty()) {
                chatRepository.saveRecommendedQuestion(recordId, json);
                recommendedQuestionRepository.replaceByChat(Rows.lng(record, "chat_id", 0L), questions);
            }
            emit(writer, Rows.of("type", "recommended_question", "content", json));
        }
    }

    // ==================================================================
    // 内部：SQL 生成 / 校验 / 执行
    // ==================================================================

    // ------------------------------------------------------------------
    // 快捷命令 /regenerate（对齐 SQLBot parse_quick_command + find_base_question）
    // ------------------------------------------------------------------

    /** 是否为「/regenerate [<record_id>]」命令（前端 askAgain 的发送形态）。 */
    static boolean isRegenerateCommand(String question) {
        if (question == null) {
            return false;
        }
        String text = question.trim();
        return REGENERATE_COMMAND.matcher(text).find();
    }

    /** 提取命令后的记录 id；未带 id 时返回 null（由调用方取最近一条记录）。 */
    static Long extractCommandRecordId(String question) {
        java.util.regex.Matcher matcher = REGENERATE_COMMAND.matcher(question.trim());
        if (!matcher.find() || matcher.group(1) == null) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 沿 {@code regenerate_record_id} 链上溯到最初的问题文本
     * （对齐 SQLBot {@code find_base_question}：重新生成时用原始提问，不叠加历史答案）。
     */
    private String findBaseQuestion(long recordId) {
        long current = recordId;
        String baseQuestion = null;
        for (int depth = 0; depth < MAX_REGENERATE_DEPTH; depth++) {
            Map<String, Object> record = chatRepository.findRecord(current).orElse(null);
            if (record == null) {
                return baseQuestion == null ? "" : baseQuestion;
            }
            baseQuestion = Rows.str(record, "question");
            Long parent = Rows.lngOrNull(record, "regenerate_record_id");
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return baseQuestion == null ? "" : baseQuestion;
    }

    /**
     * 验收 A3：取前序问答（最近若干条）作为追问上下文，使 follow-up 能引用前文出现的表/列/实体。
     */
    private String priorContext(long chatId, long excludeRecordId) {
        List<Map<String, Object>> records = chatRepository.listRecordsByChat(chatId);
        if (records == null || records.isEmpty()) {
            return "";
        }
        List<String> blocks = new ArrayList<String>();
        for (Map<String, Object> r : records) {
            if (Rows.lng(r, "id", 0L) == excludeRecordId) {
                continue;
            }
            String q = Rows.str(r, "question");
            if (q.isEmpty()) {
                continue;
            }
            String sql = Rows.str(r, "sql");
            StringBuilder b = new StringBuilder("Q: ").append(q);
            if (!sql.isEmpty()) {
                b.append("\nA(SQL): ").append(sql);
            }
            blocks.add(b.toString());
        }
        if (blocks.size() > 5) {
            blocks = blocks.subList(blocks.size() - 5, blocks.size());
        }
        return String.join("\n", blocks);
    }

    /** 数据源解析：会话已绑定则直接用，否则 RAG 选库（并回写会话）。 */
    private Long resolveDatasource(Map<String, Object> chat, long oid, String question, FrameWriter writer)
            throws IOException {
        long bound = Rows.lng(chat, "datasource", 0L);
        if (bound > 0L) {
            return bound;
        }
        Long dsId = schemaContextBuilder.selectDatasource(oid, question);
        if (dsId != null) {
            emit(writer, Rows.of("type", "datasource-result", "content", "selected datasource " + dsId,
                    "reasoning_content", ""));
        }
        return dsId;
    }

    /**
     * 解析请求中的动态数据源（A8）：仅高级应用（assistant type 1/3）允许按请求从宿主拉取，
     * 不写入 core_datasource；未携带时返回 null，走既有静态数据源链路。
     */
    private DynamicDatasource resolveDynamicDatasource(Map<String, Object> body) {
        Object raw = body.get("dynamic_datasource");
        if (raw == null) {
            return null;
        }
        Map<String, Object> hostConfig;
        if (raw instanceof Map) {
            hostConfig = objectMapper.convertValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } else {
            try {
                hostConfig = objectMapper.readValue(String.valueOf(raw),
                        new TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                throw new BadRequestException("Invalid dynamic datasource: " + e.getMessage());
            }
        }
        int assistantType = Rows.intg(body, "assistant_type", DynamicDatasourceResolver.TYPE_ADVANCED_APP);
        return dynamicDatasourceResolver.resolve(assistantType, hostConfig);
    }

    /** 错误回喂修复一轮（不流式重发，仅用于纠错），失败返回 null。 */
    private String regenerateSql(ModelRef aiModel, String question, Long dsId, DynamicDatasource dynamic,
                                 String brokenSql, String error, FrameWriter writer) {
        try {
            List<ModelMessage> fixMessages = dynamic != null
                    ? schemaContextBuilder.buildSqlFixMessagesForConf(question, dynamic.getType(),
                            dynamic.getConf(), brokenSql == null ? "" : brokenSql, error)
                    : schemaContextBuilder.buildSqlFixMessages(question, dsId,
                            brokenSql == null ? "" : brokenSql, error);
            String text = modelClient.generate(aiModel.apiDomain, aiModel.apiKey, aiModel.baseModel,
                    fixMessages);
            if (text == null) {
                return null;
            }
            emit(writer, Rows.of("type", "sql-result", "content", "", "reasoning_content", text));
            return text;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 只读 SELECT 校验：先过 {@link SqlSafety}（与 {@code /datasource/execSql} 同一安全子集），
     * 再校验涉及的表都在已勾选白名单内。
     *
     * @return 错误信息；通过时返回 null
     */
    public String validateSql(String sql, String dsType, Long dsId) {
        String safety = validateSqlSafety(sql, dsType);
        if (safety != null) {
            return safety;
        }
        if (dsId == null) {
            return null;
        }
        return validateTables(sql, schemaContextBuilder.checkedTableNames(dsId));
    }

    /** 只读安全性校验（SELECT/WITH 白名单 + 危险模式/函数），通过返回 null。 */
    static String validateSqlSafety(String sql, String dsType) {
        try {
            SqlSafety.checkRead(sql, dsType);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /** 表名白名单校验（防幻觉到未勾选的表），通过返回 null。 */
    static String validateTables(String sql, List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return null;
        }
        List<String> lowerAllowed = new ArrayList<>();
        for (String table : allowed) {
            lowerAllowed.add(table.toLowerCase(Locale.ROOT));
        }
        for (String table : usedTables(sql)) {
            if (!lowerAllowed.contains(table)) {
                return "SQL 使用了未授权的表: " + table;
            }
        }
        return null;
    }

    /** 从 SQL 中提取 FROM/JOIN 后的表名（小写）。 */
    static List<String> usedTables(String sql) {
        List<String> tables = new ArrayList<>();
        String text = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String[] tokens = text.split(" ");
        for (int i = 0; i < tokens.length - 1; i++) {
            if ("from".equals(tokens[i]) || "join".equals(tokens[i])) {
                String name = tokens[i + 1].replaceAll("[^\\p{L}\\p{N}_]", "");
                if (!name.isEmpty()) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    /** 从模型输出中抽取 ```sql 代码块内的 SQL；无代码块时取首个 SELECT 起的内容。 */
    public static String extractSql(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int fenceStart = lower.indexOf("```sql");
        if (fenceStart < 0) {
            fenceStart = lower.indexOf("```");
        }
        if (fenceStart >= 0) {
            int contentStart = text.indexOf('\n', fenceStart);
            int fenceEnd = text.indexOf("```", contentStart < 0 ? fenceStart + 3 : contentStart);
            if (contentStart >= 0 && fenceEnd > contentStart) {
                String sql = text.substring(contentStart + 1, fenceEnd).trim();
                if (!sql.isEmpty()) {
                    return stripSemicolon(sql);
                }
            }
        }
        // WITH 优先于 SELECT（CTE 语句以 WITH 开头，内含 SELECT）
        int with = lower.indexOf("with ");
        if (with >= 0) {
            return stripSemicolon(text.substring(with).trim());
        }
        int select = lower.indexOf("select ");
        if (select >= 0) {
            return stripSemicolon(text.substring(select).trim());
        }
        return null;
    }

    private static String stripSemicolon(String sql) {
        String result = sql.trim();
        if (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        // 只取首条语句（对齐 SQLBot 只执行一条 SQL 的语义）
        int semicolon = result.indexOf(';');
        if (semicolon > 0) {
            result = result.substring(0, semicolon).trim();
        }
        return result;
    }

    /** SQL 排版（对齐 SQLBot {@code sqlparse.format(reindent=True)} 的观感）。 */
    static String formatSql(String sql) {
        String text = sql.replaceAll("\\s+", " ").trim();
        return text
                .replaceAll("(?i)\\s*,\\s*", ", ")
                .replaceAll("(?i)\\s+FROM\\s+", "\nFROM ")
                .replaceAll("(?i)\\s+WHERE\\s+", "\nWHERE ")
                .replaceAll("(?i)\\s+GROUP\\s+BY\\s+", "\nGROUP BY ")
                .replaceAll("(?i)\\s+ORDER\\s+BY\\s+", "\nORDER BY ")
                .replaceAll("(?i)\\s+HAVING\\s+", "\nHAVING ")
                .replaceAll("(?i)\\s+LIMIT\\s+", "\nLIMIT ");
    }

    // ==================================================================
    // 内部：图表 / 推荐 / 文本生成
    // ==================================================================

    /** 图表生成：模型产出优先，失败/非法时回退启发式构造。 */
    private String generateChart(FrameWriter writer, ModelRef aiModel, String question,
                                 Map<String, Object> result, AtomicLong totalTokens) throws IOException {
        String dataJson = chatRepository.writeJson(
                Rows.of("fields", result.get("fields"), "data", sampleRows(result.get("data"))));
        String text = generateText(writer, aiModel, schemaContextBuilder.buildChartMessages(question, dataJson),
                "chart-result", totalTokens);
        Map<String, Object> chart = text == null ? null : chartBuilder.normalize(text);
        if (chart == null) {
            chart = chartBuilder.build(result, question);
        }
        return chatRepository.writeJson(chart);
    }

    /** 推荐问题（非流式；解析为字符串列表，失败返回空列表）。 */
    private List<String> recommendQuestions(ModelRef aiModel, String question, int articlesNumber) {
        String text = modelClient.generate(aiModel.apiDomain, aiModel.apiKey, aiModel.baseModel,
                schemaContextBuilder.buildRecommendMessages(question, articlesNumber));
        return parseStringList(text, objectMapper);
    }

    /** 流式文本生成：逐段发帧，同时累积全文；失败返回 null。 */
    private String generateText(FrameWriter writer, ModelRef aiModel, List<ModelMessage> messages, String frameType,
                                AtomicLong totalTokens) throws IOException {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final AtomicReference<String> error = new AtomicReference<>(null);
        modelClient.streamCompletion(aiModel.apiDomain, aiModel.apiKey, aiModel.baseModel, messages,
                new TokenHandler() {
                    @Override
                    public void onToken(String token, String reasoningToken) {
                        if (token != null) {
                            content.append(token);
                        }
                        if (reasoningToken != null) {
                            reasoning.append(reasoningToken);
                        }
                        try {
                            if (content.length() < MAX_GENERATED_TEXT) {
                                emit(writer, Rows.of("type", frameType, "content",
                                        token == null ? "" : token,
                                        "reasoning_content", reasoningToken == null ? "" : reasoningToken));
                            }
                        } catch (IOException e) {
                            // 客户端断开（AbortController）：静默结束
                            log.info("流式写入中断（客户端已断开）: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onComplete() {
                        // no-op
                    }

                    @Override
                    public void onError(String message) {
                        error.set(message);
                    }

                    @Override
                    public void onUsage(long tokens) {
                        totalTokens.addAndGet(tokens);
                    }
                });
        if (error.get() != null) {
            emit(writer, Rows.of("type", "info", "msg", error.get()));
            return null;
        }
        return content.length() == 0 ? null : content.toString();
    }

    // ==================================================================
    // 内部：辅助
    // ==================================================================

    /** 会话摘要：取问题前 20 字（对齐 SQLBot 无 LLM 摘要时的截断语义）。 */
    static String briefOf(String question) {
        if (question == null || question.trim().isEmpty()) {
            return "新会话";
        }
        String text = question.trim().replaceAll("\\s+", " ");
        return text.length() <= 20 ? text : text.substring(0, 20);
    }

    private boolean recommendedEnabled(Map<String, Object> ds) {
        return ds != null && Rows.intg(ds, "recommended_config", 1) != 0;
    }

    private List<Object> sampleRows(Object data) {
        List<Map<String, Object>> rows = Rows.mapList(data);
        if (rows.size() <= CHART_SAMPLE_ROWS) {
            return new ArrayList<Object>(rows);
        }
        return new ArrayList<Object>(rows.subList(0, CHART_SAMPLE_ROWS));
    }

    private Object fieldsOf(Map<String, Object> record) {
        return parseResult(Rows.str(record, "data")).get("fields");
    }

    private Object dataOf(Map<String, Object> record) {
        return parseResult(Rows.str(record, "data")).get("data");
    }

    private Map<String, Object> parseResult(String json) {
        if (json == null || json.isEmpty()) {
            return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>());
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>());
        }
    }

    /** 从模型输出中解析 JSON 字符串数组（兼容纯数组输出与逐行文本形式）。 */
    static List<String> parseStringList(String text, ObjectMapper mapper) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String trimmed = text.trim();
        String json = trimmed.startsWith("[") ? trimmed : ChatChartBuilder.extractJson(text);
        if (json != null) {
            try {
                List<String> parsed = mapper.readValue(json, new TypeReference<List<String>>() {
                });
                return parsed == null ? Collections.<String>emptyList() : parsed;
            } catch (Exception e) {
                // 落到逐行解析
            }
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String item = line.trim().replaceAll("^[0-9]+[.、)]\\s*", "").replaceAll("^[-*]\\s*", "");
            if (!item.isEmpty() && !item.startsWith("```") && !item.startsWith("[")) {
                lines.add(item);
            }
        }
        return lines;
    }

    /** 从模型输出中解析预测数据数组。 */
    private List<Map<String, Object>> extractJsonArray(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(text.substring(start, end + 1),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            return parsed == null ? Collections.<Map<String, Object>>emptyList() : parsed;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 记录操作日志（token/耗时/错误），支撑 /record/{rid}/log 与 usage。 */
    private void logOperation(long recordId, long oid, long uid, String operate, long startMillis, long totalTokens,
                              boolean error, Map<String, Object> message) {
        if (recordId <= 0L) {
            return;
        }
        try {
            Timestamp start = new Timestamp(startMillis);
            Timestamp finish = new Timestamp(System.currentTimeMillis());
            chatRepository.insertLog(recordId, oid, uid, start, finish, finish.getTime() - start.getTime(),
                    totalTokens, operate, true, error,
                    message == null ? null : chatRepository.writeJson(message));
        } catch (Exception e) {
            log.info("对话日志写入失败（忽略）: {}", e.getMessage());
        }
    }

    private static String dsTypeOf(Map<String, Object> ds) {
        return ds == null ? null : Rows.str(ds, "type");
    }

    private void requireOwner(Map<String, Object> chat, Map<String, Object> user, String label) {
        if (currentUserService.isAdmin(user)) {
            return;
        }
        long id = Rows.lng(chat, "id", 0L);
        if (Rows.lng(chat, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new BadRequestException(label + " with id " + id + " does not belong to current workspace");
        }
        if (Rows.lng(chat, "create_by", 0L) != Rows.lng(user, "id", 0L)) {
            throw new BadRequestException(label + " with id " + id + " not Owned by the current user");
        }
    }

    private int sizeOf(Object data) {
        return data instanceof List ? ((List<?>) data).size() : 0;
    }

    private static void emit(FrameWriter writer, Map<String, Object> frame) throws IOException {
        writer.write(frame);
    }

    private static void emitError(FrameWriter writer, String message) throws IOException {
        emit(writer, Rows.of("type", "error", "content", message));
        emit(writer, Rows.of("type", "finish"));
    }
}
