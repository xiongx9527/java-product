package com.xiongx9527.askdata.scheduled;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Io;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A10：定时问数任务端到端集成测试（H2 应用库 + H2 目标库 + 本地模型/推送桩）。
 *
 * <p>真实跑通「保存任务 → 立即执行（复用 NL2SQL+执行）→ 导出 CSV → 落运行历史」，
 * 以及启停、失败记录、重试与 webhook 推送。</p>
 */
class ScheduledTaskIntegrationTest {

    private static final String TARGET_URL = "jdbc:h2:mem:sched_target;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static final String STUB_SQL =
            "SELECT \"status\", COUNT(*) AS \"cnt\" FROM \"orders\" GROUP BY \"status\" ORDER BY \"cnt\" DESC";

    @TempDir
    Path exportDir;

    private JdbcTemplate appJdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ScheduledTaskService service;

    private DatasourceService datasourceService;

    private long dsId;

    private HttpServer stubServer;

    private final AtomicReference<String> pushedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:sched_app_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
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
        MessageResolver messageResolver = new MessageResolver(new StaticMessageSource());
        datasourceService = new DatasourceService(datasourceRepository, tableRepository, fieldRepository,
                new ExcelService(appJdbc, engine, exportDir.toString(), messageResolver), engine, currentUserService,
                objectMapper, messageResolver,
                new TableEmbeddingService(tableRepository, fieldRepository, modelRouter, modelClient, appJdbc,
                        objectMapper));

        String plain = "{\"host\":\"\",\"port\":0,\"username\":\"sa\",\"password\":\"\",\"database\":\""
                + TARGET_URL + "\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\",\"sheets\":[],"
                + "\"mode\":\"\",\"timeout\":10,\"lowVersion\":false,\"ssl\":false,\"poolSize\":5,"
                + "\"engine\":\"hive\"}";
        dsId = Rows.lng(datasourceService.add(Rows.of(
                "name", "sched target", "description", "", "type", DatasourceType.H2.getType(),
                "configuration", com.xiongx9527.askdata.system.auth.CryptoUtil.encryptSqlbot(plain),
                "tables", Collections.singletonList(
                        Rows.of("table_name", "orders", "table_comment", "订单表")))), "id", 0L);

        startStubServer();
        new AiModelRepository(appJdbc, idGenerator).insert(idGenerator.nextId(), "",
                "http://127.0.0.1:" + stubServer.getAddress().getPort(), 1, 0, "stub-gemma3", 0,
                "gemma3", true, "[]", 1, System.currentTimeMillis());

