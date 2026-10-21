package com.xiongx9527.askdata.datasource.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.DataFormatUtil;
import com.xiongx9527.askdata.datasource.DatasourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 数据源引擎（移植 SQLBot apps/db/db.py 的 {@code get_engine/check_connection/get_tables/
 * get_fields/get_schema/get_version/exec_sql} + apps/db/es_engine.py 的 HTTP 面）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>连接策略对齐 SQLBot 的池化路径：按数据源 id 缓存连接池
 *       （{@link ConnectionPoolManager}，LRU 淘汰上限 500，淘汰即 dispose），
 *       借出的连接其 {@code close()} 被拦截为「归还连接池」，
 *       故 try-with-resources 写法与连接复用可并存；</li>
 *   <li>{@code excel} 类型走应用元数据库（主 {@link DataSource}，对齐 SQLBot get_engine_config），
 *       方言按主库实际产品名（H2/MySQL/PostgreSQL）选择，保证生产切换元数据库后仍正确；</li>
 *   <li>{@code es} 类型为 HTTP 协议：连接检查 ping {@code GET /}，元数据走 {@code _cat/indices} /
 *       {@code _mapping}（对齐 SQLBot es_engine.py 的行为面）；</li>
 *   <li>执行结果字段名保持原始大小写（origin_column=True，与 datasource 模块调用一致），
 *       值转换对齐 SQLBot {@code convert_value}。</li>
 * </ul>
 */
@Service
public class JdbcEngine {

    private static final Logger log = LoggerFactory.getLogger(JdbcEngine.class);

    private final JdbcTemplate appJdbc;

    private final ObjectMapper objectMapper;

    /** 数据源连接池（LRU 淘汰 + 归还语义；上限可配，默认 500）。 */
    private final ConnectionPoolManager poolManager;

    /** 主库方言缓存（excel 类型用）：H2/MySQL/PostgreSQL。 */
    private volatile DatasourceType appDbType;

    @Autowired
    public JdbcEngine(JdbcTemplate appJdbc, ObjectMapper objectMapper, ConnectionPoolManager poolManager) {
        this.appJdbc = appJdbc;
        this.objectMapper = objectMapper;
        this.poolManager = poolManager == null ? new ConnectionPoolManager() : poolManager;
    }

    /** 构造（不注入连接池时使用默认池管理器；保留旧签名以便既有调用点零改动）。 */
    public JdbcEngine(JdbcTemplate appJdbc, ObjectMapper objectMapper) {
        this(appJdbc, objectMapper, new ConnectionPoolManager());
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    /**
     * 连接检查（对齐 SQLBot check_connection）：成功返回 true；失败且 isRaise 时抛出
     * {@code 数据源连接失败: <原因>}（SQLBot trans('i18n_ds_invalid') + ': ' + e.args）。
     */
    public boolean checkConnection(DatasourceType type, DatasourceConf conf, boolean isRaise) {
        return checkConnection(type, conf, isRaise, null);
    }

    /** 连接检查（带数据源 id；命中该数据源的连接池）。 */
    public boolean checkConnection(DatasourceType type, DatasourceConf conf, boolean isRaise, Long datasourceId) {
        if (type.usesAppDatabase()) {
            return true;
        }
        if (type.isHttpProtocol()) {
            return checkEs(conf, isRaise);
        }
        try (Connection ignored = openConnection(type, conf, datasourceId)) {
            return true;
        } catch (Exception e) {
            log.info("Datasource connection failed [{}]: {}", type.getType(), e.getMessage());
            if (isRaise) {
                throw new DatasourceException("数据源连接失败: " + errorText(e), e);
            }
            return false;
        }
    }

    private Connection openConnection(DatasourceType type, DatasourceConf conf) throws SQLException {
        return openConnection(type, conf, null);
    }

    /**
     * 打开一条（池化的）外部数据源连接。
     *
     * @param datasourceId 数据源 id（已入库数据源传 id 以命中其池；未入库的临时配置传 null，
     *                     退化为按连接配置派生的池键）
     */
    private Connection openConnection(DatasourceType type, DatasourceConf conf, Long datasourceId) throws SQLException {
        if (type.usesAppDatabase()) {
            return appJdbc.getDataSource().getConnection();
        }
        String url = buildJdbcUrl(type, conf);
        String driver = type.getDriverClass();
        if (driver == null) {
            throw new DatasourceException("数据源连接失败: datasource type [" + type.getType()
                    + "] does not support jdbc connection");
        }
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new DatasourceException("数据源连接失败: jdbc driver [" + driver + "] not found, "
                    + "please put the driver jar into the extension directory (see README)", e);
        }
        int timeout = conf.getTimeout() > 0 ? conf.getTimeout() : 30;
        DriverManager.setLoginTimeout(Math.min(timeout, 30));
        String poolKey = datasourceId == null
                ? ConnectionPoolManager.poolKeyOfConfig(url, conf.getUsername())
                : ConnectionPoolManager.poolKeyOf(datasourceId);
        // 借出的连接其 close() 被拦截为「归还连接池」，try-with-resources 语义不变
        return poolManager.borrow(poolKey, () -> DriverManager.getConnection(url, conf.getUsername(),
                conf.getPassword()));
    }

