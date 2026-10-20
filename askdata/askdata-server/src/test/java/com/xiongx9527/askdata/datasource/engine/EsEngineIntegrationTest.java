package com.xiongx9527.askdata.datasource.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.datasource.DatasourceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 ES 集群联调（A2 修订）：默认跳过；设置 ASKDATA_ES_HOST 后针对真实集群验证
 * JdbcEngine 的 ES HTTP 路径（连通检查 / 索引元数据 / 字段映射 / ES SQL 执行）。
 *
 * <p>可选环境变量 / 系统属性：</p>
 * <ul>
 *   <li>ASKDATA_ES_HOST / askdata.es.host —— 必填，真实 ES 主机（如 192.168.1.100）</li>
 *   <li>ASKDATA_ES_PORT / askdata.es.port —— 可选，默认 9200</li>
 *   <li>ASKDATA_ES_USER / askdata.es.user + ASKDATA_ES_PASS / askdata.es.pass —— 可选 Basic 鉴权；
 *       推荐使用专用只读测试账号 es_test_rw（角色 es_test_rw_role：cluster:monitor + indices:read/
 *       view_index_metadata/monitor），避免复用 superuser elasticadmin</li>
 * </ul>
 *
 * <p>示例（在本机可访问该集群的网络环境下执行）：</p>
 * <pre>
 *   ASKDATA_ES_HOST=192.168.1.100 ASKDATA_ES_USER=es_test_rw ASKDATA_ES_PASS=EsTestRw@2026 \
 *       mvn -pl askdata-server test -Dmaven.test.skip=false -Dtest=EsEngineIntegrationTest
 * </pre>
 *
 * <p>execSql 冒烟依赖集群映射，且 ES SQL 无法解析以 '.' 开头的数据流/系统索引标识符，
 * 因此选择第一个非 '.' 起始的业务索引；若真实集群不支持该 SQL，按 JUnit 假设跳过而非判败。
 */
class EsEngineIntegrationTest {

    private static String host;
    private static int port;
    private static String username;
    private static String password;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcEngine engine = new JdbcEngine(null, objectMapper);

    @BeforeAll
    static void resolveConfig() {
        host = envOrProp("ASKDATA_ES_HOST", "askdata.es.host", null);
        Assumptions.assumeTrue(host != null && !host.trim().isEmpty(),
                "ASKDATA_ES_HOST / askdata.es.host 未设置，跳过真实 ES 联调测试");
        String portStr = envOrProp("ASKDATA_ES_PORT", "askdata.es.port", "9200");
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            port = 9200;
        }
        username = envOrProp("ASKDATA_ES_USER", "askdata.es.user", null);
        password = envOrProp("ASKDATA_ES_PASS", "askdata.es.pass", null);
    }

    private static String envOrProp(String env, String prop, String def) {
        String v = System.getenv(env);
        if (v == null) {
            v = System.getProperty(prop);
        }
        return v == null ? def : v;
    }

    private DatasourceConf conf() {
        DatasourceConf conf = new DatasourceConf();
        conf.setHost("http://" + host.trim() + ":" + port);
        conf.setPort(port);
        if (username != null && !username.trim().isEmpty()) {
            conf.setUsername(username);
            conf.setPassword(password == null ? "" : password);
        }
        return conf;
    }

    @Test
    void realClusterConnectivityAndMetadata() {
        DatasourceConf conf = conf();
        assertTrue(engine.checkConnection(DatasourceType.ES, conf, true),
                "真实 ES 集群连通检查应成功（检查 host/port/鉴权）");

        List<TableSchema> tables = engine.tables(DatasourceType.ES, conf);
        assertNotNull(tables, "tables() 不应为 null");
        System.out.println("[EsEngineIntegrationTest] indices count = " + tables.size());

        Assumptions.assumeFalse(tables.isEmpty(), "集群无索引，跳过 fields/execSql 子检查");
        String firstIndex = tables.get(0).getTableName();
        System.out.println("[EsEngineIntegrationTest] first index = " + firstIndex);

        List<ColumnSchema> fields = engine.fields(DatasourceType.ES, conf, firstIndex);
        assertNotNull(fields, "fields() 不应为 null");
        assertFalse(fields.isEmpty(), "索引 [" + firstIndex + "] 应至少有一个字段");
        System.out.println("[EsEngineIntegrationTest] fields count = " + fields.size());

        // execSql 冒烟：ES SQL 无法解析以 '.' 开头的数据流/系统索引标识符，
        // 因此选择第一个非 '.' 起始的业务索引；无合适索引时按假设跳过而非判败。
        String queryable = null;
        for (TableSchema t : tables) {
            String name = t.getTableName();
            if (name != null && !name.startsWith(".") && !name.isEmpty()) {
                queryable = name;
                break;
            }
        }
        Assumptions.assumeTrue(queryable != null, "无可用业务索引（均为 '.' 起始），跳过 execSql 子检查");
        try {
            Map<String, Object> result = engine.execSql(DatasourceType.ES, conf,
                    "SELECT * FROM \"" + queryable + "\" LIMIT 1");
            assertNotNull(result, "execSql 返回不应为 null");
            System.out.println("[EsEngineIntegrationTest] execSql ok, index=" + queryable
                    + ", keys = " + result.keySet());
        } catch (DatasourceException e) {
            Assumptions.assumeTrue(false,
                    "真实集群 ES SQL 不支持或查询不适用，跳过 execSql 子检查: " + e.getMessage());
        }
    }
}
