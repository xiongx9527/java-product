package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验收 A2：方言由连接的数据库产品名动态解析，不再硬编码 MySQL。
 */
class AskdataNl2SqlDialectTest {

    private DatasourceType detectFor(String productName) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn(productName);
        return AskdataNl2Sql.detectDialect(ds);
    }

    @Test
    void postgresResolvesToPg() throws Exception {
        assertEquals(DatasourceType.PG, detectFor("PostgreSQL 15"));
    }

    @Test
    void oracleResolvesToOracle() throws Exception {
        assertEquals(DatasourceType.ORACLE, detectFor("Oracle"));
    }

    @Test
    void sqlServerResolvesToSqlServer() throws Exception {
        assertEquals(DatasourceType.SQLSERVER, detectFor("Microsoft SQL Server"));
    }

    @Test
    void h2ResolvesToH2() throws Exception {
        assertEquals(DatasourceType.H2, detectFor("H2"));
    }

    @Test
    void clickhouseResolvesToCk() throws Exception {
        assertEquals(DatasourceType.CK, detectFor("ClickHouse"));
    }

    @Test
    void mysqlStillResolvesToMysql() throws Exception {
        assertEquals(DatasourceType.MYSQL, detectFor("MySQL"));
    }

    @Test
    void unknownFallsBackToMysql() throws Exception {
        assertEquals(DatasourceType.MYSQL, detectFor("SomeExoticDb"));
    }
}
