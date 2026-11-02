package com.xiongx9527.askdata.datasource.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.support.MysqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎真实连通 smoke（A5/A12 证据）：
 * MySQL 数据源全流程（check/tables/fields/version/execSql/预览）、
 * sqlite 文件库全流程、excel 应用库（真实 MySQL 元数据库）DDL/存在性。
 *
 * <p>不再使用 H2：目标库与应用库都是本机真实 MySQL 的独立临时 schema，
 * 方言（反引号、information_schema、DATETIME）与生产一致。</p>
 */
class JdbcEngineSmokeTest {

    private static JdbcTemplate targetJdbc;

    private static JdbcTemplate appJdbc;

    private static final String SQLITE_PATH;

    static {
        try {
            SQLITE_PATH = java.nio.file.Files.createTempDirectory("askdata_sqlite").resolve("demo.db").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectMapper objectMapper;

    @BeforeAll
    static void createDatabases() {
        targetJdbc = MysqlTestSupport.createEmptySchema();
        appJdbc = MysqlTestSupport.createEmptySchema();

        targetJdbc.execute("CREATE TABLE orders ("
                + "id BIGINT PRIMARY KEY COMMENT '订单ID', "
                + "user_id BIGINT, "
                + "amount DECIMAL(10,2) COMMENT '订单金额', "
                + "status VARCHAR(20), "
                + "created_at DATETIME, "
                + "remark VARCHAR(200)) COMMENT='订单表'");
        targetJdbc.update("INSERT INTO orders VALUES (1, 100, 99.50, 'PAID', '2026-01-15 10:30:00', 'x'),"
                + " (2, 101, 12.00, 'NEW', '2026-02-20 08:00:00', NULL)");
    }

    @AfterAll
    static void dropDatabases() {
        MysqlTestSupport.dropSchema(targetJdbc);
        MysqlTestSupport.dropSchema(appJdbc);
    }

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
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
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + SQLITE_PATH);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS users");
        }
    }

    private JdbcEngine engine(JdbcTemplate app) {
        return new JdbcEngine(app, objectMapper);
    }

    /** 指向本次运行目标 MySQL schema 的数据源配置。 */
    private DatasourceConf targetConf() {
        DatasourceConf conf = new DatasourceConf();
        MysqlTestSupport.bindTarget(conf, targetJdbc);
        conf.setTimeout(10);
        return conf;
    }

    @Test
    void mysqlDatasourceFullFlow() {
        JdbcEngine engine = engine(null);
        DatasourceConf conf = targetConf();

        // 连通检查
        assertTrue(engine.checkConnection(DatasourceType.MYSQL, conf, false));
        assertTrue(engine.checkConnection(DatasourceType.MYSQL, conf, true));
        // 版本
        assertFalse(engine.version(DatasourceType.MYSQL, conf).isEmpty());

        // 表元数据（MySQL 方言：information_schema）
        List<TableSchema> tables = engine.tables(DatasourceType.MYSQL, conf);
        assertEquals(1, tables.size());
        assertEquals("orders", tables.get(0).getTableName());
        assertEquals("订单表", tables.get(0).getTableComment());

        // 字段元数据（含注释）
        List<ColumnSchema> fields = engine.fields(DatasourceType.MYSQL, conf, "orders");
        assertEquals(6, fields.size());
        ColumnSchema idField = fields.stream().filter(f -> "id".equals(f.getFieldName())).findFirst()
                .orElseThrow(() -> new AssertionError("id 字段缺失: " + fields));
        assertEquals("订单ID", idField.getFieldComment());
        assertTrue(idField.getFieldType().toUpperCase().contains("BIGINT"));

        // execSql：字段名保持原始大小写（origin_column=True），值转换对齐 SQLBot
        Map<String, Object> result = engine.execSql(DatasourceType.MYSQL, conf,
                "SELECT id, amount, status, created_at FROM orders ORDER BY id");
        assertEquals(2, ((List<?>) result.get("data")).size());
        @SuppressWarnings("unchecked")
        List<Object> columns = (List<Object>) result.get("fields");
        assertTrue(columns.contains("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
        assertEquals(99.5, ((Number) rows.get(0).get("amount")).doubleValue(), 0.0001);
        assertEquals("2026-01-15 10:30:00", rows.get(0).get("created_at"));
        // fields_info：数值判定
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldsInfo = (List<Map<String, Object>>) result.get("fields_info");
        assertEquals(4, fieldsInfo.size());
        assertTrue((Boolean) fieldsInfo.get(0).get("is_numeric"));
        assertFalse((Boolean) fieldsInfo.get(2).get("is_numeric"));
        // sql 回显为 base64
        assertEquals("SELECT id, amount, status, created_at FROM orders ORDER BY id",
                new String(java.util.Base64.getDecoder().decode(String.valueOf(result.get("sql")))));

        // 预览 SQL 执行（方言 limit）
        Map<String, Object> preview = engine.execSql(DatasourceType.MYSQL, conf,
                DatasourceType.MYSQL.previewSql(java.util.Arrays.asList("id", "amount"), "orders", conf, null));
        assertEquals(2, ((List<?>) preview.get("data")).size());
    }

    @Test
    void sqliteDatasourceFullFlow() {
        JdbcEngine engine = engine(null);
        DatasourceConf conf = new DatasourceConf();
        conf.setFilename(SQLITE_PATH);
        assertTrue(engine.checkConnection(DatasourceType.SQLITE, conf, true));
        List<TableSchema> tables = engine.tables(DatasourceType.SQLITE, conf);
        assertEquals(1, tables.size());
        assertEquals("users", tables.get(0).getTableName());
        List<ColumnSchema> fields = engine.fields(DatasourceType.SQLITE, conf, "users");
        assertEquals(3, fields.size());
        assertEquals("id", fields.get(0).getFieldName());
        Map<String, Object> result = engine.execSql(DatasourceType.SQLITE, conf, "SELECT * FROM users");
        assertEquals(2, ((List<?>) result.get("data")).size());
    }

    @Test
    void excelAppDatabaseFlow() {
        // 应用库（真实 MySQL）：DDL 建表/存在性/元数据/执行（对齐 SQLBot get_engine_conn 语义）
        JdbcEngine engine = engine(appJdbc);
        engine.executeAppDdl("CREATE TABLE excel_orders_abc123 (id BIGINT, amount DOUBLE, note TEXT)");
        engine.executeAppDdl("INSERT INTO excel_orders_abc123 VALUES (1, 9.9, 'hello')");
        assertTrue(engine.appTableExists("excel_orders_abc123"));
        assertFalse(engine.appTableExists("not_exists_table"));

        DatasourceConf conf = new DatasourceConf();
        List<TableSchema> tables = engine.tables(DatasourceType.EXCEL, conf);
        assertTrue(tables.stream().anyMatch(t -> "excel_orders_abc123".equals(t.getTableName())));
        List<ColumnSchema> fields = engine.fields(DatasourceType.EXCEL, conf, "excel_orders_abc123");
        assertEquals(3, fields.size());
        // excel 预览/执行走应用库
        Map<String, Object> preview = engine.execSql(DatasourceType.EXCEL, conf,
                engine.previewSql(DatasourceType.EXCEL, conf,
                        java.util.Arrays.asList("id", "note"), "excel_orders_abc123", null));
        assertEquals(1, ((List<?>) preview.get("data")).size());
        // 应用库方言识别为 MySQL
        assertEquals(DatasourceType.MYSQL, engine.appDbType());

        engine.executeAppDdl("DROP TABLE excel_orders_abc123");
        assertFalse(engine.appTableExists("excel_orders_abc123"));
    }

    /**
     * 回归：大文本列必须转成字符串，否则 execSql/previewData 序列化时抛
     * {@code No serializer found for class java.io.BufferedReader}（HTTP 500）。
     */
    @Test
    void clobValueConvertedToString() throws Exception {
        JdbcEngine engine = engine(appJdbc);
        engine.executeAppDdl("CREATE TABLE excel_clob_abc123 (id BIGINT, note TEXT)");
        engine.executeAppDdl("INSERT INTO excel_clob_abc123 VALUES (1, 'hello clob')");
        Map<String, Object> result = engine.execSql(DatasourceType.EXCEL, new DatasourceConf(),
                "SELECT id, note FROM excel_clob_abc123");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) result.get("data")).get(0);
        assertEquals("hello clob", row.get("note"));
        assertTrue(new ObjectMapper().writeValueAsString(result).contains("hello clob"));
        engine.executeAppDdl("DROP TABLE excel_clob_abc123");
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
        DatasourceConf conf = new DatasourceConf();
        conf.setDatabase("db1");
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
    void dmDriverBundledSoConnectionFailsOnNetworkNotOnDriver() {
        // A8：达梦驱动已随包（runtime scope）。驱动存在，连接检查因此走真实连接失败路径，
        // 报「数据源连接失败: ...」而不是「驱动缺失」——这正是前端可选即可用的前提。
        JdbcEngine engine = engine(null);
        DatasourceConf conf = new DatasourceConf();
        conf.setDatabase("db1");
        conf.setHost("127.0.0.1");
        conf.setPort(1);
        conf.setUsername("u");
        conf.setPassword("p");
        DatasourceException ex = assertThrows(DatasourceException.class,
                () -> engine.checkConnection(DatasourceType.DM, conf, true));
        // 驱动已随包：失败原因是连不上 127.0.0.1:1（网络错误），而不是「驱动缺失」
        assertTrue(ex.getMessage() != null && !ex.getMessage().isEmpty()
                && !ex.getMessage().contains("not found"), ex.getMessage());
        // 连接串仍可按方言生成（元数据策略存在；与驱动是否随包无关）
        assertTrue(engine.buildJdbcUrl(DatasourceType.ORACLE, conf)
                .startsWith("jdbc:oracle:thin:@//127.0.0.1:1/db1"));
    }

    @Test
    void readOnlyEnforcedOnExec() {
        JdbcEngine engine = engine(null);
        DatasourceConf conf = targetConf();
        assertThrows(DatasourceException.class,
                () -> engine.execSql(DatasourceType.MYSQL, conf, "DELETE FROM orders"));
        assertThrows(DatasourceException.class,
                () -> engine.execSql(DatasourceType.MYSQL, conf, "SELECT 1; DROP TABLE orders"));
    }
}