    /**
     * JDBC 连接串（移植 SQLBot get_uri_from_config + get_engine 的 connect_args 语义）。
     * extraJdbc（k=v&k=v）按方言分隔符追加；非法项按 SQLBot get_extra_config 约定抛错。
     */
    public String buildJdbcUrl(DatasourceType type, DatasourceConf conf) {
        String url;
        // 非法 JDBC 参数拦截（对齐 SQLBot get_uri_from_config → checkParams）
        try {
            DatasourceType.checkParams(conf.getExtraJdbc(), type.illegalParams());
        } catch (IllegalArgumentException e) {
            throw new DatasourceException(e.getMessage(), e);
        }
        switch (type) {
            case MYSQL:
            case DORIS:
            case STARROCKS:
                url = "jdbc:mysql://" + host(conf) + ":" + port(conf) + "/" + conf.getDatabase();
                url = appendParams(url, "?", "&", "useUnicode=true&characterEncoding=utf8&useSSL="
                        + conf.isSsl() + "&allowPublicKeyRetrieval=true", conf.getExtraJdbc());
                break;
            case PG:
            case KINGBASE:
            case REDSHIFT:
                url = "jdbc:postgresql://" + host(conf) + ":" + port(conf) + "/" + conf.getDatabase();
                if (conf.getDbSchema() != null && !conf.getDbSchema().isEmpty()) {
                    // pgjdbc 原生 currentSchema 参数（等价 SQLBot 的 -c search_path 连接参数）
                    url = appendParams(url, "?", "&",
                            "currentSchema=" + encodeUri(conf.getDbSchema()), conf.getExtraJdbc());
                } else {
                    url = appendParams(url, "?", "&", null, conf.getExtraJdbc());
                }
                break;
            case H2:
                url = h2Url(conf);
                url = appendParams(url, url.contains("?") ? "&" : "?", "&", null, conf.getExtraJdbc());
                break;
            case SQLSERVER:
                url = "jdbc:sqlserver://" + host(conf) + ":" + port(conf) + ";databaseName=" + conf.getDatabase()
                        + ";encrypt=false";
                url = appendParams(url, ";", ";", null, conf.getExtraJdbc());
                break;
            case SQLITE:
                url = "jdbc:sqlite:" + conf.getFilename();
                break;
            case CK:
                url = "jdbc:ch://" + host(conf) + ":" + port(conf) + "/" + conf.getDatabase();
                url = appendParams(url, "?", "&", null, conf.getExtraJdbc());
                break;
            case ORACLE:
                if ("service_name".equalsIgnoreCase(conf.getMode()) || "serviceName".equalsIgnoreCase(conf.getMode())
                        || conf.getMode().isEmpty()) {
                    url = "jdbc:oracle:thin:@//" + host(conf) + ":" + port(conf) + "/" + conf.getDatabase();
                } else {
                    url = "jdbc:oracle:thin:@" + host(conf) + ":" + port(conf) + ":" + conf.getDatabase();
                }
                url = appendParams(url, "?", "&", null, conf.getExtraJdbc());
                break;
            case DM:
                url = "jdbc:dm://" + host(conf) + ":" + port(conf);
                if (conf.getDbSchema() != null && !conf.getDbSchema().isEmpty()) {
                    url = appendParams(url, "?", "&", "schema=" + encodeUri(conf.getDbSchema()), conf.getExtraJdbc());
                } else {
                    url = appendParams(url, "?", "&", null, conf.getExtraJdbc());
                }
                break;
            case HIVE:
                url = "jdbc:hive2://" + host(conf) + ":" + port(conf) + "/" + conf.getDatabase();
                url = appendParams(url, "?", "&", null, conf.getExtraJdbc());
                break;
            case ES:
                throw new DatasourceException("datasource type [es] uses http protocol, no jdbc url");
            case EXCEL:
                throw new DatasourceException("datasource type [excel] uses the application database, no jdbc url");
            default:
                throw new DatasourceException("The datasource type not support.");
        }
        return url;
    }

