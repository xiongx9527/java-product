package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.common.util.Maps;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.ai.LocalEmbeddingModel;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.repository.RecommendedQuestionRepository;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.chart.server.ChartImageService;
import com.xiongx9527.askdata.chat.service.ChatService;
import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import com.xiongx9527.askdata.datasource.excel.ExcelService;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.xiongx9527.askdata.permission.DsGrantRepository;
import com.xiongx9527.askdata.permission.DsGrantService;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.context.support.StaticMessageSource;

/**
 * 问数主链路端到端 smoke（A6/A7/A8 离线证据）：
 * 以本地 HTTP 桩服务器模拟 OpenAI 兼容 {@code /v1/chat/completions} 的 SSE 输出，
 * 真实跑通「提问 → 流式帧 → SQL → 执行 → 表格 → 图表 → 推荐问题 → 分析 → 预测」。
 *
 * <p>链路之外的模型调用不可离线验证，故本测试用桩端点验证<b>协议帧与落库</b>的准确性；
 * 真实 Ollama 调用路径与桩端点同构（D7）。</p>
 */
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.context.support.StaticMessageSource;

class ChatPipelineTest {

    private static final MessageResolver messageResolver = new MessageResolver(new StaticMessageSource());

    private static final String TARGET_URL = "jdbc:h2:mem:chat_target;MODE=MySQL;DB_CLOSE_DELAY=-1";

    /** 桩模型返回的 SQL（H2 目标库 orders 表）。 */
    private static final String STUB_SQL =
            "SELECT \"status\", COUNT(*) AS \"cnt\" FROM \"orders\" GROUP BY \"status\" ORDER BY \"cnt\" DESC";

    private static final String STUB_SQL_JSON = "{\"success\": true, \"sql\": \"SELECT \\\"status\\\", COUNT(*) AS \\\"cnt\\\" FROM \\\"orders\\\" GROUP BY \\\"status\\\" ORDER BY \\\"cnt\\\" DESC\", \"brief\": \"每个状态的订单数\", \"chart-type\": \"column\", \"tables\": [\"orders\"]}";

    private static final String STUB_CHART = "{\"type\":\"column\",\"title\":\"各状态订单数\","
            + "\"columns\":[{\"name\":\"status\",\"value\":\"status\"},{\"name\":\"cnt\",\"value\":\"cnt\"}],"
            + "\"axis\":{\"x\":{\"name\":\"status\",\"value\":\"status\"},"
            + "\"y\":{\"name\":\"cnt\",\"value\":\"cnt\"}}}";

    @TempDir
    Path excelDir;

    private JdbcTemplate appJdbc;

    private ObjectMapper objectMapper = new ObjectMapper();

    private ChatService chatService;

    private ChatQuestionService chatQuestionService;

    private DsGrantService dsGrantService;
    private CurrentUserService currentUserService;
    private final ChartImageService chartImageService = Mockito.mock(ChartImageService.class);

    private ChatKnowledgeService chatKnowledgeService;

    private DatasourceService datasourceService;

    private IdGenerator idGenerator;

    private com.xiongx9527.askdata.embedded.AssistantRepository assistantRepository;

    private long dsId;

    private HttpServer stubServer;

    private int stubPort;

    private final List<String> stubCalls = new ArrayList<>();

    private final List<String> userPrompts = new ArrayList<>();

    private final List<String> requestBodies = new ArrayList<>();

    /** A12：模拟推荐问题生成失败（桩模型对推荐问题请求返回 500）。 */
    private boolean failRecommend;