        SchemaContextBuilder schemaContextBuilder = new SchemaContextBuilder(datasourceRepository, tableRepository,
                fieldRepository, datasourceService, modelClient, modelRouter, appJdbc, objectMapper,
                new TableEmbeddingService(tableRepository, fieldRepository, modelRouter, modelClient, appJdbc,
                        objectMapper));
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(false);
        properties.setExportDir(exportDir.toString());
        ScheduledTaskRepository repository = new ScheduledTaskRepository(appJdbc, idGenerator);
        ScheduledTaskExecutor executor = new ScheduledTaskExecutor(repository, datasourceService,
                schemaContextBuilder, modelClient, modelRouter, properties, objectMapper, messageResolver);
        service = new ScheduledTaskService(repository, executor, currentUserService, messageResolver);
    }

    @AfterEach
    void tearDown() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    @Test
    void runNowGeneratesSqlExecutesAndExportsCsv() throws Exception {
        long id = createTask("每日订单统计", "每个状态的订单数", "csv", null);
        Map<String, Object> run = service.runNow(id);

        assertEquals("SUCCESS", run.get("status"), run.toString());
        assertEquals(2, Rows.intg(run, "row_count", 0), run.toString());
        assertNotNull(run.get("sql"));
        assertTrue(String.valueOf(run.get("sql")).toUpperCase().contains("ORDERS"), run.toString());
        String exportPath = Rows.str(run, "export_path");
        assertNotNull(exportPath, run.toString());
        assertTrue(exportPath.endsWith(".csv"), exportPath);
        assertTrue(Files.exists(Paths.get(exportPath)), exportPath);

        List<Map<String, Object>> runs = service.runs(id);
        assertEquals(1, runs.size(), runs.toString());
        assertEquals("MANUAL", runs.get(0).get("trigger_type"));
        assertEquals("SUCCESS", runs.get(0).get("status"));
    }

    @Test
    void startAndStopToggleSchedule() {
        long id = createTask("interval task", "每个状态的订单数", null, null);
        Map<String, Object> started = service.start(id);
        assertTrue(Rows.bool(started, "enabled", false));
        assertNotNull(started.get("next_run_at"), started.toString());

        Map<String, Object> stopped = service.stop(id);
        assertFalse(Rows.bool(stopped, "enabled", true));
        assertNull(stopped.get("next_run_at"), stopped.toString());
    }

    @Test
    void failedRunIsRecordedAndRetryCreatesNewRun() {
        long id = createTask("broken task", "BROKEN 每个状态的订单数", null, null);
        Map<String, Object> failed = service.runNow(id);
        assertEquals("FAILED", failed.get("status"), failed.toString());
        assertNotNull(failed.get("error"), failed.toString());

        Map<String, Object> retried = service.retry(Rows.lng(failed, "id", 0L));
        assertEquals("RETRY", retried.get("trigger_type"), retried.toString());
        assertEquals("FAILED", retried.get("status"), retried.toString());
        assertEquals(2, service.runs(id).size(), service.runs(id).toString());
    }

    @Test
    void runPushesResultToWebhook() {
        String pushUrl = "http://127.0.0.1:" + stubServer.getAddress().getPort() + "/push";
        long id = createTask("push task", "每个状态的订单数", null, pushUrl);
        Map<String, Object> run = service.runNow(id);

        assertEquals("SUCCESS", run.get("status"), run.toString());
        assertNotNull(pushedBody.get(), "webhook should receive payload");
        try {
            JsonNode payload = objectMapper.readTree(pushedBody.get());
            assertEquals(2, payload.get("row_count").asInt(), pushedBody.get());
            assertTrue(payload.get("data").isArray(), pushedBody.get());
        } catch (Exception e) {
            throw new AssertionError("invalid push payload: " + pushedBody.get(), e);
        }
    }

    private long createTask(String name, String question, String exportFormat, String pushUrl) {
        Map<String, Object> body = Rows.of(
                "name", name, "question", question, "datasource_id", dsId,
                "interval_seconds", 3600, "enabled", false);
        if (exportFormat != null) {
            body.put("export_format", exportFormat);
        }
        if (pushUrl != null) {
            body.put("push_url", pushUrl);
        }
        return Rows.lng(service.save(body), "id", 0L);
    }

    private void startStubServer() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/v1/chat/completions", this::handleChatCompletions);
        stubServer.createContext("/push", this::handlePush);
        stubServer.setExecutor(null);
        stubServer.start();
    }

    private void handleChatCompletions(HttpExchange exchange) {
        try {
            String request = readBody(exchange.getRequestBody());
            JsonNode messages = objectMapper.readTree(request).get("messages");
            // 汇总所有 user 消息：回喂修复会追加消息，"BROKEN" 需在整段对话中持续生效，
            // 才能稳定模拟“模型始终返回非法 SQL”的持久失败场景。
            StringBuilder allUser = new StringBuilder();
            for (int i = 0; i < messages.size(); i++) {
                if ("user".equals(messages.get(i).get("role").asText())) {
                    allUser.append(messages.get(i).get("content").asText()).append('\n');
                }
            }
            List<String> chunks = chunksFor(allUser.toString());
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
                // ignore
            }
        } finally {
            exchange.close();
        }
    }

    private List<String> chunksFor(String prompt) {
        if (prompt.contains("BROKEN")) {
            return Arrays.asList("```sql\n", "DELETE FROM \"orders\"", "\n```");
        }
        return Arrays.asList("```sql\n", STUB_SQL, "\n```");
    }

    private void handlePush(HttpExchange exchange) {
        try {
            pushedBody.set(readBody(exchange.getRequestBody()));
            exchange.sendResponseHeaders(200, -1);
        } catch (Exception e) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (Exception ignored) {
                // ignore
            }
        } finally {
            exchange.close();
        }
    }

    private static String readBody(InputStream input) throws Exception {
        byte[] bytes = Io.readAll(input);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

