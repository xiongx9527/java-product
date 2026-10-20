package com.xiongx9527.askdata.datasource.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ES 数据源引擎验证（A2 离线证据）：JdbcEngine 已内置 ES HTTP 路径（checkConnection→ping、
 * tables→_cat/indices、fields→_mapping、execSql→_sql），本测试用 JDK 内置 HttpServer 模拟
 * ES 节点，覆盖连通检查、索引元数据、字段映射、ES SQL 执行、Basic 鉴权与失败路径，无需真实 ES。
 */
class EsEngineTest {

    private HttpServer server;
    private int port;
    private String authHeader;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcEngine engine = new JdbcEngine(null, objectMapper);

    @BeforeEach
    void setUp() throws IOException {
        authHeader = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            if (!checkAuth(exchange)) {
                send(exchange, 401, objectMapper.writeValueAsString(mapOf("error", "unauthorized")));
                return;
            }
            send(exchange, 200, objectMapper.writeValueAsString(
                    mapOf("cluster_name", "test", "version", mapOf("number", "8.0.0"))));
        });
        server.createContext("/_cat/indices", exchange -> {
            if (!checkAuth(exchange)) {
                send(exchange, 401, "{}");
                return;
            }
            List<Object> indices = new ArrayList<>();
            indices.add(mapOf("index", "orders"));
            indices.add(mapOf("index", "products"));
            send(exchange, 200, objectMapper.writeValueAsString(indices));
        });
        server.createContext("/orders/_mapping", exchange -> {
            if (!checkAuth(exchange)) {
                send(exchange, 401, "{}");
                return;
            }
            Map<String, Object> props = new HashMap<>();
            props.put("id", mapOf("type", "long"));
            props.put("amount", mapOf("type", "double"));
            props.put("status", mapOf("type", "keyword"));
            Map<String, Object> mapping = mapOf("properties", props);
            Map<String, Object> mappings = mapOf("mappings", mapping);
            send(exchange, 200, objectMapper.writeValueAsString(mapOf("orders", mappings)));
        });
        server.createContext("/_sql", exchange -> {
            if (!checkAuth(exchange)) {
                send(exchange, 401, objectMapper.writeValueAsString(mapOf("error", "unauthorized")));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{}");
                return;
            }
            List<Object> columns = new ArrayList<>();
            columns.add(mapOf("name", "id", "type", "long"));
            columns.add(mapOf("name", "amount", "type", "double"));
            List<Object> rows = new ArrayList<>();
            List<Object> row1 = new ArrayList<>();
            row1.add(1);
            row1.add(99.5);
            List<Object> row2 = new ArrayList<>();
            row2.add(2);
            row2.add(12.0);
            rows.add(row1);
            rows.add(row2);
            send(exchange, 200, objectMapper.writeValueAsString(mapOf("columns", columns, "rows", rows)));
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private boolean checkAuth(com.sun.net.httpserver.HttpExchange exchange) {
        if (authHeader == null) {
            return true;
        }
        String incoming = exchange.getRequestHeaders().getFirst("Authorization");
        return authHeader.equals(incoming);
    }

    private void send(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @SafeVarargs
    private final Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private DatasourceConf conf() {
        DatasourceConf conf = new DatasourceConf();
        conf.setHost("http://127.0.0.1:" + port);
        conf.setPort(port);
        return conf;
    }

    @Test
    void checkConnectionPingSucceeds() {
        assertTrue(engine.checkConnection(DatasourceType.ES, conf(), false));
    }

    @Test
    void checkConnectionRefusedReturnsFalse() {
        DatasourceConf conf = new DatasourceConf();
        conf.setHost("http://127.0.0.1:1");
        conf.setPort(1);
        assertFalse(engine.checkConnection(DatasourceType.ES, conf, false));
    }

    @Test
    void checkConnectionRefusedRaises() {
        DatasourceConf conf = new DatasourceConf();
        conf.setHost("http://127.0.0.1:1");
        conf.setPort(1);
        assertThrows(com.xiongx9527.askdata.datasource.DatasourceException.class,
                () -> engine.checkConnection(DatasourceType.ES, conf, true));
    }

    @Test
    void tablesReturnsIndices() {
        List<TableSchema> tables = engine.tables(DatasourceType.ES, conf());
        assertEquals(2, tables.size());
        assertTrue(tables.stream().anyMatch(t -> "orders".equals(t.getTableName())));
        assertTrue(tables.stream().anyMatch(t -> "products".equals(t.getTableName())));
    }

    @Test
    void fieldsReturnsMappingColumns() {
        List<ColumnSchema> fields = engine.fields(DatasourceType.ES, conf(), "orders");
        assertEquals(3, fields.size());
        Map<String, ColumnSchema> byName = new HashMap<>();
        for (ColumnSchema f : fields) {
            byName.put(f.getFieldName(), f);
        }
        assertEquals("long", byName.get("id").getFieldType());
        assertEquals("double", byName.get("amount").getFieldType());
        assertEquals("keyword", byName.get("status").getFieldType());
    }

    @Test
    void execSqlReturnsRows() {
        Map<String, Object> result = engine.execSql(DatasourceType.ES, conf(), "SELECT id, amount FROM orders");
        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<Object> fields = (List<Object>) result.get("fields");
        assertEquals(2, fields.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        assertEquals(2, data.size());
        assertEquals(1, ((Number) data.get(0).get("id")).intValue());
        assertEquals(99.5, ((Number) data.get(0).get("amount")).doubleValue(), 0.001);
    }

    @Test
    void basicAuthSucceeds() {
        authHeader = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("elastic:secret".getBytes(StandardCharsets.UTF_8));
        DatasourceConf conf = conf();
        conf.setUsername("elastic");
        conf.setPassword("secret");
        assertTrue(engine.checkConnection(DatasourceType.ES, conf, false));
        assertEquals(2, engine.tables(DatasourceType.ES, conf).size());
    }

    @Test
    void basicAuthMissingFails() {
        authHeader = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("elastic:secret".getBytes(StandardCharsets.UTF_8));
        DatasourceConf conf = conf();
        assertFalse(engine.checkConnection(DatasourceType.ES, conf, false));
    }
}
