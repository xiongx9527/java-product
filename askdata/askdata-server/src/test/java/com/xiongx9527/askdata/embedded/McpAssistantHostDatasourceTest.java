package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A24：MCP {@code mcp_assistant} 的第三方数据接口链路（对齐 SQLBot {@code mcp_assistant}：
 * {@code configuration={"endpoint": url}} + {@code certificate=authorization} 构造虚拟助手，
 * 再经 {@code AssistantOutDs} 拉取数据源列表）——端点构造、凭证请求头、列表选取。
 * HttpServer 绑定 IPv6 回环（沙箱代理占 IPv4 127.0.0.1）。
 */
class McpAssistantHostDatasourceTest {

    private static HttpServer server;

    private static int port;

    private static final List<String> seenAuthorization = new CopyOnWriteArrayList<>();

    private static final DynamicDatasourceResolver resolver = new DynamicDatasourceResolver(
            new ObjectMapper(), messageResolver());

    private static MessageResolver messageResolver() {
        StaticMessageSource sms = new StaticMessageSource();
        sms.setUseCodeAsDefaultMessage(true);
        for (java.util.Locale locale : new java.util.Locale[] {java.util.Locale.ROOT,
                java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.ENGLISH,
                org.springframework.context.i18n.LocaleContextHolder.getLocale(),
                java.util.Locale.getDefault()}) {
            sms.addMessage("i18n_embedded.host_fetch_failed", locale, "宿主数据源拉取失败: {0}");
            sms.addMessage("i18n_embedded.dynamic_ds_advanced_only", locale, "仅高级应用支持动态数据源: {0}");
            sms.addMessage("i18n_miss_args", locale, "缺少参数: {0}");
        }
        return new MessageResolver(sms);
    }

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        server.createContext("/ds/list", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = ("{\"code\":0,\"data\":["
                    + "{\"id\":11,\"name\":\"crm-mysql\",\"type\":\"mysql\",\"host\":\"10.0.0.11\",\"port\":3306,"
                    + "\"user\":\"u\",\"password\":\"p\",\"dataBase\":\"crm\"},"
                    + "{\"id\":12,\"name\":\"crm-pg\",\"type\":\"pg\",\"host\":\"10.0.0.12\",\"port\":5432,"
                    + "\"user\":\"u\",\"password\":\"p\",\"dataBase\":\"crm\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ds/empty", exchange -> {
            byte[] body = "{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String url(String path) {
        return "http://[::1]:" + port + path;
    }

    @Test
    void bareAuthorizationBecomesAuthorizationHeader() {
        Map<String, Object> endpoint = resolver.hostEndpointFromMcp(url("/ds/list"), "Bearer abc123");
        assertEquals(url("/ds/list"), endpoint.get("endpoint"));
        assertEquals(10, endpoint.get("timeout"));
        Map<String, String> headers = cast(endpoint.get("headers"));
        assertEquals("Bearer abc123", headers.get("Authorization"));
    }

    @Test
    void jsonCertificateListBecomesHeaders() {
        Map<String, Object> endpoint = resolver.hostEndpointFromMcp(url("/ds/list"),
                "[{\"key\":\"x-de-token\",\"value\":\"t0ken\",\"target\":\"header\"},"
                        + "{\"key\":\"ignored\",\"value\":\"x\",\"target\":\"cookie\"}]");
        Map<String, String> headers = cast(endpoint.get("headers"));
        assertEquals("t0ken", headers.get("x-de-token"));
        assertEquals(1, headers.size(), "cookie 项不应进入请求头: " + headers);
    }

    @Test
    void missingUrlIsRejected() {
        assertThrows(BadRequestException.class, () -> resolver.hostEndpointFromMcp("  ", "Bearer x"));
    }

    @Test
    void firstFromHostListPicksFirstDatasourceAndSendsCredential() {
        Map<String, Object> endpoint = resolver.hostEndpointFromMcp(url("/ds/list"), "Bearer first");
        DynamicDatasource ds = resolver.resolveFirstFromHostList(1, endpoint);
        assertEquals(DatasourceType.MYSQL, ds.getType());
        assertEquals("crm-mysql", ds.getName());
        assertEquals("10.0.0.11", ds.getConf().getHost());
        assertTrue(seenAuthorization.contains("Bearer first"),
                "宿主应收到下发的 authorization: " + seenAuthorization);
    }

    @Test
    void datasourceIdSelectsMatchingEntry() {
        Map<String, Object> endpoint = resolver.hostEndpointFromMcp(url("/ds/list"), "Bearer by-id");
        DynamicDatasource ds = resolver.resolveFromHostList(1, endpoint, 12L);
        assertEquals(DatasourceType.PG, ds.getType());
        assertEquals("crm-pg", ds.getName());
        assertTrue(seenAuthorization.contains("Bearer by-id"));
    }

    @Test
    void unknownDatasourceIdFailsReadably() {
        Map<String, Object> endpoint = resolver.hostEndpointFromMcp(url("/ds/list"), "Bearer none");
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> resolver.resolveFromHostList(1, endpoint, 99L));
        assertTrue(ex.getMessage().contains("99"), ex.getMessage());
    }

    @Test
    void emptyHostListFailsReadably() {
        Map<String, Object> endpoint = resolver.hostEndpointFromMcp(url("/ds/empty"), "Bearer e");
        assertThrows(BadRequestException.class, () -> resolver.resolveFirstFromHostList(1, endpoint));
    }

    @Test
    void nonAdvancedApplicationRejected() {
        Map<String, Object> endpoint = resolver.hostEndpointFromMcp(url("/ds/list"), "Bearer x");
        assertThrows(BadRequestException.class, () -> resolver.resolveFirstFromHostList(0, endpoint));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> cast(Object raw) {
        return (Map<String, String>) raw;
    }
}
