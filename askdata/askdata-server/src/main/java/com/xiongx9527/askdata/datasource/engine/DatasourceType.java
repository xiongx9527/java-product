package com.xiongx9527.askdata.datasource.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 数据源类型与方言策略（对齐 SQLBot apps/db/constant.py 的 {@code DB} 枚举 +
 * apps/db/db_sql.py 的元数据 SQL + apps/db/db.py 的连接串/预览 SQL）。
 *
 * <p>覆盖（spec §2.2 / 验收 A12）：</p>
 * <ul>
 *   <li>首选全集（真实连通 + 元数据 + 执行）：{@link #EXCEL}、{@link #MYSQL}、{@link #PG}、{@link #H2}；</li>
 *   <li>JDBC 通用可测：{@link #SQLSERVER}、{@link #SQLITE}、{@link #CK}、{@link #DORIS}、{@link #STARROCKS}；</li>
 *   <li>连接级检查 + 方言策略：{@link #ORACLE}、{@link #DM}、{@link #KINGBASE}、{@link #REDSHIFT}、
 *       {@link #ES}、{@link #HIVE}（驱动缺失或语义不保证时按 SQLBot 错误约定返回）。</li>
 * </ul>
 *
 * <p>驱动类缺失（未放扩展目录）时连接检查返回连接失败错误，行为对齐 SQLBot
 * {@code check_connection} 的 is_raise 分支。H2 为 askdata 扩展类型（元数据库即 H2，
 * 便于开箱验证；SQLBot DB 枚举无此项）。</p>
 */
public enum DatasourceType {

    /** Excel/CSV：表存放在应用元数据库（对齐 SQLBot get_engine_config 语义），无外部驱动。 */
    EXCEL("excel", "Excel/CSV", "\"", "\"", null, "PostgreSQL") {
        @Override
        public boolean usesAppDatabase() {
            return true;
        }
    },

    MYSQL("mysql", "MySQL", "`", "`", "com.mysql.cj.jdbc.Driver", "MySQL") {
        @Override
        public String versionSql() {
            return "SELECT VERSION()";
        }

        @Override
        public List<String> illegalParams() {
            // SQLBot DB.mysql.illegalParams：禁止 local_infile（本地文件读取，防目录穿越）；
            // 同时封禁现代 Connector/J 的同义参数 allowLoadLocalInfile（A5：非法 JDBC 连接参数拦截）
            return Collections.unmodifiableList(Arrays.asList("local_infile", "allowLoadLocalInfile"));
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT TABLE_NAME, COALESCE(TABLE_COMMENT, '') AS TABLE_COMMENT "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?", conf.getDatabase());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT COLUMN_NAME, DATA_TYPE, COALESCE(COLUMN_COMMENT, '') AS COLUMN_COMMENT "
                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDatabase());
            }
            return new SqlText(sql + " AND TABLE_NAME = ?", conf.getDatabase(), tableName);
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM " + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    PG("pg", "PostgreSQL", "\"", "\"", "org.postgresql.Driver", "PostgreSQL") {
        @Override
        public String versionSql() {
            return "SELECT current_setting('server_version')";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT c.relname AS TABLE_NAME, "
                    + "COALESCE(COALESCE(d.description, obj_description(c.oid)), '') AS TABLE_COMMENT "
                    + "FROM pg_class c LEFT JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "LEFT JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = 0 "
                    + "WHERE n.nspname = ? AND c.relkind IN ('r', 'v', 'p', 'm') "
                    + "AND c.relname NOT LIKE 'pg_%' AND c.relname NOT LIKE 'sql_%' ORDER BY c.relname",
                    conf.getDbSchema());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT a.attname AS COLUMN_NAME, "
                    + "pg_catalog.format_type(a.atttypid, a.atttypmod) AS DATA_TYPE, "
                    + "col_description(c.oid, a.attnum) AS COLUMN_COMMENT "
                    + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON a.attrelid = c.oid "
                    + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND a.attnum > 0 AND NOT a.attisdropped";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDbSchema());
            }
            return new SqlText(sql + " AND c.relname = ?", conf.getDbSchema(), tableName);
        }

        @Override
        public String schemaSql() {
            return "SELECT nspname FROM pg_namespace";
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM "
                    + quote(this, defaultSchema(conf)) + "." + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    /** askdata 扩展：H2 数据源（开发/验证默认库）。 */
    H2("h2", "H2", "\"", "\"", "org.h2.Driver", "H2") {
        @Override
        public String versionSql() {
            return "SELECT H2VERSION()";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT TABLE_NAME, COALESCE(REMARKS, '') AS TABLE_COMMENT "
                    + "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? "
                    + "AND TABLE_TYPE IN ('BASE TABLE', 'VIEW') ORDER BY TABLE_NAME", h2Schema(conf));
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT COLUMN_NAME, DATA_TYPE, COALESCE(REMARKS, '') AS COLUMN_COMMENT "
                    + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ?";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, h2Schema(conf));
            }
            return new SqlText(sql + " AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION", h2Schema(conf), tableName);
        }

        @Override
        public String schemaSql() {
            return "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA";
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM "
                    + quote(this, h2Schema(conf)) + "." + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    SQLSERVER("sqlServer", "Microsoft SQL Server", "[", "]", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "Microsoft_SQL_Server") {
        @Override
        public String versionSql() {
            return "select SERVERPROPERTY('ProductVersion')";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT t.TABLE_NAME, ISNULL(ep.value, '') AS TABLE_COMMENT "
                    + "FROM INFORMATION_SCHEMA.TABLES t LEFT JOIN sys.extended_properties ep "
                    + "ON ep.major_id = OBJECT_ID(t.TABLE_SCHEMA + '.' + t.TABLE_NAME) AND ep.minor_id = 0 "
                    + "AND ep.name = 'MS_Description' "
                    + "WHERE t.TABLE_TYPE IN ('BASE TABLE', 'VIEW') AND t.TABLE_SCHEMA = ?", conf.getDbSchema());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT c.COLUMN_NAME, c.DATA_TYPE, ISNULL(ep.value, '') AS COLUMN_COMMENT "
                    + "FROM INFORMATION_SCHEMA.COLUMNS c LEFT JOIN sys.extended_properties ep "
                    + "ON ep.major_id = OBJECT_ID(c.TABLE_SCHEMA + '.' + c.TABLE_NAME) "
                    + "AND ep.minor_id = c.ORDINAL_POSITION AND ep.name = 'MS_Description' "
                    + "WHERE c.TABLE_SCHEMA = ?";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDbSchema());
            }
            return new SqlText(sql + " AND c.TABLE_NAME = ?", conf.getDbSchema(), tableName);
        }

        @Override
        public String schemaSql() {
            return "select name from sys.schemas";
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT TOP 100 " + quoteAll(this, fields) + " FROM "
                    + quote(this, conf.getDbSchema()) + "." + quote(this, tableName)
                    + (where == null ? "" : " " + where);
        }
    },

    SQLITE("sqlite", "SQLite", "\"", "\"", "org.sqlite.JDBC", "SQLite") {
        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT name AS TABLE_NAME, '' AS TABLE_COMMENT FROM sqlite_master "
                    + "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            // table-valued pragma 函数支持参数绑定
            return new SqlText("SELECT name AS COLUMN_NAME, type AS DATA_TYPE, '' AS COLUMN_COMMENT "
                    + "FROM pragma_table_info(?)", tableName);
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM " + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    CK("ck", "ClickHouse", "\"", "\"", "com.clickhouse.jdbc.ClickHouseDriver", "ClickHouse") {
        @Override
        public String versionSql() {
            return "select version()";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            int major = majorVersion(dbVersion);
            if (major > 0 && major < 22) {
                return new SqlText("SELECT name, '' AS comment FROM system.tables "
                        + "WHERE database = ? AND engine NOT IN ('Dictionary') ORDER BY name", conf.getDatabase());
            }
            return new SqlText("SELECT name, comment FROM system.tables "
                    + "WHERE database = ? AND engine NOT IN ('Dictionary') ORDER BY name", conf.getDatabase());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT name AS COLUMN_NAME, type AS DATA_TYPE, comment AS COLUMN_COMMENT "
                    + "FROM system.columns WHERE database = ?";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDatabase());
            }
            return new SqlText(sql + " AND table = ?", conf.getDatabase(), tableName);
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM " + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    DORIS("doris", "Apache Doris", "`", "`", "com.mysql.cj.jdbc.Driver", "Doris") {
        @Override
        public String versionSql() {
            return "SELECT VERSION()";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT TABLE_NAME, COALESCE(TABLE_COMMENT, '') AS TABLE_COMMENT "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?", conf.getDatabase());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT COLUMN_NAME, DATA_TYPE, COALESCE(COLUMN_COMMENT, '') AS COLUMN_COMMENT "
                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDatabase());
            }
            return new SqlText(sql + " AND TABLE_NAME = ?", conf.getDatabase(), tableName);
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM " + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    STARROCKS("starrocks", "StarRocks", "`", "`", "com.mysql.cj.jdbc.Driver", "StarRocks") {
        @Override
        public String versionSql() {
            return "SELECT VERSION()";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT TABLE_NAME, COALESCE(TABLE_COMMENT, '') AS TABLE_COMMENT "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?", conf.getDatabase());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT COLUMN_NAME, DATA_TYPE, COALESCE(COLUMN_COMMENT, '') AS COLUMN_COMMENT "
                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDatabase());
            }
            return new SqlText(sql + " AND TABLE_NAME = ?", conf.getDatabase(), tableName);
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM " + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    ORACLE("oracle", "Oracle", "\"", "\"", "oracle.jdbc.OracleDriver", "Oracle") {
        @Override
        public String versionSql() {
            return "SELECT version FROM v$instance";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT DISTINCT t.TABLE_NAME, NVL(c.COMMENTS, '') AS TABLE_COMMENT FROM ("
                    + "SELECT TABLE_NAME, 'TABLE' AS OBJECT_TYPE FROM ALL_TABLES WHERE OWNER = ? "
                    + "UNION ALL SELECT VIEW_NAME AS TABLE_NAME, 'VIEW' AS OBJECT_TYPE FROM ALL_VIEWS WHERE OWNER = ? "
                    + "UNION ALL SELECT MVIEW_NAME AS TABLE_NAME, 'MATERIALIZED VIEW' AS OBJECT_TYPE "
                    + "FROM ALL_MVIEWS WHERE OWNER = ?) t LEFT JOIN ALL_TAB_COMMENTS c "
                    + "ON t.TABLE_NAME = c.TABLE_NAME AND c.TABLE_TYPE = t.OBJECT_TYPE AND c.OWNER = ? "
                    + "ORDER BY t.TABLE_NAME",
                    conf.getDbSchema(), conf.getDbSchema(), conf.getDbSchema(), conf.getDbSchema());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT col.COLUMN_NAME, (CASE "
                    + "WHEN col.DATA_TYPE IN ('VARCHAR2', 'CHAR', 'NVARCHAR2', 'NCHAR') "
                    + "THEN col.DATA_TYPE || '(' || col.DATA_LENGTH || ')' "
                    + "WHEN col.DATA_TYPE = 'NUMBER' AND col.DATA_PRECISION IS NOT NULL "
                    + "THEN col.DATA_TYPE || '(' || col.DATA_PRECISION || "
                    + "CASE WHEN col.DATA_SCALE > 0 THEN ',' || col.DATA_SCALE END || ')' "
                    + "ELSE col.DATA_TYPE END) AS DATA_TYPE, NVL(com.COMMENTS, '') AS COLUMN_COMMENT "
                    + "FROM ALL_TAB_COLUMNS col LEFT JOIN ALL_COL_COMMENTS com "
                    + "ON col.OWNER = com.OWNER AND col.TABLE_NAME = com.TABLE_NAME "
                    + "AND col.COLUMN_NAME = com.COLUMN_NAME WHERE col.OWNER = ?";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDbSchema());
            }
            return new SqlText(sql + " AND col.TABLE_NAME = ?", conf.getDbSchema(), tableName);
        }

        @Override
        public String schemaSql() {
            return "select * from all_users";
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT * FROM (SELECT " + quoteAll(this, fields) + " FROM "
                    + quote(this, conf.getDbSchema()) + "." + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " ORDER BY " + quote(this, fields.get(0))
                    + ") WHERE ROWNUM <= 100";
        }
    },

    DM("dm", "达梦", "\"", "\"", "dm.jdbc.driver.DmDriver", "DM") {
        @Override
        public String versionSql() {
            return "SELECT * FROM v$version";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("select table_name, comments from all_tab_comments where owner = ? "
                    + "AND (table_type = 'TABLE' or table_type = 'VIEW')", conf.getDbSchema());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT c.COLUMN_NAME, c.DATA_TYPE, COALESCE(com.COMMENTS, '') AS COLUMN_COMMENT "
                    + "FROM ALL_TAB_COLUMNS c LEFT JOIN ALL_COL_COMMENTS com "
                    + "ON c.TABLE_NAME = com.TABLE_NAME AND c.COLUMN_NAME = com.COLUMN_NAME WHERE c.OWNER = ?";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDbSchema());
            }
            return new SqlText(sql + " AND c.TABLE_NAME = ?", conf.getDbSchema(), tableName);
        }

        @Override
        public String schemaSql() {
            return "select OBJECT_NAME from all_objects where object_type = 'SCH'";
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM "
                    + quote(this, conf.getDbSchema()) + "." + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    KINGBASE("kingbase", "Kingbase", "\"", "\"", "com.kingbase8.Driver", "Kingbase") {
        @Override
        public String versionSql() {
            return "SELECT current_setting('server_version')";
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT c.relname AS TABLE_NAME, "
                    + "COALESCE(COALESCE(d.description, obj_description(c.oid)), '') AS TABLE_COMMENT "
                    + "FROM pg_class c LEFT JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "LEFT JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = 0 "
                    + "WHERE n.nspname = ? AND c.relkind IN ('r', 'v', 'p', 'm') "
                    + "AND c.relname NOT LIKE 'pg_%' AND c.relname NOT LIKE 'sql_%' ORDER BY c.relname",
                    conf.getDbSchema());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT a.attname AS COLUMN_NAME, "
                    + "pg_catalog.format_type(a.atttypid, a.atttypmod) AS DATA_TYPE, "
                    + "col_description(c.oid, a.attnum) AS COLUMN_COMMENT "
                    + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON a.attrelid = c.oid "
                    + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND a.attnum > 0 AND NOT a.attisdropped";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDbSchema());
            }
            return new SqlText(sql + " AND c.relname = ?", conf.getDbSchema(), tableName);
        }

        @Override
        public String schemaSql() {
            return "SELECT nspname FROM pg_namespace";
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM "
                    + quote(this, defaultSchema(conf)) + "." + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    /** Redshift 为 PostgreSQL 线协议兼容，复用 pg 驱动（SQLBot 用 redshift_connector，此处按 JDBC 通用可测）。 */
    REDSHIFT("redshift", "AWS Redshift", "\"", "\"", "org.postgresql.Driver", "AWS_Redshift") {
        @Override
        public String versionSql() {
            return null;
        }

        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SELECT relname AS TableName, "
                    + "obj_description(relfilenode::regclass, 'pg_class') AS TableDescription FROM pg_class "
                    + "WHERE relkind in ('r','p', 'f') "
                    + "AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?)", conf.getDbSchema());
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            String sql = "SELECT a.attname AS COLUMN_NAME, "
                    + "pg_catalog.format_type(a.atttypid, a.atttypmod) AS DATA_TYPE, "
                    + "col_description(c.oid, a.attnum) AS COLUMN_COMMENT "
                    + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON a.attrelid = c.oid "
                    + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND a.attnum > 0 AND NOT a.attisdropped";
            if (tableName == null || tableName.isEmpty()) {
                return new SqlText(sql, conf.getDbSchema());
            }
            return new SqlText(sql + " AND c.relname = ?", conf.getDbSchema(), tableName);
        }

        @Override
        public String schemaSql() {
            return "SELECT nspname FROM pg_namespace";
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM "
                    + quote(this, defaultSchema(conf)) + "." + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    },

    /** Elasticsearch：HTTP 协议（无 JDBC 驱动），连接检查走 ping，元数据走 _cat/_mapping。 */
    ES("es", "Elasticsearch", "\"", "\"", null, "Elasticsearch") {
        @Override
        public boolean isHttpProtocol() {
            return true;
        }
    },

    HIVE("hive", "Apache Hive", "`", "`", "org.apache.hive.jdbc.HiveDriver", "Hive") {
        @Override
        public SqlText tableSql(DatasourceConf conf, String dbVersion) {
            return new SqlText("SHOW TABLES");
        }

        @Override
        public SqlText fieldSql(DatasourceConf conf, String tableName) {
            // HiveServer2 / Spark Thrift 的 DESCRIBE 不支持参数绑定，表名来自本模块元数据查询结果
            return new SqlText("DESCRIBE " + inlineIdentifier(tableName));
        }

        @Override
        public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
            return "SELECT " + quoteAll(this, fields) + " FROM " + quote(this, tableName)
                    + (where == null ? "" : " " + where) + " LIMIT 100";
        }
    };

    /** 带绑定参数的 SQL（对齐 SQLBot 的 :param / %s 两种占位风格，统一为 JDBC ?）。 */
    public static final class SqlText {

        private final String sql;

        private final Object[] args;

        public SqlText(String sql, Object... args) {
            this.sql = sql;
            this.args = args == null ? new Object[0] : args;
        }

        public String getSql() {
            return sql;
        }

        public Object[] getArgs() {
            return args;
        }
    }

    private final String type;

    private final String dbName;

    private final String prefix;

    private final String suffix;

    private final String driverClass;

    private final String templateName;

    DatasourceType(String type, String dbName, String prefix, String suffix, String driverClass,
                   String templateName) {
        this.type = type;
        this.dbName = dbName;
        this.prefix = prefix;
        this.suffix = suffix;
        this.driverClass = driverClass;
        this.templateName = templateName;
    }

    public String getType() {
        return type;
    }

    /** 展示名（SQLBot DB.db_name；落 core_datasource.type_name）。 */
    public String getDbName() {
        return dbName;
    }

    /** 标识符前缀（SQLBot DB.prefix）。 */
    public String getPrefix() {
        return prefix;
    }

    /** 标识符后缀（SQLBot DB.suffix）。 */
    public String getSuffix() {
        return suffix;
    }

    /** JDBC 驱动类；null 表示非 JDBC 协议（es）或走应用库（excel）。 */
    public String getDriverClass() {
        return driverClass;
    }

    /** SQLBot DB.template_name（保留对齐用）。 */
    public String getTemplateName() {
        return templateName;
    }

    /** excel 类型：数据存放在应用元数据库（对齐 SQLBot get_engine_config）。 */
    public boolean usesAppDatabase() {
        return false;
    }

    /** es 类型：HTTP 协议，无 JDBC URL。 */
    public boolean isHttpProtocol() {
        return false;
    }

    /**
     * 该方言禁止的 extra JDBC 参数（对齐 SQLBot {@code DB.<type>.illegalParams}）。
     *
     * <p>SQLBot 仅 MySQL 禁止 {@code local_infile}（本地文件读取，防目录穿越）；
     * 其余方言为空集合。</p>
     */
    public List<String> illegalParams() {
        return Collections.emptyList();
    }

    /**
     * 校验 extra JDBC 参数不含该方言的禁止项（对齐 SQLBot {@code checkParams}）。
     *
     * @param extraJdbc    形如 {@code k=v&k=v} 的额外参数
     * @param illegalParams 禁止参数名集合
     * @throws IllegalArgumentException 命中禁止参数时抛出（消息与 SQLBot 一致）
     */
    public static void checkParams(String extraJdbc, List<String> illegalParams) {
        if (extraJdbc == null || extraJdbc.isEmpty() || illegalParams == null || illegalParams.isEmpty()) {
            return;
        }
        for (String item : extraJdbc.split("&")) {
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = item.substring(0, eq);
            if (illegalParams.contains(key)) {
                throw new IllegalArgumentException("Illegal Parameter: " + key);
            }
        }
    }

    /** 版本查询 SQL；null 表示不支持（SQLBot get_version_sql 返回 ''）。 */
    public String versionSql() {
        return null;
    }

    /** 表列表元数据 SQL（绑定参数风格）。 */
    public SqlText tableSql(DatasourceConf conf, String dbVersion) {
        return new SqlText("");
    }

    /** 字段列表元数据 SQL（tableName 为空时返回全部表字段）。 */
    public SqlText fieldSql(DatasourceConf conf, String tableName) {
        return new SqlText("");
    }

    /** schema 列表 SQL（getSchemaByConf）；null 表示不支持，返回空列表。 */
    public String schemaSql() {
        return null;
    }

    /** 数据预览 SQL（对齐 SQLBot crud.datasource.preview 的方言分支）。 */
    public String previewSql(List<String> fields, String tableName, DatasourceConf conf, String where) {
        return "SELECT " + quoteAll(this, fields) + " FROM " + quote(this, tableName)
                + (where == null ? "" : " " + where) + " LIMIT 100";
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 标识符引用（SQLBot DB.prefix/suffix；sqlServer 用 []，且不支持 schema 点分双引号场景）。 */
    public static String quote(DatasourceType type, String identifier) {
        if (identifier == null) {
            return "";
        }
        return type.getPrefix() + identifier + type.getSuffix();
    }

    public static String quoteAll(DatasourceType type, List<String> identifiers) {
        List<String> quoted = new ArrayList<>();
        for (String identifier : identifiers) {
            quoted.add(quote(type, identifier));
        }
        return String.join(", ", quoted);
    }

    /** pg 系 schema 缺省时退回 database（SQLBot get_table_obj_by_ds 语义）。 */
    static String defaultSchema(DatasourceConf conf) {
        return conf.getDbSchema() == null || conf.getDbSchema().isEmpty() ? conf.getDatabase() : conf.getDbSchema();
    }

    /** H2 schema 缺省为 PUBLIC（H2 默认 schema）。 */
    static String h2Schema(DatasourceConf conf) {
        return conf.getDbSchema() == null || conf.getDbSchema().isEmpty() ? "PUBLIC" : conf.getDbSchema();
    }

    /** 无法参数绑定的标识符内联（hive DESCRIBE）：包裹并转义引号。 */
    static String inlineIdentifier(String identifier) {
        if (identifier == null) {
            return "``";
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    static int majorVersion(String version) {
        if (version == null || version.isEmpty()) {
            return -1;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 按 type 取枚举（大小写不敏感，对齐 SQLBot equals_ignore_case）；未注册类型抛错。 */
    public static DatasourceType fromType(String type) {
        for (DatasourceType candidate : values()) {
            if (candidate.type.equalsIgnoreCase(type)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Invalid db type: " + type);
    }

    /** 前端「新增数据源」下拉顺序（对齐 SQLBot frontend/src/views/ds/js/ds-type.ts；h2/sqlite 为扩展）。 */
    public static List<DatasourceType> selectable() {
        List<DatasourceType> result = new ArrayList<>();
        result.add(EXCEL);
        result.add(MYSQL);
        result.add(ORACLE);
        result.add(PG);
        result.add(SQLSERVER);
        result.add(CK);
        result.add(DM);
        result.add(DORIS);
        result.add(REDSHIFT);
        result.add(ES);
        result.add(KINGBASE);
        result.add(STARROCKS);
        result.add(HIVE);
        result.add(H2);
        result.add(SQLITE);
        return result;
    }
}