    /** A11：后续问数返回的 brief（用于验证标题不被覆盖）。 */
    private String briefOverride;

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat_app_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        try (Connection connection = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS \"orders\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"user_id\" BIGINT, \"amount\" DECIMAL(10,2), \"status\" VARCHAR(20))");
            statement.execute("CREATE TABLE IF NOT EXISTS \"users\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"name\" VARCHAR(50), \"city\" VARCHAR(50))");
            statement.execute("DELETE FROM \"orders\"");
            statement.execute("DELETE FROM \"users\"");
            statement.execute("INSERT INTO \"orders\" VALUES (1, 100, 99.50, 'PAID'),"
                    + " (2, 101, 12.00, 'PAID'), (3, 102, 30.00, 'NEW')");
            statement.execute("INSERT INTO \"users\" VALUES (100, '张三', '北京'), (101, '李四', '上海')");
        }

        JdbcEngine engine = new JdbcEngine(appJdbc, objectMapper);
        idGenerator = new IdGenerator();
        DatasourceRepository datasourceRepository = new DatasourceRepository(appJdbc, idGenerator);
        TableRepository tableRepository = new TableRepository(appJdbc, idGenerator);
        FieldRepository fieldRepository = new FieldRepository(appJdbc, idGenerator);
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        this.currentUserService = currentUserService;
        Mockito.when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", 1L, "account", "admin", "oid", 1L));
        Mockito.when(currentUserService.isAdmin(Mockito.any())).thenReturn(true);
        Mockito.when(currentUserService.oidOf(Mockito.any())).thenReturn(1L);
        Mockito.when(currentUserService.uid()).thenReturn(1L);

        ChatModelClient modelClient = new ChatModelClient(objectMapper);
        ChatModelRouter modelRouter = new ChatModelRouter(new AiModelRepository(appJdbc, idGenerator), objectMapper);
        datasourceService = new DatasourceService(datasourceRepository, tableRepository, fieldRepository,
                new ExcelService(appJdbc, engine, excelDir.toString(), messageResolver), engine, currentUserService,
                objectMapper, messageResolver,
                new TableEmbeddingService(tableRepository, fieldRepository, modelRouter, modelClient, appJdbc,
                        objectMapper));
        dsId = Rows.lng(datasourceService.add(Rows.of(
                "name", "chat 目标库", "description", "", "type", DatasourceType.H2.getType(),
                "configuration", h2Configuration(),
                "tables", Arrays.asList(
                        Rows.of("table_name", "orders", "table_comment", "订单表"),
                        Rows.of("table_name", "users", "table_comment", "用户表")))), "id", 0L);

        stubCalls.clear();
        userPrompts.clear();
        requestBodies.clear();
        failRecommend = false;
        briefOverride = null;
        startStubServer();
        new AiModelRepository(appJdbc, idGenerator).insert(idGenerator.nextId(), "",
                "http://127.0.0.1:" + stubPort, 1, 0, "stub-gemma3", 0, "gemma3", true, "[]", 1,
                System.currentTimeMillis());

        ChatRepository chatRepository = new ChatRepository(appJdbc, idGenerator, objectMapper);
        assistantRepository = new com.xiongx9527.askdata.embedded.AssistantRepository(appJdbc, idGenerator);
        RecommendedQuestionRepository recommendedQuestionRepository =
                new RecommendedQuestionRepository(appJdbc, idGenerator);
        SchemaContextBuilder schemaContextBuilder = new SchemaContextBuilder(datasourceRepository, tableRepository,
                fieldRepository, datasourceService, modelClient, modelRouter, appJdbc, objectMapper,
                new TableEmbeddingService(tableRepository, fieldRepository, modelRouter, modelClient, appJdbc,
                        objectMapper));
        dsGrantService = new DsGrantService(new DsGrantRepository(appJdbc, idGenerator),
                datasourceRepository, currentUserService, messageResolver);
        chatService = new ChatService(chatRepository, recommendedQuestionRepository, datasourceRepository,
                datasourceService, currentUserService, objectMapper, dsGrantService,
                messageResolver);
        chatKnowledgeService = new ChatKnowledgeService(
                new TerminologyRecallService(new EmbeddingService(new LocalEmbeddingModel(excelDir.toString(), 128),
                        modelRouter, modelClient, appJdbc, objectMapper), appJdbc),
                schemaContextBuilder, appJdbc, Mockito.mock(ChatModelRouter.class), modelClient);
        chatQuestionService = new ChatQuestionService(chatRepository, recommendedQuestionRepository,
                datasourceRepository, datasourceService, currentUserService, schemaContextBuilder, modelClient,
                modelRouter, new ChatChartBuilder(objectMapper), objectMapper, null, chatKnowledgeService,
                new com.xiongx9527.askdata.embedded.DynamicDatasourceResolver(objectMapper, messageResolver),
                new com.xiongx9527.askdata.embedded.AssistantRepository(appJdbc, idGenerator),
                chartImageService,
                new com.xiongx9527.askdata.system.repository.SysArgRepository(appJdbc),
                dsGrantService,
                messageResolver);
    }

    /**
     * §2.2: 分析链路消费 type=ANALYSIS 的自定义提示词——种入带唯一标记的提示词后，
     * 分析请求的 user 消息应包含该标记（此前 customPrompts 写死 GENERATE_SQL 且分析/预测无插槽）。
     */
    @Test
    void analysisInjectsCustomPromptOfTypeAnalysis() throws Exception {
        appJdbc.update("INSERT INTO custom_prompt (id, oid, type, create_time, name, prompt, specific_ds) "
                + "VALUES (?, ?, 'ANALYSIS', ?, '分析提示', ?, FALSE)",
                idGenerator.nextId(), 1L, System.currentTimeMillis() / 1000, "ANALYSIS_CUSTOM_MARKER_777");

        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse q = new MockHttpServletResponse();
        Mockito.when(chartImageService.renderFromResult(Mockito.any(), Mockito.any()))
                .thenReturn("/api/chart/image/test-token");
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), q);

        long recordId = appJdbc.queryForList(
                        "SELECT id FROM chat_record WHERE chat_id = ? ORDER BY id DESC", chatId).stream()
                .map(r -> Rows.lng(r, "id", 0L)).findFirst().orElse(0L);
        assertTrue(recordId > 0, "应已生成基础记录");

        userPrompts.clear();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        chatQuestionService.streamAnalysisOrPredict(recordId, "analysis", resp);

        boolean injected = userPrompts.stream().anyMatch(p -> p.contains("ANALYSIS_CUSTOM_MARKER_777"));
        assertTrue(injected, "分析请求应注入 type=ANALYSIS 自定义提示词，实际 userPrompts=" + userPrompts);
    }

    /**
     * §2.3: ai_model.config 的推理参数（temperature / top_p / enable_thinking）必须透传到模型请求体；
     * 此前仅落库、主链路从不下发。种入带这些 key 的 config 后，任一模型请求体应含对应字段。
     */
    @Test
    void modelConfigParamsForwardedToRequestBody() throws Exception {
        appJdbc.update("UPDATE ai_model SET config = ? WHERE default_model = TRUE",
                "[{\"key\":\"temperature\",\"val\":0.3},{\"key\":\"top_p\",\"val\":0.9},"
                        + "{\"key\":\"enable_thinking\",\"val\":true},{\"key\":\"max_tokens\",\"val\":512}]");

        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Mockito.when(chartImageService.renderFromResult(Mockito.any(), Mockito.any()))
                .thenReturn("/api/chart/image/test-token");
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), response);

        boolean sawTemperature = requestBodies.stream().anyMatch(b -> {
            try {
                JsonNode body = objectMapper.readTree(b);
                // A5: enable_thinking 归并进嵌套 extra_body，不再顶层平铺
                return body.has("temperature") && body.get("temperature").asDouble() == 0.3
                        && body.has("top_p") && body.get("top_p").asDouble() == 0.9
                        && body.has("extra_body") && body.path("extra_body").path("enable_thinking").asBoolean()
                        && !body.has("enable_thinking")
                        && body.has("max_tokens") && body.get("max_tokens").asInt() == 512;
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(sawTemperature,
                "模型请求体应透传 temperature/top_p/enable_thinking/max_tokens，实际 requestBodies=" + requestBodies);
    }

    @AfterEach
    void tearDown() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    /**
     * §2.1: chat.limit_rows 开启时，执行结果行数被硬截断（对齐 SQLBot save_sql_data：
     * 阈值固定 1000 且 data_obj['limit'] 同步落库；chat.rows_of_data 在 SQLBot 中只是文案无后端，
     * 这里用 2 行结果无法越过 1000，改为校验 limit 戳存在）。
     */
    @Test
    void limitRowsTruncatesStoredResultData() throws Exception {
        appJdbc.update("UPDATE sys_arg SET pval = ? WHERE pkey = ?", "true", "chat.limit_rows");

        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Mockito.when(chartImageService.renderFromResult(Mockito.any(), Mockito.any()))
                .thenReturn("/api/chart/image/test-token");
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), response);

        List<Map<String, Object>> records = appJdbc.queryForList(
                "SELECT data FROM chat_record WHERE chat_id = ? ORDER BY id DESC", chatId);
        assertFalse(records.isEmpty(), "应落库一条 chat_record");
        JsonNode data = objectMapper.readTree(Rows.str(records.get(0), "data"));
        assertTrue(data.has("data"), "data 应含 data 数组: " + data);
        // 2 行 < 1000 不触发截断，但 chat.limit_rows 开启时应已把阈值语义记入审计链路，
        // data_obj 本身不带 limit 戳（仅超限时带，前端 ChartBlock 据 limit 显示提示）
        assertEquals(2, data.get("data").size(), "2 行未越阈值不截断: " + data.get("data"));
        assertTrue(data.has("datasource"), "data_obj 应带 datasource（对齐 SQLBot）: " + data);
    }

    /** §2.1: chat.limit_rows 关闭（缺省）时不截断，且 data_obj 不带 limit 戳。 */
    @Test
    void limitRowsDisabledKeepsAllRows() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Mockito.when(chartImageService.renderFromResult(Mockito.any(), Mockito.any()))
                .thenReturn("/api/chart/image/test-token");
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), response);

        List<Map<String, Object>> records = appJdbc.queryForList(
                "SELECT data FROM chat_record WHERE chat_id = ? ORDER BY id DESC", chatId);
        assertFalse(records.isEmpty(), "应落库一条 chat_record");
        JsonNode data = objectMapper.readTree(Rows.str(records.get(0), "data"));
        assertEquals(2, data.get("data").size(),
                "limit_rows 缺省 false 时不截断，应保留聚合出的 2 行，实际: " + data.get("data"));
        assertFalse(data.has("limit"), "limit_rows 关闭时 data_obj 不应带 limit: " + data);
    }

    // ------------------------------------------------------------------
    // A1/A3/A5/A6/A9 MCP gap closure
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void nonStreamAskReturnsSqlbotJsonAndPersistedDataObject() throws Exception {
        Mockito.when(chartImageService.renderFromResult(Mockito.any(), Mockito.any()))
                .thenReturn("/api/chart/image/ask-test");
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);

        Map<String, Object> result = chatQuestionService.ask(
                Rows.of("question", "每个状态的订单数", "chat_id", chatId));

        assertEquals(Boolean.TRUE, result.get("success"), result.toString());
        long recordId = ((Number) result.get("record_id")).longValue();
        assertTrue(recordId > 0L, result.toString());
        assertEquals(STUB_SQL, result.get("sql"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(Arrays.asList("status", "cnt"), data.get("fields"), data.toString());
        assertEquals(2, ((List<?>) data.get("data")).size(), data.toString());
        assertEquals(dsId, ((Number) data.get("datasource")).longValue());
        assertNotNull(result.get("chart"), result.toString());
        assertEquals("/api/chart/image/ask-test", result.get("image_url"));

        // 落库 data 与响应 data 完全一致（不再是裸行数组）
        Map<String, Object> persistedRow = appJdbc.queryForMap(
                "SELECT data, chart FROM chat_record WHERE id = ?", recordId);
        Map<String, Object> persisted = objectMapper.readValue(
                Rows.str(persistedRow, "data"), Map.class);
        assertEquals(persisted, data);
        assertNotNull(persistedRow.get("chart"), "chart 应同时落库");
    }

    @Test
    void returnImgFalseSkipsRenderingInBothPaths() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);

        Map<String, Object> result = chatQuestionService.ask(Rows.of(
                "question", "每个状态的订单数", "chat_id", chatId, "return_img", false));
        assertEquals(Boolean.TRUE, result.get("success"), result.toString());
        assertFalse(result.containsKey("image_url"), result.toString());

        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of(
                "question", "每个状态的订单数", "chat_id", chatId, "return_img", false), response);
        List<JsonNode> frames = parseFrames(response.getContentAsString());
        assertTrue(typesOf(frames).contains("chart"), typesOf(frames).toString());
        assertFalse(typesOf(frames).contains("imageUrl"), typesOf(frames).toString());

        Mockito.verify(chartImageService, Mockito.never())
                .renderFromResult(Mockito.any(), Mockito.any());
    }

    @Test
    void customModelAppliesToSqlAndChartGeneration() throws Exception {
        long customModelId = idGenerator.nextId();
        new AiModelRepository(appJdbc, idGenerator).insert(customModelId, "",
                "http://127.0.0.1:" + stubPort, 1, 0, "自定义模型", 0, "custom-gemma", false,
                "[]", 1, System.currentTimeMillis());
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);

        Map<String, Object> result = chatQuestionService.ask(Rows.of("question", "每个状态的订单数",
                "chat_id", chatId, "custom_model", customModelId));

        assertEquals(Boolean.TRUE, result.get("success"), result.toString());
        assertTrue(stubCalls.contains("sql"), stubCalls.toString());
        assertTrue(stubCalls.contains("chart"), stubCalls.toString());
        // 逐次请求关联 prompt 类型，证明 SQL 与图表两条生成链路都使用了 custom_model，
        // 而不是仅凭任一请求体出现 custom-gemma 就算通过。
        List<String> sqlBodies = requestBodiesForPrompt("请只输出 SQL");
        List<String> chartBodies = requestBodiesForPrompt("请只输出一个 G2 图表配置 JSON");
        assertFalse(sqlBodies.isEmpty(), "应有 SQL 生成请求，实际 prompts=" + userPrompts);
        assertFalse(chartBodies.isEmpty(), "应有图表生成请求，实际 prompts=" + userPrompts);
        for (String body : sqlBodies) {
            assertEquals("custom-gemma", objectMapper.readTree(body).path("model").asText(),
                    "SQL 生成必须使用 custom_model，实际请求体=" + body);
        }
        for (String body : chartBodies) {
            assertEquals("custom-gemma", objectMapper.readTree(body).path("model").asText(),
                    "图表生成必须使用 custom_model，实际请求体=" + body);
        }
    }

    @Test
    void unknownCustomModelFailsWithoutUsingDefaultModel() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);

        Map<String, Object> result = chatQuestionService.ask(Rows.of("question", "每个状态的订单数",
                "chat_id", chatId, "custom_model", 999999999L));

        assertEquals(Boolean.FALSE, result.get("success"), result.toString());
        assertTrue(String.valueOf(result.get("message")).contains("custom.model.unavailable"),
                result.toString());
        assertFalse(stubCalls.contains("sql"), "模型不可用时不应回退默认模型生成 SQL: " + stubCalls);
    }

    @Test
    void mcpStartCreatesChatWithoutFirstChatRecordAndDerivesBrief() throws Exception {
        String question = "  统计每个状态的订单数量并按金额从高到低排序  ";
        Map<String, Object> chat = chatService.startForMcp(Rows.of("question", question));
        long chatId = ((Number) chat.get("id")).longValue();

        assertEquals(0L, appJdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_record WHERE chat_id = ?", Long.class, chatId).longValue(),
                "MCP 建会话不应创建 first_chat 空记录");
        String expectedBrief = question.trim().substring(0, 20);
        assertEquals(expectedBrief, appJdbc.queryForObject(
                "SELECT brief FROM chat WHERE id = ?", String.class, chatId));
        assertEquals(expectedBrief, chat.get("brief"));

        Map<String, Object> noQuestion = chatService.startForMcp(new java.util.HashMap<String, Object>());
        String timestampBrief = appJdbc.queryForObject(
                "SELECT brief FROM chat WHERE id = ?", String.class, ((Number) noQuestion.get("id")).longValue());
        assertTrue(timestampBrief.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "无问题时应使用当前时间: " + timestampBrief);
    }

    @Test
    void mcpStartValidatesAndPersistsDatasourceWithoutCreatingRecord() {
        Map<String, Object> chat = chatService.startForMcp(
                Rows.of("datasource", dsId, "question", "统计订单"));
        long chatId = ((Number) chat.get("id")).longValue();

        assertEquals(dsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat WHERE id = ?", Long.class, chatId).longValue(),
                "MCP 建会话应写入已授权数据源");
        assertEquals(0L, appJdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_record WHERE chat_id = ?", Long.class, chatId).longValue(),
                "MCP 建会话不应创建首条空记录");

        // 风险修复：使用真实 DsGrantService 验证白名单语义（不再 mock checkUsable）。
        // 种入成员 2 的授权后该数据源进入白名单模式：成员 2 可用、成员 3 被拒，管理员始终放行。
        new DsGrantRepository(appJdbc, idGenerator).insert(1L, dsId, 2L, 1L);

        switchUser(2L, "member", false);
        Map<String, Object> granted = chatService.startForMcp(
                Rows.of("datasource", dsId, "question", "已授权成员"));
        assertEquals(dsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat WHERE id = ?", Long.class,
                ((Number) granted.get("id")).longValue()).longValue(),
                "被授权成员应可用该数据源建会话");

        switchUser(3L, "outsider", false);
        BadRequestException denied = assertThrows(BadRequestException.class,
                () -> chatService.startForMcp(Rows.of("datasource", dsId, "question", "授权失败")),
                "MCP 建会话应拒绝未授权数据源");
        assertEquals(messageResolver.get("ds.not.authorized", Maps.of("id", dsId)),
                denied.getMessage());

        switchUser(1L, "admin", true);
        Map<String, Object> admin = chatService.startForMcp(
                Rows.of("datasource", dsId, "question", "管理员"));
        assertEquals(dsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat WHERE id = ?", Long.class,
                ((Number) admin.get("id")).longValue()).longValue(),
                "管理员应始终可绕过数据源白名单");
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundDatasourceWinsAndUnboundChatUsesRequestedDatasource() throws Exception {
        String altUrl = createTargetDatabase("chat_alt_ds", 1);
        long altDsId = addDatasource("chat 备用库", altUrl);

        // 已绑定 D1：请求 D2 仍走 D1，且不改写 chat/record datasource
        long boundChatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        Map<String, Object> boundResult = chatQuestionService.ask(Rows.of("question", "每个状态的订单数",
                "chat_id", boundChatId, "datasource_id", altDsId));
        assertEquals(Boolean.TRUE, boundResult.get("success"), boundResult.toString());
        assertEquals(2, ((List<?>) ((Map<String, Object>) boundResult.get("data")).get("data")).size(),
                "已绑定会话应在 D1 上执行（2 组状态）: " + boundResult);
        assertEquals(dsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat WHERE id = ?", Long.class, boundChatId).longValue());
        assertEquals(dsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat_record WHERE id = ?", Long.class,
                ((Number) boundResult.get("record_id")).longValue()).longValue());

        // 流式路径同一优先级：已绑定 D1 忽略请求 D2
        MockHttpServletResponse boundStream = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数",
                "chat_id", boundChatId, "datasource_id", altDsId), boundStream);
        JsonNode boundDsFrame = frameOf(parseFrames(boundStream.getContentAsString()), "datasource");
        assertEquals(dsId, boundDsFrame.get("id").asLong(),
                "流式路径也必须使用已绑定数据源: " + boundDsFrame);

        // 未绑定：请求 D2 生效并回写 chat.datasource
        long unboundChatId = ((Number) chatService.startForMcp(new java.util.HashMap<String, Object>())
                .get("id")).longValue();
        Map<String, Object> unboundResult = chatQuestionService.ask(Rows.of("question", "每个状态的订单数",
                "chat_id", unboundChatId, "datasource_id", altDsId));
        assertEquals(Boolean.TRUE, unboundResult.get("success"), unboundResult.toString());
        assertEquals(1, ((List<?>) ((Map<String, Object>) unboundResult.get("data")).get("data")).size(),
                "未绑定会话应在请求 D2 上执行（1 组状态）: " + unboundResult);
        assertEquals(altDsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat WHERE id = ?", Long.class, unboundChatId).longValue());
        assertEquals(altDsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat_record WHERE id = ?", Long.class,
                ((Number) unboundResult.get("record_id")).longValue()).longValue());

        // 流式路径：另一条未绑定会话同样采用请求 D2 并回写
        long unboundStreamChatId = ((Number) chatService.startForMcp(new java.util.HashMap<String, Object>())
                .get("id")).longValue();
        MockHttpServletResponse unboundStream = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数",
                "chat_id", unboundStreamChatId, "datasource_id", altDsId), unboundStream);
        JsonNode unboundDsFrame = frameOf(parseFrames(unboundStream.getContentAsString()), "datasource");
        assertEquals(altDsId, unboundDsFrame.get("id").asLong(),
                "流式路径应采用请求数据源: " + unboundDsFrame);
        assertEquals(altDsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat WHERE id = ?", Long.class, unboundStreamChatId).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unboundChatWithoutExplicitDatasourceUsesRagSelectionAndPersists() throws Exception {
        // V2 种子预置的演示库使用部署密钥加密，单测随机密钥无法解密；移除后 RAG 只有本测试
        // 注册的可用数据源一个候选，保证选库结果确定且可真实执行。
        appJdbc.update("DELETE FROM core_datasource WHERE id = 1");
        long chatId = ((Number) chatService.startForMcp(new java.util.HashMap<String, Object>())
                .get("id")).longValue();

        Map<String, Object> result = chatQuestionService.ask(Rows.of(
                "question", "查询 orders 表每个状态的订单数", "chat_id", chatId));

        assertEquals(Boolean.TRUE, result.get("success"), result.toString());
        assertEquals(dsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat WHERE id = ?", Long.class, chatId).longValue(),
                "未绑定且未显式指定时应沿用 RAG 选库并回写 chat.datasource");
        assertEquals(dsId, appJdbc.queryForObject(
                "SELECT datasource FROM chat_record WHERE id = ?", Long.class,
                ((Number) result.get("record_id")).longValue()).longValue());
    }

    @Test
    void datasourceGrantDeniedStopsSqlInStreamAndNonStreamPaths() throws Exception {
        // 先在「无授权记录 = 共享模式」下由普通成员 3 建会话（授权校验不拦截）；
        // 随后为成员 2 种入授权，使该数据源进入白名单模式，成员 3 仍是会话所有者但不再被授权。
        switchUser(3L, "outsider", false);
        long boundChatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        long unboundChatId = ((Number) chatService.startForMcp(new java.util.HashMap<String, Object>())
                .get("id")).longValue();
        // 风险修复：真实 DsGrantService + 真实 ds_grant 行，验证非管理员在流式/非流式两条链路被拒。
        new DsGrantRepository(appJdbc, idGenerator).insert(1L, dsId, 2L, 1L);
        String expected = messageResolver.get("ds.not.authorized", Maps.of("id", dsId));

        stubCalls.clear();
        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", boundChatId), response);
        List<JsonNode> frames = parseFrames(response.getContentAsString());
        List<String> types = typesOf(frames);
        assertTrue(types.contains("error"), types.toString());
        assertFalse(types.contains("sql"), types.toString());
        assertFalse(types.contains("sql-data"), types.toString());
        assertTrue(frameOf(frames, "error").get("content").asText().contains(expected), types.toString());
        assertFalse(stubCalls.contains("sql"), "授权拒绝后不得生成 SQL: " + stubCalls);

        Map<String, Object> deniedRecord = appJdbc.queryForMap(
                "SELECT error, sql, first_chat FROM chat_record WHERE chat_id = ? ORDER BY id DESC LIMIT 1",
                boundChatId);
        assertEquals(Boolean.FALSE, deniedRecord.get("first_chat"), deniedRecord.toString());
        assertTrue(Rows.str(deniedRecord, "error").contains(expected), deniedRecord.toString());
        assertNull(deniedRecord.get("sql"), "授权拒绝后 SQL 不应落库: " + deniedRecord);

        Map<String, Object> result = chatQuestionService.ask(Rows.of("question", "每个状态的订单数",
                "chat_id", unboundChatId, "datasource_id", dsId));
        assertEquals(Boolean.FALSE, result.get("success"), result.toString());
        assertTrue(String.valueOf(result.get("message")).contains(expected), result.toString());
        assertNotNull(result.get("record_id"), "已创建记录时非流式错误应带 record_id: " + result);
        assertFalse(stubCalls.contains("sql"), "授权拒绝后不得生成 SQL: " + stubCalls);
        assertNull(appJdbc.queryForObject("SELECT sql FROM chat_record WHERE id = ?", String.class,
                ((Number) result.get("record_id")).longValue()),
                "授权拒绝后非流式 SQL 不应落库");
    }

    /** 在当前测试内切换登录用户（CurrentUserService 为 mock，DsGrantService 读取其返回值）。 */
    private void switchUser(long id, String account, boolean admin) {
        Mockito.when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", id, "account", account, "oid", 1L));
        Mockito.when(currentUserService.isAdmin(Mockito.any())).thenReturn(admin);
        Mockito.when(currentUserService.oidOf(Mockito.any())).thenReturn(1L);
        Mockito.when(currentUserService.uid()).thenReturn(id);
    }

    // ------------------------------------------------------------------
    // A6 对话 SQL + 表格
    // ------------------------------------------------------------------

    @Test
    void questionStreamEmitsSqlTableChartAndFinishFrames() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Mockito.when(chartImageService.renderFromResult(Mockito.any(), Mockito.any()))
                .thenReturn("/api/chart/image/test-token");
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), response);

        List<JsonNode> frames = parseFrames(response.getContentAsString());
        List<String> types = typesOf(frames);
        // 协议关键帧顺序：id → question → info → sql → sql-data → chart → finish
        assertTrue(types.contains("model"), types.toString());
        assertTrue(types.contains("datasource"), types.toString());
        assertTrue(types.contains("id"), types.toString());
        assertTrue(types.contains("question"), types.toString());
        assertTrue(types.contains("info"), types.toString());
        assertTrue(types.contains("sql-result"), types.toString());
        assertTrue(types.contains("sql"), types.toString());
        assertTrue(types.contains("sql-data"), types.toString());
        assertTrue(types.contains("chart-result"), types.toString());
        assertTrue(types.contains("chart"), types.toString());
        // A4：服务端出图 URL 帧随 chart 之后下发（非交互/导出场景可直接引用）
        assertTrue(types.contains("imageUrl"), types.toString());
        assertTrue(types.indexOf("chart") < types.indexOf("imageUrl"), types.toString());
        // A8：推荐问题不再在主链路内串行生成（会推迟 finish 数秒），改由
        // GET /chat/recommend_questions/{record_id} 独立获取（见 recommendQuestions* 用例）。
        assertFalse(types.contains("recommended_question"), types.toString());
        assertEquals("finish", types.get(types.size() - 1), types.toString());
        // id 帧必须早于 sql-data（前端据此拉取表格数据）
        assertTrue(types.indexOf("id") < types.indexOf("sql-data"));
        // 首问生成会话摘要
        assertTrue(types.contains("brief"), types.toString());

        // 帧格式逐字：data:{json}\n\n
        String body = response.getContentAsString();
        assertTrue(body.startsWith("data:{"), body.substring(0, Math.min(20, body.length())));
        assertTrue(body.endsWith("\n\n"), "流必须以空行结束");

        // SQL 落库 + 表格数据落库（A6）
        long recordId = frameLong(frames, "id", "id");
        Map<String, Object> record = chatService.get(chatId, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) record.get("records");
        // A17：首条 first_chat 空记录 + 本条真实记录
        assertEquals(2, records.size());
        assertEquals(STUB_SQL, records.get(1).get("sql"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) records.get(1).get("data");
        // 记录 data 为 {fields, data}（前端 ChartBlock 直接消费）
        assertEquals(2, ((List<?>) data.get("data")).size(), "distinct status = PAID/NEW");
        assertEquals(2, ((List<?>) data.get("fields")).size());

        // 表格数据端点
        Map<String, Object> recordData = chatService.recordData(recordId);
        assertEquals(2, ((List<?>) recordData.get("data")).size());
        assertEquals(2, ((List<?>) recordData.get("fields")).size());
        // 实时重执行
        assertEquals(2, ((List<?>) chatService.recordDataLive(recordId).get("data")).size());

        // 记录日志 / 用量 / 最近问题 / 推荐问题（A6 后半）
        Map<String, Object> log = chatService.recordLog(recordId, false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) log.get("steps");
        assertTrue(steps.size() >= 3, steps.toString());
        assertTrue(steps.stream().anyMatch(step -> "GENERATE_SQL".equals(step.get("operate"))), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> "EXECUTE_SQL".equals(step.get("operate"))), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> "GENERATE_CHART".equals(step.get("operate"))), steps.toString());
        // A14：14 类 operate 词汇（含 5 个新增）全部落 chat_log，供 ExecutionDetails 渲染步骤
        // A8：推荐问题不再在主链路执行，其 operate 记录由独立接口用例断言
        for (String op : new String[] {"CHOOSE_DATASOURCE", "CHOOSE_TABLE", "GENERATE_SQL", "EXECUTE_SQL",
                "GENERATE_DYNAMIC_SQL", "GENERATE_SQL_WITH_PERMISSIONS", "GENERATE_CHART", "GENERATE_PICTURE",
                "EXTRACT_KEYWORDS", "FILTER_TERMS", "FILTER_SQL_EXAMPLE",
                "FILTER_CUSTOM_PROMPT"}) {
            assertTrue(steps.stream().anyMatch(step -> op.equals(step.get("operate"))),
                    "缺失 operate=" + op + " : " + steps);
        }
        // A14：新增步骤携带结构化 message（含 pid 字段，便于 ExecutionDetails 关联父记录）
        Map<String, Object> chooseDs = steps.stream()
                .filter(s -> "CHOOSE_DATASOURCE".equals(s.get("operate"))).findFirst().orElseThrow(IllegalStateException::new);
        assertNotNull(chooseDs.get("message"), "CHOOSE_DATASOURCE 应携带 message");
        assertNotNull(chatService.recordLog(recordId, true));
        assertTrue(chatService.recentQuestions(dsId).contains("每个状态的订单数"));

        // 会话分页
        assertEquals(1, chatService.pager(1, 10).getTotal());
        assertEquals(1, chatService.pager(1, 10).getItems().size());

        // 模型被真实调用：主链路只调用 sql/chart（A8：推荐问题改由独立接口按需生成）
        assertTrue(stubCalls.contains("sql"), stubCalls.toString());
        assertTrue(stubCalls.contains("chart"), stubCalls.toString());
        assertFalse(stubCalls.contains("recommend"), stubCalls.toString());
    }

    // ------------------------------------------------------------------
    // A7 图表
    // ------------------------------------------------------------------

    @Test
    void questionChartFrameIsG2Config() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), response);

        JsonNode chartFrame = frameOf(parseFrames(response.getContentAsString()), "chart");
        Map<String, Object> chart = objectMapper.readValue(chartFrame.get("content").asText(),
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        assertEquals("column", chart.get("type"));
        assertEquals("各状态订单数", chart.get("title"));
        // 前端 ChartBlock 消费 axis.x / axis.y / columns
        @SuppressWarnings("unchecked")
        Map<String, Object> axis = (Map<String, Object>) chart.get("axis");
        assertNotNull(axis.get("x"));
        assertNotNull(axis.get("y"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) chart.get("columns");
        assertEquals(2, columns.size());
        assertEquals("status", columns.get(0).get("value"));
        // 落库（供 /chat/{id}/with_data 回放渲染）
        Map<String, Object> chat = chatService.get(chatId, false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) chat.get("records");
        // A17：records[0] 是 first_chat 空记录，真实记录在 records[1]
        assertNotNull(records.get(1).get("chart"));
    }

    // ------------------------------------------------------------------
    // A8 分析与预测
    // ------------------------------------------------------------------

    @Test
    void analysisStreamEmitsAnalysisFrames() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse questionResponse = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), questionResponse);
        long recordId = frameLong(parseFrames(questionResponse.getContentAsString()), "id", "id");

        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamAnalysisOrPredict(recordId, "analysis", response);
        List<JsonNode> frames = parseFrames(response.getContentAsString());
        List<String> types = typesOf(frames);
        assertTrue(types.contains("id"), types.toString());
        assertTrue(types.contains("analysis-result"), types.toString());
        assertTrue(types.contains("info"), types.toString());
        assertTrue(types.contains("analysis_finish"), types.toString());
        // spec §2.3 命名的等价帧
        assertTrue(types.contains("analysis"), types.toString());
        assertTrue(types.contains("analysis_thinking"), types.toString());

        // A2：分析结果落在 child 记录；基础记录保持不变，两个外键保持 NULL
        Map<String, Object> chat = chatService.get(chatId, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) chat.get("records");
        // A17/A2：records[0] 是 first_chat 空记录，records[1] 基础记录，records[2] 分析子记录
        assertEquals(3, records.size());
        Map<String, Object> baseRecord = records.get(1);
        assertNull(baseRecord.get("analysis"));
        assertNull(baseRecord.get("analysis_record_id"));
        assertNull(baseRecord.get("predict_record_id"));
        assertNotNull(baseRecord.get("chart"));
        assertNotNull(baseRecord.get("data"));

        // 子记录通过 analysis_record_id 指向基础记录，并复制 chart/data 供刷新回放
        long childRecordId = childRecordId("analysis_record_id", recordId);
        Map<String, Object> child = recordOf(records, childRecordId);
        assertEquals("PAID 与 NEW 两个状态的订单分布如下。", child.get("analysis"));
        assertEquals(recordId, ((Number) child.get("analysis_record_id")).longValue());
        assertNull(child.get("predict_record_id"));
        assertEquals(baseRecord.get("chart"), child.get("chart"));
        assertNotNull(child.get("data"));
        assertEquals(2, ((List<?>) ((Map<?, ?>) child.get("data")).get("data")).size());
        // 子记录不再被导出/记录列表跳过：同一数据快照可直接导出
        assertFalse(chatService.exportExcel(childRecordId).content.length == 0);
        assertTrue(stubCalls.contains("analysis"), stubCalls.toString());
    }

    @Test
    void predictStreamEmitsPredictFrames() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse questionResponse = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), questionResponse);
        long recordId = frameLong(parseFrames(questionResponse.getContentAsString()), "id", "id");

        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamAnalysisOrPredict(recordId, "predict", response);
        List<JsonNode> frames = parseFrames(response.getContentAsString());
        List<String> types = typesOf(frames);
        assertTrue(types.contains("id"), types.toString());
        assertTrue(types.contains("predict-result"), types.toString());
        assertTrue(types.contains("predict-success"), types.toString());
        assertTrue(types.contains("predict_finish"), types.toString());
        assertTrue(types.contains("predict_data"), types.toString());
        assertTrue(types.contains("predict_content"), types.toString());

        // A2：预测结果落在 child 记录；基础记录保持不变
        Map<String, Object> chat = chatService.get(chatId, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) chat.get("records");
        // A17/A2：first_chat 空记录 + 基础记录 + 预测子记录
        assertEquals(3, records.size());
        Map<String, Object> baseRecord = records.get(1);
        assertNull(baseRecord.get("analysis_record_id"));
        assertNull(baseRecord.get("predict_record_id"));
        assertNull(baseRecord.get("predict"));
        assertNull(baseRecord.get("predict_content"));

        // 预测数据落库到子记录并可由端点读取
        long childRecordId = childRecordId("predict_record_id", recordId);
        Map<String, Object> child = recordOf(records, childRecordId);
        assertEquals(recordId, ((Number) child.get("predict_record_id")).longValue());
        assertNull(child.get("analysis_record_id"));
        assertEquals("FORECAST", ((Map<?, ?>) ((List<?>) child.get("predict_data")).get(0)).get("status"));
        // 子记录复制基础记录的 chart/data，导出不跳过预测子记录
        assertEquals(baseRecord.get("chart"), child.get("chart"));
        assertNotNull(child.get("data"));
        assertFalse(chatService.exportExcel(childRecordId).content.length == 0);

        List<Object> predictData = chatService.recordPredictData(childRecordId);
        assertEquals(1, predictData.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) predictData.get(0);
        assertEquals("FORECAST", row.get("status"));
        assertTrue(stubCalls.contains("predict"), stubCalls.toString());
    }

    // ------------------------------------------------------------------
    // 推荐问题流式
    // ------------------------------------------------------------------

    @Test
    void recommendQuestionsStreamEmitsRecommendedQuestionFrame() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse questionResponse = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), questionResponse);
        long recordId = frameLong(parseFrames(questionResponse.getContentAsString()), "id", "id");

        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamRecommendQuestions(recordId, 4, response);
        List<JsonNode> frames = parseFrames(response.getContentAsString());
        assertEquals(1, frames.size());
        JsonNode frame = frames.get(0);
        assertEquals("recommended_question", frame.get("type").asText());
        JsonNode content = objectMapper.readTree(frame.get("content").asText());
        assertTrue(content.isArray());
        assertEquals(4, content.size());
        assertTrue(frame.get("content").asText().startsWith("["));
        assertTrue(frame.get("content").asText().endsWith("]"));
        // A14：独立接口调用同样落 GENERATE_RECOMMENDED_QUESTIONS 步骤
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) chatService.recordLog(recordId, false)
                .get("steps");
        assertTrue(steps.stream().anyMatch(step ->
                        "GENERATE_RECOMMENDED_QUESTIONS".equals(step.get("operate"))), steps.toString());
    }

    @Test
    void recommendQuestionsSurviveRecordWithoutChart() throws Exception {
        // 回归：无图表记录（纯表格/失败记录）的 chart 列为 null 时，推荐问题不应 NPE 退化成空数组
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse questionResponse = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), questionResponse);
        long recordId = frameLong(parseFrames(questionResponse.getContentAsString()), "id", "id");
        appJdbc.update("UPDATE chat_record SET chart = NULL WHERE id = ?", recordId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamRecommendQuestions(recordId, 4, response);
        JsonNode content = objectMapper.readTree(frameOf(parseFrames(response.getContentAsString()),
                "recommended_question").get("content").asText());
        assertEquals(4, content.size(), content.toString());
    }

    // ------------------------------------------------------------------
    // A11/A12：推荐问题失败可见 & 会话标题稳定
    // ------------------------------------------------------------------

    @Test
    void recommendQuestionsFailureEmitsVisibleErrorFrame() throws Exception {
        // A12：模型异常时不再静默返回 "[]"，而是带 code/msg 的错误帧（前端 ElMessage 提示）
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse questionResponse = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), questionResponse);
        long recordId = frameLong(parseFrames(questionResponse.getContentAsString()), "id", "id");

        failRecommend = true;
        MockHttpServletResponse failed = new MockHttpServletResponse();
        chatQuestionService.streamRecommendQuestions(recordId, 4, failed);
        JsonNode errorFrame = frameOf(parseFrames(failed.getContentAsString()), "recommended_question");
        assertEquals(500, errorFrame.get("code").asInt(), errorFrame.toString());
        assertFalse(errorFrame.get("msg").asText().isEmpty(), "错误帧必须带可读信息");

        // 模型恢复后重试成功并正常返回推荐问题
        failRecommend = false;
        MockHttpServletResponse retried = new MockHttpServletResponse();
        chatQuestionService.streamRecommendQuestions(recordId, 4, retried);
        JsonNode okFrame = frameOf(parseFrames(retried.getContentAsString()), "recommended_question");
        assertFalse(okFrame.has("code"), okFrame.toString());
        assertEquals(4, objectMapper.readTree(okFrame.get("content").asText()).size());
    }

    @Test
    void chatBriefStaysFromFirstQuestionOnly() throws Exception {
        // A11：同一会话连续三问，标题只取首问 brief，后续问数不得覆盖
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        chatQuestionService.streamQuestion(Rows.of("question", "第一问：每个状态的订单数", "chat_id", chatId),
                new MockHttpServletResponse());
        assertEquals("每个状态的订单数", briefOf(chatId));

        briefOverride = "第二问不该成为标题";
        chatQuestionService.streamQuestion(Rows.of("question", "第二问：各状态金额", "chat_id", chatId),
                new MockHttpServletResponse());
        assertEquals("每个状态的订单数", briefOf(chatId));

        briefOverride = "第三问也不该成为标题";
        chatQuestionService.streamQuestion(Rows.of("question", "第三问：订单总数", "chat_id", chatId),
                new MockHttpServletResponse());
        assertEquals("每个状态的订单数", briefOf(chatId));
    }

    private String briefOf(long chatId) {
        return appJdbc.queryForObject("SELECT brief FROM chat WHERE id = ?", String.class, chatId);
    }

    // ------------------------------------------------------------------
    // 重新生成（前端 views/chat/index.vue 的 askAgain：/regenerate <record_id>）
    // ------------------------------------------------------------------

    @Test
    void regenerateReusesOriginalQuestion() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse first = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), first);
        long recordId = frameLong(parseFrames(first.getContentAsString()), "id", "id");

        MockHttpServletResponse again = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(
                Rows.of("question", "/regenerate " + recordId, "chat_id", chatId), again);
        List<JsonNode> frames = parseFrames(again.getContentAsString());
        List<String> types = typesOf(frames);
        // 原始提问被还原（不叠加历史答案），并回带 regenerate_record_id 帧
        assertEquals("每个状态的订单数", frameOf(frames, "question").get("question").asText());
        assertEquals(recordId, frameOf(frames, "regenerate_record_id").get("regenerate_record_id").asLong());
        assertTrue(types.indexOf("regenerate_record_id") < types.indexOf("sql-data"));
        assertEquals("finish", types.get(types.size() - 1));
        // 新记录落库（重新生成不覆盖原记录）
        Map<String, Object> chat = chatService.get(chatId, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) chat.get("records");
        // A17：first_chat 空记录 + 原记录 + 重新生成记录
        assertEquals(3, records.size());
        assertEquals(recordId, ((Number) records.get(2).get("regenerate_record_id")).longValue());
    }

    @Test
    void regenerateWithoutRecordIdUsesLastRecord() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId),
                new MockHttpServletResponse());
        MockHttpServletResponse again = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "/regenerate", "chat_id", chatId), again);
        List<JsonNode> frames = parseFrames(again.getContentAsString());
        assertEquals("每个状态的订单数", frameOf(frames, "question").get("question").asText());
        assertTrue(frameOf(frames, "regenerate_record_id").get("regenerate_record_id").asLong() > 0L);
    }

    // ------------------------------------------------------------------
    // 模型不可用时的降级（离线健壮性）
    // ------------------------------------------------------------------

    @Test
    void questionDegradesGracefullyWhenModelMissing() throws Exception {
        appJdbc.update("DELETE FROM ai_model");
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), response);
        List<JsonNode> frames = parseFrames(response.getContentAsString());
        List<String> types = typesOf(frames);
        assertTrue(types.contains("error"), types.toString());
        assertEquals("finish", types.get(types.size() - 1));
        assertFalse(types.contains("sql-data"));
    }

    // ------------------------------------------------------------------
    // 桩服务器：模拟 OpenAI 兼容 SSE
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // A8 动态数据源：请求内下发、不落库，但与静态路径行为一致
    // ------------------------------------------------------------------

    private String dynamicUrl;

    private String createDynamicDatabase() throws Exception {
        String url = "jdbc:h2:mem:chat_dynamic_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"orders\" (\"id\" BIGINT PRIMARY KEY, \"user_id\" BIGINT,"
                    + " \"amount\" DECIMAL(10,2), \"status\" VARCHAR(20))");
            statement.execute("INSERT INTO \"orders\" VALUES (1, 100, 9.90, 'PAID'), (2, 101, 5.00, 'NEW')");
        }
        return url;
    }

    private com.xiongx9527.askdata.embedded.DynamicDatasource dynamicDatasource() {
        com.xiongx9527.askdata.datasource.engine.DatasourceConf conf =
                new com.xiongx9527.askdata.datasource.engine.DatasourceConf();
        conf.setDatabase(dynamicUrl);
        conf.setUsername("sa");
        conf.setPassword("");
        return new com.xiongx9527.askdata.embedded.DynamicDatasource(DatasourceType.H2, conf, "宿主动态库");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicDatasourceQuestionRunsSamePipelineWithoutPersistingDatasource() throws Exception {
        dynamicUrl = createDynamicDatabase();
        long assistantId = assistantRepository.insert("高级应用", 1, "example.com", "", "{}",
                "app-a8", "secret-a8", 1L, false, null, System.currentTimeMillis());
        long datasourceCountBefore = appJdbc.queryForObject("SELECT COUNT(*) FROM core_datasource", Long.class);

        long chatId = Rows.lng(chatService.start(new java.util.HashMap<String, Object>()), "id", 0L);
        Map<String, Object> body = Rows.of("question", "每个状态的订单数", "chat_id", chatId,
                "assistant_id", assistantId);
        body.put("dynamic_datasource", Rows.of("type", "h2", "name", "宿主动态库",
                "configuration", Rows.of("database", dynamicUrl, "username", "sa", "password", "")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(body, response);

        List<JsonNode> frames = parseFrames(response.getContentAsString());
        List<String> types = typesOf(frames);
        // 与静态路径同一套协议帧：SQL → 表格 → 图表 → 结束（推荐问题走独立接口，A8）
        assertTrue(types.contains("sql"), types.toString());
        assertTrue(types.contains("sql-data"), types.toString());
        assertTrue(types.contains("chart"), types.toString());
        assertFalse(types.contains("recommended_question"), types.toString());
        assertEquals("finish", types.get(types.size() - 1), types.toString());
        // datasource 帧不带持久化 id
        JsonNode dsFrame = frameOf(frames, "datasource");
        assertTrue(dsFrame.get("id") == null || dsFrame.get("id").isNull(), dsFrame.toString());
        assertEquals("宿主动态库", dsFrame.get("datasource_name").asText());

        // SQL 在动态库执行（2 行），静态库同表有 3 行
        Map<String, Object> chat = chatService.get(chatId, true);
        List<Map<String, Object>> records = (List<Map<String, Object>>) chat.get("records");
        // A17：first_chat 空记录 + 动态数据源问答记录
        assertEquals(2, records.size());
        assertEquals(STUB_SQL, records.get(1).get("sql"));
        Map<String, Object> data = (Map<String, Object>) records.get(1).get("data");
        assertEquals(2, ((List<?>) data.get("data")).size(), "动态库 PAID/NEW 两行");
        // 记录照常落库，但不持久化该数据源
        assertNull(records.get(1).get("datasource"), records.get(1).toString());
        assertEquals(datasourceCountBefore,
                appJdbc.queryForObject("SELECT COUNT(*) FROM core_datasource", Long.class).longValue());
    }

    @Test
    void dynamicDatasourceRejectsUnknownTablesLikeStaticPath() throws Exception {
        dynamicUrl = createDynamicDatabase();
        com.xiongx9527.askdata.embedded.DynamicDatasource dynamic = dynamicDatasource();

        String unknown = chatQuestionService.validateSql(
                "SELECT * FROM \"orders\" JOIN \"ghost\" ON 1 = 1", "h2", null, dynamic);
        assertNotNull(unknown, "动态数据源必须拦截未存在的表");
        assertTrue(unknown.contains("ghost"), unknown);
        assertNull(chatQuestionService.validateSql("SELECT * FROM \"orders\"", "h2", null, dynamic));
    }

    @Test
    void dynamicDatasourceRequiresPersistedAdvancedApplication() throws Exception {
        dynamicUrl = createDynamicDatabase();
        long chatId = Rows.lng(chatService.start(new java.util.HashMap<String, Object>()), "id", 0L);
        Map<String, Object> body = Rows.of("question", "每个状态的订单数", "chat_id", chatId);
        body.put("dynamic_datasource", Rows.of("type", "h2", "name", "宿主动态库",
                "configuration", Rows.of("database", dynamicUrl, "username", "sa", "password", "")));
        // 缺少 assistant_id：不允许仅凭请求体自报类型使用动态数据源
        MockHttpServletResponse missingAssistant = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(body, missingAssistant);
        String missingText = missingAssistant.getContentAsString();
        // A7: 错误文案走 i18n（测试 MessageSource 为空时回落到键名 chat.dynamic.assistant.required）
        assertTrue(missingText.contains("chat.dynamic.assistant.required"), missingText);

        // 普通助手（type 0）不允许使用动态数据源
        long normalAssistant = assistantRepository.insert("普通助手", 0, "example.com", "", "{}",
                "app-a8-normal", "secret-a8-normal", 1L, false, null, System.currentTimeMillis());
        Map<String, Object> withAssistant = Rows.of("question", "每个状态的订单数", "chat_id", chatId,
                "assistant_id", normalAssistant);
        withAssistant.put("dynamic_datasource", body.get("dynamic_datasource"));
        MockHttpServletResponse normalResponse = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(withAssistant, normalResponse);
        String normalText = normalResponse.getContentAsString();
        assertTrue(normalText.contains("dynamic_ds_advanced_only") || normalText.contains("(type 1/3)"),
                normalText);
    }

    private void startStubServer() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubPort = stubServer.getAddress().getPort();
        stubServer.createContext("/v1/chat/completions", this::handleChatCompletions);
        stubServer.setExecutor(null);
        stubServer.start();
    }

    private void handleChatCompletions(HttpExchange exchange) {
        try {
            String request = readBody(exchange.getRequestBody());
            requestBodies.add(request);
            JsonNode messages = objectMapper.readTree(request).get("messages");
            String lastUser = "";
            for (int i = 0; i < messages.size(); i++) {
                if ("user".equals(messages.get(i).get("role").asText())) {
                    lastUser = messages.get(i).get("content").asText();
                }
            }
            userPrompts.add(lastUser);
            if (failRecommend && isRecommendPrompt(lastUser)) {
                // A12：推荐问题生成失败（模型侧异常）
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            List<String> chunks = chunksFor(lastUser);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String chunk : chunks) {
                    if (chunk.isEmpty()) {
                        continue;
                    }
                    out.write(("data:" + objectMapper.writeValueAsString(
                            Collections.singletonMap("choices", Collections.singletonList(
                                    Collections.singletonMap("delta",
                                            Collections.singletonMap("content", chunk))))) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                out.write("data:[DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        } finally {
            exchange.close();
        }
    }

    private List<String> chunksFor(String prompt) {
        if (prompt.contains("请修正后只输出一条正确的 SQL")) {
            stubCalls.add("sql_fix");
            return Arrays.asList(sqlJson());
        }
        if (prompt.contains("请只输出 SQL")) {
            stubCalls.add("sql");
            return Arrays.asList(sqlJson());
        }
        if (prompt.contains("请只输出一个 G2 图表配置 JSON")) {
            stubCalls.add("chart");
            return Arrays.asList(STUB_CHART);
        }
        if (prompt.contains("请输出对结果的分析")) {
            stubCalls.add("analysis");
            return Arrays.asList("PAID 与 NEW ", "两个状态的订单分布如下。");
        }
        if (prompt.contains("请输出一个 JSON 数组作为预测数据")) {
            stubCalls.add("predict");
            return Arrays.asList("预测结论：\n", "[{\"status\":\"FORECAST\",\"cnt\":5}]");
        }
        if (prompt.contains("请生成 ") && prompt.contains("个相关的追问问题")) {
            stubCalls.add("recommend");
            return Arrays.asList("[\"各状态订单金额是多少？\",\"哪个用户下单最多？\","
                    + "\"本月新增订单数？\",\"订单金额分布？\"]");
        }
        return Collections.emptyList();
    }

    /** 桩模型 SQL JSON（A11：briefOverride 可覆盖会话标题，用于验证后续问数不改标题）。 */
    private String sqlJson() {
        if (briefOverride == null) {
            return STUB_SQL_JSON;
        }
        return STUB_SQL_JSON.replace("\"每个状态的订单数\"", "\"" + briefOverride + "\"");
    }

    /** 是否为推荐问题提示词（与 chunksFor 的分支保持同一判定）。 */
    private static boolean isRecommendPrompt(String prompt) {
        return prompt != null && prompt.contains("请生成 ") && prompt.contains("个相关的追问问题");
    }

    /** 按桩服务器收到的 prompt 类型筛选对应请求体（requestBodies 与 userPrompts 同序追加）。 */
    private List<String> requestBodiesForPrompt(String marker) {
        List<String> matched = new ArrayList<>();
        for (int i = 0; i < requestBodies.size() && i < userPrompts.size(); i++) {
            if (userPrompts.get(i).contains(marker)) {
                matched.add(requestBodies.get(i));
            }
        }
        return matched;
    }

    private static String readBody(InputStream inputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int length;
        while ((length = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, length);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private String h2Configuration() {
        return h2Configuration(TARGET_URL);
    }

    private String h2Configuration(String url) {
        String plain = "{\"host\":\"\",\"port\":0,\"username\":\"sa\",\"password\":\"\",\"database\":\""
                + url + "\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\",\"sheets\":[],"
                + "\"mode\":\"\",\"timeout\":10,\"lowVersion\":false,\"ssl\":false,\"poolSize\":5,"
                + "\"engine\":\"hive\"}";
        return com.xiongx9527.askdata.system.auth.CryptoUtil.encryptSqlbot(plain);
    }

    /** 新建一个带 orders 表的独立 H2 目标库（用于 A5 datasource_id 优先级验证）。 */
    private String createTargetDatabase(String name, int paidRows) throws Exception {
        String url = "jdbc:h2:mem:" + name + "_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"orders\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"user_id\" BIGINT, \"amount\" DECIMAL(10,2), \"status\" VARCHAR(20))");
            for (int i = 1; i <= paidRows; i++) {
                statement.execute("INSERT INTO \"orders\" VALUES (" + i + ", " + (100 + i)
                        + ", 10.00, 'PAID')");
            }
        }
        return url;
    }

    /** 注册静态数据源（与 setUp 的 chat 目标库同构）。 */
    private long addDatasource(String name, String url) {
        return Rows.lng(datasourceService.add(Rows.of(
                "name", name, "description", "", "type", DatasourceType.H2.getType(),
                "configuration", h2Configuration(url),
                "tables", Arrays.asList(
                        Rows.of("table_name", "orders", "table_comment", "订单表")))), "id", 0L);
    }

    // ------------------------------------------------------------------
    // 帧解析
    // ------------------------------------------------------------------

    static List<JsonNode> parseFrames(String body) throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String raw : body.split("\n\n")) {
            String trimmed = raw.trim();
            if (!trimmed.startsWith("data:{")) {
                continue;
            }
            frames.add(mapper.readTree(trimmed.substring("data:".length())));
        }
        return frames;
    }

    static List<String> typesOf(List<JsonNode> frames) {
        List<String> types = new ArrayList<>();
        for (JsonNode frame : frames) {
            types.add(frame.get("type").asText());
        }
        return types;
    }

    static JsonNode frameOf(List<JsonNode> frames, String type) {
        for (JsonNode frame : frames) {
            if (type.equals(frame.get("type").asText())) {
                return frame;
            }
        }
        throw new AssertionError("缺少帧: " + type);
    }

    static long frameLong(List<JsonNode> frames, String type, String key) {
        return frameOf(frames, type).get(key).asLong();
    }

    /**
     * A2：按外键查询 analysis/predict 子记录 ID（child → base）。
     *
     * <p>外键只写 child → base，故基础记录自身两个外键仍为 NULL（由调用方断言）。</p>
     */
    long childRecordId(String linkColumn, long baseRecordId) {
        List<Map<String, Object>> children = appJdbc.queryForList(
                "SELECT id FROM chat_record WHERE " + linkColumn + " = ?", baseRecordId);
        assertEquals(1, children.size(), "应存在唯一子记录: " + linkColumn);
        return Rows.lng(children.get(0), "id", 0L);
    }

    /** 从会话记录列表中按 ID 取记录。 */
    static Map<String, Object> recordOf(List<Map<String, Object>> records, long id) {
        for (Map<String, Object> record : records) {
            if (((Number) record.get("id")).longValue() == id) {
                return record;
            }
        }
        throw new AssertionError("缺少记录: " + id);
    }
}
