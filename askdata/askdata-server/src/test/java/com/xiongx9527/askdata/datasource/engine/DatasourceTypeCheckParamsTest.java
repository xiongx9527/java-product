package com.xiongx9527.askdata.datasource.engine;

import com.xiongx9527.askdata.datasource.DatasourceException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非法 JDBC 连接参数拦截单测（对齐 SQLBot {@code apps/db/db.py} 的 {@code checkParams}）。
 */
class DatasourceTypeCheckParamsTest {

    @Test
    void mysqlRejectsLocalInfile() {
        assertThrows(IllegalArgumentException.class, () -> DatasourceType.checkParams(
                "local_infile=1", DatasourceType.MYSQL.illegalParams()));
    }

    @Test
    void mysqlAllowsOtherParams() {
        assertDoesNotThrow(() -> DatasourceType.checkParams(
                "useSSL=false&serverTimezone=Asia/Shanghai", DatasourceType.MYSQL.illegalParams()));
    }

    @Test
    void otherDialectsHaveNoIllegalParams() {
        assertTrue(DatasourceType.PG.illegalParams().isEmpty());
        assertTrue(DatasourceType.H2.illegalParams().isEmpty());
        assertDoesNotThrow(() -> DatasourceType.checkParams("local_infile=1",
                DatasourceType.PG.illegalParams()));
    }

    @Test
    void emptyParamsAreAllowed() {
        assertDoesNotThrow(() -> DatasourceType.checkParams(null,
                DatasourceType.MYSQL.illegalParams()));
        assertDoesNotThrow(() -> DatasourceType.checkParams("", DatasourceType.MYSQL.illegalParams()));
        assertDoesNotThrow(() -> DatasourceType.checkParams("a=1&b=2", null));
    }

    @Test
    void malformedItemsAreSkipped() {
        // 缺 "=" 的片段被跳过（不因畸形片段抛解析异常），
        // 但同一串里格式合法的 local_infile=1 仍必须被拦截（对齐 SQLBot checkParams）。
        assertThrows(IllegalArgumentException.class, () -> DatasourceType.checkParams(
                "noequals&local_infile=1", DatasourceType.MYSQL.illegalParams()));
        // 只有畸形片段时不抛错
        assertDoesNotThrow(() -> DatasourceType.checkParams("noequals&=novalue",
                DatasourceType.MYSQL.illegalParams()));
    }

    @Test
    void jdbcUrlBuildRejectsIllegalParams() {
        JdbcEngine engine = new JdbcEngine(null, null, new ConnectionPoolManager(10));
        DatasourceConf conf = new DatasourceConf();
        conf.setHost("127.0.0.1");
        conf.setPort(3306);
        conf.setDatabase("test");
        conf.setExtraJdbc("local_infile=1");
        DatasourceException e = assertThrows(DatasourceException.class,
                () -> engine.buildJdbcUrl(DatasourceType.MYSQL, conf));
        assertTrue(e.getMessage().contains("Illegal Parameter: local_infile"));
    }

    @Test
    void jdbcUrlBuildAcceptsLegalParams() {
        JdbcEngine engine = new JdbcEngine(null, null, new ConnectionPoolManager(10));
        DatasourceConf conf = new DatasourceConf();
        conf.setHost("127.0.0.1");
        conf.setPort(3306);
        conf.setDatabase("test");
        conf.setExtraJdbc("serverTimezone=UTC");
        String url = engine.buildJdbcUrl(DatasourceType.MYSQL, conf);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3306/test"));
        assertTrue(url.contains("serverTimezone=UTC"));
    }

    @Test
    void mysqlIllegalParamsMatchSqlBot() {
        assertEquals(Arrays.asList("local_infile"), DatasourceType.MYSQL.illegalParams());
    }
}
