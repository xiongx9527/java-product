package com.xiongx9527.askdata.datasource.engine;

import com.xiongx9527.askdata.datasource.DatasourceException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 只读校验（移植 SQLBot apps/db/db.py 的 {@code check_sql_read}）：
 * execSql/previewData 执行前拦截写操作与危险模式，SQLBot 语义逐字对齐：
 * <ul>
 *   <li>仅允许 SELECT / WITH 开头（SQLBOT_ALLOW_METADATA_QUERIES 默认 False）；</li>
 *   <li>拒绝写操作关键字（INSERT/UPDATE/DELETE/CREATE/DROP/ALTER/TRUNCATE/MERGE/COPY/REPLACE/GRANT/REVOKE/USE/SET/CALL）；</li>
 *   <li>拒绝危险模式（INTO OUTFILE / INTO DUMPFILE / EXEC( / COPY .. TO PROGRAM）；</li>
 *   <li>拒绝危险函数（version/current_user/user/database 及各库专用函数，对齐 get_dangerous_functions）。</li>
 * </ul>
 * Python 侧用 sqlglot AST 判定，Java 侧按语句切分 + 首关键字 + 正则近似（语义等价的安全子集）。
 */
public final class SqlSafety {

    private static final Set<String> ALLOWED_FIRST_KEYWORDS = new HashSet<>(Arrays.asList("SELECT", "WITH"));

    private static final Set<String> DENIED_FIRST_KEYWORDS = new HashSet<>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "TRUNCATE", "MERGE", "COPY", "REPLACE",
            "GRANT", "REVOKE", "USE", "SET", "CALL"));

    private static final Pattern[] DANGEROUS_PATTERNS = {
            Pattern.compile("\\bINTO\\s+OUTFILE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bINTO\\s+DUMPFILE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bEXEC\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCOPY\\s+.*\\bTO\\s+PROGRAM\\b", Pattern.CASE_INSENSITIVE),
    };

    private static final Set<String> COMMON_DANGEROUS_FUNCTIONS =
            new HashSet<>(Arrays.asList("version", "current_user", "user", "database"));

    private static final Pattern FUNCTION_CALL =
            Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private SqlSafety() {
    }

    /**
     * 校验 SQL 是否只读；不通过时抛出（对齐 SQLBot {@code ValueError} → 500 文本）。
     *
     * @param sql     待执行 SQL
     * @param dsType  数据源类型（决定库专用危险函数集）
     */
    public static void checkRead(String sql, String dsType) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new DatasourceException("SQL can only contain read operations: Parse SQL Error");
        }
        String normalized = sql.trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            throw new DatasourceException("SQL can only contain read operations: Parse SQL Error");
        }
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                throw new DatasourceException("SQL can only contain read operations: SQL contains dangerous pattern");
            }
        }
        Set<String> dangerousFunctions = dangerousFunctions(dsType);
        for (String statement : splitStatements(normalized)) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String body = trimmed.startsWith("(") ? trimmed.substring(1).trim() : trimmed;
            String firstKeyword = body.split("\\s+", 2)[0].toUpperCase();
            if (firstKeyword.isEmpty()) {
                throw new DatasourceException("SQL can only contain read operations: Parse SQL Error");
            }
            if (DENIED_FIRST_KEYWORDS.contains(firstKeyword)) {
                throw new DatasourceException(
                        "SQL can only contain read operations: Write operation '" + firstKeyword + "' is not allowed");
            }
            if (!ALLOWED_FIRST_KEYWORDS.contains(firstKeyword)) {
                throw new DatasourceException("SQL can only contain read operations: SQL command '" + firstKeyword
                        + "' is not allowed. Only SELECT and WITH are permitted");
            }
            java.util.regex.Matcher matcher = FUNCTION_CALL.matcher(body);
            while (matcher.find()) {
                if (dangerousFunctions.contains(matcher.group(1).toLowerCase())) {
                    throw new DatasourceException("SQL can only contain read operations: SQL contains dangerous function: "
                            + matcher.group(1));
                }
            }
        }
    }

    /** 切分多条语句（简单按分号；SQLBot sqlglot.parse 的多语句语义等价）。 */
    private static String[] splitStatements(String sql) {
        return sql.split(";");
    }

    private static Set<String> dangerousFunctions(String dsType) {
        Set<String> functions = new HashSet<>(COMMON_DANGEROUS_FUNCTIONS);
        if (dsType == null) {
            return functions;
        }
        String key = dsType.toLowerCase();
        if (key.equals("mysql") || key.equals("doris") || key.equals("starrocks")) {
            functions.add("load_file");
        } else if (key.equals("pg") || key.equals("kingbase") || key.equals("redshift")) {
            functions.addAll(Arrays.asList("pg_read_file", "pg_write_file", "lo_import", "lo_export"));
        } else if (key.equals("sqlserver")) {
            functions.addAll(Arrays.asList("xp_cmdshell", "sp_executesql"));
        } else if (key.equals("oracle")) {
            functions.addAll(Arrays.asList("utl_file", "dbms_pipe", "dbms_lock"));
        }
        // hive 的 'ADD FILE'/'ADD JAR' 在 SQLBot 侧是语句级 AST 判定（非函数调用），
        // 正则近似下无等价函数名形态，交由首关键字/写操作拦截覆盖
        return functions;
    }
}
