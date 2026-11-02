package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.datasource.engine.ColumnSchema;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import com.xiongx9527.askdata.datasource.engine.TableSchema;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.support.MysqlTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A8：动态数据源与静态数据源问数路径一致性集成测试（真实 MySQL）。
 *
 * <p>动态数据源仅存在于请求内（连接配置按请求传入），不写入 core_datasource；其执行/元数据
 * 走与已入库数据源相同的 {@link JdbcEngine} 链路（唯一差异是连接池键按连接配置派生），
 * 因此同一连接配置下两者结果必须一致。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DynamicDatasourceParityTest {

    private static JdbcTemplate mysql;

    /** 作为「动态数据源」被查询的独立 MySQL schema。 */
    private static JdbcTemplate dynamicJdbc;

    @DynamicPropertySource
    static void mysqlDatasource(DynamicPropertyRegistry registry) {
        mysql = MysqlTestSupport.createEmptySchema();
        MysqlTestSupport.bindSpringDatasource(registry, mysql);
    }

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private JdbcEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void createDynamicDatabase() {
        dynamicJdbc = MysqlTestSupport.createEmptySchema();
        dynamicJdbc.execute("CREATE TABLE a8_parity (id INT PRIMARY KEY, name VARCHAR(50))");
        dynamicJdbc.update("INSERT INTO a8_parity (id, name) VALUES (1, 'alice'), (2, 'bob')");
    }

    private DatasourceConf dynamicConf() {
        DatasourceConf conf = new DatasourceConf();
        MysqlTestSupport.bindTarget(conf, dynamicJdbc);
        return conf;
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientExecutionMatchesPersistedExecution() {
        DatasourceConf conf = dynamicConf();
        String sql = "SELECT id, name FROM a8_parity ORDER BY id";

        Map<String, Object> transientResult = datasourceService.execSqlTransient(DatasourceType.MYSQL, conf, sql);
        // 同一连接配置以「已入库数据源」的池键执行：除池键外与动态路径完全相同，结果应一致
        Map<String, Object> persistedKeyResult = engine.execSql(DatasourceType.MYSQL, conf, sql, 987654321L);

        assertEquals(persistedKeyResult.get("fields"), transientResult.get("fields"));
        assertEquals(persistedKeyResult.get("data"), transientResult.get("data"));
        List<Map<String, Object>> data = (List<Map<String, Object>>) transientResult.get("data");
        assertEquals(2, data.size());
        assertEquals("alice", data.get(0).get("name"));
        assertEquals("bob", data.get(1).get("name"));
    }

    @Test
    void transientDatasourceIsNotPersisted() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM core_datasource", Long.class);
        DatasourceConf conf = dynamicConf();
        datasourceService.execSqlTransient(DatasourceType.MYSQL, conf, "SELECT id FROM a8_parity");
        datasourceService.checkConnectionTransient(DatasourceType.MYSQL, conf);
        long after = jdbc.queryForObject("SELECT COUNT(*) FROM core_datasource", Long.class);
        assertEquals(before, after, "动态数据源不应写入 core_datasource");
    }

    @Test
    void transientMetadataReflectsLiveSchema() {
        DatasourceConf conf = dynamicConf();
        List<TableSchema> tables = datasourceService.tablesTransient(DatasourceType.MYSQL, conf);
        assertTrue(tables.stream().anyMatch(t -> "a8_parity".equalsIgnoreCase(t.getTableName())),
                tables.toString());

        List<ColumnSchema> fields = datasourceService.fieldsTransient(DatasourceType.MYSQL, conf, "a8_parity");
        assertTrue(fields.stream().anyMatch(f -> "id".equalsIgnoreCase(f.getFieldName())), fields.toString());
        assertTrue(fields.stream().anyMatch(f -> "name".equalsIgnoreCase(f.getFieldName())), fields.toString());
    }

    @Test
    void transientConnectionCheckSucceeds() {
        assertTrue(datasourceService.checkConnectionTransient(DatasourceType.MYSQL, dynamicConf()));
    }
}