    private static String h2Url(DatasourceConf conf) {
        String database = conf.getDatabase() == null ? "" : conf.getDatabase().trim();
        if (database.startsWith("jdbc:h2:")) {
            return database;
        }
        if (conf.getHost() != null && !conf.getHost().isEmpty()) {
            return "jdbc:h2:tcp://" + conf.getHost() + ":" + port(conf) + "/" + database;
        }
        return "jdbc:h2:file:./data/" + database;
    }

    private static String host(DatasourceConf conf) {
        return conf.getHost() == null ? "" : conf.getHost().trim();
    }

    private static String port(DatasourceConf conf) {
        int port = conf.getPort();
        return port <= 0 ? "" : String.valueOf(port);
    }

    private static String encodeUri(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    /** extraJdbc 解析对齐 SQLBot get_extra_config：k=v&k=v，非法项抛错。 */
    private static String appendParams(String url, String firstSep, String sep, String builtin, String extraJdbc) {
        StringBuilder sb = new StringBuilder(url);
        List<String> pairs = new ArrayList<>();
        if (builtin != null && !builtin.isEmpty()) {
            pairs.add(builtin);
        }
        if (extraJdbc != null && !extraJdbc.isEmpty()) {
            for (String item : extraJdbc.split("&")) {
                if (item.isEmpty()) {
                    continue;
                }
                String[] kv = item.split("=", 2);
                if (kv.length != 2 || kv[0].isEmpty() || kv[1].isEmpty()) {
                    throw new DatasourceException("param: " + item + " is error");
                }
                pairs.add(item);
            }
        }
        boolean first = true;
        for (String pair : pairs) {
            sb.append(first ? firstSep : sep).append(pair);
            first = false;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 元数据
    // ------------------------------------------------------------------

    public String version(DatasourceType type, DatasourceConf conf) {
        return version(type, conf, null);
    }

    /** 版本查询（带数据源 id，命中该数据源的连接池）。 */
    public String version(DatasourceType type, DatasourceConf conf, Long datasourceId) {
        String sql = type.versionSql();
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        if (type.usesAppDatabase()) {
            return queryForString(appJdbc, sql);
        }
        if (type.isHttpProtocol()) {
            return "";
        }
        try (Connection connection = openConnection(type, conf, datasourceId);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                Object value = rs.getObject(1);
                return value == null ? "" : String.valueOf(value);
            }
        } catch (Exception e) {
            log.info("Get version failed [{}]: {}", type.getType(), e.getMessage());
            return "";
        }
        return "";
    }

    /** 表列表（对齐 SQLBot get_tables → TableSchemaResponse）。 */
    public List<TableSchema> tables(DatasourceType type, DatasourceConf conf) {
        return tables(type, conf, null);
    }

    /** 表列表（带数据源 id，命中该数据源的连接池）。 */
    public List<TableSchema> tables(DatasourceType type, DatasourceConf conf, Long datasourceId) {
        if (type.isHttpProtocol()) {
            return esIndices(conf);
        }
        DatasourceType dialect = dialectOf(type);
        DatasourceType.SqlText sqlText = dialect.tableSql(conf, version(type, conf, datasourceId));
        List<TableSchema> result = new ArrayList<>();
        for (Map<String, Object> row : query(dialect, type, conf, sqlText, datasourceId)) {
            result.add(new TableSchema(stringOf(row.get("TABLE_NAME")), stringOf(row.get("TABLE_COMMENT"))));
        }
        return result;
    }

    /** 字段列表（对齐 SQLBot get_fields → ColumnSchema）。 */
    public List<ColumnSchema> fields(DatasourceType type, DatasourceConf conf, String tableName) {
        return fields(type, conf, tableName, null);
    }

    /** 字段列表（带数据源 id，命中该数据源的连接池）。 */
    public List<ColumnSchema> fields(DatasourceType type, DatasourceConf conf, String tableName, Long datasourceId) {
        if (type.isHttpProtocol()) {
            return esFields(conf, tableName);
        }
        DatasourceType dialect = dialectOf(type);
        DatasourceType.SqlText sqlText = dialect.fieldSql(conf, tableName);
        List<ColumnSchema> result = new ArrayList<>();
        for (Map<String, Object> row : query(dialect, type, conf, sqlText, datasourceId)) {
            result.add(new ColumnSchema(stringOf(row.get("COLUMN_NAME")), stringOf(row.get("DATA_TYPE")),
                    stringOf(row.get("COLUMN_COMMENT"))));
        }
        return result;
    }

    /** schema 列表（对齐 SQLBot get_schema；不支持的方言返回空列表）。 */
    public List<String> schemas(DatasourceType type, DatasourceConf conf) {
        return schemas(type, conf, null);
    }

    /** schema 列表（带数据源 id，命中该数据源的连接池）。 */
    public List<String> schemas(DatasourceType type, DatasourceConf conf, Long datasourceId) {
        if (type.isHttpProtocol()) {
            return new ArrayList<>();
        }
        DatasourceType dialect = dialectOf(type);
        String sql = dialect.schemaSql();
        if (sql == null || sql.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Map<String, Object> row : query(dialect, type, conf, new DatasourceType.SqlText(sql), datasourceId)) {
            Object value = row.values().isEmpty() ? null : row.values().iterator().next();
            if (value != null) {
                result.add(String.valueOf(value));
            }
        }
        return result;
    }

    /**
     * excel 类型使用应用元数据库，方言按主库实际产品解析；
     * 其余类型即自身方言。
     */
    private DatasourceType dialectOf(DatasourceType type) {
        if (type.usesAppDatabase()) {
            return appDbType();
        }
        return type;
    }

    /** 应用元数据库方言类型（excel 元数据查询/标识符引用用）。 */
    public DatasourceType appDbType() {
        if (appDbType == null) {
            synchronized (this) {
                if (appDbType == null) {
                    String product;
                    try (Connection connection = appJdbc.getDataSource().getConnection()) {
                        product = connection.getMetaData().getDatabaseProductName();
                    } catch (Exception e) {
                        throw new DatasourceException("Resolve application database dialect failed: " + e.getMessage(),
                                e);
                    }
                    if (product != null && product.toLowerCase().contains("mysql")) {
                        appDbType = DatasourceType.MYSQL;
                    } else if (product != null && product.toLowerCase().contains("postgresql")) {
                        appDbType = DatasourceType.PG;
                    } else {
                        appDbType = DatasourceType.H2;
                    }
                }
            }
        }
        return appDbType;
    }

    private List<Map<String, Object>> query(DatasourceType dialect, DatasourceType type, DatasourceConf conf,
                                            DatasourceType.SqlText sqlText) {
        return query(dialect, type, conf, sqlText, null);
    }

    private List<Map<String, Object>> query(DatasourceType dialect, DatasourceType type, DatasourceConf conf,
                                            DatasourceType.SqlText sqlText, Long datasourceId) {
        if (sqlText.getSql() == null || sqlText.getSql().isEmpty()) {
            return new ArrayList<>();
        }
        if (type.usesAppDatabase()) {
            return appJdbc.queryForList(sqlText.getSql(), sqlText.getArgs());
        }
        try (Connection connection = openConnection(type, conf, datasourceId);
             java.sql.PreparedStatement statement = connection.prepareStatement(sqlText.getSql())) {
            bind(statement, sqlText.getArgs());
            try (ResultSet rs = statement.executeQuery()) {
                return toRows(rs);
            }
        } catch (SQLException e) {
            throw new DatasourceException("Query metadata failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 执行只读 SQL（对齐 SQLBot exec_sql 返回结构）：
     * {@code {"fields": [...], "data": [{col: value}], "fields_info": [{"name","is_numeric"}], "sql": base64}}。
     */
    public Map<String, Object> execSql(DatasourceType type, DatasourceConf conf, String sql) {
        return execSql(type, conf, sql, null);
    }

    /**
     * 执行只读 SQL（带数据源 id，命中该数据源的连接池）。
     *
     * <p>返回值经 {@link DataFormatUtil} 处理：大数转十进制字符串（无科学计数法）、
     * 限定列名 {@code alias.column} 归一补短列名。</p>
     */
    public Map<String, Object> execSql(DatasourceType type, DatasourceConf conf, String sql, Long datasourceId) {
        String statement = sql == null ? "" : sql.trim();
        while (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        SqlSafety.checkRead(statement, type.getType());
        String encoded = Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> data = new ArrayList<>();
        List<Object> fields = new ArrayList<>();
        List<Map<String, Object>> fieldsInfo = new ArrayList<>();
        if (type.isHttpProtocol()) {
            return esExec(conf, statement, encoded);
        }
        if (type.usesAppDatabase()) {
            appJdbc.query(statement, rs -> {
                collect(rs, fields, fieldsInfo, data);
                return null;
            });
        } else {
            try (Connection connection = openConnection(type, conf, datasourceId);
                 Statement stmt = connection.createStatement()) {
                applyTimeout(stmt, conf);
                try (ResultSet rs = stmt.executeQuery(statement)) {
                    collect(rs, fields, fieldsInfo, data);
                }
            } catch (SQLException e) {
                throw new DatasourceException("Execute SQL failed: " + e.getMessage(), e);
            }
        }
        return Rows0.of("fields", fields,
                "data", DataFormatUtil.normalizeQualifiedColumnKeysInRows(
                        DataFormatUtil.convertLargeNumbers(data)),
                "fields_info", fieldsInfo, "sql", encoded);
    }

    private void collect(ResultSet rs, List<Object> fields, List<Map<String, Object>> fieldsInfo,
                         List<Map<String, Object>> data) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
            fieldsInfo.add(Rows0.of("name", metaData.getColumnLabel(i), "is_numeric", isNumeric(metaData.getColumnType(i))));
        }
        fields.addAll(columns);
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1), convertValue(rs.getObject(i)));
            }
            data.add(row);
        }
    }

