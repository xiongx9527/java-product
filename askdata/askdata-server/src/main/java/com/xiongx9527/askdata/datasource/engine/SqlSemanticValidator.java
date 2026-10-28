package com.xiongx9527.askdata.datasource.engine;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL 语义校验（A10）：在只读校验之后、执行之前，按数据源已勾选的表/字段元数据拦截
 * 「引用不存在的表/列」「错误聚合」「聚合查询缺少 GROUP BY 维度」三类模型幻觉。
 *
 * <p>设计原则是<b>保守拒绝、绝不误杀</b>：</p>
 * <ul>
 *   <li>SQL 无法解析时返回 {@code null}（交给 {@link SqlSafety} 报解析错误，避免重复报错）；</li>
 *   <li>含 CTE、派生表、集合运算混合结构时跳过列级校验（这些结构里标识符可能来自子查询别名，
 *       静态元数据无法准确判定）；</li>
 *   <li>列名大小写不敏感比对；未加限定符的列只要命中任一已勾选表的字段即通过；</li>
 *   <li>聚合类型校验只在字段类型明确为文本/时间类时触发，未知类型一律放行。</li>
 * </ul>
 *
 * <p>命中问题时返回可读错误信息（由调用方走既有的一次修复轮，修复失败即中止执行），
 * 未命中返回 {@code null}。</p>
 */
public final class SqlSemanticValidator {

    /** 聚合函数（用于识别「聚合 + 非聚合列」混排）。 */
    private static final Set<String> AGGREGATES = new HashSet<>(Arrays.asList(
            "sum", "avg", "count", "min", "max", "stddev", "variance", "var_pop", "var_samp",
            "stddev_pop", "stddev_samp", "median", "group_concat", "string_agg", "array_agg"));

    /** 只接受数值参数的聚合函数（MIN/MAX/COUNT 可用于文本/时间列）。 */
    private static final Set<String> NUMERIC_AGGREGATES = new HashSet<>(Arrays.asList("sum", "avg"));

    /** 明确非数值的类型特征（用于识别错误聚合）。 */
    private static final String[] NON_NUMERIC_HINTS = {
            "char", "text", "varchar", "nvarchar", "nchar", "clob", "date", "time", "timestamp",
            "blob", "binary", "bool", "bit", "enum", "set", "json", "uuid", "uniqueidentifier",
            "interval", "year",
    };

    /** 明确数值的类型特征。 */
    private static final String[] NUMERIC_HINTS = {
            "int", "decimal", "numeric", "number", "float", "double", "real", "money", "serial",
            "fixed", "smallmoney", "int64", "uint",
    };

    private SqlSemanticValidator() {
    }

    /** 数据源已勾选的表/字段元数据（表名、列名键统一小写）。 */
    public static final class Schema {

        private final Map<String, Map<String, String>> tables = new LinkedHashMap<>();

        /**
         * @param tables 表名 → (字段名 → 字段类型)；名称大小写不敏感
         */
        public Schema(Map<String, Map<String, String>> tables) {
            if (tables == null) {
                return;
            }
            for (Map.Entry<String, Map<String, String>> entry : tables.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                Map<String, String> columns = new LinkedHashMap<>();
                if (entry.getValue() != null) {
                    for (Map.Entry<String, String> column : entry.getValue().entrySet()) {
                        if (column.getKey() != null) {
                            columns.put(column.getKey().toLowerCase(Locale.ROOT), column.getValue());
                        }
                    }
                }
                this.tables.put(entry.getKey().toLowerCase(Locale.ROOT), columns);
            }
        }

        public boolean isEmpty() {
            return tables.isEmpty();
        }

        boolean hasTable(String table) {
            return table != null && tables.containsKey(table.toLowerCase(Locale.ROOT));
        }

        boolean hasColumn(String table, String column) {
            if (table == null || column == null) {
                return false;
            }
            Map<String, String> columns = tables.get(table.toLowerCase(Locale.ROOT));
            if (columns == null) {
                return false;
            }
            // 表已勾选但字段元数据为空（未同步字段/同步失败）：无法判定，放行避免误杀
            return columns.isEmpty() || columns.containsKey(column.toLowerCase(Locale.ROOT));
        }

        boolean hasAnyColumn(String column) {
            if (column == null) {
                return false;
            }
            String key = column.toLowerCase(Locale.ROOT);
            for (Map<String, String> columns : tables.values()) {
                // 任一张表缺字段元数据时，未限定列名无法证伪，按放行处理
                if (columns.isEmpty() || columns.containsKey(key)) {
                    return true;
                }
            }
            return false;
        }

        /** 字段类型（未知返回 null）。 */
        String typeOf(String table, String column) {
            if (table == null || column == null) {
                return null;
            }
            Map<String, String> columns = tables.get(table.toLowerCase(Locale.ROOT));
            return columns == null ? null : columns.get(column.toLowerCase(Locale.ROOT));
        }

