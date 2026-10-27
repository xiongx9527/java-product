package com.xiongx9527.askdata.datasource.engine;

import com.xiongx9527.askdata.datasource.DatasourceException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 只读校验（对齐 SQLBot {@code apps/db/db.py} 的 {@code check_sql_read}，语义等价 sqlglot AST 校验）。
 *
 * <p>与 SQLBot 一致采用<b>保守拒绝</b>：</p>
 * <ul>
 *   <li>仅允许 {@code SELECT} / {@code WITH}（CTE）/ 只读 {@code UNION} 结构；</li>
 *   <li>解析失败即拒绝（{@code Parse SQL Error}），避免正则近似绕过；</li>
 *   <li>多语句（含隐藏写操作，如 {@code SELECT 1; DROP TABLE t}）一律拒绝；</li>
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

    /** 系统目录（元数据）查询特征：命中即视为 metadata query。 */
    private static final Pattern METADATA_QUERY = Pattern.compile(
            "(?i)\\b(?:information_schema|pg_catalog|performance_schema|mysql\\.|sys\\.)");

    /**
     * A27：是否允许元数据（系统目录）查询，缺省 false（对齐 SQLBot {@code ALLOW_METADATA_QUERIES=False}）。
     *
     * <p>由 {@code askdata.sql.allow-metadata-queries} 在启动时写入；缺省拒绝，避免模型探测
     * {@code information_schema}/{@code pg_catalog} 等系统目录。未配置时行为与缺省值一致。</p>
     */
    private static volatile boolean allowMetadataQueries = false;

    private SqlSafety() {
    }

    /** 设置是否允许元数据查询（由 Spring 配置在启动时调用；缺省 false）。 */
    public static void setAllowMetadataQueries(boolean allow) {
        allowMetadataQueries = allow;
    }

    /** 当前是否允许元数据查询。 */
    public static boolean isAllowMetadataQueries() {
        return allowMetadataQueries;
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
        if (!allowMetadataQueries && METADATA_QUERY.matcher(normalized).find()) {
            throw new DatasourceException(
                    "SQL can only contain read operations: Metadata queries are not allowed");
        }
        List<Statement> statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(normalized).getStatements();
        } catch (JSQLParserException e) {
            // 保守拒绝：解析失败即视为不安全
            throw new DatasourceException("SQL can only contain read operations: Parse SQL Error");
        }
        // 多语句（含隐藏写操作）保守拒绝
        if (statements.size() != 1) {
            throw new DatasourceException(
                    "SQL can only contain read operations: Only a single read statement is allowed");
        }
        if (containsWriteOperation(statements.get(0))) {
            throw new DatasourceException(
                    "SQL can only contain read operations: Write operation is not allowed");
        }
        Set<String> dangerousFunctions = dangerousFunctions(dsType);
        // A7：基于 SQL AST 函数节点（而非原文正则）识别危险函数调用。
        // 字符串字面量中的 "version(" 不会被误判为函数调用，而真正的函数调用（如 version()）会被识别。
        for (String fn : collectFunctionNames(statements.get(0))) {
            if (dangerousFunctions.contains(fn)) {
                throw new DatasourceException(
                        "SQL can only contain read operations: SQL contains dangerous function: " + fn);
            }
        }
    }

    /**
     * 遍历解析后的语句 AST，收集所有函数调用的名称（小写）。基于 {@link net.sf.jsqlparser.expression.Function}
     * 节点识别，因此字符串字面量（如 {@code 'version(x)'}) 不会被当成函数调用。
     */
    /**
     * 遍历解析后的语句 AST，收集所有函数调用的名称（小写）。基于 Function 节点识别，
     * 因此字符串字面量（如 version(x)）不会被当成函数调用。
     */
    private static Set<String> collectFunctionNames(Statement stmt) {
        Set<String> names = new HashSet<>();
        if (stmt instanceof Select s) {
            collectSelect(s, names);
        } else if (stmt instanceof SetOperationList sol) {
            if (sol.getWithItemsList() != null) {
                for (WithItem<?> w : sol.getWithItemsList()) {
                    collectSelect(w.getSelect(), names);
                }
            }
            for (Select sel : sol.getSelects()) {
                collectSelect(sel, names);
            }
        }
        return names;
    }

    /** 递归遍历 SELECT（覆盖 CTE / UNION / FROM-JOIN / 子查询）中的表达式，收集函数名。 */
    private static void collectSelect(Select select, Set<String> out) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> w : select.getWithItemsList()) {
                collectSelect(w.getSelect(), out);
            }
        }
        SetOperationList setOp = null;
        try {
            setOp = select.getSetOperationList();
        } catch (ClassCastException ignore) {
            setOp = null;
        }
        if (setOp != null) {
            if (setOp.getWithItemsList() != null) {
                for (WithItem<?> w : setOp.getWithItemsList()) {
                    collectSelect(w.getSelect(), out);
                }
            }
            for (Select sel : setOp.getSelects()) {
                collectSelect(sel, out);
            }
            return;
        }
        PlainSelect ps = select.getPlainSelect();
        if (ps == null) {
            return;
        }
        if (ps.getSelectItems() != null) {
            for (SelectItem<?> si : ps.getSelectItems()) {
                Expression e = si.getExpression();
                if (e != null) {
                    collectExpr(e, out);
                }
            }
        }
        if (ps.getWhere() != null) {
            collectExpr(ps.getWhere(), out);
        }
        if (ps.getHaving() != null) {
            collectExpr(ps.getHaving(), out);
        }
        if (ps.getFromItem() != null) {
            collectFromItem(ps.getFromItem(), out);
        }
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                collectFromItem(join.getRightItem(), out);
                if (join.getOnExpression() != null) {
                    collectExpr(join.getOnExpression(), out);
                }
            }
        }
        if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressions() != null) {
            for (Object o : ps.getGroupBy().getGroupByExpressions()) {
                if (o instanceof Expression e) {
                    collectExpr(e, out);
                }
            }
        }
        if (ps.getOrderByElements() != null) {
            for (OrderByElement o : ps.getOrderByElements()) {
                collectExpr(o.getExpression(), out);
            }
        }
    }

    private static void collectFromItem(FromItem item, Set<String> out) {
        if (item instanceof ParenthesedSelect ps) {
            collectSelect(ps.getSelect(), out);
        }
    }

    /** 递归遍历表达式树，收集 Function 节点名称；对常见容器节点继续向下遍历。 */
    private static void collectExpr(Expression e, Set<String> out) {
        if (e == null) {
            return;
        }
        if (e instanceof Function f) {
            String name = f.getName();
            if (name != null) {
                out.add(name.toLowerCase(Locale.ROOT));
            }
            ExpressionList<?> params = f.getParameters();
            if (params != null) {
                for (Object p : params.getExpressions()) {
                    if (p instanceof Expression ex) {
                        collectExpr(ex, out);
                    }
                }
            }
            Object attr = f.getAttribute();
            if (attr instanceof Expression ex) {
                collectExpr(ex, out);
            }
            return;
        }
        if (e instanceof BinaryExpression be) {
            collectExpr(be.getLeftExpression(), out);
            collectExpr(be.getRightExpression(), out);
            return;
        }
        if (e instanceof Parenthesis p) {
            collectExpr(p.getExpression(), out);
            return;
        }
        if (e instanceof CastExpression ce) {
            collectExpr(ce.getLeftExpression(Expression.class), out);
            return;
        }
        if (e instanceof CaseExpression c) {
            if (c.getSwitchExpression() != null) {
                collectExpr(c.getSwitchExpression(), out);
            }
            if (c.getWhenClauses() != null) {
                for (WhenClause w : c.getWhenClauses()) {
                    collectExpr(w.getWhenExpression(), out);
                    collectExpr(w.getThenExpression(), out);
                }
            }
            if (c.getElseExpression() != null) {
                collectExpr(c.getElseExpression(), out);
            }
            return;
        }
        if (e instanceof NotExpression ne) {
            collectExpr(ne.getExpression(), out);
            return;
        }
        if (e instanceof Between bt) {
            collectExpr(bt.getLeftExpression(), out);
            collectExpr(bt.getBetweenExpressionStart(), out);
            collectExpr(bt.getBetweenExpressionEnd(), out);
            return;
        }
        if (e instanceof InExpression ie) {
            collectExpr(ie.getLeftExpression(), out);
            collectExpr(ie.getRightExpression(), out);
            return;
        }
    }

    /**
     * 递归判定语句是否包含写操作（含 CTE 子查询体）。仅 SELECT / WITH(CTE) / UNION 只读结构放行。
     */
    private static boolean containsWriteOperation(Statement statement) {
        if (statement instanceof Select) {
            Select select = (Select) statement;
            if (select.getWithItemsList() != null) {
                for (WithItem<?> withItem : select.getWithItemsList()) {
                    if (cteBodyIsWrite(withItem)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (statement instanceof SetOperationList) {
            SetOperationList setOp = (SetOperationList) statement;
            if (setOp.getWithItemsList() != null) {
                for (WithItem<?> withItem : setOp.getWithItemsList()) {
                    if (cteBodyIsWrite(withItem)) {
                        return true;
                    }
                }
            }
            return false;
        }
        // 非只读结构（INSERT/UPDATE/DELETE/DROP/EXPLAIN/SHOW/...）一律按写操作/不允许处理
        return true;
    }

    /** CTE 子查询体为写操作语句（DELETE/INSERT/...）时返回 true。 */
    private static boolean cteBodyIsWrite(WithItem<?> withItem) {
        Statement inner;
        try {
            inner = withItem.getSelect();
        } catch (ClassCastException e) {
            // CTE 体内是写操作语句（ParenthesedDelete/Insert 等无法转型为 Select）→ 拒绝
            return true;
        }
        return inner == null || containsWriteOperation(inner);
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