    private static void applyTimeout(Statement statement, DatasourceConf conf) {
        if (conf.getTimeout() > 0) {
            try {
                statement.setQueryTimeout(conf.getTimeout());
            } catch (SQLException ignored) {
                // 部分驱动不支持 queryTimeout，忽略（对齐 SQLBot 的尽力语义）
            }
        }
    }

    private static void bind(java.sql.PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    // ------------------------------------------------------------------
    // 应用元数据库 DDL（excel 建表/删表，对齐 SQLBot create_table / delete_ds）
    // ------------------------------------------------------------------

    /** 在应用元数据库执行 DDL/DML（excel 数据表的创建与删除；不走只读校验）。 */
    public void executeAppDdl(String sql) {
        try {
            appJdbc.execute(sql);
        } catch (Exception e) {
            throw new DatasourceException("Execute application database sql failed: " + e.getMessage(), e);
        }
    }

    /** 应用元数据库方言的标识符引用（excel 建表/删表用）。 */
    public String appQuote(String identifier) {
        return DatasourceType.quote(appDbType(), identifier);
    }

    /**
     * 应用元数据库标识符的规范化形态（excel 建表/查询共用的表名与列名）。
     *
     * <p>H2/MySQL 等库对未加引号标识符做大小写折叠（H2 折叠为大写、PostgreSQL 折叠为小写），
     * 若建表用带引号的小写名而后续查询（LLM 生成的 SQL）用未加引号标识符，两边形态不一致会报
     * {@code Table "XXX" not found}。此处按方言折叠为与未加引号写法等价的形态，保证
     * 建表 → getTables/getFields → previewData → execSql 全程一致。</p>
     */
    public String appIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        DatasourceType type = appDbType();
        if (type == DatasourceType.H2) {
            return identifier.toUpperCase(Locale.ROOT);
        }
        if (type == DatasourceType.PG) {
            return identifier.toLowerCase(Locale.ROOT);
        }
        return identifier;
    }

