package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问数执行期行/列权限强制（A3；对齐 SQLBot {@code apps/chat/task/llm.py} 的 {@code generate_filter}
 * + {@code apps/datasource/crud/datasource.py} 的权限强制）。
 *
 * <p>此前 {@link DsPermissionQueryService} 只计算规则却无任何调用方（权限「存而不生效」）。本服务把它接入
 * 问数执行路径，使权限对普通用户 <b>运行时生效</b>：</p>
 * <ul>
 *   <li><b>行权限</b>：把每条 {@code ds_permission(type=row)} 的 WHERE 片段，以子查询包裹的方式注入到
 *       SQL 的对应表上（{@code SELECT * FROM (SELECT * FROM t WHERE <filter>) t}），
 *       过滤条件因此出现在最终执行的 SQL 中，由数据库层完成行级过滤；</li>
 *   <li><b>列权限</b>：把 {@code type=column} 中 {@code enable=false} 的字段从返回结果集（fields/data）
 *       中剔除，用户不可见无权限列；</li>
 *   <li>管理员与白名单用户不受影响（{@link DsPermissionQueryService} 内部已对用户身份做短路）。</li>
 * </ul>
 *
 * <p>降级优先于报错：解析失败、权限配置不可用或规则无法套用时，原样返回 SQL 且不隐藏列，
 * 不阻断问数主链路（对齐 SQLBot 的降级语义）。</p>
 */
@Service
public class PermissionEnforcer {

    private final DsPermissionQueryService permQuery;
    private final CurrentUserService currentUserService;

    public PermissionEnforcer(DsPermissionQueryService permQuery, CurrentUserService currentUserService) {
        this.permQuery = permQuery;
        this.currentUserService = currentUserService;
    }

    /** 强制结果：改写后的 SQL + 需隐藏的列名集合。 */
    public static class EnforcedQuery {
        private final String sql;
        private final Set<String> hiddenColumns;

        public EnforcedQuery(String sql, Set<String> hiddenColumns) {
            this.sql = sql;
            this.hiddenColumns = hiddenColumns == null ? Collections.emptySet() : hiddenColumns;
        }

        public String getSql() {
            return sql;
        }

        public Set<String> getHiddenColumns() {
            return hiddenColumns;
        }
    }

    /**
     * 对问数 SQL 施加行/列权限（A3 运行时生效入口）。
     *
     * @param oid    工作空间
     * @param userId 当前用户（管理员自动跳过）
     * @param dsId   数据源
     * @param sql    模型生成的原始 SQL
     * @return 强制后的 SQL 与隐藏列集合
     */
    public EnforcedQuery enforce(long oid, long userId, long dsId, String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return new EnforcedQuery(sql, Collections.emptySet());
        }
        if (currentUserService.isAdmin(currentUserService.currentUser())) {
            return new EnforcedQuery(sql, Collections.emptySet());
        }
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plain = select.getPlainSelect();
            if (plain == null) {
                return new EnforcedQuery(sql, Collections.emptySet());
            }
            List<String> tables = collectBaseTables(plain);
            if (tables.isEmpty()) {
                return new EnforcedQuery(sql, Collections.emptySet());
            }

            // 行权限：注入 WHERE 片段
            Map<String, String> tableFilters = new LinkedHashMap<>();
            for (Map<String, Object> f : permQuery.rowFilters(oid, userId, dsId, tables)) {
                String tableName = Rows.str(f, "table");
                String filter = Rows.str(f, "filter");
                if (!tableName.isEmpty() && !filter.isEmpty()) {
                    tableFilters.put(tableName, filter);
                }
            }
            String rewritten = rewriteSqlWithRowFilters(select, plain, tableFilters);

