package com.xiongx9527.askdata.datasource.engine;

import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.engine.DatasourceType.SqlText;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎策略层单测（A12 类型覆盖的离线证据）：连接串、标识符引用、预览 SQL、
 * 元数据 SQL、类型注册表。真实连通在 {@code JdbcEngineSmokeTest} 验证。
 */
class DatasourceTypeStrategyTest {

    private DatasourceConf conf(String host, int port, String user, String password, String database,
                                String dbSchema) {
        DatasourceConf conf = new DatasourceConf();
        conf.setHost(host);
        conf.setPort(port);
        conf.setUsername(user);
        conf.setPassword(password);
        conf.setDatabase(database);
        conf.setDbSchema(dbSchema);
        return conf;
    }

    @Test
    void allFifteenTypesRegistered() {
        // A12：excel + 12 库 + h2/sqlite 扩展
        assertEquals(15, DatasourceType.values().length);
        for (String type : Arrays.asList("excel", "mysql", "pg", "h2", "sqlServer", "sqlite", "ck", "doris",
                "starrocks", "oracle", "dm", "kingbase", "redshift", "es", "hive")) {
            assertNotNull(DatasourceType.fromType(type), type);
        }
        // 前端下拉 13 类必须全部可解析
        for (DatasourceType type : DatasourceType.selectable()) {
            assertNotNull(DatasourceType.fromType(type.getType()));
        }
        assertThrows(IllegalArgumentException.class, () -> DatasourceType.fromType("not_exists"));
    }

    @Test
    void jdbcUrlsPerType() {
        DatasourceConf my = conf("127.0.0.1", 3306, "root", "p@ss", "db1", "");
        // A18：allowPublicKeyRetrieval 默认不追加
        assertEquals("jdbc:mysql://127.0.0.1:3306/db1?useUnicode=true&characterEncoding=utf8&useSSL=false",
                url(DatasourceType.MYSQL, my));
        my.setAllowPublicKeyRetrieval(true);
        assertEquals("jdbc:mysql://127.0.0.1:3306/db1?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&allowPublicKeyRetrieval=true", url(DatasourceType.MYSQL, my));

        DatasourceConf pg = conf("pg.host", 5432, "u", "p", "db1", "public");
        assertEquals("jdbc:postgresql://pg.host:5432/db1?currentSchema=public", url(DatasourceType.PG, pg));

        DatasourceConf ss = conf("ss.host", 1433, "u", "p", "db1", "dbo");
        assertEquals("jdbc:sqlserver://ss.host:1433;databaseName=db1;encrypt=false", url(DatasourceType.SQLSERVER, ss));
        // A18：lowVersion 追加旧版服务器兼容参数（URL 与驱动参数均不同）
        ss.setLowVersion(true);
        String lowVersionUrl = url(DatasourceType.SQLSERVER, ss);
        assertTrue(lowVersionUrl.contains(";encrypt=false"), lowVersionUrl);
        assertTrue(lowVersionUrl.contains(";trustServerCertificate=true"), lowVersionUrl);
        assertTrue(lowVersionUrl.contains(";sendStringParametersAsUnicode=false"), lowVersionUrl);

        DatasourceConf oracleService = conf("o.host", 1521, "u", "p", "ORCL", "");
        oracleService.setMode("service_name");
        assertEquals("jdbc:oracle:thin:@//o.host:1521/ORCL", url(DatasourceType.ORACLE, oracleService));
        oracleService.setMode("sid");
        assertEquals("jdbc:oracle:thin:@o.host:1521:ORCL", url(DatasourceType.ORACLE, oracleService));
        // A18：thick（OCI）模式按配置启用
        oracleService.setThick(true);
        assertEquals("jdbc:oracle:oci:@o.host:1521:ORCL", url(DatasourceType.ORACLE, oracleService));
        oracleService.setMode("service_name");
        assertEquals("jdbc:oracle:oci:@//o.host:1521/ORCL", url(DatasourceType.ORACLE, oracleService));

        DatasourceConf ck = conf("ck.host", 8123, "u", "p", "default", "");
        assertEquals("jdbc:ch://ck.host:8123/default", url(DatasourceType.CK, ck));

        DatasourceConf kb = conf("kb.host", 54321, "u", "p", "db1", "public");
        // A8：人大金仓驱动只认 jdbc:kingbase8:// 前缀（com.kingbase8.Driver.acceptsURL 实测），
        // 不复用 pgjdbc 的 jdbc:postgresql://；参数形态保持 PG 兼容
        assertEquals("jdbc:kingbase8://kb.host:54321/db1?currentSchema=public", url(DatasourceType.KINGBASE, kb));
        assertEquals("jdbc:postgresql://rs.host:5439/db1?currentSchema=public",
                url(DatasourceType.REDSHIFT, conf("rs.host", 5439, "u", "p", "db1", "public")));
        assertEquals("jdbc:dm://dm.host:5236?schema=SCHEMA", url(DatasourceType.DM, conf("dm.host", 5236, "u", "p", "", "SCHEMA")));
        // A18：Hive 配置密码即启用 LDAP（auth=LDAP + user 连接属性）
        assertEquals("jdbc:hive2://h.host:10000/db1;auth=LDAP;user=u",
                url(DatasourceType.HIVE, conf("h.host", 10000, "u", "p", "db1", "")));
        assertEquals("jdbc:hive2://h.host:10000/db1",
                url(DatasourceType.HIVE, conf("h.host", 10000, "u", "", "db1", "")));

        DatasourceConf h2File = conf("", 0, "sa", "", "testdb", "");
        assertEquals("jdbc:h2:file:./data/testdb", url(DatasourceType.H2, h2File));
        DatasourceConf h2Verbatim = conf("", 0, "sa", "", "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1", "");
        assertEquals("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1", url(DatasourceType.H2, h2Verbatim));

        DatasourceConf sqlite = conf("", 0, "", "", "", "");
        sqlite.setFilename("/tmp/demo.db");
        assertEquals("jdbc:sqlite:/tmp/demo.db", url(DatasourceType.SQLITE, sqlite));
    }

