package com.xiongx9527.askdata.datasource.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.datasource.DatasourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎真实连通 smoke（A5/A12 离线证据）：
 * H2 数据源全流程（check/tables/fields/version/execSql/预览）、
 * sqlite 文件库全流程、excel 应用库（H2）DDL/存在性。
 * 不依赖外部数据库网络。
 */
class JdbcEngineSmokeTest {

    private static final String TARGET_URL = "jdbc:h2:mem:engine_target;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static final String SQLITE_PATH;

    static {
        try {
            SQLITE_PATH = java.nio.file.Files.createTempDirectory("askdata_sqlite").resolve("demo.db").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        // 目标 H2 库：建表 + 注释 + 样例数据（对齐演示库结构；MODE=MySQL 下未加引号标识符
        // 会折叠为大写，故 DDL 全部加引号保留小写，与真实 MySQL 库一致）
        try (Connection connection = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS \"orders\" ("
                    + "\"id\" BIGINT PRIMARY KEY, \"user_id\" BIGINT, \"amount\" DECIMAL(10,2), "
                    + "\"status\" VARCHAR(20), \"created_at\" TIMESTAMP,"
                    + "\"remark\" VARCHAR(200))");
            statement.execute("COMMENT ON TABLE \"orders\" IS '订单表'");
            statement.execute("COMMENT ON COLUMN \"orders\".\"id\" IS '订单ID'");
            statement.execute("COMMENT ON COLUMN \"orders\".\"amount\" IS '订单金额'");
            statement.execute("DELETE FROM \"orders\"");
            statement.execute("INSERT INTO \"orders\" VALUES (1, 100, 99.50, 'PAID', "
                    + "TIMESTAMP '2026-01-15 10:30:00', 'x'), "
                    + "(2, 101, 12.00, 'NEW', TIMESTAMP '2026-02-20 08:00:00', NULL)");
        }
        // sqlite 文件库
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + SQLITE_PATH);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT, score REAL)");
            statement.execute("DELETE FROM users");
            statement.execute("INSERT INTO users VALUES (1, 'alice', 9.5), (2, 'bob', 8.0)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS orders");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + SQLITE_PATH);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS users");
        }
    }

    private JdbcEngine engine(JdbcTemplate appJdbc) {
        return new JdbcEngine(appJdbc, objectMapper);
    }

    private DatasourceConf conf(String database) {
        DatasourceConf conf = new DatasourceConf();
        conf.setDatabase(database);
        conf.setUsername("sa");
        conf.setPassword("");
        conf.setTimeout(10);
        return conf;
    }

    @Test
    void h2DatasourceFullFlow() {
        JdbcEngine engine = engine(null);
        DatasourceConf conf = conf(TARGET_URL);

        // 连通检查
        assertTrue(engine.checkConnection(DatasourceType.H2, conf, false));
        assertTrue(engine.checkConnection(DatasourceType.H2, conf, true));
        // 版本
        assertFalse(engine.version(DatasourceType.H2, conf).isEmpty());

        // 表元数据（H2 方言：INFORMATION_SCHEMA）
        List<TableSchema> tables = engine.tables(DatasourceType.H2, conf);
        assertEquals(1, tables.size());
        assertEquals("orders", tables.get(0).getTableName());
        assertEquals("订单表", tables.get(0).getTableComment());

        // 字段元数据（含注释）
        List<ColumnSchema> fields = engine.fields(DatasourceType.H2, conf, "orders");
        assertEquals(6, fields.size());
        assertEquals("id", fields.get(0).getFieldName());
        assertEquals("订单ID", fields.get(0).getFieldComment());
        assertTrue(fields.get(0).getFieldType().toUpperCase().contains("BIGINT"));

        // schema 列表
        List<String> schemas = engine.schemas(DatasourceType.H2, conf);
        assertTrue(schemas.contains("PUBLIC"));

        // execSql：字段名保持原始大小写（origin_column=True），值转换对齐 SQLBot
        java.util.Map<String, Object> result = engine.execSql(DatasourceType.H2, conf,
                "SELECT \"id\", \"amount\", \"status\", \"created_at\" FROM \"orders\" ORDER BY \"id\"");
        assertEquals(2, ((List<?>) result.get("data")).size());
        @SuppressWarnings("unchecked")
        List<Object> columns = (List<Object>) result.get("fields");
        assertTrue(columns.contains("id"));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> rows = (List<java.util.Map<String, Object>>) result.get("data");
        assertEquals(99.5, ((Number) rows.get(0).get("amount")).doubleValue(), 0.0001);
        assertEquals("2026-01-15 10:30:00", rows.get(0).get("created_at"));
        // fields_info：数值判定
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> fieldsInfo = (List<java.util.Map<String, Object>>) result.get("fields_info");
        assertTrue((Boolean) fieldsInfo.get(0).get("is_numeric"));
        assertFalse((Boolean) fieldsInfo.get(2).get("is_numeric"));
        // sql 回显为 base64
        assertEquals("SELECT \"id\", \"amount\", \"status\", \"created_at\" FROM \"orders\" ORDER BY \"id\"",
                new String(java.util.Base64.getDecoder().decode(String.valueOf(result.get("sql")))));

        // 预览 SQL 执行（方言 limit）
        java.util.Map<String, Object> preview = engine.execSql(DatasourceType.H2, conf,
                DatasourceType.H2.previewSql(java.util.Arrays.asList("id", "amount"), "orders", conf, null));
        assertEquals(2, ((List<?>) preview.get("data")).size());
    }

    @Test
    void sqliteDatasourceFullFlow() {
        JdbcEngine engine = engine(null);
        DatasourceConf conf = conf("");
        conf.setFilename(SQLITE_PATH);
        assertTrue(engine.checkConnection(DatasourceType.SQLITE, conf, true));
        List<TableSchema> tables = engine.tables(DatasourceType.SQLITE, conf);
        assertEquals(1, tables.size());
        assertEquals("users", tables.get(0).getTableName());
        List<ColumnSchema> fields = engine.fields(DatasourceType.SQLITE, conf, "users");
        assertEquals(3, fields.size());
        assertEquals("id", fields.get(0).getFieldName());
        java.util.Map<String, Object> result = engine.execSql(DatasourceType.SQLITE, conf, "SELECT * FROM users");
        assertEquals(2, ((List<?>) result.get("data")).size());
    }

    @Test
    void excelAppDatabaseFlow() {
        // 应用库（H2）：DDL 建表/存在性/元数据/执行（对齐 SQLBot get_engine_conn 语义）
        JdbcTemplate appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:engine_app;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        JdbcEngine engine = engine(appJdbc);
        engine.executeAppDdl("CREATE TABLE \"excel_orders_abc123\" (\"id\" BIGINT, \"amount\" DOUBLE, \"note\" CLOB)");
        engine.executeAppDdl("INSERT INTO \"excel_orders_abc123\" VALUES (1, 9.9, 'hello')");
        assertTrue(engine.appTableExists("excel_orders_abc123"));
        assertFalse(engine.appTableExists("not_exists_table"));

        DatasourceConf conf = new DatasourceConf();
        List<TableSchema> tables = engine.tables(DatasourceType.EXCEL, conf);
        assertTrue(tables.stream().anyMatch(t -> "excel_orders_abc123".equals(t.getTableName())));
        List<ColumnSchema> fields = engine.fields(DatasourceType.EXCEL, conf, "excel_orders_abc123");
        assertEquals(3, fields.size());
        // excel 预览/执行走应用库
        java.util.Map<String, Object> preview = engine.execSql(DatasourceType.EXCEL, conf,
                DatasourceType.EXCEL.previewSql(java.util.Arrays.asList("id", "note"), "excel_orders_abc123", conf, null));
        assertEquals(1, ((List<?>) preview.get("data")).size());
        // 应用库方言识别为 H2
        assertEquals(DatasourceType.H2, engine.appDbType());

        engine.executeAppDdl("DROP TABLE \"excel_orders_abc123\"");
        assertFalse(engine.appTableExists("excel_orders_abc123"));
    }

    /**
     * 回归：CLOB 列必须转成字符串，否则 execSql/previewData 序列化时抛
     * {@code No serializer found for class java.io.BufferedReader}（HTTP 500）。
     */
    @Test
    void clobValueConvertedToString() throws Exception {
        JdbcTemplate appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:engine_clob;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        JdbcEngine engine = engine(appJdbc);
        engine.executeAppDdl("CREATE TABLE \"excel_clob_abc123\" (\"id\" BIGINT, \"note\" CLOB)");
        engine.executeAppDdl("INSERT INTO \"excel_clob_abc123\" VALUES (1, 'hello clob')");
        Map<String, Object> result = engine.execSql(DatasourceType.EXCEL, new DatasourceConf(),
                "SELECT \"id\", \"note\" FROM \"excel_clob_abc123\"");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) result.get("data")).get(0);
        assertEquals("hello clob", row.get("note"));
        assertTrue(new ObjectMapper().writeValueAsString(result).contains("hello clob"));
        engine.executeAppDdl("DROP TABLE \"excel_clob_abc123\"");
    }

    @Test
    void convertValueReadsClobAndReader() throws Exception {
        assertEquals("hello", JdbcEngine.convertValue(
                new javax.sql.rowset.serial.SerialClob("hello".toCharArray())));
        assertEquals("world", JdbcEngine.convertValue(
                new java.io.BufferedReader(new java.io.StringReader("world"))));
        assertNull(JdbcEngine.convertValue(null));
    }

    @Test
    void connectionFailureFollowsSqlbotConvention() {
        JdbcEngine engine = engine(null);
        DatasourceConf conf = conf("db1");
        conf.setHost("127.0.0.1");
        conf.setPort(1);
        conf.setUsername("root");
        conf.setPassword("x");
        // is_raise=false：返回 false；is_raise=true：抛 500 文本（数据源连接失败: ...）
        assertFalse(engine.checkConnection(DatasourceType.MYSQL, conf, false));
        DatasourceException ex = assertThrows(DatasourceException.class,
                () -> engine.checkConnection(DatasourceType.MYSQL, conf, true));
        assertTrue(ex.getMessage().startsWith("数据源连接失败:"), ex.getMessage());
    }

    @Test
    void missingDriverFollowsSqlbotConvention() {
        // 驱动不在 classpath（未放扩展目录）：连接检查报驱动缺失（A12 其余类型的离线行为）
        JdbcEngine engine = engine(null);
        DatasourceConf conf = conf("db1");
        conf.setHost("h");
        conf.setPort(1);
        conf.setUsername("u");
        conf.setPassword("p");
        DatasourceException ex = assertThrows(DatasourceException.class,
                () -> engine.checkConnection(DatasourceType.ORACLE, conf, true));
        assertTrue(ex.getMessage().contains("jdbc driver [oracle.jdbc.OracleDriver] not found"), ex.getMessage());
        // 连接串仍可按方言生成（元数据策略存在）
        assertTrue(engine.buildJdbcUrl(DatasourceType.ORACLE, conf).startsWith("jdbc:oracle:thin:@//h:1/db1"));
    }

    @Test
    void readOnlyEnforcedOnExec() {
        JdbcEngine engine = engine(null);
        DatasourceConf conf = conf(TARGET_URL);
        assertThrows(DatasourceException.class,
                () -> engine.execSql(DatasourceType.H2, conf, "DELETE FROM \"orders\""));
        assertThrows(DatasourceException.class,
                () -> engine.execSql(DatasourceType.H2, conf, "SELECT 1; DROP TABLE \"orders\""));
    }
}