    /** 应用元数据库中的表是否存在（excel syncFields 存在性校验；DatabaseMetaData 跨库通用）。 */
    public boolean appTableExists(String tableName) {
        try (Connection connection = appJdbc.getDataSource().getConnection();
             ResultSet rs = connection.getMetaData()
                     .getTables(null, null, tableName, new String[]{"TABLE", "VIEW"})) {
            return rs.next();
        } catch (Exception e) {
            throw new DatasourceException("Check application table failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // ES（HTTP 协议，移植 apps/db/es_engine.py 的行为面）
    // ------------------------------------------------------------------

    private String esBaseUrl(DatasourceConf conf) {
        String host = conf.getHost() == null ? "" : conf.getHost().trim();
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        }
        int port = conf.getPort() > 0 ? conf.getPort() : 9200;
        return "http://" + host + ":" + port;
    }

    /** 连接检查：GET /（对齐 SQLBot es_conn.ping()）。 */
    public boolean checkEs(DatasourceConf conf, boolean isRaise) {
        try {
            int code = esGet(conf, "/", null);
            if (code >= 200 && code < 300) {
                return true;
            }
            if (isRaise) {
                throw new DatasourceException("数据源连接失败: es ping http " + code);
            }
            return false;
        } catch (DatasourceException e) {
            throw e;
        } catch (Exception e) {
            if (isRaise) {
                throw new DatasourceException("数据源连接失败: " + errorText(e), e);
            }
            return false;
        }
    }

    private List<TableSchema> esIndices(DatasourceConf conf) {
        List<TableSchema> result = new ArrayList<>();
        try {
            String body = esGetBody(conf, "/_cat/indices?format=json&h=index");
            if (body == null) {
                return result;
            }
            for (Map<String, Object> item : objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {
            })) {
                Object index = item.get("index");
                if (index != null) {
                    result.add(new TableSchema(String.valueOf(index), ""));
                }
            }
        } catch (Exception e) {
            throw new DatasourceException("Get es index failed: " + errorText(e), e);
        }
        return result;
    }

    private List<ColumnSchema> esFields(DatasourceConf conf, String indexName) {
        List<ColumnSchema> result = new ArrayList<>();
        try {
            String body = esGetBody(conf, "/" + encodeUri(indexName == null ? "" : indexName) + "/_mapping");
            if (body == null) {
                return result;
            }
            Map<String, Object> root = objectMapper.readValue(body,
                    new TypeReference<Map<String, Object>>() {
                    });
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                Object mappings = ((Map<?, ?>) entry.getValue()).get("mappings");
                if (mappings instanceof Map) {
                    Object properties = ((Map<?, ?>) mappings).get("properties");
                    if (properties instanceof Map) {
                        for (Map.Entry<?, ?> field : ((Map<?, ?>) properties).entrySet()) {
                            Object type = field.getValue() instanceof Map
                                    ? ((Map<?, ?>) field.getValue()).get("type") : null;
                            result.add(new ColumnSchema(String.valueOf(field.getKey()),
                                    type == null ? "" : String.valueOf(type), ""));
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new DatasourceException("Get es fields failed: " + errorText(e), e);
        }
        return result;
    }

    private Map<String, Object> esExec(DatasourceConf conf, String sql, String encoded) {
        String path = "/_sql?format=json";
        String payload = "{\"query\": " + objectMapper.valueToTree(sql).toString() + "}";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(esBaseUrl(conf) + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "application/json");
            if (conf.getUsername() != null && !conf.getUsername().isEmpty()) {
                String auth = Base64.getEncoder().encodeToString(
                        (conf.getUsername() + ":" + conf.getPassword()).getBytes(StandardCharsets.UTF_8));
                connection.setRequestProperty("Authorization", "Basic " + auth);
            }
            connection.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            connection.getOutputStream().flush();
            int code = connection.getResponseCode();
            String body = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code >= 400) {
                throw new DatasourceException("Execute SQL failed: es http " + code + (body.isEmpty() ? "" : ": " + body));
            }
            List<Object> fields = new ArrayList<>();
            List<Map<String, Object>> fieldsInfo = new ArrayList<>();
            List<Map<String, Object>> data = new ArrayList<>();
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
            Object columns = parsed.get("columns");
            if (columns instanceof List) {
                for (Object column : (List<?>) columns) {
                    if (column instanceof Map) {
                        Object name = ((Map<?, ?>) column).get("name");
                        Object type = ((Map<?, ?>) column).get("type");
                        String columnName = name == null ? "" : String.valueOf(name);
                        fields.add(columnName);
                        fieldsInfo.add(Rows0.of("name", columnName, "is_numeric", isEsNumeric(type)));
                    }
                }
            }
            Object rows = parsed.get("rows");
            if (rows instanceof List) {
                for (Object row : (List<?>) rows) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    if (row instanceof List) {
                        List<?> values = (List<?>) row;
                        for (int i = 0; i < fields.size() && i < values.size(); i++) {
                            item.put(String.valueOf(fields.get(i)), convertValue(values.get(i)));
                        }
                    }
                    data.add(item);
                }
            }
            return Rows0.of("fields", fields, "data", data, "fields_info", fieldsInfo, "sql", encoded);
        } catch (DatasourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DatasourceException("Execute SQL failed: " + errorText(e), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isEsNumeric(Object type) {
        if (type == null) {
            return false;
        }
        String text = String.valueOf(type).toLowerCase();
        return text.equals("long") || text.equals("integer") || text.equals("short") || text.equals("byte")
                || text.equals("double") || text.equals("float") || text.equals("half_float")
                || text.equals("scaled_float") || text.equals("unsigned_long") || text.equals("boolean");
    }

    private int esGet(DatasourceConf conf, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(esBaseUrl(conf) + path).openConnection();
        try {
            connection.setRequestMethod(body == null ? "GET" : "POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(60000);
            if (conf.getUsername() != null && !conf.getUsername().isEmpty()) {
                String auth = Base64.getEncoder().encodeToString(
                        (conf.getUsername() + ":" + conf.getPassword()).getBytes(StandardCharsets.UTF_8));
                connection.setRequestProperty("Authorization", "Basic " + auth);
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private String esGetBody(DatasourceConf conf, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(esBaseUrl(conf) + path).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(60000);
            if (conf.getUsername() != null && !conf.getUsername().isEmpty()) {
                String auth = Base64.getEncoder().encodeToString(
                        (conf.getUsername() + ":" + conf.getPassword()).getBytes(StandardCharsets.UTF_8));
                connection.setRequestProperty("Authorization", "Basic " + auth);
            }
            int code = connection.getResponseCode();
            if (code >= 400) {
                throw new DatasourceException("es http " + code);
            }
            return readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    private static String queryForString(JdbcTemplate jdbc, String sql) {
        try {
            Object value = jdbc.queryForObject(sql, Object.class);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception e) {
            log.info("Query [{}] failed: {}", sql, e.getMessage());
            return "";
        }
    }

    private static List<Map<String, Object>> toRows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(metaData.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 值转换（移植 SQLBot convert_value）：bytes→bool/int/字符串，
     * BigDecimal→double，Timestamp→"yyyy-MM-dd HH:mm:ss"，Date→ISO，Time→字符串。
     */
    public static Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return convertBytes((byte[]) value);
        }
        if (value instanceof Blob) {
            try {
                Blob blob = (Blob) value;
                long length = blob.length();
                if (length <= Integer.MAX_VALUE) {
                    return convertBytes(blob.getBytes(1, (int) length));
                }
                return readAll(blob.getBinaryStream());
            } catch (Exception e) {
                return null;
            } finally {
                try {
                    ((Blob) value).free();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).doubleValue();
        }
        // 字符大对象：H2 的 CLOB 列经 getObject 可能返回 Clob 或 Reader，直接序列化会抛
        // No serializer found for class java.io.BufferedReader（对齐 SQLBot convert_value 的文本语义）
        if (value instanceof Clob) {
            return readClob((Clob) value);
        }
        if (value instanceof Reader) {
            return readReader((Reader) value);
        }
        if (value instanceof Timestamp) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((Timestamp) value);
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().toString();
        }
        if (value instanceof Time) {
            return value.toString();
        }
        return value;
    }

    /** Clob → 字符串（读取失败返回 null，对齐 Blob 分支的尽力语义）。 */
    private static String readClob(Clob clob) {
        try {
            return readReader(clob.getCharacterStream());
        } catch (Exception e) {
            return null;
        } finally {
            try {
                clob.free();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static String readReader(Reader reader) {
        if (reader == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader buffered = reader instanceof BufferedReader
                ? (BufferedReader) reader : new BufferedReader(reader)) {
            char[] buffer = new char[2048];
            int length;
            while ((length = buffered.read(buffer)) != -1) {
                sb.append(buffer, 0, length);
            }
        } catch (Exception e) {
            return null;
        }
        return sb.toString();
    }

    private static Object convertBytes(byte[] bytes) {
        if (bytes.length <= 8) {
            try {
                int intValue = 0;
                for (byte b : bytes) {
                    intValue = (intValue << 8) | (b & 0xff);
                }
                if (intValue == 0 || intValue == 1) {
                    return intValue == 1;
                }
                return intValue;
            } catch (Exception ignored) {
                // fall through
            }
        }
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            for (byte b : bytes) {
                if (b < 32 && b != 9 && b != 10 && b != 13) {
                    StringBuilder hex = new StringBuilder("0x");
                    for (byte item : bytes) {
                        hex.append(String.format("%02x", item));
                    }
                    return hex.toString();
                }
            }
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static boolean isNumeric(int sqlType) {
        switch (sqlType) {
            case java.sql.Types.TINYINT:
            case java.sql.Types.SMALLINT:
            case java.sql.Types.INTEGER:
            case java.sql.Types.BIGINT:
            case java.sql.Types.FLOAT:
            case java.sql.Types.REAL:
            case java.sql.Types.DOUBLE:
            case java.sql.Types.NUMERIC:
            case java.sql.Types.DECIMAL:
            case java.sql.Types.BIT:
            case java.sql.Types.BOOLEAN:
                return true;
            default:
                return false;
        }
    }

    private static String stringOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            Object converted = convertBytes((byte[]) value);
            return converted == null ? "" : String.valueOf(converted);
        }
        return String.valueOf(value);
    }

    private static String errorText(Throwable e) {
        if (e == null) {
            return "";
        }
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.toString() : message;
    }

    private static String readAll(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, length);
            }
        }
        return sb.toString();
    }

    /** 保持插入顺序的响应 Map（避免依赖 common 包，方便引擎层独立测试）。 */
    private static final class Rows0 {

        private Rows0() {
        }

        static Map<String, Object> of(Object... keyValues) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
            return map;
        }
    }
}
