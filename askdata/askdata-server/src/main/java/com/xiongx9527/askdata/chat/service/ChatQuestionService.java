package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.xiongx9527.askdata.datasource.engine.SqlSemanticValidator;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.chart.server.ChartImageService;
import com.xiongx9527.askdata.embedded.AssistantRepository;
import com.xiongx9527.askdata.embedded.DynamicDatasource;
import com.xiongx9527.askdata.embedded.DynamicDatasourceResolver;
import com.xiongx9527.askdata.permission.DsGrantService;
import com.xiongx9527.askdata.permission.PermissionEnforcer;
import com.xiongx9527.askdata.system.repository.SysArgRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /** SQLBot {@code apps/chat/task/llm.py} 默认数据量阈值。 */
    private static final int SQL_ROW_LIMIT = 1000;

    /** SQL 生成失败时的回喂修复重试上限（覆盖 gpt-oss 偶发不输出可解析 SQL 的方差）。 */
    private static final int MAX_SQL_REPAIR_RETRIES = 2;

    /** 模型单次生成的最大字符数（防止异常模型刷爆记录）。 */
    private static final int MAX_GENERATED_TEXT = 32000;

    /** 图表提示词携带的结果行数上限。 */
    private static final int CHART_SAMPLE_ROWS = 20;

    /** 推荐问题数量（对齐 SQLBot 默认 articles_number）。 */
    private static final int DEFAULT_ARTICLES_NUMBER = 4;

    private static final String OPERATE_GENERATE_SQL = "GENERATE_SQL";
    private static final String ERROR_TYPE_EXEC_SQL = "exec-sql-err";
    private static final String ERROR_TYPE_DB_CONNECTION = "db-connection-err";
    private static final String ERROR_TYPE_PERMISSION = "permission-err";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String OPERATE_EXECUTE_SQL = "EXECUTE_SQL";

    private static final String OPERATE_GENERATE_CHART = "GENERATE_CHART";

    private static final String OPERATE_ANALYSIS = "ANALYSIS";

    private static final String OPERATE_PREDICT = "PREDICT_DATA";

    private static final String OPERATE_RECOMMEND_QUESTIONS = "GENERATE_RECOMMENDED_QUESTIONS";

    /** A2 知识增强链路操作（对齐 SQLBot OperationEnum：EXTRACT_KEYWORDS/FILTER_TERMS/FILTER_SQL_EXAMPLE/FILTER_CUSTOM_PROMPT）。 */
    private static final String OPERATE_EXTRACT_KEYWORDS = "EXTRACT_KEYWORDS";

    private static final String OPERATE_FILTER_TERMS = "FILTER_TERMS";

    private static final String OPERATE_FILTER_SQL_EXAMPLE = "FILTER_SQL_EXAMPLE";

    private static final String OPERATE_FILTER_CUSTOM_PROMPT = "FILTER_CUSTOM_PROMPT";

    // A14：SQLBot OperationEnum 对齐的 5 个新增操作类型（选表/出图/选数据源/动态 SQL/权限加固 SQL）
    private static final String OPERATE_CHOOSE_TABLE = "CHOOSE_TABLE";
    private static final String OPERATE_GENERATE_PICTURE = "GENERATE_PICTURE";
    private static final String OPERATE_CHOOSE_DATASOURCE = "CHOOSE_DATASOURCE";
    private static final String OPERATE_GENERATE_DYNAMIC_SQL = "GENERATE_DYNAMIC_SQL";
    private static final String OPERATE_GENERATE_SQL_WITH_PERMISSIONS = "GENERATE_SQL_WITH_PERMISSIONS";

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

    private final AssistantRepository assistantRepository;

    private final ChartImageService chartImageService;

    private final SysArgRepository sysArgRepository;

    private final DsGrantService dsGrantService;

    public ChatQuestionService(ChatRepository chatRepository,
                               RecommendedQuestionRepository recommendedQuestionRepository,
                               DatasourceRepository datasourceRepository, DatasourceService datasourceService,
                               CurrentUserService currentUserService, SchemaContextBuilder schemaContextBuilder,
                               ChatModelClient modelClient, ChatModelRouter modelRouter,
                               ChatChartBuilder chartBuilder, ObjectMapper objectMapper,
                               PermissionEnforcer permissionEnforcer, ChatKnowledgeService chatKnowledgeService,
                               DynamicDatasourceResolver dynamicDatasourceResolver,
                               AssistantRepository assistantRepository,
                               ChartImageService chartImageService,
                               SysArgRepository sysArgRepository,
                               DsGrantService dsGrantService,
                               com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver) {
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
        this.assistantRepository = assistantRepository;
        this.chartImageService = chartImageService;
        this.sysArgRepository = sysArgRepository;
        this.dsGrantService = dsGrantService;
        this.messageResolver = messageResolver;
    }

    /** A7: i18n 消息解析（错误文案统一走 messageResolver）。 */
    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

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
        try (FrameWriter writer = new FrameWriter(response, objectMapper)) {
            streamQuestion(body, writer);
        }
    }

    /**
     * 流式问数（可注入 {@link FrameWriter}）：HTTP 流式端点与非流式 {@link #ask(Map)}
     * 共用同一管线，避免两套 SQL/权限/落库逻辑分叉。
     */
    public void streamQuestion(Map<String, Object> body, FrameWriter writer) throws IOException {
        Map<String, Object> user = currentUserService.currentUser();
        long uid = Rows.lng(user, "id", 0L);
        long oid = currentUserService.oidOf(user);
        long chatId = Rows.lng(body, "chat_id", 0L);
        String question = Rows.str(body, "question");
        long started = System.currentTimeMillis();
        boolean queryDataOnly = "QUERY_DATA".equalsIgnoreCase(Rows.str(body, "finish_step"));
        boolean returnImage = Rows.bool(body, "return_img", true);

        Map<String, Object> chat = chatRepository.findChat(chatId)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("chat.not.found", chatId)));
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
                throw new BadRequestException(messageResolver.get("chat.no.question"));
            }
            regenerateRecordId = target;
            question = findBaseQuestion(target);
        }

        // A16：空提问在调用 LLM 前拒绝，不产生记录与模型费用
        if (question == null || question.trim().isEmpty()) {
            emitError(writer, messageResolver.get("chat.question.empty"));
            return;
        }

        AtomicLong totalTokens = new AtomicLong(0L);
            AtomicLong currentRecordId = new AtomicLong(0L);
            try {
                // A3/D9：custom_model 在流式与非流式均生效；不可用时明确报错，不回退默认模型
                Long customModelId = Rows.lngOrNull(body, "custom_model");
                Optional<ModelRef> model = customModelId == null
                        ? modelRouter.defaultChatModel() : modelRouter.chatModelById(customModelId);
                if (!model.isPresent() || !model.get().usable()) {
                    emitError(writer, customModelId == null
                            ? messageResolver.get("scheduled.no.default.model")
                            : messageResolver.get("mcp.error.custom.model.unavailable", customModelId));
                    return;
                }
                ModelRef aiModel = model.get();

                DynamicDatasource dynamic = resolveDynamicDatasource(body);
                // A23：请求未携带 dynamic_datasource 时，若该高级应用配置了宿主数据源端点，
                // 优先按宿主 API 下发结果解析（对齐 SQLBot AssistantOutDs：构造即拉取宿主列表）
                if (dynamic == null) {
                    dynamic = resolveHostApiDatasource(body, chat);
                }
                Long dsId;
                Map<String, Object> ds = null;
                if (dynamic != null) {
                    // A8：高级应用按请求从宿主拉取动态数据源（不落库，dsId 为空）
                    dsId = null;
                } else {
                    // A5/M2：会话已绑定优先；未绑定时才采用请求 datasource_id；否则 RAG 选库
                    dsId = resolveDatasource(chat, oid, question, Rows.lngOrNull(body, "datasource_id"), writer);
                    if (dsId == null) {
                        emitError(writer, messageResolver.get("chat.no.datasource"));
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
                boolean isFirstRecord = !chatRepository.hasUserRecord(chatId);
                boolean firstChat = Rows.bool(body, "first_chat", false);
                long recordId = chatRepository.insertRecord(chatId, question, uid, oid, dsId, firstChat,
                        regenerateRecordId);
                chatRepository.touchChat(chatId);
                if (dsId != null && Rows.lng(chat, "datasource", 0L) <= 0L) {
                    chatRepository.setChatDatasource(chatId, dsId, dsTypeName, dsType);
                }
                currentRecordId.set(recordId);

                emit(writer, Rows.of("type", "id", "id", recordId));
                // A14：记录选数据源步骤（含普通数据源 id 与动态数据源名称）
                logOperation(recordId, oid, uid, OPERATE_CHOOSE_DATASOURCE, System.currentTimeMillis(), 0L, false,
                        Rows.of("datasource_id", dsId, "datasource_name", dsName, "engine_type", dsTypeName),
                        regenerateRecordId);
                if (regenerateRecordId != null) {
                    emit(writer, Rows.of("type", "regenerate_record_id", "regenerate_record_id", regenerateRecordId));
                }
                emit(writer, Rows.of("type", "question", "question", question));
                // A9/M6：对最终数据源复检资源级使用授权（含已绑定会话），拒绝时不生成/执行 SQL。
                // 记录已创建，错误结果可带 record_id。
                if (dsId != null) {
                    try {
                        dsGrantService.checkUsable(oid, uid, currentUserService.isAdmin(user), dsId);
                    } catch (RuntimeException e) {
                        chatRepository.saveError(recordId, e.getMessage());
                        emitError(writer, e.getMessage(), ERROR_TYPE_PERMISSION, stackTraceOf(e));
                        return;
                    }
                }
                // -- A26：生成 SQL 前先做连通性检查（对齐 SQLBot check_connection），
                // 连接不可用直接报 db-connection-err，不等生成完 SQL 才失败
                if (dsId != null) {
                    try {
                        datasourceService.checkConnection(dsId);
                    } catch (RuntimeException e) {
                        chatRepository.saveError(recordId, e.getMessage());
                        emitError(writer, e.getMessage(), ERROR_TYPE_DB_CONNECTION, stackTraceOf(e));
                        return;
                    }
                }
                emit(writer, Rows.of("type", "info", "msg", "sql generating"));

                // -- A2 知识增强：关键词提取 → 术语过滤 → SQL 示例/自定义提示词过滤
                // （未配置 embedding 模型时术语召回退化为词法匹配，不报错）
                long knowledgeStart = System.currentTimeMillis();
                // A9：高级应用知识范围隔离——请求携带的 assistant_id 作为知识作用域
                // （仅影响术语/训练样例/自定义提示词的召回范围，不会扩大权限）
                Long advancedAppId = Rows.lngOrNull(body, "assistant_id");
                // 验收 A3：追问上下文（前序 Q&A）先于知识增强计算，供 LLM 实体抽取与 SQL 生成复用
                String historyText = priorContext(chatId, currentRecordId.get());
                // 偏差②收敛：SQL 生成使用的历史改为「上一次 GENERATE_SQL 的完整 model messages」（剔除 system）
                List<ModelMessage> historyMessages = priorContextMessages(chatId, currentRecordId.get());
                ChatKnowledgeService.KnowledgeContext knowledge =
                        chatKnowledgeService.prepare(question, oid, dsId, advancedAppId, historyText);
                chatRepository.saveKeywords(recordId, knowledge.getExtractedKeywords(),
                        knowledge.getExpandedKeywords());
                logOperation(recordId, oid, uid, OPERATE_EXTRACT_KEYWORDS, knowledgeStart, 0L, false,
                        Rows.of("keywords", knowledge.getExtractedKeywords(),
                                "expanded_keywords", knowledge.getExpandedKeywords(),
                                "llm_used", knowledge.isKeywordLlmUsed()));
                // A3：降级原因随步骤落库，可在 /chat/record/{id}/log 观测（不只停留在日志文件）
                logOperation(recordId, oid, uid, OPERATE_FILTER_TERMS, knowledgeStart, 0L, false,
                        Rows.of("terms", knowledge.getTermGroups(),
                                "degraded", knowledge.degradationReasons()));
                logOperation(recordId, oid, uid, OPERATE_FILTER_SQL_EXAMPLE, knowledgeStart, 0L, false,
                        Rows.of("examples", knowledge.getSqlExamples(),
                                "degraded", knowledge.degradationReasons()));
                logOperation(recordId, oid, uid, OPERATE_FILTER_CUSTOM_PROMPT, knowledgeStart, 0L, false,
                        Rows.of("prompts", knowledge.getCustomPrompts()));

                // -- 生成 SQL（流式） + 只读校验 + 错误回喂修复一轮
                long sqlStart = System.currentTimeMillis();
                String customPromptText = String.join("\n", knowledge.getCustomPrompts());
                // 2.3：用扩展关键词驱动表级召回（对齐 SQLBot 用 expanded_keywords 做表匹配），提升同义表述选表命中率
                String matchText = knowledge.getExpandedKeywords();
                TokenUsage sqlUsage = new TokenUsage();
                List<ModelMessage> sqlMessages = dynamic != null
                        ? schemaContextBuilder.buildSqlMessagesForConf(question, dynamic.getType(),
                                dynamic.getConf(), knowledge.getTerminologyText(), customPromptText,
                                historyMessages, matchText, dynamic.getTableSqlTemplates(),
                                dynamic.getTableRules())
                        : schemaContextBuilder.buildSqlMessages(question, dsId, knowledge.getTerminologyText(),
                                customPromptText, knowledge.getSqlExamples(), historyMessages, matchText);
                String sqlText = generateText(writer, aiModel, sqlMessages, "sql-result", totalTokens, sqlUsage);
                // A12：解析结构化 SQL JSON 协议（success,sql,brief,chart-type,tables），不再用正则抠 SQL
                SqlJsonResult sqlJson = parseSqlJson(sqlText);
                // 发现-5 延伸修复（A5 偶发 run FAILED）：gpt-oss 模型偶发不输出可解析 SQL，
                // 原单轮回喂不足；改为至多 MAX_SQL_REPAIR_RETRIES 次重试回喂，显著降低偶发失败率。
                int sqlRepairLeft = MAX_SQL_REPAIR_RETRIES;
                while (sqlJson == null || !sqlJson.success || sqlJson.sql == null || sqlJson.sql.isEmpty()) {
                    if (sqlRepairLeft <= 0) {
                        String reason = sqlJson == null ? "模型未返回可解析的 JSON"
                                : (sqlJson.message == null ? "SQL 生成失败" : sqlJson.message);
                        // 非法 JSON / success=false：返回明确错误，不执行 SQL、不截断标题
                        emitError(writer, reason);
                        return;
                    }
                    String reason = sqlJson == null ? "模型未返回可解析的 JSON"
                            : (sqlJson.message == null ? "SQL 生成失败" : sqlJson.message);
                    log.warn("SQL 生成结果不可用（{}），第 {} 次回喂修复，模型原始输出：{}", reason,
                            (MAX_SQL_REPAIR_RETRIES - sqlRepairLeft + 1), abbreviate(sqlText, 2000));
                    String repaired = regenerateSql(aiModel, question, dsId, dynamic, sqlText, reason, writer);
                    sqlJson = parseSqlJson(repaired);
                    sqlText = repaired;
                    sqlRepairLeft--;
                }
                String sql = sqlJson.sql;
                String chartType = sqlJson.chartType;
                String sessionBrief = sqlJson.brief;
                // A14：记录选表步骤（模型在 SQL JSON 中给出的 FROM 表范围）
                logOperation(recordId, oid, uid, OPERATE_CHOOSE_TABLE, sqlStart, 0L, false,
                        Rows.of("tables", sqlJson.tables), regenerateRecordId);
                // A12：用 tables 限制 FROM 表范围（防止模型幻觉到未列出的表）
                if (sqlJson.tables != null && !sqlJson.tables.isEmpty()) {
                    String tableErr = validateTables(sql, sqlJson.tables);
                    if (tableErr != null) {
                        emitError(writer, tableErr);
                        return;
                    }
                }
                // §2.4: 动态数据源表级 SQL 占位符闭环（brief D2）：先查直接物理表引用，再确定性替换占位符；
                // 替换/引用违规都走既有的错误回喂修复轮，不执行未替换 SQL
                if (sql != null && dynamic != null && !dynamic.getTableSqlTemplates().isEmpty()) {
                    String directErr = DynamicDatasource.directTableReferenceError(sql, dynamic.getTableSqlTemplates(),
                            ChatQuestionService::usedTables);
                    if (directErr == null) {
                        sql = DynamicDatasource.replaceSubSqlPlaceholders(sql, dynamic.getTableSqlTemplates());
                    } else {
                        String fixedText = regenerateSql(aiModel, question, dsId, dynamic, sql, directErr, writer);
                        String fixedSql = parseSqlJson(fixedText).sql;
                        if (fixedSql != null
                                && DynamicDatasource.directTableReferenceError(fixedSql, dynamic.getTableSqlTemplates(),
                                        ChatQuestionService::usedTables) == null) {
                            sql = DynamicDatasource.replaceSubSqlPlaceholders(fixedSql, dynamic.getTableSqlTemplates());
                        } else {
                            sql = fixedSql;
                        }
                    }
                }
                // A18：注入宿主下发的表级行规则（动态数据源的数据边界）。规则文本由宿主提供、
                // 会被内联进 SQL，非法规则（语句拼接/注释/关键字）按校验错误处理并走修复轮。
                String rowRuleError = null;
                if (sql != null && dynamic != null && !dynamic.getTableRules().isEmpty()) {
                    try {
                        sql = DynamicDatasource.applyRowRules(sql, dynamic.getTableRules(),
                                ChatQuestionService::usedTables);
                    } catch (IllegalArgumentException ruleError) {
                        rowRuleError = ruleError.getMessage();
                    }
                }
                String validation = rowRuleError != null ? rowRuleError
                        : (sql == null ? "模型未输出可执行的 SQL"
                                : validateSql(sql, dsType, dsId, dynamic, question, matchText));
                // 发现-5 延伸修复（A5 偶发 run FAILED）：校验/空 SQL 失败时回喂修复，至多重试 MAX_SQL_REPAIR_RETRIES 次。
                int validationRetryLeft = MAX_SQL_REPAIR_RETRIES;
                while (validation != null && validationRetryLeft > 0) {
                    String fixed = regenerateSql(aiModel, question, dsId, dynamic, sql == null ? "" : sql, validation, writer);
                    String fixedSql = parseSqlJson(fixed).sql;
                    if (fixedSql != null && dynamic != null && !dynamic.getTableSqlTemplates().isEmpty()
                            && DynamicDatasource.directTableReferenceError(fixedSql, dynamic.getTableSqlTemplates(),
                                    ChatQuestionService::usedTables) == null) {
                        fixedSql = DynamicDatasource.replaceSubSqlPlaceholders(fixedSql, dynamic.getTableSqlTemplates());
                    }
                    // A18：修复轮同样要重新注入行规则，否则修复 SQL 会绕过宿主数据边界
                    if (fixedSql != null && dynamic != null && !dynamic.getTableRules().isEmpty()) {
                        try {
                            fixedSql = DynamicDatasource.applyRowRules(fixedSql, dynamic.getTableRules(),
                                    ChatQuestionService::usedTables);
                        } catch (IllegalArgumentException ruleError) {
                            fixedSql = null;
                        }
                    }
                    if (fixedSql != null && validateSql(fixedSql, dsType, dsId, dynamic, question, matchText) == null) {
                        sqlText = fixed;
                        sql = fixedSql;
                        validation = null;
                    } else {
                        validationRetryLeft--;
                        if (validationRetryLeft <= 0) {
                            emitError(writer, validation);
                            return;
                        }
                    }
                }
                logOperation(currentRecordId.get(), oid, uid, OPERATE_GENERATE_SQL, sqlStart,
                        sqlUsage, validation != null, sqlLogMessage(sqlMessages, sqlText));
                if (validation != null) {
                    emitError(writer, validation);
                    return;
                }
                // A14：动态 SQL 构建完成（含动态数据源模板/行规则/表范围替换、只读校验）
                logOperation(recordId, oid, uid, OPERATE_GENERATE_DYNAMIC_SQL, sqlStart, 0L, false,
                        Rows.of("sql", sql), regenerateRecordId);
                long permStart = System.currentTimeMillis();
                // -- 行/列权限强制（A3）：普通用户注入行过滤、隐藏无权限列；管理员/白名单跳过
                PermissionEnforcer.EnforcedQuery enforced;
                try {
                    enforced = (dynamic != null || dsId == null || permissionEnforcer == null)
                            ? null : permissionEnforcer.enforce(oid, uid, dsId, sql);
                } catch (PermissionEnforcer.PermissionEnforceException e) {
                    // A3 fail-closed：无法解析/改写 SQL 时拒绝执行，不以未加固 SQL 放行
                    logOperation(currentRecordId.get(), oid, uid, OPERATE_GENERATE_SQL, sqlStart,
                            totalTokens.get(), true, null);
                    chatRepository.saveError(recordId, e.getMessage());
                    emitError(writer, e.getMessage(), ERROR_TYPE_PERMISSION, stackTraceOf(e));
                    return;
                }
                if (enforced != null) {
                    sql = enforced.getSql();
                }
                chatRepository.saveSql(recordId, sql, sqlText);
                // A14：行/列权限加固后的 SQL 生成（enforced=true 表示注入了行过滤/隐藏列）
                logOperation(recordId, oid, uid, OPERATE_GENERATE_SQL_WITH_PERMISSIONS, permStart, 0L, false,
                        Rows.of("sql", sql, "enforced", enforced != null,
                                "hidden_columns", enforced == null ? null : enforced.getHiddenColumns()),
                        regenerateRecordId);
                emit(writer, Rows.of("type", "sql", "content", formatSql(sql)));

                // -- 首问时生成会话摘要（对齐 SQLBot change_title 语义）
                if (isFirstRecord) {
                    // A12：优先使用 SQL 生成 JSON 返回的 brief 重命名会话；缺失时回退完整问题（不截断前 20 字）
                    String brief = (sessionBrief != null && !sessionBrief.isEmpty()) ? sessionBrief : fullBrief(question);
                    chatRepository.renameChat(chatId, brief);
                    emit(writer, Rows.of("type", "brief", "brief", brief));
                }

                // -- 执行 SQL → 表格数据
                emit(writer, Rows.of("type", "info", "msg", "sql executing"));
                long execStart = System.currentTimeMillis();
                Map<String, Object> result;
                try {
                    result = dynamic != null
                            ? datasourceService.execSqlTransient(dynamic.getType(), dynamic.getConf(), sql)
                            : datasourceService.execSql(dsId, Rows.of("sql", sql));
                } catch (Exception execEx) {
                    // 发现-5 缓解：模型幻觉不存在的列（如 status 而非 order_status）时执行报“未知列”，
                    // 用真实 schema 列名反馈模型一次性改写后重试，避免把执行错误直接抛给用户。
                    if (dsId != null && isColumnError(execEx.getMessage())) {
                        String fixedText = regenerateSql(aiModel, question, dsId, dynamic, sql,
                                execEx.getMessage(), writer);
                        String fixedSql = parseSqlJson(fixedText).sql;
                        if (fixedSql != null && validateSql(fixedSql, dsType, dsId, dynamic, question, matchText) == null) {
                            String finalSql = fixedSql;
                            if (dynamic == null && permissionEnforcer != null) {
                                try {
                                    com.xiongx9527.askdata.permission.PermissionEnforcer.EnforcedQuery enf2 =
                                            permissionEnforcer.enforce(oid, uid, dsId, fixedSql);
                                    if (enf2 != null) {
                                        finalSql = enf2.getSql();
                                    }
                                } catch (com.xiongx9527.askdata.permission.PermissionEnforcer.PermissionEnforceException ignore) {
                                    // 改写后权限改写失败：放弃纠正，保留原始错误
                                }
                            }
                            sql = finalSql;
                            chatRepository.saveSql(recordId, sql, fixedText);
                            result = datasourceService.execSql(dsId, Rows.of("sql", sql));
                        } else {
                            throw execEx;
                        }
                    } else {
                        throw execEx;
                    }
                }
                if (enforced != null) {
                    result = permissionEnforcer.applyHiddenColumns(result, enforced.getHiddenColumns());
                }
                // §2.1: chat.limit_rows 开启时对结果行数硬截断（对齐 SQLBot save_sql_data：
                // 阈值固定 1000，超限时截断并写 data_obj['limit']，前端据 limit 显示「仅展示前 N 条」）。
                // 此前 askdata 只有提示词一句「1000 行以内」，没有任何运行时兜底。
                Map<String, Object> dataObj = new LinkedHashMap<String, Object>();
                dataObj.put("fields", result.get("fields"));
                List<Map<String, Object>> allRows = Rows.mapList(result.get("data"));
                if (chatBool("chat.limit_rows", true) && allRows.size() > SQL_ROW_LIMIT) {
                    result = new LinkedHashMap<String, Object>(result);
                    result.put("data", new ArrayList<Object>(allRows.subList(0, SQL_ROW_LIMIT)));
                    dataObj.put("limit", SQL_ROW_LIMIT);
                }
                dataObj.put("data", result.get("data"));
                // SQLBot 同位置写入 data_obj['datasource']，供前端 /chat/record/{rid}/data_live 回源执行
                if (dsId != null) {
                    dataObj.put("datasource", dsId);
                }
                chatRepository.saveSqlData(recordId, chatRepository.writeJson(dataObj));
                logOperation(recordId, oid, uid, OPERATE_EXECUTE_SQL, execStart, 0L, false,
                        Rows.of("sql", sql, "count", sizeOf(result.get("data"))));
                emit(writer, Rows.of("type", "info", "msg", "sql executed"));
                emit(writer, Rows.of("type", "sql-data", "content", "execute-success"));

                // -- 图表生成（A4/D4：finish_step=QUERY_DATA 时只到数据为止）
                if (!queryDataOnly) {
                    emit(writer, Rows.of("type", "info", "msg", "chart generating"));
                    long chartStart = System.currentTimeMillis();
                    // A10：图表生成携带 <sql> 与 <m-schema> 上下文（对齐 SQLBot llm.py:1085-1130）
                    String chartMSchema = dynamic != null
                            ? schemaContextBuilder.mSchemaOfConf(dynamic.getType(), dynamic.getConf(),
                            dynamic.getTableSqlTemplates())
                            : schemaContextBuilder.mSchemaOf(dsId);
                    ChartOutcome chartOutcome = generateChart(writer, aiModel, question, result, totalTokens,
                            sql, chartMSchema, chartType);
                    String chartJson = chartOutcome.chart;
                    logOperation(recordId, oid, uid, OPERATE_GENERATE_CHART, chartStart, totalTokens.get(),
                            false, null);
                    // A3：return_img=false 时不渲染图片、不发 imageUrl 帧（图表配置仍生成）
                    String imageUrl = null;
                    if (returnImage) {
                        // A4：服务端出图（SSR）图片 URL——对齐 SQLBot g2-ssr，供非交互/MCP/导出场景直接引用
                        try {
                            imageUrl = chartImageService.renderFromResult(result, question);
                        } catch (Exception e) {
                            log.debug("SSR 出图失败（降级无图）: {}", e.getMessage());
                        }
                    }
                    // A26：chart_answer 存模型原始思考（reasoning/content），不是 SSR 图片 URL
                    chatRepository.saveChart(recordId, chartJson, chartOutcome.answer);
                    emit(writer, Rows.of("type", "info", "msg", "chart generated"));
                    emit(writer, Rows.of("type", "chart", "content", chartJson));
                    if (imageUrl != null) {
                        emit(writer, Rows.of("type", "imageUrl", "content", imageUrl));
                    }
                    // A14：出图步骤（图表配置 + SSR 图片 URL，rendered 标记是否实际产出图片）
                    logOperation(recordId, oid, uid, OPERATE_GENERATE_PICTURE, chartStart, 0L, false,
                            Rows.of("chart_type", chartType, "image_url", imageUrl,
                                    "rendered", imageUrl != null), regenerateRecordId);

                    // -- 推荐问题：A8 性能优化——不再在主链路内串行调用大模型。
                    // 前端（ChartAnswer.vue → onChartAnswerFinish）与 E2E/MCP 都通过独立接口
                    // GET /chat/recommend_questions/{record_id} 获取并落库推荐问题，主链路内
                    // 再生成一次属于重复 LLM 调用，会把 finish 帧推迟数秒。
                    // 推荐问题生成失败/不可用的可见性由该接口的错误帧负责（A12）。
                }

                chatRepository.finishRecord(recordId, System.currentTimeMillis() - started, totalTokens.get());
                emit(writer, Rows.of("type", "finish"));
            } catch (Exception e) {
                log.warn("提问链路异常: {}", e.getMessage());
                emitError(writer, e.getMessage() == null ? e.toString() : e.getMessage());
                // A16：仅真实执行错误才落 saveError；客户端断连（IOException）不视为业务错误
                if (currentRecordId.get() > 0L && !(e instanceof IOException)) {
                    chatRepository.saveError(currentRecordId.get(), String.valueOf(e.getMessage()));
                }
            }
    }

    /**
     * 非流式问数（A1/L3）：复用同一流式管线，捕获帧后组装 SQLBot JSON
     * {@code {success,record_id,sql,data,chart,image_url?}}。
     *
     * <p>数据与图表从本次记录落库内容读取，保证响应与后续 Web 端读取一致；
     * 管线内的业务错误以 {@code success=false} 返回，由 HTTP 兼容层映射为 500。</p>
     */
    public Map<String, Object> ask(Map<String, Object> body) throws IOException {
        List<Map<String, Object>> frames = new ArrayList<>();
        try (FrameWriter writer = FrameWriter.capturing(objectMapper, frames::add)) {
            streamQuestion(body, writer);
        }
        return assembleAskResult(body, frames);
    }

    private Map<String, Object> assembleAskResult(Map<String, Object> body,
                                                  List<Map<String, Object>> frames) {
        long recordId = 0L;
        String error = null;
        String imageUrl = null;
        for (Map<String, Object> frame : frames) {
            String type = Rows.str(frame, "type");
            if ("id".equals(type)) {
                recordId = Rows.lng(frame, "id", recordId);
            } else if ("error".equals(type)) {
                error = errorMessageOf(frame);
            } else if ("imageUrl".equals(type)) {
                imageUrl = Rows.str(frame, "content");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (error != null) {
            result.put("success", false);
            result.put("message", error);
            if (recordId > 0L) {
                result.put("record_id", recordId);
            }
            return result;
        }
        if (recordId <= 0L) {
            result.put("success", false);
            result.put("message", messageResolver.get("mcp.error.tool.failed", "mcp_question", "no record"));
            return result;
        }

        Map<String, Object> record = chatRepository.findRecord(recordId).orElse(null);
        result.put("success", true);
        result.put("record_id", recordId);
        result.put("sql", record == null ? null : Rows.str(record, "sql"));
        result.put("data", dataObjectOf(record));
        if (!"QUERY_DATA".equalsIgnoreCase(Rows.str(body, "finish_step"))) {
            result.put("chart", chartObjectOf(record));
        }
        if (imageUrl != null && Rows.bool(body, "return_img", true)) {
            result.put("image_url", imageUrl);
        }
        return result;
    }

    /** 记录落库的 data 对象（含 fields/data/datasource/limit），与 Web 端读取一致。 */
    private Map<String, Object> dataObjectOf(Map<String, Object> record) {
        String json = record == null ? null : Rows.str(record, "data");
        if (json != null && !json.isEmpty() && !"null".equals(json)) {
            try {
                Map<String, Object> data = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
                });
                if (data != null) {
                    return data;
                }
            } catch (Exception ignored) {
                // 落库异常时回退空表格，不影响 success 语义
            }
        }
        return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>());
    }

    /** 记录落库的图表配置；未生成时为 null。 */
    private Object chartObjectOf(Map<String, Object> record) {
        String json = record == null ? null : Rows.str(record, "chart");
        if (json == null || json.isEmpty() || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 错误帧 content 为 JSON（message/traceback/type）；解析失败时按原文返回。 */
    private String errorMessageOf(Map<String, Object> frame) {
        String content = Rows.str(frame, "content");
        if (content == null || content.isEmpty()) {
            return "";
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(content,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object message = payload.get("message");
            return message == null ? content : String.valueOf(message);
        } catch (Exception ignored) {
            return content;
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
            throw new BadRequestException(messageResolver.get("chat.type.not.found", actionType));
        }
        Map<String, Object> user = currentUserService.currentUser();
        long uid = Rows.lng(user, "id", 0L);
        long oid = currentUserService.oidOf(user);
        Map<String, Object> base = chatRepository.findRecord(baseRecordId)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("chat.record.not.found", baseRecordId)));
        Map<String, Object> chat = chatRepository.findChat(Rows.lng(base, "chat_id", 0L))
                .orElseThrow(() -> new BadRequestException(messageResolver.get("chat.record.not.found", baseRecordId)));
        requireOwner(chat, user, "ChatRecord");
        long started = System.currentTimeMillis();

        try (FrameWriter writer = new FrameWriter(response, objectMapper)) {
            try {
                Optional<ModelRef> model = modelRouter.defaultChatModel();
                if (!model.isPresent() || !model.get().usable()) {
                    emitError(writer, messageResolver.get("scheduled.no.default.model"));
                    return;
                }
                ModelRef aiModel = model.get();
                if (Rows.str(base, "chart") == null || Rows.str(base, "chart").isEmpty()) {
                    emitError(writer, messageResolver.get("chat.no.chart", baseRecordId));
                    return;
                }
                Long dsId = Rows.lngOrNull(base, "datasource");
                // A2：子记录指向基础记录，并复制基础记录的 chart/data；基础记录的两个外键保持 NULL
                String baseChart = Rows.str(base, "chart");
                String baseData = Rows.str(base, "data");
                long recordId = chatRepository.insertSubRecord(Rows.lng(base, "chat_id", 0L),
                        Rows.str(base, "question"), uid, oid, dsId, actionType, baseRecordId,
                        baseChart, baseData);
                emit(writer, Rows.of("type", "id", "id", recordId));

                String question = Rows.str(base, "question");
                String dataJson = chatRepository.writeJson(
                        Rows.of("fields", fieldsOf(base), "data", dataOf(base)));
                AtomicLong totalTokens = new AtomicLong(0L);
                boolean analysis = "analysis".equals(actionType);
                // §2.2: 分析/预测也消费自定义提示词（按 custom_prompt.type=ANALYSIS/PREDICT_DATA 召回并注入；
                // 此前 customPrompts 写死 GENERATE_SQL 且两条消息无插槽，UI 配了运行时不生效）。
                // 类型字面量对齐 SQLBot customprompttypeenum（GENERATE_SQL / ANALYSIS / PREDICT_DATA）。
                String customPromptText = String.join("\n",
                        chatKnowledgeService.customPromptsForType(oid, dsId, analysis ? "ANALYSIS" : "PREDICT_DATA"));
                String full = generateText(writer, aiModel,
                        analysis ? schemaContextBuilder.buildAnalysisMessages(question, Rows.str(base, "chart"),
                                dataJson, customPromptText)
                                : schemaContextBuilder.buildPredictMessages(question, Rows.str(base, "chart"),
                                dataJson, customPromptText),
                        analysis ? "analysis-result" : "predict-result", totalTokens);

                if (analysis) {
                    // A2：分析结果落在子记录，基础记录保持原问题气泡内容不变
                    chatRepository.saveAnalysis(recordId, full, "");
                    // spec §2.3 命名（analysis / analysis_thinking）
                    emit(writer, Rows.of("type", "analysis", "content", full));
                    emit(writer, Rows.of("type", "analysis_thinking", "content", ""));
                    emit(writer, Rows.of("type", "info", "msg", "analysis generated"));
                    emit(writer, Rows.of("type", "analysis_finish"));
                } else {
                    chatRepository.savePredictContent(recordId, full, "");
                    List<Map<String, Object>> predictData = extractJsonArray(full);
                    boolean hasData = !predictData.isEmpty();
                    if (hasData) {
                        chatRepository.savePredictData(recordId, chatRepository.writeJson(predictData));
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
                logOperation(recordId, oid, uid, analysis ? OPERATE_ANALYSIS : OPERATE_PREDICT,
                        started, totalTokens.get(), false, Rows.of("record_id", recordId,
                                "base_record_id", baseRecordId));
                chatRepository.finishRecord(recordId, duration, totalTokens.get());
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
                // A12：推荐问题失败不再静默返回空数组，改为带 code/msg 的错误帧，前端可读提示
                emitRecommendError(writer, messageResolver.get("chat.record.not.found", recordId));
                return;
            }
            Map<String, Object> chat = chatRepository.findChat(Rows.lng(record, "chat_id", 0L)).orElse(null);
            if (chat == null) {
                emitRecommendError(writer, messageResolver.get("chat.not.found", Rows.lng(record, "chat_id", 0L)));
                return;
            }
            // A1：推荐问题同样按当前用户归属校验，管理员不再拥有隐式跨用户读取权限
            requireOwner(chat, user, "ChatRecord");
            Optional<ModelRef> model = modelRouter.defaultChatModel();
            if (!model.isPresent() || !model.get().usable()) {
                emitRecommendError(writer, messageResolver.get("scheduled.no.default.model"));
                return;
            }
            long start = System.currentTimeMillis();
        long chatId = Rows.lng(record, "chat_id", 0L);
        String chartHint = Rows.str(record, "chart");
        boolean hasChartHint = chartHint != null && !chartHint.isEmpty();
        List<String> history = new ArrayList<>();
        for (Map<String, Object> prev : chatRepository.listRecordsByChat(chatId)) {
            if (Rows.lng(prev, "id", 0L) == recordId) {
                continue;
            }
            String q = Rows.str(prev, "question");
            if (q != null && !q.isEmpty()) {
                history.add(q);
            }
        }
        List<String> questions;
        try {
            questions = recommendQuestions(model.get(), Rows.str(record, "question"),
                    articlesNumber, hasChartHint ? chartHint : null, history);
        } catch (Exception e) {
            // A12：推荐问题异常改为显式错误帧（带 code/msg），前端展示失败提示并可重试
            log.warn("推荐问题生成失败: {}", e.getMessage());
            emitRecommendError(writer, messageResolver.get("chat.recommend.failed", e.getMessage()));
            return;
        }
        if (questions.isEmpty()) {
            // A12：模型不可用/输出不可解析时 generate 返回 null，解析结果为空也要显式报错，
            // 不再静默下发空数组让前端误以为「没有推荐问题」。
            log.warn("推荐问题生成失败: 模型未返回可用问题");
            emitRecommendError(writer, messageResolver.get("chat.recommend.failed", "模型未返回可用问题"));
            return;
        }
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
     * 取前序问答（最近若干条）作为追问上下文文本，供知识增强（实体/术语/样例召回）复用。
     * 注意：SQL 生成的 model messages 历史由 {@link #priorContextMessages} 提供，本方法仅产出文本。
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
            if (q == null || q.isEmpty()) {
                continue;
            }
            String sql = Rows.str(r, "sql");
            StringBuilder b = new StringBuilder("Q: ").append(q);
            if (!sql.isEmpty()) {
                b.append("\nA(SQL): ").append(sql);
            }
            blocks.add(b.toString());
        }
        int maxRecords = chatInt("chat.context_record_count", 3);
        if (maxRecords <= 0) {
            return "";
        }
        if (blocks.size() > maxRecords) {
            blocks = blocks.subList(blocks.size() - maxRecords, blocks.size());
        }
        String lastError = lastExecSqlError(chatId, excludeRecordId);
        if (!lastError.isEmpty()) {
            blocks.add("Last SQL execution failed with the following error. "
                    + "Fix it in this round and do not repeat the same mistake:\n" + lastError);
        }
        return String.join("\n", blocks);
    }

    /**
     * 验收 A2/A3：取上一次 GENERATE_SQL 的完整 model messages 作为追问历史（剔除 system），
     * 取最近 N 轮（N 由 chat.context_record_count 控制，≤0 不注入），对齐 SQLBot
     * get_last_conversation_rounds + init_messages 的历史注入（偏差②收敛）。
     */
    private List<ModelMessage> priorContextMessages(long chatId, long excludeRecordId) {
        List<Map<String, Object>> records = chatRepository.listRecordsByChat(chatId);
        int roundCount = chatInt("chat.context_record_count", 3);
        if (records == null || records.isEmpty() || roundCount <= 0) {
            return Collections.emptyList();
        }
        // 取当前记录之前的「最近一条」记录：其 GENERATE_SQL 日志已累积了前序历史轮次
        Long prevRecordId = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            long rid = Rows.lng(records.get(i), "id", 0L);
            if (rid != excludeRecordId) {
                prevRecordId = rid;
                break;
            }
        }
        if (prevRecordId == null) {
            return Collections.emptyList();
        }
        Map<String, Object> parsed = chatRepository.lastLogMessage(prevRecordId, OPERATE_GENERATE_SQL);
        if (parsed == null) {
            return Collections.emptyList();
        }
        Object msgsObj = parsed.get("messages");
        if (!(msgsObj instanceof List)) {
            return Collections.emptyList();
        }
        List<?> rawMessages = (List<?>) msgsObj;
        List<ModelMessage> filtered = new ArrayList<>();
        for (Object o : rawMessages) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            String role = Rows.str(m, "role");
            if ("system".equalsIgnoreCase(role)) {
                continue;
            }
            filtered.add(new ModelMessage(role, Rows.str(m, "content")));
        }
        // 对齐 SQLBot get_last_conversation_rounds：从倒数第 N 个 user 消息起截取
        List<Integer> humanIdx = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            if ("user".equalsIgnoreCase(filtered.get(i).role)) {
                humanIdx.add(i);
            }
        }
        if (humanIdx.isEmpty()) {
            return Collections.emptyList();
        }
        int start = humanIdx.size() <= roundCount ? humanIdx.get(0) : humanIdx.get(humanIdx.size() - roundCount);
        List<ModelMessage> history = new ArrayList<>(filtered.subList(start, filtered.size()));
        // A16：跨轮错误自愈——把上一轮 exec-sql 失败的错误注入下一轮提示词
        String lastError = lastExecSqlError(chatId, excludeRecordId);
        if (!lastError.isEmpty()) {
            history.add(new ModelMessage("user", "Last SQL execution failed with the following error. "
                    + "Fix it in this round and do not repeat the same mistake:\n" + lastError));
        }
        return history;
    }

    /** 构造 GENERATE_SQL 日志的 message（完整 model messages + 本轮模型输出），供 priorContextMessages 回读。 */
    private Map<String, Object> sqlLogMessage(List<ModelMessage> messages, String answer) {
        List<Map<String, String>> arr = new ArrayList<>();
        for (ModelMessage m : messages) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("role", m.role);
            o.put("content", m.content);
            arr.add(o);
        }
        Map<String, String> ans = new LinkedHashMap<>();
        ans.put("role", "assistant");
        ans.put("content", answer == null ? "" : answer);
        arr.add(ans);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("messages", arr);
        return msg;
    }

    /** A16：取本会话最近一条非空 error 的记录错误信息（排除当前记录）。 */
    private String lastExecSqlError(long chatId, long excludeRecordId) {
        List<Map<String, Object>> records = chatRepository.listRecordsByChat(chatId);
        if (records == null || records.isEmpty()) {
            return "";
        }
        for (int i = records.size() - 1; i >= 0; i--) {
            Map<String, Object> r = records.get(i);
            if (Rows.lng(r, "id", 0L) == excludeRecordId) {
                continue;
            }
            String error = Rows.str(r, "error");
            if (error != null && !error.isEmpty()) {
                return error;
            }
        }
        return "";
    }

    /** §2.1: 读取 chat.* 整型参数（sys_arg）；缺失或非法返回默认值。 */
    private int chatInt(String pkey, int def) {
        Optional<Map<String, Object>> row = sysArgRepository.findByPkey(pkey);
        if (!row.isPresent()) {
            return def;
        }
        try {
            return Integer.parseInt(Rows.str(row.get(), "pval").trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    /** §2.1: 读取 chat.* 布尔参数（sys_arg）；缺失返回默认值。 */
    private boolean chatBool(String pkey, boolean def) {
        Optional<Map<String, Object>> row = sysArgRepository.findByPkey(pkey);
        return row.isPresent() ? Boolean.parseBoolean(Rows.str(row.get(), "pval")) : def;
    }

    /**
     * 数据源解析（A5/M2/D5）：会话已绑定 &gt; 请求显式 {@code datasource_id} &gt; RAG 选库。
     * 显式数据源仍校验存在性与工作空间归属，最终数据源在记录创建后统一做使用授权复检。
     */
    private Long resolveDatasource(Map<String, Object> chat, long oid, String question,
                                   Long requestedDatasourceId, FrameWriter writer) throws IOException {
        long bound = Rows.lng(chat, "datasource", 0L);
        if (bound > 0L) {
            // 已绑定会话忽略请求 datasource_id（不校验、不改绑）
            return bound;
        }
        if (requestedDatasourceId != null && requestedDatasourceId > 0L) {
            Map<String, Object> requested = datasourceRepository.findById(requestedDatasourceId)
                    .orElseThrow(() -> new BadRequestException(
                            messageResolver.get("ds.not.found", requestedDatasourceId)));
            Map<String, Object> user = currentUserService.currentUser();
            if (!currentUserService.isAdmin(user) && Rows.lng(requested, "oid", 0L) != oid) {
                throw new BadRequestException(messageResolver.get("ds.not.found", requestedDatasourceId));
            }
            emit(writer, Rows.of("type", "datasource-result",
                    "content", "selected datasource " + requestedDatasourceId,
                    "reasoning_content", ""));
            return requestedDatasourceId;
        }
        Long dsId = schemaContextBuilder.selectDatasource(oid, question);
        if (dsId != null) {
            emit(writer, Rows.of("type", "datasource-result", "content", "selected datasource " + dsId,
                    "reasoning_content", ""));
        }
        return dsId;
    }

    /**
     * A23：高级应用的宿主 API 数据源下发。
     *
     * <p>会话所属应用是已登记的高级应用（type 1/3）且其配置里带有 {@code endpoint} 时，
     * 按宿主下发的数据源 id（会话 datasource / 请求 datasource）实时拉取连接配置。
     * 宿主不可达或数据源不在下发列表中时报可读错误（对齐 SQLBot AssistantOutDs）。</p>
     *
     * @return 动态数据源；非高级应用、未配置端点或解析失败时返回 null（调用方回落静态数据源）
     */
    private DynamicDatasource resolveHostApiDatasource(Map<String, Object> body, Map<String, Object> chat) {
        Long assistantId = Rows.lngOrNull(body, "assistant_id");
        if (assistantId == null) {
            assistantId = Rows.lngOrNull(chat, "assistant_id");
        }
        if (assistantId == null) {
            return null;
        }
        Map<String, Object> assistant = assistantRepository.findById(assistantId).orElse(null);
        if (assistant == null) {
            return null;
        }
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        if (Rows.lng(assistant, "oid", 0L) != oid) {
            return null;
        }
        int assistantType = Rows.intg(assistant, "type", 0);
        if (!DynamicDatasourceResolver.isAdvancedApplication(assistantType)) {
            return null;
        }
        Map<String, Object> hostEndpoint = dynamicDatasourceResolver.hostEndpointOf(assistant);
        if (hostEndpoint == null) {
            return null;
        }
        Long hostDsId = Rows.lngOrNull(body, "datasource");
        if (hostDsId == null) {
            hostDsId = Rows.lngOrNull(chat, "datasource");
        }
        if (hostDsId == null || hostDsId <= 0L) {
            throw new BadRequestException(messageResolver.get("i18n_embedded.host_fetch_failed",
                    "assistant datasource id is missing"));
        }
        return dynamicDatasourceResolver.resolveFromHostList(assistantType, hostEndpoint, hostDsId);
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
        // mcp_assistant 已在请求上下文中解析出宿主数据源，直接复用（不按 assistant_id 再解析）
        if (raw instanceof DynamicDatasource) {
            return (DynamicDatasource) raw;
        }
        // 宿主 URL 形态（对齐 SQLBot AssistantOutDsFactory 主动拉取）：{"host_url": "...", "host_headers"?}
        // ——askdata 实时 GET 宿主 API（10s 超时），宿主侧数据源动态变化无需随请求重复下发
        String hostUrl = null;
        Map<String, String> hostHeaders = null;
        if (raw instanceof Map) {
            Object url = ((Map<?, ?>) raw).get("host_url");
            if (url != null && !String.valueOf(url).trim().isEmpty()) {
                hostUrl = String.valueOf(url).trim();
                Object hs = ((Map<?, ?>) raw).get("host_headers");
                if (hs instanceof Map) {
                    hostHeaders = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> h : ((Map<?, ?>) hs).entrySet()) {
                        hostHeaders.put(String.valueOf(h.getKey()), String.valueOf(h.getValue()));
                    }
                }
            }
        }
        Map<String, Object> hostConfig = null;
        if (hostUrl == null) {
            if (raw instanceof Map) {
                hostConfig = objectMapper.convertValue(raw, new TypeReference<Map<String, Object>>() {
                });
            } else {
                try {
                    hostConfig = objectMapper.readValue(String.valueOf(raw),
                            new TypeReference<Map<String, Object>>() {
                            });
                } catch (Exception e) {
                    throw new BadRequestException(messageResolver.get("chat.invalid.dynamic.ds", e.getMessage()));
                }
            }
        }
        // A8：动态数据源仅对已登记的高级应用（type 1/3）开放。类型以 sys_assistant 记录为准，
        // 不信任请求体自报的 assistant_type——否则任意账号都能让服务端连接任意 JDBC 地址。
        Long assistantId = Rows.lngOrNull(body, "assistant_id");
        if (assistantId == null) {
            throw new BadRequestException(messageResolver.get("chat.dynamic.assistant.required"));
        }
        Map<String, Object> assistant = assistantRepository.findById(assistantId)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.not.found", assistantId)));
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        if (Rows.lng(assistant, "oid", 0L) != oid) {
            throw new BadRequestException(messageResolver.get("assistant.not.found", assistantId));
        }
        if (hostUrl != null) {
            return dynamicDatasourceResolver.resolveFromHost(Rows.intg(assistant, "type", 0), hostUrl, hostHeaders);
        }
        return dynamicDatasourceResolver.resolve(Rows.intg(assistant, "type", 0), hostConfig);
    }

    /** 错误回喂修复一轮（不流式重发，仅用于纠错），失败返回 null。 */
    private String regenerateSql(ModelRef aiModel, String question, Long dsId, DynamicDatasource dynamic,
                                 String brokenSql, String error, FrameWriter writer) {
        try {
            List<ModelMessage> fixMessages = dynamic != null
                    ? schemaContextBuilder.buildSqlFixMessagesForConf(question, dynamic.getType(),
                            dynamic.getConf(), brokenSql == null ? "" : brokenSql, error,
                            dynamic.getTableSqlTemplates())
                    : schemaContextBuilder.buildSqlFixMessages(question, dsId,
                            brokenSql == null ? "" : brokenSql, error);
            String text = modelClient.generate(aiModel.apiDomain, aiModel.apiKey, aiModel.baseModel,
                    fixMessages, aiModel.params);
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
        return validateSql(sql, dsType, dsId, null, "", "");
    }

    /**
     * 只读 SELECT 校验（静态 / 动态数据源共用）：先过 {@link SqlSafety}，再校验表名白名单。
     *
     * <p>A8：动态数据源没有 core_datasource 勾选记录，白名单改为实时读取该连接的表名，
     * 与静态数据源一样拦截模型幻觉出的表，而不是把错误留到执行阶段。</p>
     *
     * @return 错误信息；通过时返回 null
     */
    public String validateSql(String sql, String dsType, Long dsId, DynamicDatasource dynamic) {
        return validateSql(sql, dsType, dsId, dynamic, "", "");
    }

    /**
     * 只读 SELECT 校验（静态 / 动态数据源共用）：先过 {@link SqlSafety}，再校验表名白名单。
     *
     * <p>A8：动态数据源没有 core_datasource 勾选记录，白名单改为实时读取该连接的表名，
     * 与静态数据源一样拦截模型幻觉出的表，而不是把错误留到执行阶段。</p>
     * <p>偏差④收敛：静态白名单由 {@code checkedTableNames}（全部勾选表）改为 {@code selectedTableNames}
     * （本轮 schema 实际召回的表，retrieved-only），与 SQLBot {@code table_name_list} 对齐。</p>
     *
     * @param question   用户问题（驱动表召回，决定 selectedTableNames 范围）
     * @param matchText  表匹配文本（通常为扩展关键词），为空时退化为勾选表次序
     * @return 错误信息；通过时返回 null
     */
    public String validateSql(String sql, String dsType, Long dsId, DynamicDatasource dynamic,
                             String question, String matchText) {
        String safety = validateSqlSafety(sql, dsType);
        if (safety != null) {
            return safety;
        }
        if (dynamic != null) {
            // §2.4: 残留占位符不得执行（宿主未下发对应表模板）
            String leftover = DynamicDatasource.leftoverPlaceholderError(sql, dynamic.getTableSqlTemplates(),
                    ChatQuestionService::usedTables);
            if (leftover != null) {
                return leftover;
            }
            // 白名单允许集 = 动态连接实时表 + 宿主子查询模板内部引用的物理表
            List<String> allowed = dynamicTableNames(dynamic);
            if (allowed != null) {
                for (String inner : DynamicDatasource.templateInnerTables(dynamic.getTableSqlTemplates(),
                        ChatQuestionService::usedTables)) {
                    if (!allowed.contains(inner)) {
                        allowed.add(inner);
                    }
                }
            }
            return validateTables(sql, allowed);
        }
        if (dsId == null) {
            return null;
        }
        String tableError = validateTables(sql, schemaContextBuilder.selectedTableNames(dsId, question, matchText));
        if (tableError != null) {
            return tableError;
        }
        // A10：表/列/聚合语义校验（元数据缺失或结构超出静态判定范围时自动放行）
        return SqlSemanticValidator.validate(sql,
                new SqlSemanticValidator.Schema(schemaContextBuilder.checkedTableColumns(dsId)));
    }

    /** 动态数据源当前表名（实时元数据）；读取失败返回 null（不阻断，交由执行阶段报真实错误）。 */
    private List<String> dynamicTableNames(DynamicDatasource dynamic) {
        try {
            List<String> names = new ArrayList<String>();
            for (com.xiongx9527.askdata.datasource.engine.TableSchema table
                    : datasourceService.tablesTransient(dynamic.getType(), dynamic.getConf())) {
                names.add(table.getTableName());
            }
            return names;
        } catch (Exception e) {
            return null;
        }
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
    public static String validateTables(String sql, List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return null;
        }
        List<String> lowerAllowed = new ArrayList<>();
        for (String table : allowed) {
            lowerAllowed.add(unquoteIdentifier(table).toLowerCase(Locale.ROOT));
        }
        for (String table : usedTables(sql)) {
            if (!lowerAllowed.contains(table)) {
                return "SQL 使用了未授权的表: " + table;
            }
        }
        return null;
    }

    /**
     * 从 SQL 中提取真实涉及的表名（小写、去引号、排除 CTE 别名）。
     *
     * <p>§2.7：对齐 SQLBot {@code extract_tables_from_sql}（sqlglot 解析后收集 {@code exp.Table}
     * 并剔除 CTE 名）。此前用「按空格分词 + FROM/JOIN 取下一个 token」，会把
     * {@code WITH cte AS (...) SELECT * FROM cte} 的 CTE 名当成表（误报未授权表），
     * 也会把 {@code FROM (SELECT ...)} 的子查询括号解析错。</p>
     *
     * <p>方言语法导致 JSqlParser 解析失败时，退化为原分词实现（不阻断主链路）。</p>
     */
    public static List<String> usedTables(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            net.sf.jsqlparser.statement.Statement stmt =
                    net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (String raw : new net.sf.jsqlparser.util.TablesNamesFinder().getTables(stmt)) {
                String name = unquoteIdentifier(raw);
                if (!name.isEmpty()) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
            return new ArrayList<>(names);
        } catch (Exception e) {
            log.info("SQL 表名解析失败，退化为分词提取（{}）: {}", e.getMessage(), sql);
            return usedTablesByToken(sql);
        }
    }

    /**
     * 去掉标识符两侧的引号/反引号/方括号（{@code "user"} / {@code `user`} / {@code [user]}）。
     *
     * <p>与 SQLBot 一致：只取最后一段（{@code exp.Table.name} 不含 schema/database），
     * 因此 {@code myschema.orders} 归一为 {@code orders}。</p>
     */
    static String unquoteIdentifier(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim();
        int dot = name.lastIndexOf('.');
        String last = dot >= 0 ? name.substring(dot + 1) : name;
        if (last.length() >= 2) {
            char first = last.charAt(0);
            char end = last.charAt(last.length() - 1);
            if ((first == '"' && end == '"') || (first == '`' && end == '`')
                    || (first == '[' && end == ']') || (first == '\'' && end == '\'')) {
                last = last.substring(1, last.length() - 1);
            }
        }
        return last.trim();
    }

    /** 分词兜底：FROM/JOIN 后的下一个 token（仅在 SQL 无法被 JSqlParser 解析时使用）。 */
    static List<String> usedTablesByToken(String sql) {
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
    /**
     * 供定时任务等非流式调用方使用：优先按 A12 JSON 协议（{"success":true,"sql":...}）解析，
     * 解析不到再退化为正则抽取裸 SQL。避免提示词要求 JSON 而调用方按裸 SQL 解析导致“无 SQL”。
     */
    public static String extractSqlFromOutput(String text, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        if (mapper != null) {
            SqlJsonResult json = parseSqlJson(text, mapper);
            if (json != null && json.success && json.sql != null && !json.sql.isEmpty()) {
                return json.sql;
            }
        }
        return extractSql(text);
    }

    public static String extractSql(String text) {
        text = stripReasoningTags(text);
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

    /**
     * 去除模型输出中的推理标签与代码围栏，便于后续稳定抽取 SQL / JSON。
     * 兼容 gpt-oss 等会包裹 <think>...</think> 的模型，以及 ```sql / ``` 围栏。
     */
    private static String stripReasoningTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String t = text;
        // 去除 <think>...</think>（可能跨行，大小写不敏感）
        t = t.replaceAll("(?is)<think>.*?</think>", " ");
        // 去除残留的单独 <think> / </think> 标签
        t = t.replaceAll("(?is)</?think>", " ");
        // 去除 ```sql / ``` 之类的代码围栏标记（保留内部内容，交给后续抽取）
        t = t.replaceAll("(?i)```\\s*sql\\s*", " ");
        t = t.replaceAll("(?i)```\\s*", " ");
        return t;
    }

    /**
     * 发现-5 缓解：判断异常消息是否为「未知列/列不存在」类错误（模型幻觉列的典型报错）。
     * 覆盖 MySQL(Unknown column)、PostgreSQL(column does not exist)、Oracle(invalid identifier) 等。
     */
    private static boolean isColumnError(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        return m.contains("unknown column")
                || (m.contains("column") && m.contains("does not exist"))
                || m.contains("invalid identifier")
                || m.contains("无效的列名")
                || m.contains("未知列");
    }

    /** A12：单步 LLM 调用的 token 用量（input / output / total）。 */
    static final class TokenUsage {
        long input;
        long output;
        long total;
    }

    /**
     * A12：SQL 生成结果的结构化 JSON 解析载体（{success,sql,brief,chart-type,tables}）。
     * 当 {@link #success} 为 false 或解析失败时，{@link #message} 携带明确错误原因。
     */
    static final class SqlJsonResult {
        boolean success;
        String sql;
        String brief;
        String chartType;
        List<String> tables = new ArrayList<>();
        String message;
    }

    /** 实例入口：使用本服务注入的 ObjectMapper。 */
    private SqlJsonResult parseSqlJson(String text) {
        return parseSqlJson(text, objectMapper);
    }

    /**
     * 静态入口（便于单测）：从 LLM 输出中解析结构化 SQL JSON。
     * 解析失败、非 JSON 或 success=false 时返回非 success 结果（调用方据此返回明确错误，
     * 不回退正则抠 SQL、不把标题截断前 20 字）。
     */
    static SqlJsonResult parseSqlJson(String text, ObjectMapper mapper) {
        SqlJsonResult result = new SqlJsonResult();
        if (text == null || text.trim().isEmpty()) {
            result.message = "模型未返回任何内容";
            return result;
        }
        String json = extractJsonObject(text);
        if (json == null) {
            result.message = "模型输出不是合法的 JSON";
            return result;
        }
        try {
            Map<String, Object> map = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Object success = map.get("success");
            if (success instanceof Boolean) {
                result.success = (Boolean) success;
            } else if (success != null) {
                result.success = Boolean.parseBoolean(String.valueOf(success));
            }
            Object sql = map.get("sql");
            result.sql = sql == null ? null : String.valueOf(sql).trim();
            Object brief = map.get("brief");
            result.brief = brief == null ? null : String.valueOf(brief).trim();
            Object chartType = map.get("chart-type");
            result.chartType = chartType == null ? null : String.valueOf(chartType).trim();
            Object tables = map.get("tables");
            if (tables instanceof List) {
                for (Object t : (List<?>) tables) {
                    if (t != null) {
                        String name = String.valueOf(t).trim();
                        if (!name.isEmpty()) {
                            result.tables.add(name);
                        }
                    }
                }
            }
            if (!result.success) {
                Object msg = map.get("message");
                result.message = msg == null ? "SQL 生成失败（success=false）" : String.valueOf(msg);
            }
        } catch (Exception e) {
            result.message = "JSON 解析失败: " + e.getMessage();
        }
        return result;
    }

    /** 从自由文本中提取一个 JSON 对象（容忍 Markdown 代码块与前后说明文字）。 */
    private static String extractJsonObject(String text) {
        String t = stripReasoningTags(text).trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return null;
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
    /**
     * 图表生成结果：{@code chart} 为图表配置 JSON，{@code answer} 为模型原始思考
     * （A26：对齐 SQLBot {@code save_chart_answer(answer=orjson.dumps({'content': ...,
     * 'reasoning_content': ...}))}，落库后读取时按 {@code reasoning_content} 优先解包）。
     */
    static final class ChartOutcome {
        final String chart;
        final String answer;

        ChartOutcome(String chart, String answer) {
            this.chart = chart;
            this.answer = answer;
        }
    }

    private ChartOutcome generateChart(FrameWriter writer, ModelRef aiModel, String question,
                                       Map<String, Object> result, AtomicLong totalTokens,
                                       String sql, String mSchema, String chartType) throws IOException {
        String dataJson = chatRepository.writeJson(
                Rows.of("fields", result.get("fields"), "data", sampleRows(result.get("data"))));
        StringBuilder reasoning = new StringBuilder();
        String text = generateText(writer, aiModel,
                schemaContextBuilder.buildChartMessages(question, dataJson, sql, mSchema, chartType),
                "chart-result", totalTokens, null, reasoning);
        Map<String, Object> chart = null;
        if (text != null) {
            try {
                chart = chartBuilder.normalize(text);
            } catch (IllegalArgumentException e) {
                // A4：模型给出未知 chart type 属于产出错误；记录后回退确定性启发式构造，
                // 不把错误类型静默改成 table 写进 chart 配置。
                log.info("图表配置不可用（{}），回退启发式构造", e.getMessage());
            }
        }
        if (chart == null) {
            chart = chartBuilder.build(result, question);
        }
        return new ChartOutcome(chatRepository.writeJson(chart),
                chatRepository.writeJson(Rows.of("content", text == null ? "" : text,
                        "reasoning_content", reasoning.toString())));
    }

    /** 推荐问题（非流式；解析为字符串列表，失败返回空列表）。基础版不注入图表/历史上下文。 */
    private List<String> recommendQuestions(ModelRef aiModel, String question, int articlesNumber) {
        return recommendQuestions(aiModel, question, articlesNumber, null, null);
    }

    /**
     * 推荐问题（增强版，SQLBot 推荐问题模板增强）：注入图表类型提示与历史提问，
     * 使生成的追问更贴合当前可视化与对话上下文。
     */
    private List<String> recommendQuestions(ModelRef aiModel, String question, int articlesNumber,
                                            String chartHint, List<String> history) {
        return recommendQuestions(aiModel, question, articlesNumber, chartHint, history, null);
    }

    /** A12：推荐问题生成同样记录 token 用量（usage 非 null 时写入）。 */
    private List<String> recommendQuestions(ModelRef aiModel, String question, int articlesNumber,
                                            String chartHint, List<String> history, TokenUsage usage) {
        String text = modelClient.generate(aiModel.apiDomain, aiModel.apiKey, aiModel.baseModel,
                schemaContextBuilder.buildRecommendMessages(question, articlesNumber, chartHint, history),
                aiModel.params, toClientUsage(usage));
        return parseStringList(text, objectMapper);
    }

    /** TokenUsage → ChatModelClient.Usage（null 传入 null）。 */
    private static ChatModelClient.Usage toClientUsage(TokenUsage usage) {
        return usage == null ? null : new ChatModelClient.Usage();
    }

    /** 流式文本生成：逐段发帧，同时累积全文；失败返回 null。 */
    private String generateText(FrameWriter writer, ModelRef aiModel, List<ModelMessage> messages, String frameType,
                                AtomicLong totalTokens) throws IOException {
        return generateText(writer, aiModel, messages, frameType, totalTokens, null);
    }

    /**
     * A12：带步骤级 usage 的生成入口。
     *
     * @param stepUsage 非 null 时记录本步的 input/output/total（同时累加到 totalTokens）
     */
    private String generateText(FrameWriter writer, ModelRef aiModel, List<ModelMessage> messages, String frameType,
                                AtomicLong totalTokens, TokenUsage stepUsage) throws IOException {
        return generateText(writer, aiModel, messages, frameType, totalTokens, stepUsage, null);
    }

    /**
     * 流式文本生成：逐段发帧，同时累积全文；失败返回 null。
     *
     * @param reasoningOut 非 null 时把模型思考流（reasoning_content）累积进去（A26 chart_answer 用）
     */
    private String generateText(FrameWriter writer, ModelRef aiModel, List<ModelMessage> messages, String frameType,
                                AtomicLong totalTokens, TokenUsage stepUsage,
                                StringBuilder reasoningOut) throws IOException {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final AtomicReference<String> error = new AtomicReference<>(null);
        // A16: token 统计以最后一次 usage 为准，不逐帧累加。
        // 单条流内可能多次回调 usage（逐帧），这里只保留最后一次，待 onComplete 时一次性累加，
        // 避免逐帧求和导致 token 数虚高；跨步骤（SQL/图表/分析等）的 totals 仍各自累加。
        final AtomicReference<long[]> lastUsage = new AtomicReference<>(null);
        // §2.3: protocol（1=openai 兼容 / 2=vllm）随模型解析下发，端点形态（含 Azure）在 client 内分发
        modelClient.streamCompletion(aiModel.apiDomain, aiModel.apiKey, aiModel.baseModel, messages,
                aiModel.params, aiModel.protocol, new TokenHandler() {
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
                    public void onError(String message) {
                        error.set(message);
                    }

                    @Override
                    public void onUsage(long tokens) {
                        // A16: 单值 usage 记为最后一次，不逐帧累加
                        lastUsage.set(new long[] { tokens, tokens, tokens });
                    }

                    @Override
                    public void onUsageDetail(long inputTokens, long outputTokens, long total) {
                        // A16: 以最后一次 usage 为准，不逐帧累加
                        lastUsage.set(new long[] { inputTokens, outputTokens, total });
                        if (stepUsage != null) {
                            stepUsage.input = inputTokens;
                            stepUsage.output = outputTokens;
                            stepUsage.total = total;
                        }
                    }

                    @Override
                    public void onComplete() {
                        // A16: 流结束时按最后一次 usage 一次性累加（避免逐帧求和）
                        long[] u = lastUsage.get();
                        if (u != null && u[2] > 0) {
                            totalTokens.addAndGet(u[2]);
                        }
                    }
                });
        if (error.get() != null) {
            emit(writer, Rows.of("type", "info", "msg", error.get()));
            return null;
        }
        if (reasoningOut != null && reasoning.length() > 0) {
            reasoningOut.append(reasoning);
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

    /**
     * A12：会话标题回退值（当 SQL 生成 JSON 未给出 brief 时使用）。
     * 与 {@link #briefOf} 不同，这里保留完整问题、不做前 20 字截断。
     */
    static String fullBrief(String question) {
        if (question == null || question.trim().isEmpty()) {
            return "新会话";
        }
        String text = question.trim().replaceAll("\\s+", " ");
        return text.length() <= 50 ? text : text.substring(0, 50);
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
        logOperation(recordId, oid, uid, operate, startMillis,
                totalTokens <= 0L ? null : usageOf(0L, 0L, totalTokens), error, message);
    }

    /** A12：按步骤 usage 记录日志（input/output/total 三项落库）。 */
    private void logOperation(long recordId, long oid, long uid, String operate, long startMillis,
                              TokenUsage usage, boolean error, Map<String, Object> message) {
        if (recordId <= 0L) {
            return;
        }
        try {
            Timestamp start = new Timestamp(startMillis);
            Timestamp finish = new Timestamp(System.currentTimeMillis());
            chatRepository.insertLog(recordId, oid, uid, start, finish, finish.getTime() - start.getTime(),
                    usage == null ? 0L : usage.total, operate, true, error,
                    message == null ? null : chatRepository.writeJson(message),
                    usage == null ? 0L : usage.input, usage == null ? 0L : usage.output);
        } catch (Exception e) {
            log.info("对话日志写入失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * A14：带父记录 pid 的操作日志（pid 写入 message，便于 ExecutionDetails 关联步骤）。
     * 现有调用方均使用不带 pid 的重载，保持兼容。
     */
    private void logOperation(long recordId, long oid, long uid, String operate, long startMillis,
                              long totalTokens, boolean error, Map<String, Object> message, Long pid) {
        logOperation(recordId, oid, uid, operate, startMillis,
                totalTokens <= 0L ? null : usageOf(0L, 0L, totalTokens), error, message, pid);
    }

    /** A14：带 pid 的 TokenUsage 重载（pid 合并进 message 落库）。 */
    private void logOperation(long recordId, long oid, long uid, String operate, long startMillis,
                              TokenUsage usage, boolean error, Map<String, Object> message, Long pid) {
        if (pid != null) {
            Map<String, Object> withPid = new LinkedHashMap<String, Object>();
            if (message != null) {
                withPid.putAll(message);
            }
            withPid.put("pid", pid);
            message = withPid;
        }
        logOperation(recordId, oid, uid, operate, startMillis, usage, error, message);
    }

    private static TokenUsage usageOf(long input, long output, long total) {
        TokenUsage usage = new TokenUsage();
        usage.input = input;
        usage.output = output;
        usage.total = total;
        return usage;
    }

    private static String dsTypeOf(Map<String, Object> ds) {
        return ds == null ? null : Rows.str(ds, "type");
    }

    /**
     * 会话/记录归属校验：同时校验工作空间与创建人。
     *
     * <p>A1：管理员没有隐式 owner 豁免；跨用户操作只能走专门的系统管理端点。</p>
     */
    private void requireOwner(Map<String, Object> chat, Map<String, Object> user, String label) {
        long id = Rows.lng(chat, "id", 0L);
        // A4：跨工作空间/跨用户访问属于越权，返回 403
        if (Rows.lng(chat, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new com.xiongx9527.askdata.common.web.ForbiddenException(
                    label + " with id " + id + " does not belong to current workspace");
        }
        if (Rows.lng(chat, "create_by", 0L) != Rows.lng(user, "id", 0L)) {
            throw new com.xiongx9527.askdata.common.web.ForbiddenException(
                    label + " with id " + id + " not Owned by the current user");
        }
    }

    private int sizeOf(Object data) {
        return data instanceof List ? ((List<?>) data).size() : 0;
    }

    private static void emit(FrameWriter writer, Map<String, Object> frame) throws IOException {
        writer.write(frame);
    }

    /**
     * 错误帧（A26：对齐 SQLBot 的分型契约）。
     *
     * <p>前端 {@code views/chat/ErrorInfo.vue} 要求 content 是 JSON 字符串，含
     * {@code message}、{@code traceback}、{@code type}；{@code type} 为
     * {@code exec-sql-err} / {@code db-connection-err} 时展示友好文案与「查看详情」抽屉，
     * 其他类型回退通用错误。缺省 {@link #ERROR_TYPE_EXEC_SQL}。</p>
     */
    private static void emitError(FrameWriter writer, String message) throws IOException {
        emitError(writer, message, ERROR_TYPE_EXEC_SQL, null);
    }

    /**
     * 推荐问题失败帧（A12）：沿用 {@code recommended_question} 帧类型，附加 {@code code}/{@code msg}。
     *
     * <p>前端（{@code RecommendQuestion.vue} / {@code RecommendQuestionQuick.vue}）先判断
     * {@code data.code && data.code !== 200}，命中后提示 {@code msg} 并结束渲染，因此这里不再
     * 静默发送空数组。</p>
     */
    private static void emitRecommendError(FrameWriter writer, String message) throws IOException {
        emit(writer, Rows.of("type", "recommended_question", "content", "[]",
                "code", 500, "msg", message == null ? "" : message));
    }

    /** 异常堆栈转文本（错误帧 traceback 字段，供前端「查看详情」抽屉展示）。 */
    private static String stackTraceOf(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** 截断超长文本（日志观测用，避免整段模型输出刷屏）。 */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated, length=" + text.length() + ")";
    }

    private static void emitError(FrameWriter writer, String message, String type, String traceback)
            throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message == null ? "" : message);
        payload.put("traceback", traceback == null ? "" : traceback);
        payload.put("type", type == null ? ERROR_TYPE_EXEC_SQL : type);
        String content;
        try {
            content = OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            content = message == null ? "" : message;
        }
        emit(writer, Rows.of("type", "error", "content", content));
        emit(writer, Rows.of("type", "finish"));
    }
}
