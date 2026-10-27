package com.xiongx9527.askdata.datasource.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据源连接配置（对齐 SQLBot apps/datasource/models/datasource.py 的 {@code DatasourceConf}）。
 *
 * <p>前端把整套连接参数 JSON 以 CryptoJS AES-ECB（key {@code SQLBot1234567890}）加密后放入
 * {@code configuration} 字段；服务端解密得到本结构。取值全部容忍字符串/数字/空串混合
 * （前端 el-input 可能回传空串），缺省值与 SQLBot 一致。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DatasourceConf {

    private String host = "";
    private int port;
    private String username = "";
    private String password = "";
    private String database = "";
    private String driver = "";
    private String extraJdbc = "";
    private String dbSchema = "";
    private String filename = "";
    private List<Map<String, Object>> sheets = new ArrayList<>();
    private String mode = "";
    private int timeout = 30;
    private boolean lowVersion;
    private boolean ssl;
    private int poolSize = 5;
    /** A21：并发高峰可额外创建的连接数（SQLAlchemy max_overflow 语义）。 */
    private int maxOverflow = 10;
    /** A21：池满时等待空闲连接的超时（秒）。 */
    private int poolTimeout = 30;
    /** A18：连接池 recycle（秒，SQLAlchemy pool_recycle 语义）；借出超过该年龄的连接会被重建。 */
    private int recycle = 3600;
    /** A18：MySQL 允许公钥检索（仅显式开启时拼入 URL，默认不追加）。 */
    private boolean allowPublicKeyRetrieval;
    /** A18：Oracle 使用 OCI（thick）模式连接。 */
    private boolean thick;
    private String engine = "hive";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host == null ? "" : host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(Object value) {
        this.port = toInt(value, 0);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database == null ? "" : database;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver == null ? "" : driver;
    }

    public String getExtraJdbc() {
        return extraJdbc;
    }

    public void setExtraJdbc(String extraJdbc) {
        this.extraJdbc = extraJdbc == null ? "" : extraJdbc;
    }

    public String getDbSchema() {
        return dbSchema;
    }

    public void setDbSchema(String dbSchema) {
        this.dbSchema = dbSchema == null ? "" : dbSchema;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename == null ? "" : filename;
    }

    public List<Map<String, Object>> getSheets() {
        return sheets;
    }

    public void setSheets(List<Map<String, Object>> sheets) {
        this.sheets = sheets == null ? new ArrayList<>() : sheets;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode == null ? "" : mode;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(Object value) {
        this.timeout = toInt(value, 30);
    }

    public boolean isLowVersion() {
        return lowVersion;
    }

    public void setLowVersion(Object value) {
        this.lowVersion = toBoolean(value, false);
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(Object value) {
        this.ssl = toBoolean(value, false);
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(Object value) {
        this.poolSize = toInt(value, 5);
    }

    public int getMaxOverflow() {
        return maxOverflow;
    }

    @com.fasterxml.jackson.annotation.JsonAlias({"max_overflow"})
    public void setMaxOverflow(Object value) {
        this.maxOverflow = toInt(value, 10);
    }

    public int getPoolTimeout() {
        return poolTimeout;
    }

    @com.fasterxml.jackson.annotation.JsonAlias({"pool_timeout"})
    public void setPoolTimeout(Object value) {
        this.poolTimeout = toInt(value, 30);
    }

    public int getRecycle() {
        return recycle;
    }

    @com.fasterxml.jackson.annotation.JsonAlias({"pool_recycle", "poolRecycle"})
    public void setRecycle(Object value) {
        this.recycle = toInt(value, 3600);
    }

    public boolean isAllowPublicKeyRetrieval() {
        return allowPublicKeyRetrieval;
    }

    @com.fasterxml.jackson.annotation.JsonAlias({"allow_public_key_retrieval"})
    public void setAllowPublicKeyRetrieval(Object value) {
        this.allowPublicKeyRetrieval = toBoolean(value, false);
    }

    public boolean isThick() {
        return thick;
    }

    @com.fasterxml.jackson.annotation.JsonAlias({"thickMode", "useThick"})
    public void setThick(Object value) {
        this.thick = toBoolean(value, false);
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine == null || engine.isEmpty() ? "hive" : engine;
    }

    /**
     * 返回带默认 schema 的配置副本（A8：Excel 元数据查询使用固定默认 schema，
     * 不再向元数据 SQL 写入空 {@code db_schema}）。原配置不被修改。
     *
     * @param schema 默认 schema；空值返回原配置
     */
    public DatasourceConf withDefaultSchema(String schema) {
        if (schema == null || schema.isEmpty()) {
            return this;
        }
        DatasourceConf copy = new DatasourceConf();
        copy.host = host;
        copy.port = port;
        copy.username = username;
        copy.password = password;
        copy.database = database == null || database.isEmpty() ? schema : database;
        copy.driver = driver;
        copy.extraJdbc = extraJdbc;
        copy.dbSchema = dbSchema == null || dbSchema.isEmpty() ? schema : dbSchema;
        copy.filename = filename;
        copy.sheets = sheets;
        copy.mode = mode;
        copy.timeout = timeout;
        copy.lowVersion = lowVersion;
        copy.ssl = ssl;
        copy.poolSize = poolSize;
        copy.maxOverflow = maxOverflow;
        copy.poolTimeout = poolTimeout;
        copy.recycle = recycle;
        copy.allowPublicKeyRetrieval = allowPublicKeyRetrieval;
        copy.thick = thick;
        copy.engine = engine;
        return copy;
    }

    /** schema 缺省时的库/schema 名（SQLBot get_table_obj_by_ds 的语义：dbSchema 优先，退回 database）。 */
    public String schemaOrDatabase() {
        if (dbSchema != null && !dbSchema.isEmpty()) {
            return dbSchema;
        }
        return database == null ? "" : database;
    }

    private static int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }
}
