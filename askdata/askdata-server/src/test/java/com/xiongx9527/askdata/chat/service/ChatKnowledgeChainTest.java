package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.ai.LocalEmbeddingModel;
import com.xiongx9527.askdata.chart.server.ChartImageService;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.repository.RecommendedQuestionRepository;
import com.xiongx9527.askdata.chat.service.ChatKnowledgeService.KnowledgeContext;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
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
import com.xiongx9527.askdata.terminology.TerminologyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.xiongx9527.askdata.permission.DsGrantService;
import org.mockito.Mockito;
import org.springframework.context.support.StaticMessageSource;
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

/**
 * A2 问数知识增强链路端到端（离线证据）：以本地 HTTP 桩模型跑通
 * 「提问 → 关键词提取 → 术语/示例/提示词过滤 → SQL → 执行 → 图表 → finish」，
 * 验证术语命中时：
 * <ul>
 *   <li>{@code chat_record.extracted_keywords / expanded_keywords} 落库且非空；</li>
 *   <li>{@code chat_log} 记录 EXTRACT_KEYWORDS / FILTER_TERMS / FILTER_SQL_EXAMPLE /
 *       FILTER_CUSTOM_PROMPT 四类操作；</li>
 *   <li>SQL 生成提示词包含术语释义与同义词扩展（【业务术语】节）；</li>
 *   <li><b>未配置任何 embedding 模型</b>时链路照常完成（词法降级，不报错）。</li>
 * </ul>
 */
class ChatKnowledgeChainTest {

    private static final MessageResolver messageResolver = new MessageResolver(new StaticMessageSource());

    private static final String TARGET_URL = "jdbc:h2:mem:chat_knowledge_target;MODE=MySQL;DB_CLOSE_DELAY=-1";

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

    private ChatRepository chatRepository;

    private long dsId;

    private HttpServer stubServer;

    private int stubPort;

