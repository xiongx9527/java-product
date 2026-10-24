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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主 URL 形态动态数据源（对齐 SQLBot AssistantOutDsFactory 主动拉取）：
 * resolveFromHost 实时 GET 宿主 API（10s 超时）→ 返回 JSON → 走统一 resolve 链路；
 * 非 2xx / 非 JSON / 非高级应用均报错。HttpServer 绑定 IPv6 回环（沙箱代理占 IPv4 127.0.0.1）。
 */
class DynamicDatasourceResolverHostTest {

    private static HttpServer server;

    private static int port;

    private static final DynamicDatasourceResolver resolver = new DynamicDatasourceResolver(
            new ObjectMapper(), messageResolver());

    private static MessageResolver messageResolver() {
        StaticMessageSource sms = new StaticMessageSource();
        java.util.Locale def = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        sms.addMessage("i18n_embedded.host_fetch_failed", def, "宿主数据源拉取失败: {0}");
        sms.addMessage("i18n_embedded.host_fetch_failed", java.util.Locale.ROOT, "宿主数据源拉取失败: {0}");
        sms.addMessage("i18n_embedded.host_fetch_failed", java.util.Locale.SIMPLIFIED_CHINESE, "宿主数据源拉取失败: {0}");
        sms.addMessage("i18n_embedded.host_fetch_failed", java.util.Locale.ENGLISH, "Failed to fetch host datasource: {0}");
        return new MessageResolver(sms);
    }

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = ("{\"type\":\"mysql\",\"name\":\"host-ds\","
                    + "\"configuration\":{\"host\":\"10.0.0.8\",\"port\":3306,\"user\":\"u\",\"password\":\"p\",\"database\":\"db\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/boom", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/plain", exchange -> {
            byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
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

    @Test
    void fetchesHostConfigAndResolvesDynamicDatasource() {
        DynamicDatasource ds = resolver.resolveFromHost(1, "http://[::1]:" + port + "/ok", null);
        assertEquals(DatasourceType.MYSQL, ds.getType());
        assertEquals("host-ds", ds.getName());
        assertEquals("10.0.0.8", ds.getConf().getHost());
    }

    @Test
    void non2xxResponseFailsWithReadableMessage() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> resolver.resolveFromHost(1, "http://[::1]:" + port + "/boom", null));
        assertTrue(ex.getMessage().contains("500"), "错误信息应含 HTTP 状态: " + ex.getMessage());
    }

    @Test
    void invalidJsonFailsGracefully() {
        assertThrows(BadRequestException.class,
                () -> resolver.resolveFromHost(1, "http://[::1]:" + port + "/plain", null));
    }

    @Test
    void nonAdvancedApplicationRejected() {
        assertThrows(BadRequestException.class,
                () -> resolver.resolveFromHost(0, "http://[::1]:" + port + "/ok", null));
    }
}
