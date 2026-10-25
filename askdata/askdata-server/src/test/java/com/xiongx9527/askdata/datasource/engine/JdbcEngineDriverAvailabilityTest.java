package com.xiongx9527.askdata.datasource.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.datasource.DatasourceException;
import org.junit.jupiter.api.Test;

import java.sql.Driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A8 数据源驱动可用性证据：六类新驱动（Oracle、SQLServer、ClickHouse、Hive、Kingbase、DM）
 * 进入 runtime scope 后，驱动类可由 {@link JdbcEngine} 按类型加载，
 * 且 JDBC URL 按方言正确构造。
 *
 * <p>不连接真实数据库（无网络），只验证「驱动类在 classpath + URL 形态」，
 * 与 SQLBot 「前端可选即可用」的验收面一致。</p>
 */
class JdbcEngineDriverAvailabilityTest {

    private final JdbcEngine engine = new JdbcEngine(null, new ObjectMapper());

    private DatasourceConf conf(String host, int port, String database) {
        DatasourceConf conf = new DatasourceConf();
        conf.setHost(host);
        conf.setPort(port);
        conf.setDatabase(database);
        conf.setUsername("u");
        conf.setPassword("p");
        conf.setTimeout(5);
        return conf;
    }

    /** 六类驱动类均可加载（runtime scope 生效），且接受 JdbcEngine 构造的 JDBC URL。 */
    @Test
    void sixDriversAreLoadable() throws Exception {
        DatasourceType[] types = {
                DatasourceType.ORACLE,
                DatasourceType.SQLSERVER,
                DatasourceType.CK,
                DatasourceType.HIVE,
                DatasourceType.KINGBASE,
                DatasourceType.DM,
        };
        for (DatasourceType type : types) {
            String driverClass = type.getDriverClass();
            assertNotNull(driverClass, type.getType() + " must declare a driver class");
            Driver driver;
            try {
                driver = (Driver) Class.forName(driverClass).getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new AssertionError("driver [" + driverClass + "] for type [" + type.getType()
                        + "] not loadable: " + e, e);
            }
            assertNotNull(driver, driverClass + " must be a java.sql.Driver");
            String url = engine.buildJdbcUrl(type, conf("db.host", defaultPort(type), "testdb"));
            assertTrue(driver.acceptsURL(url),
                    "driver [" + driverClass + "] for type [" + type.getType() + "] rejects url: " + url);
        }
    }

    private static int defaultPort(DatasourceType type) {
        switch (type) {
            case ORACLE:
                return 1521;
            case SQLSERVER:
                return 1433;
            case CK:
                return 8123;
            case HIVE:
                return 10000;
            case KINGBASE:
                return 54321;
            default:
                return 5236;
        }
    }

    /** 六类数据源的 JDBC URL 形态对齐 SQLBot get_uri_from_config。 */
    @Test
    void jdbcUrlsPerType() {
        assertEquals("jdbc:oracle:thin:@//db.host:1521/ORCL",
                engine.buildJdbcUrl(DatasourceType.ORACLE, conf("db.host", 1521, "ORCL")));
        assertEquals("jdbc:oracle:thin:@db.host:1521:ORCL",
                engine.buildJdbcUrl(DatasourceType.ORACLE, sidConf()));
        assertEquals("jdbc:sqlserver://db.host:1433;databaseName=master;encrypt=false",
                engine.buildJdbcUrl(DatasourceType.SQLSERVER, conf("db.host", 1433, "master")));
        assertEquals("jdbc:ch://ck.host:8123/default",
                engine.buildJdbcUrl(DatasourceType.CK, conf("ck.host", 8123, "default")));
        assertEquals("jdbc:hive2://hive.host:10000/warehouse",
                engine.buildJdbcUrl(DatasourceType.HIVE, conf("hive.host", 10000, "warehouse")));
        assertEquals("jdbc:kingbase8://kb.host:54321/test",
                engine.buildJdbcUrl(DatasourceType.KINGBASE, conf("kb.host", 54321, "test")));
        assertEquals("jdbc:dm://dm.host:5236/test",
                engine.buildJdbcUrl(DatasourceType.DM, conf("dm.host", 5236, "test")));
    }

    /** 无驱动的类型（es/excel）按 SQLBot 语义抛错而不是静默。 */
    @Test
    void nonJdbcTypesHaveNoUrl() {
        assertThrows(DatasourceException.class,
                () -> engine.buildJdbcUrl(DatasourceType.ES, conf("es.host", 9200, null)));
        assertThrows(DatasourceException.class,
                () -> engine.buildJdbcUrl(DatasourceType.EXCEL, conf(null, 0, "demo")));
    }

    private DatasourceConf sidConf() {
        DatasourceConf conf = conf("db.host", 1521, "ORCL");
        conf.setMode("sid");
        return conf;
    }
}
