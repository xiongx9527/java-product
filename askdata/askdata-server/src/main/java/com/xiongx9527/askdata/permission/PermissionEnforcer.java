package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import java.util.Locale;
import java.util.Optional;
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
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
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
 * <p><b>fail-closed</b>：SQL 无法解析（JSQLParserException）或改写过程出现运行时异常时，
 * 抛出 {@link PermissionEnforceException} 拒绝执行，不再以未加固的原始 SQL 放行
 * （对齐 SQLBot {@code llm.py:1068-1085}：重写失败即抛错终止）。
 * 无表可匹配、无权限规则或管理员场景仍按原样返回。</p>
 */
@Service
public class PermissionEnforcer {

    private final DsPermissionQueryService permQuery;
    private final CurrentUserService currentUserService;
    private final ChatModelClient chatModelClient;
    private final ChatModelRouter modelRouter;

    public PermissionEnforcer(DsPermissionQueryService permQuery, CurrentUserService currentUserService,
                             ChatModelClient chatModelClient, ChatModelRouter modelRouter) {
        this.permQuery = permQuery;
        this.currentUserService = currentUserService;
        this.chatModelClient = chatModelClient;
        this.modelRouter = modelRouter;
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

    /** 权限加固失败（fail-closed）时抛出，调用方据此拒绝执行并返回错误。 */
    public static class PermissionEnforceException extends RuntimeException {
        public PermissionEnforceException(String message, Throwable cause) {
            super(message, cause);
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
        // 解析失败即 fail-closed：连表名都拿不到，确定性改写无从谈起
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new PermissionEnforceException("failed to parse SQL for permission enforcement", e);
        }
        List<String> tables = collectAllTableNames(stmt);
        if (tables.isEmpty()) {
            return new EnforcedQuery(sql, Collections.emptySet());
        }
        // 行权限过滤器
        Map<String, String> tableFilters = new LinkedHashMap<>();
        for (Map<String, Object> f : permQuery.rowFilters(oid, userId, dsId, tables)) {
            String tableName = Rows.str(f, "table");
            String filter = Rows.str(f, "filter");
            if (!tableName.isEmpty() && !filter.isEmpty()) {
                tableFilters.put(tableName, filter);
            }
        }
        // 列权限：隐藏列集合
        Set<String> hidden = new HashSet<>();
        for (String t : tables) {
            hidden.addAll(permQuery.hiddenColumns(oid, userId, dsId, t));
        }
        // 主路径：确定性 JSQLParser 子查询包裹改写（fail-closed）
        try {
            String rewritten = rewriteSqlWithRowFilters(stmt, tableFilters);
            return new EnforcedQuery(rewritten, hidden);
        } catch (RuntimeException deterministicEx) {
            // 偏差③收敛：确定性改写失败（极少见，如不可包裹的复杂结构）时退化为 LLM 改写 + 复验 fail-closed
            return enforceWithLlmFallback(sql, tables, tableFilters, hidden, deterministicEx);
        }
    }

    /**
     * 偏差③：确定性 JSQLParser 改写失败时的 LLM 兜底（对齐 SQLBot build_table_filter）。
     * 用 LLM 按行权限 filters 改写 SQL，随后 JSQLParser 复验（filter 谓词存在且表集合不变），
     * 复验失败则 fail-closed 拒绝，绝不以未加固 SQL 放行。
     */
    private EnforcedQuery enforceWithLlmFallback(String sql, List<String> tables,
                                                Map<String, String> tableFilters, Set<String> hidden,
                                                RuntimeException deterministicEx) {
        Optional<ChatModelRouter.ModelRef> modelOpt = modelRouter.defaultChatModel();
        if (!modelOpt.isPresent()) {
            throw new PermissionEnforceException(
                    "deterministic row-permission rewrite failed and no LLM fallback available", deterministicEx);
        }
        String llmSql = callRowFilterModel(modelOpt.get(), sql, tableFilters);
        if (llmSql == null || llmSql.trim().isEmpty()) {
            throw new PermissionEnforceException("LLM row-permission fallback returned empty SQL", deterministicEx);
        }
        // 复验：① 可解析；② 表集合不变；③ filter 谓词均在
        try {
            Statement reStmt = CCJSqlParserUtil.parse(llmSql);
            List<String> newTables = collectAllTableNames(reStmt);
            if (!sameTableSet(tables, newTables)) {
                throw new PermissionEnforceException(
                        "LLM row-permission fallback changed the table set (possible privilege escalation)",
                        deterministicEx);
            }
            verifyFilterPredicatesPresent(llmSql, tableFilters);
            return new EnforcedQuery(llmSql, hidden);
        } catch (JSQLParserException re) {
            throw new PermissionEnforceException("LLM row-permission fallback produced unparseable SQL", re);
        } catch (PermissionEnforceException pe) {
            throw pe;
        } catch (RuntimeException re) {
            throw new PermissionEnforceException("LLM row-permission fallback failed re-verify", re);
        }
    }

    /** 让 LLM 按 tableFilters 改写 SQL（对齐 SQLBot build_table_filter）。 */
    private String callRowFilterModel(ChatModelRouter.ModelRef model, String sql, Map<String, String> tableFilters) {
        List<ChatModelClient.ModelMessage> messages = new ArrayList<>();
        messages.add(new ChatModelClient.ModelMessage("system",
                "你是一个 SQL 改写助手。给定一条 SQL 与若干「表 → 行级过滤条件」，"
                        + "请把每个过滤条件注入到对应表的查询中（可将该表包裹为子查询 SELECT * FROM (SELECT * FROM <表> WHERE <条件>) <别名>，"
                        + "或在已有 WHERE 上用 AND 追加；不得改动其它逻辑、不得访问未列出的表）。"
                        + "只返回修改后的完整 SQL，不要解释，不要使用 Markdown 代码块。"));
        StringBuilder user = new StringBuilder("原始 SQL：\n").append(sql).append("\n\n需要注入的行级过滤条件：\n");
        if (tableFilters.isEmpty()) {
            user.append("（无）\n");
        } else {
            for (Map.Entry<String, String> e : tableFilters.entrySet()) {
                user.append("- 表 ").append(e.getKey()).append(" 需满足：").append(e.getValue()).append("\n");
            }
        }
        user.append("\n请返回注入过滤条件后的完整 SQL。");
        messages.add(new ChatModelClient.ModelMessage("user", user.toString()));
        String text = chatModelClient.generate(model.apiDomain, model.apiKey, model.baseModel, messages, model.params);
        if (text == null) {
            return null;
        }
        // 去掉 Markdown 代码围栏（反引号，char 96）与结尾分号
        String bt = String.valueOf((char) 96);
        text = text.replace(bt, "").trim();
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** 复验：每条行权限 filter 的关键谓词必须出现在改写后的 SQL 中。 */
    private void verifyFilterPredicatesPresent(String sql, Map<String, String> tableFilters) {
        if (tableFilters == null || tableFilters.isEmpty()) {
            return;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : tableFilters.entrySet()) {
            String cond = normalizePredicate(e.getValue());
            if (!lower.contains(cond)) {
                throw new PermissionEnforceException(
                        "LLM fallback omitted row filter for table " + e.getKey() + ": " + e.getValue(), null);
            }
        }
    }

    private static String normalizePredicate(String filter) {
        String f = filter.trim();
        if (f.startsWith("(") && f.endsWith(")")) {
            f = f.substring(1, f.length() - 1).trim();
        }
        return f.toLowerCase(Locale.ROOT).replaceAll("[ \\t\\r\\n]+", " ");
    }

    private static boolean sameTableSet(List<String> a, List<String> b) {
        Set<String> sa = new HashSet<>();
        for (String t : a) {
            sa.add(t.toLowerCase(Locale.ROOT));
        }
        Set<String> sb = new HashSet<>();
        for (String t : b) {
            sb.add(t.toLowerCase(Locale.ROOT));
        }
        return sa.equals(sb);
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
            Statement stmt = CCJSqlParserUtil.parse(sql);
            return rewriteSqlWithRowFilters(stmt, tableFilters);
        } catch (JSQLParserException e) {
            return sql;
        }
    }

    /** 递归对整条语句施加行权限片段，覆盖 UNION/CTE/子查询/FROM-JOIN 各处的基表（A3 复杂 SQL 不外溢）。 */
    String rewriteSqlWithRowFilters(Statement stmt, Map<String, String> tableFilters) {
        if (tableFilters == null || tableFilters.isEmpty()) {
            return stmt.toString();
        }
        rewriteStatement(stmt, tableFilters);
        return stmt.toString();
    }

    /** 递归处理语句：SELECT 走 rewriteSelect；UNION/集合操作逐支处理且其 CTE 体也处理。 */
    private void rewriteStatement(Statement stmt, Map<String, String> tableFilters) {
        if (stmt instanceof Select) {
            rewriteSelect((Select) stmt, tableFilters);
        } else if (stmt instanceof SetOperationList) {
            rewriteSetOperation((SetOperationList) stmt, tableFilters);
        }
    }

    /** 处理单个 SELECT：先递归 CTE 体，再处理 UNION/集合操作体，最后包裹 FROM/JOIN 中的匹配基表（含 FROM 子查询）。 */
    private void rewriteSelect(Select select, Map<String, String> tableFilters) {
        if (select.getWithItemsList() != null) {
            for (WithItem w : select.getWithItemsList()) {
                Select s = w.getSelect();
                if (s != null) {
                    rewriteSelect(s, tableFilters);
                }
            }
        }
        // 集合操作（UNION/INTERSECT/EXCEPT）体不得绕过行权限：解析为 SetOperationList 时逐支递归
        SetOperationList setOp = null;
        try {
            setOp = select.getSetOperationList();
        } catch (ClassCastException ignore) {
            setOp = null;
        }
        if (setOp != null) {
            rewriteSetOperation(setOp, tableFilters);
            return;
        }
        PlainSelect ps = select.getPlainSelect();
        if (ps == null) {
            return;
        }
        FromItem wrappedFrom = rewriteFromItem(ps.getFromItem(), tableFilters);
        if (wrappedFrom != null) {
            ps.setFromItem(wrappedFrom);
        }
        List<Join> joins = ps.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                FromItem wrappedRight = rewriteFromItem(join.getRightItem(), tableFilters);
                if (wrappedRight != null) {
                    join.setRightItem(wrappedRight);
                }
            }
        }
    }

    /** 处理集合操作：其 WITH 体与各 SELECT 分支逐一递归（A3 复杂 SQL 不外溢）。 */
    private void rewriteSetOperation(SetOperationList sol, Map<String, String> tableFilters) {
        if (sol.getWithItemsList() != null) {
            for (WithItem w : sol.getWithItemsList()) {
                Select s = w.getSelect();
                if (s != null) {
                    rewriteSelect(s, tableFilters);
                }
            }
        }
        for (Select s : sol.getSelects()) {
            rewriteSelect(s, tableFilters);
        }
    }

    /** FROM/JOIN 项的改写：基表按 filter 包裹为子查询；子查询（含 CTE 引用的内层）递归处理后返回 null。 */
    private FromItem rewriteFromItem(FromItem item, Map<String, String> tableFilters) {
        if (item instanceof Table) {
            return wrapIfNeeded((Table) item, tableFilters);
        }
        if (item instanceof Select) {
            rewriteSelect((Select) item, tableFilters);
        }
        return null;
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

    /** 递归收集语句中所有基表名（含 CTE 体、UNION 各支、FROM/JOIN 子查询），用于权限规则匹配。 */
    private List<String> collectAllTableNames(Statement stmt) {
        Set<String> names = new HashSet<>();
        collectFromStatement(stmt, names);
        return new ArrayList<>(names);
    }

    private void collectFromStatement(Statement stmt, Set<String> names) {
        if (stmt instanceof Select) {
            collectFromSelect((Select) stmt, names);
        } else if (stmt instanceof SetOperationList) {
            SetOperationList sol = (SetOperationList) stmt;
            if (sol.getWithItemsList() != null) {
                for (WithItem w : sol.getWithItemsList()) {
                    Select s = w.getSelect();
                    if (s != null) {
                        collectFromSelect(s, names);
                    }
                }
            }
            for (Select s : sol.getSelects()) {
                collectFromSelect(s, names);
            }
        }
    }

    private void collectFromSelect(Select select, Set<String> names) {
        if (select.getWithItemsList() != null) {
            for (WithItem w : select.getWithItemsList()) {
                Select s = w.getSelect();
                if (s != null) {
                    collectFromSelect(s, names);
                }
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
                for (WithItem w : setOp.getWithItemsList()) {
                    Select s = w.getSelect();
                    if (s != null) {
                        collectFromSelect(s, names);
                    }
                }
            }
            for (Select s : setOp.getSelects()) {
                collectFromSelect(s, names);
            }
            return;
        }
        PlainSelect ps = select.getPlainSelect();
        if (ps == null) {
            return;
        }
        collectFromItem(ps.getFromItem(), names);
        List<Join> joins = ps.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                collectFromItem(join.getRightItem(), names);
            }
        }
    }

    private void collectFromItem(FromItem item, Set<String> names) {
        if (item instanceof Table) {
            String name = ((Table) item).getName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        } else if (item instanceof Select) {
            collectFromSelect((Select) item, names);
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
