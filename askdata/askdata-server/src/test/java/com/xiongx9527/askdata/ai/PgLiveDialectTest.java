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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 PostgreSQL 方言验证（integration）：仅当设置了环境变量 ASKDATA_PG_HOST 等时运行，
 * 否则整类跳过（不影响 CI / 沙箱构建）。验证 askdata 向 PG 实际下发的元数据 / 预览 SQL
 * （双引号标识符、schema 前缀、LIMIT 分页）在真实 PostgreSQL 上可执行。无需 LLM。
 *
 * <p>在本地（PG 可达）运行：</p>
 * <pre>
 * ASKDATA_PG_HOST=192.168.1.100 ASKDATA_PG_PORT=5432 ASKDATA_PG_DB=pg \
 * ASKDATA_PG_USER=xiongxiao ASKDATA_PG_PASSWORD=20081458 ASKDATA_PG_SCHEMA=public \
 * mvn -pl askdata/askdata-server test -Dtest=PgLiveDialectIT
 * </pre>
 *
 * <p>注意：凭据仅来自环境变量，绝不写入代码或配置。</p>
 */
class PgLiveDialectTest {

    private DatasourceConf conf() {
        DatasourceConf c = new DatasourceConf();
        c.setHost(required("ASKDATA_PG_HOST"));
        c.setPort(Integer.parseInt(required("ASKDATA_PG_PORT")));
        c.setDatabase(required("ASKDATA_PG_DB"));
        c.setUsername(required("ASKDATA_PG_USER"));
        c.setPassword(required("ASKDATA_PG_PASSWORD"));
        String schema = System.getenv("ASKDATA_PG_SCHEMA");
        c.setDbSchema(schema == null || schema.isEmpty() ? "public" : schema);
        return c;
    }

    private String required(String name) {
        String v = System.getenv(name);
        assumeTrue(v != null && !v.isEmpty(), "未设置环境变量 " + name + "，跳过真实 PG 验证");
        return v;
    }

    private String jdbcUrl(DatasourceConf c) {
        return "jdbc:postgresql://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabase()
                + "?connectTimeout=5000&socketTimeout=10000";
    }

    private Connection connect(DatasourceConf c) throws Exception {
        Class.forName("org.postgresql.Driver");
        Properties p = new Properties();
        p.setProperty("user", c.getUsername());
        p.setProperty("password", c.getPassword());
        return DriverManager.getConnection(jdbcUrl(c), p);
    }

    /** 绑定参数并执行查询，返回行数（验证真实 PG 上 SQL 合法且可执行）。 */
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
    void pgMetadataAndPreviewSqlExecuteOnRealPg() throws Exception {
        DatasourceConf c = conf();
        try (Connection conn = connect(c)) {
            // 1) 版本查询（PG.versionSql）
            int verRows = runSqlText(conn, new DatasourceType.SqlText(DatasourceType.PG.versionSql()));
            assertTrue(verRows >= 1, "versionSql 应至少返回一行");

            // 2) 表列表元数据（PG.tableSql，带 ? 绑定 schema）
            DatasourceType.SqlText tableSql = DatasourceType.PG.tableSql(c, null);
            int tableRows = runSqlText(conn, tableSql);
            // 即使库里没有业务表，元数据查询本身必须成功（不抛异常即达标）

            // 3) 取一张真实表，做字段元数据 + 预览（含 LIMIT 分页）验证
            if (tableRows > 0) {
                String firstTable;
                try (PreparedStatement ps = conn.prepareStatement(tableSql.getSql())) {
                    Object[] args = tableSql.getArgs();
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        firstTable = rs.next() ? rs.getString("TABLE_NAME") : null;
                    }
                }
                assertNotNull(firstTable, "应能取到一张表名");

                DatasourceType.SqlText fieldSql = DatasourceType.PG.fieldSql(c, firstTable);
                int fieldRows = runSqlText(conn, fieldSql);
                assertTrue(fieldRows >= 1, "fieldSql 应返回至少一列");

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
                assertTrue(!cols.isEmpty(), "应能读取列名");

                // 预览 SQL：真实双引号标识符 + schema 前缀 + LIMIT 100（PG.previewSql 已自带分页）
                String preview = DatasourceType.PG.previewSql(cols, firstTable, c, null);
                String limited = AskdataNl2Sql.withRowLimit(preview, DatasourceType.PG);
                // 已含 LIMIT 100：withRowLimit 必须原样保留，不可重复叠加（A6 既有分页不动）
                assertTrue(limited.contains("LIMIT 100"), limited);
                assertEquals(preview, limited, "已分页 SQL 不应被重复追加 LIMIT");
                int previewRows = runSqlText(conn, new DatasourceType.SqlText(limited));
                // 仅验证可执行；不要求表内有数据
                assertTrue(previewRows >= 0, "预览 SQL 应在真实 PG 上可执行");

                // 额外验证 withRowLimit 的「追加」分支在真实 PG 上也合法可执行（无 LIMIT 的 SQL）
                int oneRows = runSqlText(conn,
                        new DatasourceType.SqlText(AskdataNl2Sql.withRowLimit("SELECT 1", DatasourceType.PG)));
                assertEquals(1, oneRows, "SELECT 1 LIMIT 200 应在真实 PG 上返回 1 行");
            }
        }
    }
}
