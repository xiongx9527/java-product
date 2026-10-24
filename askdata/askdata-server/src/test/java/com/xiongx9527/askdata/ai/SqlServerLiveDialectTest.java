package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 SQL Server 方言验证（integration）：仅当设置了 ASKDATA_SQLSERVER_HOST 等环境变量时
 * 运行，否则整类跳过。测试自建 fixture 表（dbo.VERIFY_TBL）并验证 askdata 向真实 SQL Server
 * 下发的元数据 / 预览 SQL（方括号标识符、TOP 100 分页、OFFSET/FETCH 追加分支）可执行。无需 LLM。
 *
 * <pre>
 * ASKDATA_SQLSERVER_HOST=192.168.1.100 ASKDATA_SQLSERVER_PORT=1433 \
 * ASKDATA_SQLSERVER_USER=sa ASKDATA_SQLSERVER_PASSWORD=Askdata@2026 \
 * mvn -pl askdata/askdata-server test -Dtest=SqlServerLiveDialectTest
 * </pre>
 */
class SqlServerLiveDialectTest {

    private DatasourceConf conf() {
        DatasourceConf c = new DatasourceConf();
        c.setHost(required("ASKDATA_SQLSERVER_HOST"));
        c.setPort(Integer.parseInt(required("ASKDATA_SQLSERVER_PORT")));
        c.setUsername(required("ASKDATA_SQLSERVER_USER"));
        c.setPassword(required("ASKDATA_SQLSERVER_PASSWORD"));
        c.setDatabase("askdata_verify");
        c.setDbSchema("dbo");
        return c;
    }

    private String required(String name) {
        String v = System.getenv(name);
        assumeTrue(v != null && !v.isEmpty(), "未设置环境变量 " + name + "，跳过真实 SQL Server 验证");
        return v;
    }

    private Connection connect(String database) throws Exception {
        DatasourceConf c = conf();
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        DriverManager.setLoginTimeout(20);
        String url = "jdbc:sqlserver://" + c.getHost() + ":" + c.getPort()
                + ";databaseName=" + database + ";encrypt=false;trustServerCertificate=true"
                + ";loginTimeout=15;socketTimeout=20000";
        Properties p = new Properties();
        p.setProperty("user", c.getUsername());
        p.setProperty("password", c.getPassword());
        return DriverManager.getConnection(url, p);
    }

    private void bootstrap() throws Exception {
        try (Connection master = connect("master");
             Statement st = master.createStatement()) {
            st.execute("IF DB_ID('askdata_verify') IS NULL CREATE DATABASE askdata_verify");
        }
    }

    private int runSqlText(Connection conn, DatasourceType.SqlText st) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(st.getSql())) {
            Object[] args = st.getArgs();
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int n = 0;
                while (rs.next()) {
                    n++;
                }
                return n;
            }
        }
    }

    @Test
    void sqlServerMetadataAndPreviewSqlExecuteOnRealSqlServer() throws Exception {
        bootstrap();
        DatasourceConf c = conf();
        try (Connection conn = connect(c.getDatabase())) {
            try (Statement st = conn.createStatement()) {
                st.execute("IF OBJECT_ID('dbo.VERIFY_TBL', 'U') IS NOT NULL DROP TABLE dbo.VERIFY_TBL");
                st.execute("CREATE TABLE dbo.VERIFY_TBL (id INT PRIMARY KEY, name NVARCHAR(50))");
                st.execute("INSERT INTO dbo.VERIFY_TBL (id, name) VALUES (1, 'alpha'), (2, 'beta')");
            }

            int verRows = runSqlText(conn, new DatasourceType.SqlText(DatasourceType.SQLSERVER.versionSql()));
            assertTrue(verRows >= 1, "versionSql 应至少返回一行");

            DatasourceType.SqlText tableSql = DatasourceType.SQLSERVER.tableSql(c, null);
            int tableRows = runSqlText(conn, tableSql);
            assertTrue(tableRows >= 1, "应能列出至少一张表（含 VERIFY_TBL）");

            DatasourceType.SqlText fieldSql = DatasourceType.SQLSERVER.fieldSql(c, "VERIFY_TBL");
            List<String> cols = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(fieldSql.getSql())) {
                Object[] args = fieldSql.getArgs();
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        cols.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
            assertTrue(cols.contains("id") || cols.contains("ID"), "fieldSql 应返回 id 列: " + cols);
            assertTrue(cols.contains("name") || cols.contains("NAME"), "fieldSql 应返回 name 列: " + cols);

            String preview = DatasourceType.SQLSERVER.previewSql(cols, "VERIFY_TBL", c, null);
            assertTrue(preview.toUpperCase().contains("TOP 100"), preview);
            String limited = AskdataNl2Sql.withRowLimit(preview, DatasourceType.SQLSERVER);
            assertEquals(preview, limited, "已分页 SQL（含 TOP）不应被重复追加 OFFSET/FETCH");
            int previewRows = runSqlText(conn, new DatasourceType.SqlText(limited));
            assertEquals(2, previewRows, "预览应返回 fixture 的 2 行");

            // OFFSET/FETCH 追加分支需配合 ORDER BY 才合法；验证其真实可执行
            int oneRows = runSqlText(conn, new DatasourceType.SqlText(
                    AskdataNl2Sql.withRowLimit("SELECT 1 AS x ORDER BY x", DatasourceType.SQLSERVER)));
            assertEquals(1, oneRows, "SELECT 1 ... OFFSET 0 ROWS FETCH NEXT 200 ROWS ONLY 应返回 1 行");

            try (Statement st = conn.createStatement()) {
                st.execute("IF OBJECT_ID('dbo.VERIFY_TBL', 'U') IS NOT NULL DROP TABLE dbo.VERIFY_TBL");
            }
        }
    }
}
