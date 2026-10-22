package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.datasource.engine.ColumnSchema;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import com.xiongx9527.askdata.datasource.engine.TableSchema;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A8：动态数据源与静态数据源问数路径一致性集成测试（H2 in-memory）。
 *
 * <p>动态数据源仅存在于请求内（连接配置按请求传入），不写入 core_datasource；其执行/元数据
 * 走与已入库数据源相同的 {@link JdbcEngine} 链路（唯一差异是连接池键按连接配置派生），
 * 因此同一连接配置下两者结果必须一致。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:a8_parity;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class DynamicDatasourceParityTest {

    private static final String DYNAMIC_URL = "jdbc:h2:mem:a8_dynamic;DB_CLOSE_DELAY=-1";

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private JdbcEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void createDynamicDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(DYNAMIC_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS A8_PARITY");
            statement.execute("CREATE TABLE A8_PARITY (ID INT PRIMARY KEY, NAME VARCHAR(50))");
            statement.execute("INSERT INTO A8_PARITY (ID, NAME) VALUES (1, 'alice'), (2, 'bob')");
        }
    }

    private DatasourceConf dynamicConf() {
        DatasourceConf conf = new DatasourceConf();
        conf.setDatabase(DYNAMIC_URL);
        conf.setUsername("sa");
        conf.setPassword("");
        return conf;
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientExecutionMatchesPersistedExecution() {
        DatasourceConf conf = dynamicConf();
        String sql = "SELECT ID, NAME FROM A8_PARITY ORDER BY ID";

        Map<String, Object> transientResult = datasourceService.execSqlTransient(DatasourceType.H2, conf, sql);
        // 同一连接配置以「已入库数据源」的池键执行：除池键外与动态路径完全相同，结果应一致
        Map<String, Object> persistedKeyResult = engine.execSql(DatasourceType.H2, conf, sql, 987654321L);

        assertEquals(persistedKeyResult.get("fields"), transientResult.get("fields"));
        assertEquals(persistedKeyResult.get("data"), transientResult.get("data"));
        List<Map<String, Object>> data = (List<Map<String, Object>>) transientResult.get("data");
        assertEquals(2, data.size());
        assertEquals("alice", data.get(0).get("NAME"));
        assertEquals("bob", data.get(1).get("NAME"));
    }

    @Test
    void transientDatasourceIsNotPersisted() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM core_datasource", Long.class);
        DatasourceConf conf = dynamicConf();
        datasourceService.execSqlTransient(DatasourceType.H2, conf, "SELECT ID FROM A8_PARITY");
        datasourceService.checkConnectionTransient(DatasourceType.H2, conf);
        long after = jdbc.queryForObject("SELECT COUNT(*) FROM core_datasource", Long.class);
        assertEquals(before, after, "动态数据源不应写入 core_datasource");
    }

    @Test
    void transientMetadataReflectsLiveSchema() {
        DatasourceConf conf = dynamicConf();
        List<TableSchema> tables = datasourceService.tablesTransient(DatasourceType.H2, conf);
        assertTrue(tables.stream().anyMatch(t -> "A8_PARITY".equalsIgnoreCase(t.getTableName())),
                tables.toString());

        List<ColumnSchema> fields = datasourceService.fieldsTransient(DatasourceType.H2, conf, "A8_PARITY");
        assertTrue(fields.stream().anyMatch(f -> "ID".equalsIgnoreCase(f.getFieldName())), fields.toString());
        assertTrue(fields.stream().anyMatch(f -> "NAME".equalsIgnoreCase(f.getFieldName())), fields.toString());
    }

    @Test
    void transientConnectionCheckSucceeds() {
        assertTrue(datasourceService.checkConnectionTransient(DatasourceType.H2, dynamicConf()));
    }
}