    /**
     * A8：Excel 元数据查询使用固定默认 schema（PG public / H2 PUBLIC / SQL Server dbo），
     * 不再向元数据 SQL 绑定空 schema。
     */
    @Test
    void excelMetadataUsesFixedDefaultSchema() {
        JdbcEngine engine = new JdbcEngine(null, new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals("public", engine.defaultAppSchema(DatasourceType.PG));
        assertEquals("PUBLIC", engine.defaultAppSchema(DatasourceType.H2));
        assertEquals("dbo", engine.defaultAppSchema(DatasourceType.SQLSERVER));

        DatasourceConf pg = new DatasourceConf();
        DatasourceConf withSchema = pg.withDefaultSchema(engine.defaultAppSchema(DatasourceType.PG));
        assertEquals("public", withSchema.getDbSchema());
        assertEquals("public", DatasourceType.PG.fieldSql(withSchema, "excel_t").getArgs()[0]);
        assertEquals("public", DatasourceType.PG.tableSql(withSchema, "").getArgs()[0]);
        // 原配置不被修改
        assertEquals("", pg.getDbSchema());
    }

    @Test
    void extraJdbcAppendedAndValidated() {
        DatasourceConf conf = conf("h", 3306, "u", "p", "db", "");
        conf.setExtraJdbc("useSSL=true&connectTimeout=5000");
        assertEquals("jdbc:mysql://h:3306/db?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&useSSL=true&connectTimeout=5000", url(DatasourceType.MYSQL, conf));
        conf.setExtraJdbc("broken");
        assertThrows(DatasourceException.class, () -> url(DatasourceType.MYSQL, conf));
    }

    @Test
    void identifiersAndPreviewSqlPerDialect() {
        assertEquals("`t`", DatasourceType.quote(DatasourceType.MYSQL, "t"));
        assertEquals("[t]", DatasourceType.quote(DatasourceType.SQLSERVER, "t"));
        assertEquals("\"t\"", DatasourceType.quote(DatasourceType.PG, "t"));

        List<String> fields = Arrays.asList("a", "b");
        DatasourceConf conf = conf("h", 3306, "u", "p", "db", "");
        assertTrue(DatasourceType.MYSQL.previewSql(fields, "orders", conf, null)
                .startsWith("SELECT `a`, `b` FROM `orders`"));
        assertTrue(DatasourceType.MYSQL.previewSql(fields, "orders", conf, null).endsWith("LIMIT 100"));

        DatasourceConf pgConf = conf("h", 5432, "u", "p", "db", "public");
        assertTrue(DatasourceType.PG.previewSql(fields, "orders", pgConf, null)
                .startsWith("SELECT \"a\", \"b\" FROM \"public\".\"orders\""));

        DatasourceConf ssConf = conf("h", 1433, "u", "p", "db", "dbo");
        assertTrue(DatasourceType.SQLSERVER.previewSql(fields, "orders", ssConf, null)
                .startsWith("SELECT TOP 100 [a], [b] FROM [dbo].[orders]"));

        DatasourceConf oracleConf = conf("h", 1521, "u", "p", "ORCL", "SCOTT");
        String oracleSql = DatasourceType.ORACLE.previewSql(fields, "orders", oracleConf, null);
        assertTrue(oracleSql.contains("ROWNUM <= 100"));
        assertTrue(oracleSql.contains("\"SCOTT\".\"orders\""));

        // where 子句透传（行权限预留给增值模块）
        assertTrue(DatasourceType.MYSQL.previewSql(fields, "orders", conf, "where id > 1").contains("where id > 1"));
    }

    @Test
    void metadataSqlPerDialect() {
        DatasourceConf conf = conf("h", 3306, "root", "p", "db1", "");
        SqlText mysqlTables = DatasourceType.MYSQL.tableSql(conf, "8.0.33");
        assertTrue(mysqlTables.getSql().contains("information_schema.TABLES"));
        assertEquals(1, mysqlTables.getArgs().length);
        assertEquals("db1", mysqlTables.getArgs()[0]);

        SqlText mysqlFields = DatasourceType.MYSQL.fieldSql(conf, "orders");
        assertTrue(mysqlFields.getSql().contains("AND TABLE_NAME = ?"));
        assertEquals(2, mysqlFields.getArgs().length);

        SqlText pgFields = DatasourceType.PG.fieldSql(conf("h", 5432, "u", "p", "d", "public"), "orders");
        assertTrue(pgFields.getSql().contains("pg_catalog.pg_attribute"));
        assertEquals("public", pgFields.getArgs()[0]);

        assertEquals("SHOW TABLES", DatasourceType.HIVE.tableSql(conf, "").getSql());
        assertEquals("DESCRIBE `orders`", DatasourceType.HIVE.fieldSql(conf, "orders").getSql());

        // ClickHouse 版本分支（<22 无 comment 列，以 '' AS comment 占位）
        assertTrue(DatasourceType.CK.tableSql(conf, "21.8").getSql().contains("'' AS comment"));
        assertTrue(DatasourceType.CK.tableSql(conf, "22.8").getSql().contains("name, comment"));
        // 版本不可解析时按新版处理
        assertTrue(DatasourceType.CK.tableSql(conf, "").getSql().contains("name, comment"));

        // schema 列表：pg/h2/sqlServer/oracle/dm 有 SQL，mysql/ck/hive 无（返回空列表）
        assertNotNull(DatasourceType.PG.schemaSql());
        assertNotNull(DatasourceType.H2.schemaSql());
        assertNotNull(DatasourceType.SQLSERVER.schemaSql());
        assertNull(DatasourceType.MYSQL.schemaSql());
        assertNull(DatasourceType.CK.schemaSql());
        assertNull(DatasourceType.HIVE.schemaSql());
        assertNull(DatasourceType.ES.schemaSql());

        // 版本 SQL：mysql/pg/h2/ck/oracle 有，redshift/hive 无
        assertNotNull(DatasourceType.MYSQL.versionSql());
        assertNotNull(DatasourceType.H2.versionSql());
        assertNull(DatasourceType.REDSHIFT.versionSql());
        assertNull(DatasourceType.HIVE.versionSql());
    }

    @Test
    void specialTypes() {
        assertTrue(DatasourceType.EXCEL.usesAppDatabase());
        assertFalse(DatasourceType.MYSQL.usesAppDatabase());
        assertTrue(DatasourceType.ES.isHttpProtocol());
        assertFalse(DatasourceType.HIVE.isHttpProtocol());
        // es/excel 无 JDBC 驱动
        assertNull(DatasourceType.ES.getDriverClass());
        assertNull(DatasourceType.EXCEL.getDriverClass());
        assertEquals("com.mysql.cj.jdbc.Driver", DatasourceType.MYSQL.getDriverClass());
        // 展示名（type_name 落库值）与 SQLBot DB.db_name 一致
        assertEquals("PostgreSQL", DatasourceType.PG.getDbName());
        assertEquals("达梦", DatasourceType.DM.getDbName());
        assertEquals("Apache Doris", DatasourceType.DORIS.getDbName());
        assertEquals("Excel/CSV", DatasourceType.EXCEL.getDbName());
    }

    private String url(DatasourceType type, DatasourceConf conf) {
        JdbcEngine engine = new JdbcEngine(null, null);
        return engine.buildJdbcUrl(type, conf);
    }
}
