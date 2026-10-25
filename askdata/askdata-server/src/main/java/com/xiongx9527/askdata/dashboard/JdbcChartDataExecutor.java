package com.xiongx9527.askdata.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.datasource.support.DatasourceCrypto;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图表数据执行器：直连 JDBC 只读执行（对齐 SQLBot load_resource → get_chart_data_ds 行为）。
 *
 * <p>连接信息来自 {@code core_datasource.configuration}（前端 AES 加密 JSON，key 与
 * {@link CryptoUtil#SQLBOT_AES_KEY} 一致）；数据源表未就绪或连接失败时返回
 * {@code status=failed}，仪表盘仍可打开（与 SQLBot 容错语义一致）。</p>
 */
@Component
public class JdbcChartDataExecutor implements ChartDataExecutor {

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    public JdbcChartDataExecutor(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> execute(Long datasourceId, String sql) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "failed");
        result.put("data", new java.util.ArrayList<>());
        result.put("message", "");
        if (datasourceId == null || sql == null || sql.trim().isEmpty()) {
            result.put("message", "datasource and sql are required");
            return result;
        }
        try {
            Map<String, Object> ds = loadDatasource(datasourceId);
            if (ds == null) {
                result.put("message", "Datasource not found");
                return result;
            }
            String type = str(ds.get("type"));
            Map<String, Object> config = parseConfig(str(ds.get("configuration")));
            String url = jdbcUrl(type, config);
            String username = str(config.get("username"));
            String password = CryptoUtil.resolvePlain(str(config.get("password")));
            try (Connection connection = openConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.setMaxRows(10000);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columns = meta.getColumnCount();
                    java.util.List<Map<String, Object>> data = new java.util.ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns; i++) {
                            row.put(meta.getColumnLabel(i), rs.getObject(i));
                        }
                        data.add(row);
                    }
                    result.put("status", "success");
                    result.put("data", data);
                }
            }
        } catch (Exception e) {
            result.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return result;
    }

    private Map<String, Object> loadDatasource(long datasourceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'CORE_DATASOURCE'",
                Integer.class);
        if (count == null || count == 0) {
            return null;
        }
        return jdbc.queryForList("SELECT id, name, type, configuration FROM core_datasource WHERE id = ?",
                datasourceId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> parseConfig(String configuration) throws Exception {
        if (configuration == null || configuration.isEmpty()) {
            return new LinkedHashMap<>();
        }
        // 与 DatasourceService 落库形态保持一致：应用 SECRET_KEY 优先，SQLBot 前端密钥回退。
        String json = DatasourceCrypto.decodeConfiguration(configuration);
        JsonNode node = objectMapper.readTree(json);
        Map<String, Object> config = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> config.put(entry.getKey(),
                entry.getValue().isNull() ? null : entry.getValue().asText()));
        return config;
    }

    /** 数据类型 → JDBC URL（覆盖 P2 的 mysql/h2/postgresql；其余类型按 jdbc:<type> 约定兜底）。 */
    static String jdbcUrl(String type, Map<String, Object> config) {
        String host = str(config.get("host"));
        Object portValue = config.get("port");
        String port = portValue == null || String.valueOf(portValue).isEmpty() ? "" : String.valueOf(portValue);
        String database = str(config.get("database"));
        String extra = str(config.get("extraJdbc"));
        String dbSchema = str(config.get("dbSchema"));
        String t = type == null ? "" : type.toLowerCase();
        if ("mysql".equals(t)) {
            return "jdbc:mysql://" + host + (port.isEmpty() ? "" : ":" + port) + "/" + database
                    + (extra == null || extra.isEmpty() ? "" : "?" + extra);
        }
        if ("postgresql".equals(t)) {
            return "jdbc:postgresql://" + host + (port.isEmpty() ? "" : ":" + port) + "/" + database
                    + (extra == null || extra.isEmpty() ? "" : "?" + extra);
        }
        if ("h2".equals(t)) {
            return database == null || database.isEmpty() ? "jdbc:h2:mem:" : database;
        }
        // excel/doris/ck 等：无通用 JDBC 形态，返回空串交由调用方报错
        return "jdbc:" + t + "://" + host + (port.isEmpty() ? "" : ":" + port) + "/" + database;
    }

    private Connection openConnection(String url, String username, String password) throws SQLException {
        if (url == null || url.isEmpty()) {
            throw new SQLException("Unsupported datasource type for direct jdbc execution");
        }
        if (username == null || username.isEmpty()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