    private final List<String> stubRequests = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat_knowledge_chain_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        try (Connection connection = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS \"orders\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"user_id\" BIGINT, \"amount\" DECIMAL(10,2), \"status\" VARCHAR(20))");
            statement.execute("DELETE FROM \"orders\"");
            statement.execute("INSERT INTO \"orders\" VALUES (1, 100, 99.50, 'PAID'),"
                    + " (2, 101, 12.00, 'PAID'), (3, 102, 30.00, 'NEW')");
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
                "name", "知识链路目标库", "description", "", "type", DatasourceType.H2.getType(),
                "configuration", h2Configuration(),
                "tables", Arrays.asList(
                        Rows.of("table_name", "orders", "table_comment", "订单表")))), "id", 0L);

        // 术语库：父术语「销售额」（释义）+ 同义词「GMV」；自定义提示词；SQL 示例
        TerminologyRepository terminologyRepository = new TerminologyRepository(appJdbc, objectMapper, idGenerator);
        long termId = terminologyRepository.insertParent(1L, "销售额", "指成交总金额（含税）", false, null, true, null,
                System.currentTimeMillis());
        terminologyRepository.insertChild(1L, termId, "GMV", false, null, true, null, System.currentTimeMillis());
        appJdbc.update("INSERT INTO custom_prompt (id, oid, type, create_time, name, prompt, specific_ds, "
                + "datasource_ids) VALUES (?, 1, 'GENERATE_SQL', 1, 'unit', ?, FALSE, NULL)",
                6002L, "金额类问题默认以元为单位");
        appJdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "enabled) VALUES (?, 1, ?, 1, ?, ?, TRUE)",
                6001L, dsId, "每个状态的销售额", "SELECT status, SUM(amount) FROM orders GROUP BY status");

        // 无 embedding：本地模型目录为空、无 model_type=1 模型（词法降级路径）
        EmbeddingService embeddingService = new EmbeddingService(
                new LocalEmbeddingModel(excelDir.toString(), 128), modelRouter, modelClient, appJdbc, objectMapper);
        assertFalse(embeddingService.available(), "测试环境不应配置 embedding 模型");

        stubRequests.clear();
        startStubServer();
        new AiModelRepository(appJdbc, idGenerator).insert(idGenerator.nextId(), "",
                "http://127.0.0.1:" + stubPort, 1, 0, "stub-gemma3", 0, "gemma3", true, "[]", 1,
                System.currentTimeMillis());

        chatRepository = new ChatRepository(appJdbc, idGenerator, objectMapper);
        RecommendedQuestionRepository recommendedQuestionRepository =
                new RecommendedQuestionRepository(appJdbc, idGenerator);
        SchemaContextBuilder schemaContextBuilder = new SchemaContextBuilder(datasourceRepository, tableRepository,
                fieldRepository, datasourceService, modelClient, modelRouter, appJdbc, objectMapper,
                new TableEmbeddingService(tableRepository, fieldRepository, modelRouter, modelClient, appJdbc,
                        objectMapper));
        chatKnowledgeService = new ChatKnowledgeService(
                new TerminologyRecallService(embeddingService, appJdbc), schemaContextBuilder, appJdbc,
                Mockito.mock(ChatModelRouter.class), modelClient);
        chatService = new ChatService(chatRepository, recommendedQuestionRepository, datasourceRepository,
                datasourceService, currentUserService, objectMapper, Mockito.mock(DsGrantService.class),
                messageResolver);
        chatQuestionService = new ChatQuestionService(chatRepository, recommendedQuestionRepository,
                datasourceRepository, datasourceService, currentUserService, schemaContextBuilder, modelClient,
                modelRouter, new ChatChartBuilder(objectMapper), objectMapper, null, chatKnowledgeService,
                new com.xiongx9527.askdata.embedded.DynamicDatasourceResolver(objectMapper, messageResolver),
                new com.xiongx9527.askdata.embedded.AssistantRepository(appJdbc, idGenerator),
                Mockito.mock(ChartImageService.class),
                new com.xiongx9527.askdata.system.repository.SysArgRepository(appJdbc),
                Mockito.mock(DsGrantService.class),
                messageResolver);
    }

    @AfterEach
    void tearDown() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // A2：术语命中 → 关键词落库 + 四类 chat_log + 提示词含释义/同义词
    // ------------------------------------------------------------------

    @Test
    void terminologyHitPopulatesRecordKeywordsLogsOperationsAndEnrichesSqlPrompt() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的销售额", "chat_id", chatId), response);

        List<JsonNode> frames = parseFrames(response.getContentAsString());
        List<String> types = typesOf(frames);
        // 无 embedding 模型的词法降级路径下链路照常完成
        assertTrue(types.contains("sql-data"), types.toString());
        assertTrue(types.contains("chart"), types.toString());
        assertEquals("finish", types.get(types.size() - 1), types.toString());
        long recordId = frameLong(frames, "id", "id");

        // chat_record.extracted_keywords / expanded_keywords 落库且非空
        String extracted = appJdbc.queryForObject(
                "SELECT extracted_keywords FROM chat_record WHERE id = ?", String.class, recordId);
        String expanded = appJdbc.queryForObject(
                "SELECT expanded_keywords FROM chat_record WHERE id = ?", String.class, recordId);
        assertEquals("销售额", extracted);
        assertEquals("销售额,GMV", expanded);

        // chat_log 记录 A2 四类操作
        List<Map<String, Object>> logs = chatRepository.listLogsByRecord(recordId);
        List<String> operates = new ArrayList<>();
        for (Map<String, Object> row : logs) {
            operates.add(Rows.str(row, "operate"));
        }
        assertTrue(operates.contains("EXTRACT_KEYWORDS"), operates.toString());
        assertTrue(operates.contains("FILTER_TERMS"), operates.toString());
        assertTrue(operates.contains("FILTER_SQL_EXAMPLE"), operates.toString());
        assertTrue(operates.contains("FILTER_CUSTOM_PROMPT"), operates.toString());

        // A3：降级原因随步骤落库（无 embedding 模型时术语召回走词法匹配，状态可观测而非静默）
        String filterTermsMessage = null;
        for (Map<String, Object> row : logs) {
            if ("FILTER_TERMS".equals(Rows.str(row, "operate"))) {
                filterTermsMessage = Rows.str(row, "message");
            }
        }
        assertNotNull(filterTermsMessage);
        assertTrue(filterTermsMessage.contains("degraded"), filterTermsMessage);
        assertTrue(filterTermsMessage.contains("terminology: embedding model unavailable"),
                filterTermsMessage);

        // 发给模型的 SQL 提示词包含业务术语（释义 + 同义词）、自定义提示词与 SQL 示例
        String sqlPrompt = sqlPromptOf();
        assertNotNull(sqlPrompt);
        assertTrue(sqlPrompt.contains("【业务术语】"), sqlPrompt);
        assertTrue(sqlPrompt.contains("指成交总金额（含税）"), sqlPrompt);
        assertTrue(sqlPrompt.contains("GMV"), sqlPrompt);
        assertTrue(sqlPrompt.contains("【自定义提示词】"), sqlPrompt);
        assertTrue(sqlPrompt.contains("金额类问题默认以元为单位"), sqlPrompt);
        assertTrue(sqlPrompt.contains("【可参考的 SQL 样例】"), sqlPrompt);
        assertTrue(sqlPrompt.contains("SELECT status, SUM(amount) FROM orders GROUP BY status"), sqlPrompt);
    }

    @Test
    void questionWithoutTermHitLeavesKeywordsEmptyButChainStillFinishes() throws Exception {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        chatQuestionService.streamQuestion(Rows.of("question", "每个状态的订单数", "chat_id", chatId), response);

        List<JsonNode> frames = parseFrames(response.getContentAsString());
        List<String> types = typesOf(frames);
        assertEquals("finish", types.get(types.size() - 1), types.toString());
        long recordId = frameLong(frames, "id", "id");
        assertEquals("", appJdbc.queryForObject(
                "SELECT extracted_keywords FROM chat_record WHERE id = ?", String.class, recordId));
        assertEquals("", appJdbc.queryForObject(
                "SELECT expanded_keywords FROM chat_record WHERE id = ?", String.class, recordId));
        // 四类操作仍然记录（空结果也是有效的过滤结果）
        List<String> operates = new ArrayList<>();
        for (Map<String, Object> row : chatRepository.listLogsByRecord(recordId)) {
            operates.add(Rows.str(row, "operate"));
        }
        assertTrue(operates.contains("EXTRACT_KEYWORDS"), operates.toString());
        assertTrue(operates.contains("FILTER_TERMS"), operates.toString());
        assertTrue(operates.contains("FILTER_SQL_EXAMPLE"), operates.toString());
        assertTrue(operates.contains("FILTER_CUSTOM_PROMPT"), operates.toString());
        // 无术语命中时提示词不含【业务术语】节
        assertFalse(sqlPromptOf().contains("【业务术语】"));
    }

    @Test
    void knowledgeContextIsReusableForLoggingShape() {
        // prepare 的返回结构即链路落库/日志所用结构（离线直接验证）
        KnowledgeContext context = chatKnowledgeService.prepare("每个状态的销售额", 1L, dsId);
        assertTrue(context.hasTerms());
        assertEquals("销售额", context.getExtractedKeywords());
        assertEquals(1, context.getTermGroups().size());
        assertEquals(1, context.getSqlExamples().size());
        assertEquals(1, context.getCustomPrompts().size());
    }

    // ------------------------------------------------------------------
    // 桩模型：OpenAI 兼容 SSE
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
            stubRequests.add(request);
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
        if (prompt.contains("请只输出 SQL")) {
            return Arrays.asList("```sql\n", STUB_SQL, "\n```");
        }
        if (prompt.contains("请只输出一个 G2 图表配置 JSON")) {
            return Arrays.asList(STUB_CHART);
        }
        if (prompt.contains("请生成 ") && prompt.contains("个相关的追问问题")) {
            return Arrays.asList("[\"各状态订单金额是多少？\",\"哪个用户下单最多？\","
                    + "\"本月新增订单数？\",\"订单金额分布？\"]");
        }
        return Collections.emptyList();
    }

    /** 桩收到的 SQL 生成请求的 user 消息内容。 */
    private String sqlPromptOf() {
        for (String request : stubRequests) {
            try {
                JsonNode messages = objectMapper.readTree(request).get("messages");
                for (int i = 0; i < messages.size(); i++) {
                    JsonNode message = messages.get(i);
                    if ("user".equals(message.get("role").asText())
                            && message.get("content").asText().contains("请只输出 SQL")) {
                        return message.get("content").asText();
                    }
                }
            } catch (Exception ignored) {
                // 跳过非 JSON 请求
            }
        }
        return null;
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