        Set<String> tableNames() {
            return tables.keySet();
        }
    }

    /**
     * 校验 SQL 语义。
     *
     * @return 可读错误信息；通过或不适用（无法解析/结构超出静态校验范围/无元数据）时返回 null
     */
    public static String validate(String sql, Schema schema) {
        if (sql == null || sql.trim().isEmpty() || schema == null || schema.isEmpty()) {
            return null;
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            // 解析失败由 SqlSafety 统一报错，这里不重复
            return null;
        }
        if (!(statement instanceof Select select)) {
            return null;
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            // CTE：内部标识符可能是 CTE 别名，静态元数据无法准确判定，跳过列级校验
            return null;
        }
        List<PlainSelect> plains = plainSelects(select);
        if (plains == null || plains.isEmpty()) {
            return null;
        }
        for (PlainSelect plain : plains) {
            String error = validatePlainSelect(plain, schema);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    /** 拆出可校验的 PlainSelect；含派生表/UNION 等无法静态判定列归属的结构时返回 null。 */
    private static List<PlainSelect> plainSelects(Select select) {
        List<PlainSelect> plains = new ArrayList<>();
        if (select instanceof PlainSelect plain) {
            plains.add(plain);
            return plains;
        }
        if (select instanceof SetOperationList set) {
            for (Select item : set.getSelects()) {
                if (!(item instanceof PlainSelect plain)) {
                    return null;
                }
                plains.add(plain);
            }
            return plains;
        }
        return null;
    }

    private static String validatePlainSelect(PlainSelect plain, Schema schema) {
        List<Table> tables = baseTables(plain);
        if (tables == null) {
            // FROM 里含子查询/表函数/Values：列归属不确定，跳过
            return null;
        }
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        for (Table table : tables) {
            String name = table.getName() == null ? null : unquote(table.getName());
            if (name == null || name.isEmpty()) {
                return null;
            }
            if (!schema.hasTable(name)) {
                return "SQL 引用了未选择或不存在的表：" + name;
            }
            aliasToTable.put(name.toLowerCase(Locale.ROOT), name);
            Alias alias = table.getAlias();
            if (alias != null && alias.getName() != null) {
                aliasToTable.put(unquote(alias.getName()).toLowerCase(Locale.ROOT), name);
            }
        }
        List<Expression> expressions = expressions(plain);
        Set<String> selectAliases = selectAliases(plain);
        List<Column> columns = new ArrayList<>();
        for (Expression expression : expressions) {
            collectColumns(expression, columns);
        }
        for (Column column : columns) {
            String name = unquote(column.getColumnName());
            if (name == null || name.isEmpty() || "*".equals(name)) {
                continue;
            }
            String qualifier = column.getTable() == null ? null : unquote(column.getTable().getName());
            if (qualifier != null && !qualifier.isEmpty()) {
                String table = aliasToTable.get(qualifier.toLowerCase(Locale.ROOT));
                if (table == null) {
                    // 限定符不是已知的表/别名（可能来自派生表或 CTE），跳过
                    continue;
                }
                if (!schema.hasColumn(table, name)) {
                    return "SQL 引用了表 " + table + " 中不存在的列：" + name;
                }
                continue;
            }
            if (!schema.hasAnyColumn(name) && !selectAliases.contains(name.toLowerCase(Locale.ROOT))) {
                return "SQL 引用了不存在的列：" + name;
            }
        }
        return validateAggregation(plain, schema, aliasToTable, selectAliases);
    }

    /** 聚合语义：错误聚合（文本列求和 / SUM(*)）与「聚合 + 非聚合列」缺 GROUP BY。 */
    private static String validateAggregation(PlainSelect plain, Schema schema,
                                              Map<String, String> aliasToTable, Set<String> selectAliases) {
        List<SelectItem<?>> items = plain.getSelectItems();
        List<Column> groupBy = groupByColumns(plain);
        boolean hasAggregate = false;
        List<Column> bareSelectColumns = new ArrayList<>();
        for (SelectItem<?> item : items == null ? List.<SelectItem<?>>of() : items) {
            Expression expression = item.getExpression();
            if (expression instanceof Function function && isAggregate(function)) {
                hasAggregate = true;
                String error = validateAggregateFunction(function, schema, aliasToTable);
                if (error != null) {
                    return error;
                }
            } else if (expression instanceof Column column) {
                bareSelectColumns.add(column);
            }
        }
        if (!hasAggregate || bareSelectColumns.isEmpty()) {
            return null;
        }
        for (Column column : bareSelectColumns) {
            if (!containsColumn(groupBy, column)) {
                return "聚合查询缺少 GROUP BY 维度：" + unquote(column.getColumnName());
            }
        }
        return null;
    }

    /** 单个聚合函数的参数校验。 */
    private static String validateAggregateFunction(Function function, Schema schema,
                                                    Map<String, String> aliasToTable) {
        String name = function.getName() == null ? "" : function.getName().toLowerCase(Locale.ROOT);
        if (function.isAllColumns() && !"count".equals(name)) {
            return "聚合函数用法错误：" + name + "(*) 仅 COUNT 支持";
        }
        if (!NUMERIC_AGGREGATES.contains(name) || function.getParameters() == null) {
            return null;
        }
        for (Object parameter : function.getParameters().getExpressions()) {
            if (!(parameter instanceof Column column)) {
                continue;
            }
            String columnName = unquote(column.getColumnName());
            String qualifier = column.getTable() == null ? null : unquote(column.getTable().getName());
            String table = qualifier == null ? null : aliasToTable.get(qualifier.toLowerCase(Locale.ROOT));
            if (table == null) {
                // 未限定或多表查询：任一同名列的类型代表该列
                for (String candidate : aliasToTable.values()) {
                    String type = schema.typeOf(candidate, columnName);
                    if (isNonNumeric(type)) {
                        return "聚合函数用法错误：" + name + "() 不能用于文本/时间列：" + columnName;
                    }
                }
                continue;
            }
            if (isNonNumeric(schema.typeOf(table, columnName))) {
                return "聚合函数用法错误：" + name + "() 不能用于文本/时间列：" + columnName;
            }
        }
        return null;
    }

    /** FROM/JOIN 引用的物理表；含派生表等非常规 FromItem 时返回 null。 */
    private static List<Table> baseTables(PlainSelect plain) {
        List<Table> tables = new ArrayList<>();
        if (plain.getFromItem() != null) {
            if (!(plain.getFromItem() instanceof Table table)) {
                return null;
            }
            tables.add(table);
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                FromItem item = join.getRightItem();
                if (!(item instanceof Table table)) {
                    return null;
                }
                tables.add(table);
            }
        }
        return tables.isEmpty() ? null : tables;
    }

    /** 收集需要做列校验的表达式（SELECT / WHERE / GROUP BY / HAVING / ORDER BY / JOIN ON）。 */
    private static List<Expression> expressions(PlainSelect plain) {
        List<Expression> expressions = new ArrayList<>();
        if (plain.getSelectItems() != null) {
            for (SelectItem<?> item : plain.getSelectItems()) {
                expressions.add(item.getExpression());
            }
        }
        expressions.add(plain.getWhere());
        expressions.add(plain.getHaving());
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                expressions.add(join.getOnExpression());
            }
        }
        if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressions() != null) {
            for (Object expression : plain.getGroupBy().getGroupByExpressions()) {
                if (expression instanceof Expression e) {
                    expressions.add(e);
                }
            }
        }
        if (plain.getOrderByElements() != null) {
            for (OrderByElement element : plain.getOrderByElements()) {
                expressions.add(element.getExpression());
            }
        }
        expressions.removeIf(expression -> expression == null);
        return expressions;
    }

    private static Set<String> selectAliases(PlainSelect plain) {
        Set<String> aliases = new HashSet<>();
        if (plain.getSelectItems() == null) {
            return aliases;
        }
        for (SelectItem<?> item : plain.getSelectItems()) {
            if (item.getAlias() != null && item.getAlias().getName() != null) {
                aliases.add(unquote(item.getAlias().getName()).toLowerCase(Locale.ROOT));
            }
        }
        return aliases;
    }

    private static List<Column> groupByColumns(PlainSelect plain) {
        List<Column> columns = new ArrayList<>();
        if (plain.getGroupBy() == null || plain.getGroupBy().getGroupByExpressions() == null) {
            return columns;
        }
        for (Object expression : plain.getGroupBy().getGroupByExpressions()) {
            if (expression instanceof Column column) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static boolean containsColumn(List<Column> columns, Column target) {
        String name = unquote(target.getColumnName());
        String qualifier = target.getTable() == null ? null : unquote(target.getTable().getName());
        for (Column column : columns) {
            String candidate = unquote(column.getColumnName());
            String candidateQualifier = column.getTable() == null ? null : unquote(column.getTable().getName());
            if (candidate == null || name == null || !candidate.equalsIgnoreCase(name)) {
                continue;
            }
            if (qualifier == null || candidateQualifier == null || candidateQualifier.equalsIgnoreCase(qualifier)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAggregate(Function function) {
        String name = function.getName();
        return name != null && AGGREGATES.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean isNonNumeric(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        for (String hint : NUMERIC_HINTS) {
            if (normalized.contains(hint)) {
                return false;
            }
        }
        for (String hint : NON_NUMERIC_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static void collectColumns(Expression expression, List<Column> out) {
        if (expression == null) {
            return;
        }
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S context) {
                out.add(column);
                return null;
            }
        }, null);
    }

    private static String unquote(String identifier) {
        if (identifier == null) {
            return null;
        }
        String value = identifier.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
