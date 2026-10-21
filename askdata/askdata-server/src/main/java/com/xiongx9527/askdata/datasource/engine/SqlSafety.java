package com.xiongx9527.askdata.datasource.engine;

import com.xiongx9527.askdata.datasource.DatasourceException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 只读校验（对齐 SQLBot {@code apps/db/db.py} 的 {@code check_sql_read}，语义等价 sqlglot AST 校验）。
 *
 * <p>与 SQLBot 一致采用<b>保守拒绝</b>：</p>
 * <ul>
 *   <li>仅允许 {@code SELECT} / {@code WITH}（CTE）开头；</li>
 *   <li>解析失败即拒绝（{@code Parse SQL Error}），避免正则近似绕过；</li>
 *   <li>CTE 内部的写操作（如 {@code WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x}）被拒绝——
 *       通过 AST 递归遍历 {@code WITH} 子查询体实现，正则首关键字近似无法覆盖此类形态；</li>
 *   <li>拒绝危险模式（{@code INTO OUTFILE}/{@code INTO DUMPFILE}/{@code EXEC(}/{@code COPY ... TO PROGRAM}）；</li>
 *   <li>拒绝危险函数（{@code version}/{@code current_user}/{@code user}/{@code database} 及各方言专用函数）。</li>
 * </ul>
 */
public final class SqlSafety {

    private static final Pattern[] DANGEROUS_PATTERNS = {
            Pattern.compile("(?i)INTO[ ]+OUTFILE"),
            Pattern.compile("(?i)INTO[ ]+DUMPFILE"),
            Pattern.compile("(?i)" + Pattern.quote("EXEC(")),
            Pattern.compile("(?i)COPY.{0,80}TO[ ]+PROGRAM"),
    };

    private static final Set<String> COMMON_DANGEROUS_FUNCTIONS =
            new HashSet<>(Arrays.asList("version", "current_user", "user", "database"));

    // 函数调用：标识符后跟 '('（用 Pattern.quote 避免源码中出现字面量反斜杠）
    private static final Pattern FUNCTION_CALL =
            Pattern.compile("(?i)([A-Za-z_][A-Za-z0-9]*)[ ]*" + Pattern.quote("("));

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
        String normalized = stripTrailingSemicolons(sql.trim());
        if (normalized.isEmpty()) {
            throw new DatasourceException("SQL can only contain read operations: Parse SQL Error");
        }
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                throw new DatasourceException(
                        "SQL can only contain read operations: SQL contains dangerous pattern");
            }
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(normalized);
        } catch (JSQLParserException e) {
            // 保守拒绝：解析失败即视为不安全
            throw new DatasourceException("SQL can only contain read operations: Parse SQL Error");
        }
        if (containsWriteOperation(statement)) {
            throw new DatasourceException(
                    "SQL can only contain read operations: Write operation is not allowed");
        }
        Set<String> dangerousFunctions = dangerousFunctions(dsType);
        java.util.regex.Matcher matcher = FUNCTION_CALL.matcher(normalized);
        while (matcher.find()) {
            if (dangerousFunctions.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                throw new DatasourceException(
                        "SQL can only contain read operations: SQL contains dangerous function: "
                                + matcher.group(1));
            }
        }
    }

    /** 递归判定语句是否包含写操作（含 CTE 子查询体）。仅 SELECT / WITH(CTE) / UNION 只读结构放行。 */
    private static boolean containsWriteOperation(Statement statement) {
        if (statement instanceof Select) {
            Select select = (Select) statement;
            if (select.getWithItemsList() != null) {
                for (WithItem<?> withItem : select.getWithItemsList()) {
                    try {
                        if (containsWriteOperation(withItem.getSelect())) {
                            return true;
                        }
                    } catch (ClassCastException e) {
                        // CTE 体内是写操作语句（DELETE/INSERT/...），getSelect() 转型失败 → 直接拒绝
                        return true;
                    }
                }
            }
            return false;
        }
        // 非 SELECT 语句（INSERT/UPDATE/DELETE/DROP/EXPLAIN/SHOW/...）一律按写操作/不允许处理
        return true;
    }

    private static String stripTrailingSemicolons(String sql) {
        String s = sql;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static Set<String> dangerousFunctions(String dsType) {
        Set<String> functions = new HashSet<>(COMMON_DANGEROUS_FUNCTIONS);
        if (dsType == null) {
            return functions;
        }
        String key = dsType.toLowerCase(Locale.ROOT);
        if (key.equals("mysql") || key.equals("doris") || key.equals("starrocks")) {
            functions.add("load_file");
        } else if (key.equals("pg") || key.equals("kingbase") || key.equals("redshift")) {
            functions.addAll(Arrays.asList("pg_read_file", "pg_write_file", "lo_import", "lo_export"));
        } else if (key.equals("sqlserver")) {
            functions.addAll(Arrays.asList("xp_cmdshell", "sp_executesql"));
        } else if (key.equals("oracle")) {
            functions.addAll(Arrays.asList("utl_file", "dbms_pipe", "dbms_lock"));
        }
        return functions;
    }
}
