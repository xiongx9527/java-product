package com.xiongx9527.askdata.chat.service;

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
import com.xiongx9527.askdata.chat.service.ChatService;
import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
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
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private ChatKnowledgeService chatKnowledgeService;

    private DatasourceService datasourceService;

    private long dsId;

    private HttpServer stubServer;

    private int stubPort;

    private final List<String> stubCalls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat_app_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
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
        IdGenerator idGenerator = new IdGenerator();
        DatasourceRepository datasourceRepository = new DatasourceRepository(appJdbc, idGenerator);
        TableRepository tableRepository = new TableRepository(appJdbc, idGenerator);
        FieldRepository fieldRepository = new FieldRepository(appJdbc, idGenerator);
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
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
        startStubServer();
        new AiModelRepository(appJdbc, idGenerator).insert(idGenerator.nextId(), "",
                "http://127.0.0.1:" + stubPort, 1, 0, "stub-gemma3", 0, "gemma3", true, "[]", 1,
                System.currentTimeMillis());

        ChatRepository chatRepository = new ChatRepository(appJdbc, idGenerator, objectMapper);
        RecommendedQuestionRepository recommendedQuestionRepository =
                new RecommendedQuestionRepository(appJdbc, idGenerator);
        SchemaContextBuilder schemaContextBuilder = new SchemaContextBuilder(datasourceRepository, tableRepository,
                fieldRepository, datasourceService, modelClient, modelRouter, appJdbc, objectMapper,
                new TableEmbeddingService(tableRepository, fieldRepository, modelRouter, modelClient, appJdbc,
                        objectMapper));
        chatService = new ChatService(chatRepository, recommendedQuestionRepository, datasourceRepository,
                datasourceService, currentUserService, objectMapper);
        chatKnowledgeService = new ChatKnowledgeService(
                new TerminologyRecallService(new EmbeddingService(new LocalEmbeddingModel(excelDir.toString(), 128),
                        modelRouter, modelClient, appJdbc, objectMapper), appJdbc),
                schemaContextBuilder, appJdbc, Mockito.mock(ChatModelRouter.class));
        chatQuestionService = new ChatQuestionService(chatRepository, recommendedQuestionRepository,
                datasourceRepository, datasourceService, currentUserService, schemaContextBuilder, modelClient,
                modelRouter, new ChatChartBuilder(objectMapper), objectMapper, null, chatKnowledgeService);
    }

    @AfterEach
    void tearDown() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // A6 对话 SQL + 表格
    // ------------------------------------------------------------------

    @Test
    void questionStreamEmitsSqlTableChartAndFinishFrames() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
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
        assertTrue(types.contains("recommended_question"), types.toString());
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
        assertEquals(1, records.size());
        assertEquals(STUB_SQL, records.get(0).get("sql"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) records.get(0).get("data");
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
        assertTrue(steps.stream().anyMatch(step -> "generate_sql".equals(step.get("operate"))), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> "execute_sql".equals(step.get("operate"))), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> "generate_chart".equals(step.get("operate"))), steps.toString());
        assertNotNull(chatService.recordLog(recordId, true));
        assertTrue(chatService.recentQuestions(dsId).contains("每个状态的订单数"));

        // 会话分页
        assertEquals(1, chatService.pager(1, 10).getTotal());
        assertEquals(1, chatService.pager(1, 10).getItems().size());

        // 模型被真实调用（桩端点收到 4 次：sql/chart/recommend）
        assertTrue(stubCalls.contains("sql"), stubCalls.toString());
        assertTrue(stubCalls.contains("chart"), stubCalls.toString());
        assertTrue(stubCalls.contains("recommend"), stubCalls.toString());
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
        assertNotNull(records.get(0).get("chart"));
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

        // 落库到基础记录（前端从 records[].analysis 回放）
        Map<String, Object> chat = chatService.get(chatId, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) chat.get("records");
        assertEquals("PAID 与 NEW 两个状态的订单分布如下。", records.get(0).get("analysis"));
        // 子记录不单独出现在会话记录列表
        assertEquals(1, records.size());
        assertNotNull(records.get(0).get("analysis_record_id"));
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

        // 预测数据落库并可由端点读取
        List<Object> predictData = chatService.recordPredictData(recordId);
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
        assertEquals(2, records.size());
        assertEquals(recordId, ((Number) records.get(1).get("regenerate_record_id")).longValue());
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
            JsonNode messages = objectMapper.readTree(request).get("messages");
            String lastUser = "";
            for (int i = 0; i < messages.size(); i++) {
                if ("user".equals(messages.get(i).get("role").asText())) {
                    lastUser = messages.get(i).get("content").asText();
                }
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
            return Arrays.asList("```sql\n", STUB_SQL, "\n```");
        }
        if (prompt.contains("请只输出 SQL")) {
            stubCalls.add("sql");
            return Arrays.asList("```sql\n", STUB_SQL, "\n```");
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
        String plain = "{\"host\":\"\",\"port\":0,\"username\":\"sa\",\"password\":\"\",\"database\":\""
                + TARGET_URL + "\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\",\"sheets\":[],"
                + "\"mode\":\"\",\"timeout\":10,\"lowVersion\":false,\"ssl\":false,\"poolSize\":5,"
                + "\"engine\":\"hive\"}";
        return com.xiongx9527.askdata.system.auth.CryptoUtil.encryptSqlbot(plain);
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
}
