package com.xiongx9527.askdata.datasource.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.datasource.DatasourceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验收 A6：对非 MySQL 引擎（oracle/dm/kingbase/redshift/es/hive 等）元数据提取与样本查询执行的覆盖。
 *
 * <p>数据源方言策略（{@link DatasourceType}）已为全部 13 种类型提供 tableSql/fieldSql/previewSql，
 * 本测试验证：① 目标引擎方言 SQL 正确（分页/系统表/标识符约定）；② 真实 JDBC 引擎（H2）经
 * {@link JdbcEngine} 完成 表/字段元数据提取 + 样本查询执行；③ 驱动缺失时按 SQLBot 约定返回可读错误。</p>
 */
class EngineExecutionParityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DatasourceConf baseConf() {
        DatasourceConf conf = new DatasourceConf();
        conf.setDatabase("DB");
        conf.setDbSchema("SCHEMA");
        return conf;
    }

    @Test
    void oracleDialectSqlIsCorrect() {
        DatasourceConf conf = baseConf();
        assertTrue(DatasourceType.ORACLE.tableSql(conf, null).getSql().contains("ALL_TABLES"));
        assertTrue(DatasourceType.ORACLE.fieldSql(conf, "T").getSql().contains("ALL_TAB_COLUMNS"));
        String preview = DatasourceType.ORACLE.previewSql(List.of("ID", "NAME"), "T", conf, null);
        assertTrue(preview.contains("ROWNUM <= 100"), preview);
        assertTrue(DatasourceType.ORACLE.sqlDialectHint().contains("ROWNUM"));
    }

    @Test
    void dmDialectSqlIsCorrect() {
        DatasourceConf conf = baseConf();
        assertTrue(DatasourceType.DM.tableSql(conf, null).getSql().contains("all_tab_comments"));
        assertTrue(DatasourceType.DM.fieldSql(conf, "T").getSql().contains("ALL_TAB_COLUMNS"));
        assertTrue(DatasourceType.DM.previewSql(List.of("ID"), "T", conf, null).contains("LIMIT 100"));
        assertTrue(DatasourceType.DM.sqlDialectHint().contains("ROWNUM"));
    }

    @Test
    void kingbaseAndRedshiftUsePgStyleLimit() {
        DatasourceConf conf = baseConf();
        for (DatasourceType t : Arrays.asList(DatasourceType.KINGBASE, DatasourceType.REDSHIFT)) {
            assertTrue(t.tableSql(conf, null).getSql().contains("pg_class"), t.name());
            assertTrue(t.fieldSql(conf, "T").getSql().contains("pg_attribute"), t.name());
            assertTrue(t.previewSql(List.of("ID"), "T", conf, null).contains("LIMIT 100"), t.name());
        }
    }

    @Test
    void hiveDialectSqlIsCorrect() {
        DatasourceConf conf = baseConf();
        assertEquals("SHOW TABLES", DatasourceType.HIVE.tableSql(conf, null).getSql());
        assertTrue(DatasourceType.HIVE.fieldSql(conf, "T").getSql().contains("DESCRIBE "));
        assertTrue(DatasourceType.HIVE.previewSql(List.of("ID"), "T", conf, null).contains("LIMIT 100"));
    }

    @Test
    void esIsHttpProtocolWithDedicatedHint() {
        assertTrue(DatasourceType.ES.isHttpProtocol());
        assertTrue(DatasourceType.ES.sqlDialectHint().contains("Elasticsearch"));
    }

    @Test
    void sqlServerUsesTopPaging() {
        DatasourceConf conf = baseConf();
        assertTrue(DatasourceType.SQLSERVER.previewSql(List.of("ID"), "T", conf, null).contains("TOP 100"));
        assertTrue(DatasourceType.SQLSERVER.sqlDialectHint().contains("TOP"));
    }

    @Test
    void h2MetadataExtractionAndSampleQueryExecutes() throws Exception {
        String url = "jdbc:h2:mem:engineparity;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE PARITY_T (ID INT PRIMARY KEY, NAME VARCHAR(50))");
            st.execute("INSERT INTO PARITY_T VALUES (1, 'a'), (2, 'b')");
        }

        DatasourceConf conf = new DatasourceConf();
        conf.setDatabase(url);
        conf.setDbSchema("PUBLIC");
        conf.setUsername("");
        conf.setPassword("");

        ConnectionPoolManager poolManager = mock(ConnectionPoolManager.class);
        when(poolManager.borrow(anyString(), any())).thenAnswer(inv ->
                ((ConnectionPoolManager.ConnectionFactory) inv.getArgument(1)).create());

        JdbcEngine engine = new JdbcEngine(mock(JdbcTemplate.class), objectMapper, poolManager);

        List<TableSchema> tables = engine.tables(DatasourceType.H2, conf);
        assertTrue(tables.stream().anyMatch(t -> "PARITY_T".equals(t.getTableName())), tables.toString());

        List<ColumnSchema> columns = engine.fields(DatasourceType.H2, conf, "PARITY_T");
        assertEquals(2, columns.size());
        assertTrue(columns.stream().anyMatch(c -> "ID".equals(c.getFieldName())));

        Map<String, Object> result = engine.execSql(DatasourceType.H2, conf,
                "SELECT ID, NAME FROM PARITY_T ORDER BY ID");
        assertTrue(((List<?>) result.get("data")).size() == 2);
    }

    @Test
    void missingDriverReturnsReadableError() {
        DatasourceConf conf = baseConf();
        conf.setHost("localhost");
        conf.setPort(1521);
        ConnectionPoolManager poolManager = mock(ConnectionPoolManager.class);
        JdbcEngine engine = new JdbcEngine(mock(JdbcTemplate.class), objectMapper, poolManager);
        DatasourceException ex = assertThrows(DatasourceException.class,
                () -> engine.tables(DatasourceType.ORACLE, conf));
        assertTrue(ex.getMessage().toLowerCase().contains("driver"), ex.getMessage());
    }
}

