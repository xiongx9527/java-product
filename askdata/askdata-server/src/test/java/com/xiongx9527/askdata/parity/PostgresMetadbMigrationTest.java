package com.xiongx9527.askdata.parity;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.chat.service.ChatService;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.controller.LoginController;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.xiongx9527.askdata.system.web.TokenVO;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A9：PostgreSQL 空库迁移、种子、登录与一次问数（embedded-postgres 真库）。
 *
 * <p>元数据库使用进程内临时 PostgreSQL（不依赖本机 Docker/psql）；问数目标库为独立 H2，
 * 模型由本地 OpenAI 兼容桩提供，验证迁移后的 Spring 上下文可用、Flyway 种子管理员可登录，
 * 且聊天链路能完成一次 SQL 生成/执行。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("postgresql")
class PostgresMetadbMigrationTest {

    private static final String TARGET_URL =
            "jdbc:h2:mem:pg_metadb_target;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static final String STUB_SQL =
            "SELECT \"status\", COUNT(*) AS \"cnt\" FROM \"orders\" GROUP BY \"status\" ORDER BY \"cnt\" DESC";

    /** A12 起 SQL 生成必须返回结构化 JSON，桩输出同步该契约。 */
    private static final String STUB_SQL_JSON = "{\"success\":true,\"sql\":\""
            + STUB_SQL.replace("\"", "\\\"") + "\",\"brief\":\"各状态订单数\","
            + "\"chart-type\":\"column\",\"tables\":[\"orders\"]}";

    private static final String STUB_CHART = "{\"type\":\"column\",\"title\":\"各状态订单数\","
            + "\"columns\":[{\"name\":\"status\",\"value\":\"status\"},{\"name\":\"cnt\",\"value\":\"cnt\"}],"
            + "\"axis\":{\"x\":{\"name\":\"status\",\"value\":\"status\"},"
            + "\"y\":{\"name\":\"cnt\",\"value\":\"cnt\"}}}";

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 embedded-postgres", e);
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("askdata.scheduled.enabled", () -> "false");
        registry.add("askdata.embedding.backfill-enabled", () -> "false");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoginController loginController;

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatQuestionService chatQuestionService;

    @Autowired
    private AiModelRepository aiModelRepository;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpServer llmServer;

    @BeforeEach
    void setUp() throws Exception {
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/v1/chat/completions", this::handleChatCompletions);
        llmServer.setExecutor(null);
        llmServer.start();
        aiModelRepository.insert(idGenerator.nextId(), "",
                "http://127.0.0.1:" + llmServer.getAddress().getPort(),
                1, 0, "parity-stub", 0, "gemma3", true, "[]", 1, System.currentTimeMillis());
        prepareTargetDatabase();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        StpUtil.logout();
        SaTokenContextMockUtil.clearContext();
        if (llmServer != null) {
            llmServer.stop(0);
        }
    }

    @Test
    void emptyPostgresMigratesSeedsLogsInAndAnswersOneQuestion() throws Exception {
        // 1) Flyway 从空库建表 + 写入种子
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history'", Integer.class);
        assertEquals(36, tables, "PostgreSQL 空库应迁移出 36 张元数据表");
        assertEquals(1, count("sys_user"));
        assertEquals(12, count("sys_arg"));
        assertEquals(3, count("system_variable"));
        assertEquals(19, count("core_field"));
        assertEquals(Arrays.asList("1", "2", "3", "4"), jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                        + "ORDER BY installed_rank", String.class));

        // 2) 种子管理员经真实登录控制器登录
        SaTokenContextMockUtil.setMockContext();
        Result<TokenVO> login = loginController.accessToken(
                CryptoUtil.encryptSqlbot("admin"), CryptoUtil.encryptSqlbot("SQLBot@123456"));
        assertNotNull(login.getData(), "登录应返回 TokenVO");
        assertNotNull(login.getData().getAccess_token(), "种子管理员应拿到主 token");

        // 3) 一次问数：模型桩生成 SQL → 在目标库执行 → 返回表格数据
        AuthContext context = new AuthContext();
        context.setCurrentUser(Rows.of("id", 1L, "account", "admin", "oid", 1L));
        AuthContext.set(context);
        long dsId = Rows.lng(datasourceService.add(Rows.of(
                "name", "pg parity target", "description", "", "type", "h2",
                "configuration", h2Configuration(),
                "tables", List.of(Rows.of("table_name", "orders", "table_comment", "订单表")))), "id", 0L);
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);

        Map<String, Object> answer = chatQuestionService.ask(Rows.of(
                "question", "每个状态的订单数", "chat_id", chatId, "return_img", false));
        assertEquals(Boolean.TRUE, answer.get("success"), answer.toString());
        assertEquals(STUB_SQL, answer.get("sql"), answer.toString());
        Map<?, ?> data = (Map<?, ?>) answer.get("data");
        assertNotNull(data, answer.toString());
        assertEquals(Arrays.asList("status", "cnt"), data.get("fields"), answer.toString());
        assertEquals(2, ((List<?>) data.get("data")).size(), answer.toString());
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private void prepareTargetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS \"orders\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"user_id\" BIGINT, \"amount\" DECIMAL(10,2), \"status\" VARCHAR(20))");
            statement.execute("DELETE FROM \"orders\"");
            statement.execute("INSERT INTO \"orders\" VALUES (1, 100, 99.50, 'PAID'),"
                    + " (2, 101, 12.00, 'PAID'), (3, 102, 30.00, 'NEW')");
        }
    }

    private String h2Configuration() {
        return "{\"host\":\"\",\"port\":0,\"username\":\"sa\",\"password\":\"\",\"database\":\""
                + TARGET_URL + "\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\",\"sheets\":[],"
                + "\"mode\":\"\",\"timeout\":10,\"lowVersion\":false,\"ssl\":false,\"poolSize\":5,"
                + "\"engine\":\"hive\"}";
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
        if (prompt.contains("请修正后只输出一条正确的 SQL") || prompt.contains("请只输出 SQL")) {
            return Arrays.asList("```json\n", STUB_SQL_JSON, "\n```");
        }
        if (prompt.contains("G2 图表配置 JSON")) {
            return List.of(STUB_CHART);
        }
        if (prompt.contains("个相关的追问问题")) {
            return List.of("[\"各状态订单金额是多少？\"]");
        }
        if (prompt.contains("对结果的分析")) {
            return List.of("PAID 与 NEW 两个状态的订单分布如下。");
        }
        if (prompt.contains("预测数据")) {
            return List.of("[{\"status\":\"FORECAST\",\"cnt\":5}]");
        }
        return List.of("[]");
    }

    private static String readBody(InputStream inputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int length;
        while ((length = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, length);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
