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
 * 真实 MySQL 方言验证（integration）：仅当设置了 ASKDATA_MYSQL_HOST 等环境变量时运行，
 * 否则整类跳过。测试自建 fixture 表（askdata_verify.VERIFY_TBL）并验证 askdata 向真实
 * MySQL 下发的元数据 / 预览 SQL（反引号标识符、LIMIT 分页）可执行。无需 LLM。
 *
 * <p>本地（MySQL 可达）运行：</p>
 * <pre>
 * ASKDATA_MYSQL_HOST=192.168.1.100 ASKDATA_MYSQL_PORT=3306 \
 * ASKDATA_MYSQL_USER=root ASKDATA_MYSQL_PASSWORD=infini_rag_flow \
 * mvn -pl askdata/askdata-server test -Dtest=MySqlLiveDialectTest
 * </pre>
 *
 * <p>凭据仅来自环境变量，绝不写入代码或配置。</p>
 */
class MySqlLiveDialectTest {

    private DatasourceConf conf() {
        DatasourceConf c = new DatasourceConf();
        c.setHost(required("ASKDATA_MYSQL_HOST"));
        c.setPort(Integer.parseInt(required("ASKDATA_MYSQL_PORT")));
        c.setUsername(required("ASKDATA_MYSQL_USER"));
        c.setPassword(required("ASKDATA_MYSQL_PASSWORD"));
        c.setDatabase("askdata_verify");
        c.setDbSchema("");
        return c;
    }

    private String required(String name) {
        String v = System.getenv(name);
        assumeTrue(v != null && !v.isEmpty(), "未设置环境变量 " + name + "，跳过真实 MySQL 验证");
        return v;
    }

    private Connection connect(DatasourceConf c) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String url = "jdbc:mysql://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabase()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectTimeout=5000&socketTimeout=15000";
        Properties p = new Properties();
        p.setProperty("user", c.getUsername());
        p.setProperty("password", c.getPassword());
        return DriverManager.getConnection(url, p);
    }

    private void bootstrap(DatasourceConf c) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String sysUrl = "jdbc:mysql://" + c.getHost() + ":" + c.getPort() + "/mysql"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectTimeout=5000&socketTimeout=15000";
        Properties p = new Properties();
        p.setProperty("user", c.getUsername());
        p.setProperty("password", c.getPassword());
        try (Connection sys = DriverManager.getConnection(sysUrl, p);
             Statement st = sys.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS askdata_verify");
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
    void mysqlMetadataAndPreviewSqlExecuteOnRealMysql() throws Exception {
        DatasourceConf c = conf();
        bootstrap(c);
        try (Connection conn = connect(c)) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS VERIFY_TBL");
                st.execute("CREATE TABLE VERIFY_TBL (id INT PRIMARY KEY, name VARCHAR(50))");
                st.execute("INSERT INTO VERIFY_TBL (id, name) VALUES (1, 'alpha'), (2, 'beta')");
            }

            int verRows = runSqlText(conn, new DatasourceType.SqlText(DatasourceType.MYSQL.versionSql()));
            assertTrue(verRows >= 1, "versionSql 应至少返回一行");

            DatasourceType.SqlText tableSql = DatasourceType.MYSQL.tableSql(c, null);
            int tableRows = runSqlText(conn, tableSql);
            assertTrue(tableRows >= 1, "应能列出至少一张表（含 VERIFY_TBL）");

            DatasourceType.SqlText fieldSql = DatasourceType.MYSQL.fieldSql(c, "VERIFY_TBL");
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
            assertTrue(cols.stream().anyMatch(x -> x.equalsIgnoreCase("ID"))
                    && cols.stream().anyMatch(x -> x.equalsIgnoreCase("NAME")),
                    "fieldSql 应返回 ID/NAME 列: " + cols);

            String preview = DatasourceType.MYSQL.previewSql(cols, "VERIFY_TBL", c, null);
            assertTrue(preview.toLowerCase().contains("verify_tbl"), preview);
            assertTrue(preview.contains("LIMIT 100"), preview);
            String limited = AskdataNl2Sql.withRowLimit(preview, DatasourceType.MYSQL);
            assertEquals(preview, limited, "已分页 SQL 不应被重复追加 LIMIT");
            int previewRows = runSqlText(conn, new DatasourceType.SqlText(limited));
            assertEquals(2, previewRows, "预览应返回 fixture 的 2 行");

            int oneRows = runSqlText(conn,
                    new DatasourceType.SqlText(AskdataNl2Sql.withRowLimit("SELECT 1", DatasourceType.MYSQL)));
            assertEquals(1, oneRows, "SELECT 1 LIMIT 200 应在真实 MySQL 上返回 1 行");

            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS VERIFY_TBL");
            }
        }
    }
}
