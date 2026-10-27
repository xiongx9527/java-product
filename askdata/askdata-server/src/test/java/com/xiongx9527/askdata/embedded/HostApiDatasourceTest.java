package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A23：高级应用数据源优先取宿主 API 下发结果（对齐 SQLBot AssistantOutDs）。
 */
class HostApiDatasourceTest {

    private HttpServer server;

    private String baseUrl;

    private String lastPath;

    private String lastAuth;

    private String lastCookie;

    private DynamicDatasourceResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ds", exchange -> {
            lastPath = exchange.getRequestURI().toString();
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            lastCookie = exchange.getRequestHeaders().getFirst("Cookie");
            byte[] body = responseBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/ds-enc", exchange -> {
            String cipher = com.xiongx9527.askdata.system.auth.CryptoUtil.encryptSqlbot("pw-secret");
            byte[] body = ("{\"code\":0,\"data\":[{\"id\":1003,\"name\":\"enc\",\"type\":\"mysql\","
                    + "\"configuration\":{\"host\":\"h\",\"port\":3306,\"username\":\"u\","
                    + "\"password\":\"" + cipher + "\",\"password_encrypted\":true}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        resolver = new DynamicDatasourceResolver(new ObjectMapper(),
                new com.xiongx9527.askdata.common.i18n.MessageResolver(new StaticMessageSource()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String responseBody() {
        return "{\"code\":0,\"data\":["
                + "{\"id\":1001,\"name\":\"宿主订单库\",\"type\":\"mysql\","
                + "\"configuration\":{\"host\":\"db.internal\",\"port\":3306,"
                + "\"username\":\"u\",\"password\":\"p\",\"database\":\"orders\"}},"
                + "{\"id\":1002,\"name\":\"宿主用户库\",\"type\":\"pg\","
                + "\"configuration\":{\"host\":\"pg.internal\",\"port\":5432,"
                + "\"username\":\"u\",\"password\":\"p\",\"database\":\"users\"}}"
                + "]}";
    }

    private Map<String, Object> assistant(String endpointJson) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("id", 9L);
        assistant.put("type", 1);
        assistant.put("oid", 1L);
        assistant.put("configuration", endpointJson);
        return assistant;
    }

    private String configJson(String endpoint, String certificate, int timeout) {
        return "{\"endpoint\":\"" + endpoint + "\","
                + "\"certificate\":" + certificate + ","
                + "\"timeout\":" + timeout + "}";
    }

    @Test
    void endpointAndCertificateAreParsed() {
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(configJson(
                baseUrl + "/api/ds",
                "[{\"target\":\"header\",\"key\":\"Authorization\",\"value\":\"Bearer host-token\"},"
                + "{\"target\":\"cookie\",\"key\":\"sid\",\"value\":\"x\"}]",
                15)));
        assertNotNull(endpoint);
        assertEquals(baseUrl + "/api/ds", endpoint.get("endpoint"));
        assertEquals(15, endpoint.get("timeout"));
        assertEquals("Bearer host-token",
                ((Map<?, ?>) endpoint.get("headers")).get("Authorization"));
        // cookie/param 项不作为请求头
        assertEquals(1, ((Map<?, ?>) endpoint.get("headers")).size());
    }

    @Test
    void noEndpointReturnsNull() {
        assertNull(resolver.hostEndpointOf(assistant("{\"timeout\":10}")));
        assertNull(resolver.hostEndpointOf(null));
        assertNull(resolver.hostEndpointOf(assistant("not-json")));
    }

    @Test
    void defaultTimeoutIsTenSeconds() {
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(
                "{\"endpoint\":\"" + baseUrl + "/api/ds\"}"));
        assertEquals(10, endpoint.get("timeout"));
    }

    @Test
    void datasourceIsResolvedFromHostList() {
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(configJson(
                baseUrl + "/api/ds", "[]", 10)));
        DynamicDatasource ds = resolver.resolveFromHostList(1, endpoint, 1001L);
        assertEquals("mysql", ds.typeName());
        assertEquals("宿主订单库", ds.getName());
        assertEquals("db.internal", ds.getConf().getHost());
        assertEquals(3306, ds.getConf().getPort());
        assertEquals("orders", ds.getConf().getDatabase());
        assertEquals("/api/ds", lastPath);
    }

    @Test
    void certificateHeadersAreSentToHost() {
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(configJson(
                baseUrl + "/api/ds",
                "[{\"target\":\"header\",\"key\":\"Authorization\",\"value\":\"Bearer host-token\"}]",
                10)));
        resolver.resolveFromHostList(1, endpoint, 1002L);
        assertEquals("Bearer host-token", lastAuth);
    }

    @Test
    void cookieAndParamCredentialsAreSent() {
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(configJson(
                baseUrl + "/api/ds",
                "[{\"target\":\"cookie\",\"key\":\"sid\",\"value\":\"s-1\"},"
                        + "{\"target\":\"param\",\"key\":\"tenant\",\"value\":\"t 1\"}]",
                10)));
        resolver.resolveFromHostList(1, endpoint, 1001L);

        assertEquals("sid=s-1", lastCookie);
        assertTrue(lastPath.contains("tenant=t+1") || lastPath.contains("tenant=t%201"),
                "param 凭证必须拼进查询串: " + lastPath);
    }

    @Test
    void encryptedCertificateValueIsDecrypted() {
        String cipher = com.xiongx9527.askdata.system.auth.CryptoUtil.encryptSqlbot("Bearer plain-token");
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(configJson(
                baseUrl + "/api/ds",
                "[{\"target\":\"header\",\"key\":\"Authorization\","
                        + "\"value\":\"aes:" + cipher + "\",\"encrypted\":true}]",
                10)));
        resolver.resolveFromHostList(1, endpoint, 1001L);

        assertEquals("Bearer plain-token", lastAuth);
    }

    @Test
    void encryptedDatasourcePasswordIsDecrypted() {
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(configJson(
                baseUrl + "/api/ds-enc", "[]", 10)));
        DynamicDatasource ds = resolver.resolveFromHostList(1, endpoint, 1003L);

        assertEquals("pw-secret", ds.getConf().getPassword());
        assertEquals("h", ds.getConf().getHost());
    }

    @Test
    void undecryptableCredentialIsRejected() {
        Map<String, Object> config = assistant(configJson(
                baseUrl + "/api/ds",
                "[{\"target\":\"header\",\"key\":\"Authorization\","
                        + "\"value\":\"aes:not-a-cipher\",\"encrypted\":true}]",
                10));
        assertThrows(RuntimeException.class, () -> resolver.hostEndpointOf(config));
    }

    @Test
    void unknownDatasourceIdIsRejected() {
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(configJson(
                baseUrl + "/api/ds", "[]", 10)));
        assertThrows(RuntimeException.class, () -> resolver.resolveFromHostList(1, endpoint, 9999L));
    }

    @Test
    void nonAdvancedApplicationIsRejected() {
        Map<String, Object> endpoint = resolver.hostEndpointOf(assistant(configJson(
                baseUrl + "/api/ds", "[]", 10)));
        assertThrows(RuntimeException.class, () -> resolver.resolveFromHostList(0, endpoint, 1001L));
        assertTrue(DynamicDatasourceResolver.isAdvancedApplication(1));
        assertTrue(DynamicDatasourceResolver.isAdvancedApplication(3));
    }

    @Test
    void missingEndpointIsRejected() {
        assertThrows(RuntimeException.class,
                () -> resolver.resolveFromHostList(1, null, 1001L));
    }
}
