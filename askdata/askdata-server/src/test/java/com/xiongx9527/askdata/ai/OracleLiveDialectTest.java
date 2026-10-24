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
 * 真实 Oracle 方言验证（integration）：仅当设置了 ASKDATA_ORACLE_HOST 等环境变量时运行，
 * 否则整类跳过。测试自建 fixture 表（SYSTEM.VERIFY_TBL）并验证 askdata 向真实 Oracle XE
 * 下发的元数据 / 预览 SQL（双引号标识符、schema 前缀、ROWNUM 分页）可执行。无需 LLM。
 *
 * <p>Oracle 21c XE 默认 PDB 为 XEPDB1，使用 SERVICE_NAME 连接；密码为 ORACLE_PASSWORD
 * 指定的 SYS/SYSTEM 口令。</p>
 *
 * <pre>
 * ASKDATA_ORACLE_HOST=192.168.1.100 ASKDATA_ORACLE_PORT=1521 \
 * ASKDATA_ORACLE_USER=SYSTEM ASKDATA_ORACLE_PASSWORD=20081458 \
 * ASKDATA_ORACLE_SERVICE=XEPDB1 \
 * mvn -pl askdata/askdata-server test -Dtest=OracleLiveDialectTest
 * </pre>
 */
class OracleLiveDialectTest {

    private DatasourceConf conf() {
        DatasourceConf c = new DatasourceConf();
        c.setHost(required("ASKDATA_ORACLE_HOST"));
        c.setPort(Integer.parseInt(required("ASKDATA_ORACLE_PORT")));
        c.setUsername(required("ASKDATA_ORACLE_USER"));
        c.setPassword(required("ASKDATA_ORACLE_PASSWORD"));
        c.setDatabase(required("ASKDATA_ORACLE_SERVICE"));
        c.setDbSchema("SYSTEM");
        return c;
    }

    private String required(String name) {
        String v = System.getenv(name);
        assumeTrue(v != null && !v.isEmpty(), "未设置环境变量 " + name + "，跳过真实 Oracle 验证");
        return v;
    }

    private Connection connect(DatasourceConf c) throws Exception {
        Class.forName("oracle.jdbc.OracleDriver");
        DriverManager.setLoginTimeout(20);
        String url = "jdbc:oracle:thin:@" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabase();
        Properties p = new Properties();
        p.setProperty("user", c.getUsername());
        p.setProperty("password", c.getPassword());
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "15000");
        p.setProperty("oracle.jdbc.ReadTimeout", "20000");
        return DriverManager.getConnection(url, p);
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
    void oracleMetadataAndPreviewSqlExecuteOnRealOracle() throws Exception {
        DatasourceConf c = conf();
        try (Connection conn = connect(c)) {
            // 幂等 fixture：先删（忽略不存在）再建 + 灌两条
            try (Statement st = conn.createStatement()) {
                st.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE VERIFY_TBL'; EXCEPTION WHEN OTHERS THEN NULL; END;");
                st.execute("CREATE TABLE VERIFY_TBL (id NUMBER, name VARCHAR2(50))");
                st.execute("INSERT INTO VERIFY_TBL VALUES (1, 'alpha')");
                st.execute("INSERT INTO VERIFY_TBL VALUES (2, 'beta')");
            }

            // 1) 版本查询（ORACLE.versionSql）
            int verRows = runSqlText(conn, new DatasourceType.SqlText(DatasourceType.ORACLE.versionSql()));
            assertTrue(verRows >= 1, "versionSql 应至少返回一行");

            // 2) 表列表元数据（ORACLE.tableSql，OWNER = schema）
            DatasourceType.SqlText tableSql = DatasourceType.ORACLE.tableSql(c, null);
            int tableRows = runSqlText(conn, tableSql);
            assertTrue(tableRows >= 1, "应能列出至少一张表（含 VERIFY_TBL）");

            // 3) 字段元数据
            DatasourceType.SqlText fieldSql = DatasourceType.ORACLE.fieldSql(c, "VERIFY_TBL");
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
            assertTrue(cols.contains("ID") && cols.contains("NAME"), "fieldSql 应返回 ID/NAME 列: " + cols);

            // 4) 预览 SQL：schema 前缀 + ROWNUM<=100，真实执行应返回 2 行
            String preview = DatasourceType.ORACLE.previewSql(cols, "VERIFY_TBL", c, null);
            assertTrue(preview.toUpperCase().contains("\"SYSTEM\".\"VERIFY_TBL\""), preview);
            assertTrue(preview.toUpperCase().contains("ROWNUM"), preview);
            String limited = AskdataNl2Sql.withRowLimit(preview, DatasourceType.ORACLE);
            assertEquals(preview, limited, "已分页 SQL（含 ROWNUM）不应被重复追加 FETCH FIRST");
            int previewRows = runSqlText(conn, new DatasourceType.SqlText(limited));
            assertEquals(2, previewRows, "预览应返回 fixture 的 2 行");

            // 5) withRowLimit 追加分支（无分页的 SQL）在真实 Oracle 合法可执行
            int oneRows = runSqlText(conn,
                    new DatasourceType.SqlText(AskdataNl2Sql.withRowLimit("SELECT 1 FROM dual", DatasourceType.ORACLE)));
            assertEquals(1, oneRows, "SELECT 1 FROM dual FETCH FIRST 200 ROWS ONLY 应返回 1 行");

            try (Statement st = conn.createStatement()) {
                st.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE VERIFY_TBL'; EXCEPTION WHEN OTHERS THEN NULL; END;");
            }
        }
    }
}