            // 列权限：收集隐藏列
            Set<String> hidden = new HashSet<>();
            for (String t : tables) {
                hidden.addAll(permQuery.hiddenColumns(oid, userId, dsId, t));
            }
            return new EnforcedQuery(rewritten, hidden);
        } catch (JSQLParserException | RuntimeException e) {
            // 降级：权限不可用时不阻断查询
            return new EnforcedQuery(sql, Collections.emptySet());
        }
    }

    /**
     * 对 SQL 施加行权限片段（公共入口，便于单测与复用）。
     *
     * @param sql         原始 SELECT
     * @param tableFilters 表名 → WHERE 片段（如 {@code t -> "(region = 'east')"}）
     * @return 注入后的 SQL；无片段或解析失败则原样返回
     */
    public String applyRowFilters(String sql, Map<String, String> tableFilters) {
        if (sql == null || sql.trim().isEmpty() || tableFilters == null || tableFilters.isEmpty()) {
            return sql;
        }
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plain = select.getPlainSelect();
            if (plain == null) {
                return sql;
            }
            return rewriteSqlWithRowFilters(select, plain, tableFilters);
        } catch (JSQLParserException e) {
            return sql;
        }
    }

    /** 把行权限片段以子查询包裹方式注入到对应表（filter 出现在最终 SQL 中）。 */
    String rewriteSqlWithRowFilters(Select select, PlainSelect plain, Map<String, String> tableFilters) {
        if (tableFilters == null || tableFilters.isEmpty()) {
            return select.toString();
        }
        boolean changed = false;
        FromItem from = plain.getFromItem();
        FromItem wrappedFrom = wrapIfNeeded(from, tableFilters);
        if (wrappedFrom != null) {
            plain.setFromItem(wrappedFrom);
            changed = true;
        }
        List<Join> joins = plain.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                FromItem right = join.getRightItem();
                FromItem wrappedRight = wrapIfNeeded(right, tableFilters);
                if (wrappedRight != null) {
                    join.setRightItem(wrappedRight);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return select.toString();
        }
        return select.toString();
    }

    /** 若 fromItem 是带行权限的表，则返回包裹了 WHERE 的子查询；否则返回 null。 */
    private FromItem wrapIfNeeded(FromItem item, Map<String, String> tableFilters) {
        if (!(item instanceof Table)) {
            return null;
        }
        Table table = (Table) item;
        String dbName = table.getName();
        String filter = tableFilters.get(dbName);
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        Expression where;
        try {
            where = parseFilter(filter);
        } catch (JSQLParserException e) {
            return null;
        }
        if (where == null) {
            return null;
        }
        PlainSelect inner = new PlainSelect();
        inner.addSelectItems(new AllColumns());
        Table innerTable = new Table();
        if (table.getSchemaName() != null) {
            innerTable.setSchemaName(table.getSchemaName());
        }
        innerTable.setName(dbName);
        inner.setFromItem(innerTable);
        inner.setWhere(where);

        ParenthesedSelect sub = new ParenthesedSelect();
        sub.setSelect(inner);
        String alias = table.getAlias() != null ? table.getAlias().getName() : dbName;
        sub.setAlias(new Alias(alias));
        return sub;
    }

    /** 解析过滤器表达式（去掉最外层括号），失败返回 null。 */
    private Expression parseFilter(String filter) throws JSQLParserException {
        String f = filter.trim();
        if (f.startsWith("(") && f.endsWith(")")) {
            f = f.substring(1, f.length() - 1).trim();
        }
        if (f.isEmpty()) {
            return null;
        }
        return CCJSqlParserUtil.parseExpression(f);
    }

    /** 收集主查询 FROM/JOIN 中的基表名（不含子查询内的表）。 */
    private List<String> collectBaseTables(PlainSelect plain) {
        List<String> tables = new ArrayList<>();
        addTableName(plain.getFromItem(), tables);
        List<Join> joins = plain.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                addTableName(join.getRightItem(), tables);
            }
        }
        return tables;
    }

    private void addTableName(FromItem item, List<String> tables) {
        if (item instanceof Table) {
            String name = ((Table) item).getName();
            if (name != null && !name.isEmpty()) {
                tables.add(name);
            }
        }
    }

    /**
     * 从结果集中隐藏列权限禁止的列（A3 列权限生效）。
     *
     * @param result        execSql 返回结构（含 fields / fields_info / data）
     * @param hiddenColumns 需隐藏的列名集合（大小写不敏感匹配）
     * @return 剔除隐藏列后的结果（原结构被就地修改并返回）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> applyHiddenColumns(Map<String, Object> result, Set<String> hiddenColumns) {
        if (result == null || hiddenColumns == null || hiddenColumns.isEmpty()) {
            return result;
        }
        Set<String> lowerHidden = new HashSet<>();
        for (String h : hiddenColumns) {
            lowerHidden.add(h.toLowerCase(java.util.Locale.ROOT));
        }
        List<Object> fields = (List<Object>) result.get("fields");
        List<Map<String, Object>> fieldsInfo = (List<Map<String, Object>>) result.get("fields_info");
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");

        List<Integer> removeIdx = new ArrayList<>();
        if (fields != null) {
            for (int i = 0; i < fields.size(); i++) {
                if (isHidden(String.valueOf(fields.get(i)), lowerHidden)) {
                    removeIdx.add(i);
                }
            }
        }
        if (!removeIdx.isEmpty()) {
            if (fields != null) {
                List<Object> keptFields = new ArrayList<>();
                for (int i = 0; i < fields.size(); i++) {
                    if (!removeIdx.contains(i)) {
                        keptFields.add(fields.get(i));
                    }
                }
                result.put("fields", keptFields);
            }
            if (fieldsInfo != null) {
                List<Map<String, Object>> keptInfo = new ArrayList<>();
                for (int i = 0; i < fieldsInfo.size(); i++) {
                    if (!removeIdx.contains(i)) {
                        keptInfo.add(fieldsInfo.get(i));
                    }
                }
                result.put("fields_info", keptInfo);
            }
            if (data != null) {
                for (Map<String, Object> row : data) {
                    for (String h : lowerHidden) {
                        // 精确 + 短列名（alias.column → column）匹配
                        row.keySet().removeIf(k -> isHidden(k, lowerHidden));
                    }
                }
            }
        }
        return result;
    }

    private boolean isHidden(String column, Set<String> lowerHidden) {
        if (column == null) {
            return false;
        }
        String lower = column.toLowerCase(java.util.Locale.ROOT);
        if (lowerHidden.contains(lower)) {
            return true;
        }
        int dot = lower.lastIndexOf('.');
        return dot > 0 && lowerHidden.contains(lower.substring(dot + 1));
    }
}
