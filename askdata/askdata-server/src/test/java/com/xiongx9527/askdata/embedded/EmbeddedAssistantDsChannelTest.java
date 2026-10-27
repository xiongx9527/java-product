package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;

/**
 * A17：嵌入助手按 token 的 assistant_id 加载；高级应用在线时实时取宿主 ds_list，
 * 宿主失败不回退；X-SQLBOT-ASSISTANT-ONLINE 切换在线/离线通道。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:embedded_ds_channel;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class EmbeddedAssistantDsChannelTest {

    @Autowired
    private AssistantController assistantController;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CurrentUserService currentUserService;

    private HttpServer server;

    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        doNothing().when(currentUserService).requireWsAdmin();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ds", exchange -> {
            byte[] body = ("{\"code\":0,\"data\":["
                    + "{\"id\":1001,\"name\":\"宿主订单库\",\"description\":\"host\",\"type\":\"mysql\"},"
                    + "{\"id\":1002,\"name\":\"宿主用户库\",\"description\":\"host\",\"type\":\"pg\"}"
                    + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void advancedAppOnlineFetchesHostDsList() throws Exception {
        long oid = 3001L;
        long assistantId = insertAssistant("advanced-online", 1, oid,
                config(oid, baseUrl + "/api/ds", true));
        insertDatasource(501L, "local-only", oid);

        List<Map<String, Object>> result = assistantController.datasources(assistantId, null).getData();

        assertEquals(2, result.size(), result.toString());
        assertTrue(result.stream().anyMatch(ds -> "宿主订单库".equals(ds.get("name"))));
        assertTrue(result.stream().anyMatch(ds -> "宿主用户库".equals(ds.get("name"))));
        assertFalse(result.stream().anyMatch(ds -> "local-only".equals(ds.get("name"))),
                "在线通道必须使用宿主 ds_list");
        // type_name 缺失时按类型补全
        assertTrue(result.stream().anyMatch(ds -> "MySQL".equals(ds.get("type_name"))));
    }

    @Test
    void hostFailureIsExplicitAndDoesNotFallback() throws Exception {
        long oid = 3002L;
        // 关闭的端口：连接必然失败
        long assistantId = insertAssistant("advanced-broken", 1, oid,
                config(oid, "http://127.0.0.1:1/api/ds", true));
        insertDatasource(502L, "local-fallback", oid);

        assertThrows(BadRequestException.class,
                () -> assistantController.datasources(assistantId, null));
    }

    @Test
    void onlineHeaderSwitchesChannel() throws Exception {
        long oid = 3003L;
        long assistantId = insertAssistant("advanced-switch", 1, oid,
                config(oid, baseUrl + "/api/ds", true));
        insertDatasource(503L, "local-switch", oid);

        List<Map<String, Object>> offline = assistantController.datasources(assistantId, "offline").getData();
        assertEquals(1, offline.size(), offline.toString());
        assertEquals("local-switch", offline.get(0).get("name"));

        List<Map<String, Object>> online = assistantController.datasources(assistantId, "online").getData();
        assertEquals(2, online.size(), online.toString());
        assertFalse(online.stream().anyMatch(ds -> "local-switch".equals(ds.get("name"))));
    }

    @Test
    void tokenAssistantIdWinsOverFirstType0Assistant() throws Exception {
        long firstOid = 4001L;
        long tokenOid = 4002L;
        insertAssistant("first-type0", 0, firstOid, config(firstOid, null, true));
        insertDatasource(601L, "first-type0-ds", firstOid);

        long tokenAssistantId = insertAssistant("token-type0", 0, tokenOid, config(tokenOid, null, true));
        insertDatasource(602L, "token-type0-ds", tokenOid);

        Map<String, Object> tokenAssistant = new LinkedHashMap<>();
        tokenAssistant.put("id", tokenAssistantId);
        tokenAssistant.put("type", 0);
        tokenAssistant.put("oid", tokenOid);
        tokenAssistant.put("configuration", config(tokenOid, null, true));
        AuthContext context = new AuthContext();
        context.setAssistant(tokenAssistant);
        AuthContext.set(context);

        List<Map<String, Object>> result = assistantController.datasources(null, null).getData();

        assertEquals(1, result.size(), result.toString());
        assertEquals(602L, ((Number) result.get(0).get("id")).longValue());
        assertFalse(result.stream().anyMatch(ds -> "first-type0-ds".equals(ds.get("name"))));
    }

    private String config(long oid, String endpoint, boolean online) throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("oid", oid);
        config.put("online", online);
        if (endpoint != null) {
            config.put("endpoint", endpoint);
        }
        return objectMapper.writeValueAsString(config);
    }

    private long insertAssistant(String name, int type, long oid, String configuration) {
        long id = System.nanoTime();
        jdbc.update("INSERT INTO sys_assistant (id, name, type, domain, description, configuration, "
                        + "create_time, app_id, app_secret, oid, enable_custom_model, custom_model) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, type, "example.com", "desc", configuration, 0L, "app", "secret", oid, false, null);
        return id;
    }

    private void insertDatasource(long id, String name, long oid) {
        jdbc.update("INSERT INTO core_datasource (id, name, description, type, type_name, configuration, "
                        + "create_time, create_by, status, num, oid, table_relation, embedding, recommended_config) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, "local", "mysql", "MySQL", "{}", null, oid, "Success", "0/0", oid, null, null, 1);
    }
}
