package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态数据源（A8）：高级应用（assistant type 1/3）按请求从宿主拉取的数据源连接配置。
 *
 * <p>与 core_datasource 中的静态数据源不同，动态数据源<b>不持久化</b>：仅在本次请求内
 * 以 {@link DatasourceType} + {@link DatasourceConf} 形式存在，执行时交由
 * {@code JdbcEngine} 按连接配置派生池键（datasourceId=null），与静态数据源走同一条
 * 查询/元数据链路，因此问数行为保持一致。</p>
 */
public class DynamicDatasource {

    /** §2.4: 表级子查询占位符表名前缀（对齐 SQLBot dynamic_subsql_prefix + sqlbot_dynamic_temp_table_ 形态）。
     * 提示词引导模型引用 {@code <前缀><表名>} 占位符表，执行前由
     * {@link #replaceSubSqlPlaceholders} 确定性替换为宿主下发的真实子查询。 */
    public static final String DYNAMIC_SUB_SQL_PREFIX = "askdata_dynamic_temp_table_";

    /** 占位符表名：{@code askdata_dynamic_temp_table_<表名>}。 */
    public static String placeholderTable(String table) {
        return DYNAMIC_SUB_SQL_PREFIX + table;
    }

    /** A18：行规则外层包装别名（{@code SELECT * FROM (<sql>) AS <alias> WHERE ...}）。 */
    public static final String RULE_WRAPPER_ALIAS = "askdata_row_rule_t";

    /**
     * 执行前确定性替换：把 SQL 中引用的占位符表名替换为 {@code (宿主子查询)}。
     * 按词边界匹配、大小写不敏感；宿主子查询文本原样内联，不做重写（brief D2）。
     */
    public static String replaceSubSqlPlaceholders(String sql, Map<String, String> tableSqlTemplates) {
        if (sql == null || sql.trim().isEmpty() || tableSqlTemplates == null || tableSqlTemplates.isEmpty()) {
            return sql;
        }
        String result = sql;
        for (Map.Entry<String, String> entry : tableSqlTemplates.entrySet()) {
            String placeholder = placeholderTable(entry.getKey());
            Pattern p = Pattern.compile(Pattern.quote(placeholder), Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(result);
            if (m.find()) {
                result = m.replaceAll(Matcher.quoteReplacement("(" + entry.getValue() + ")"));
            }
        }
        return result;
    }

    /**
     * 直接物理表引用检查（替换前调用）：受限表必须通过占位符引用。
     * SQL 直接写宿主受限表名会绕过宿主子查询的数据边界（brief D1），按校验错误处理。
     *
     * @return 错误信息；通过返回 null
     */
    public static String directTableReferenceError(String sql, Map<String, String> tableSqlTemplates,
                                                   java.util.function.Function<String, List<String>> tablesOf) {
        if (sql == null || sql.trim().isEmpty() || tableSqlTemplates == null || tableSqlTemplates.isEmpty()) {
            return null;
        }
        Set<String> lowerKeys = new LinkedHashSet<>();
        for (String key : tableSqlTemplates.keySet()) {
            lowerKeys.add(key.toLowerCase(Locale.ROOT));
        }
        for (String table : tablesOf.apply(sql)) {
            if (lowerKeys.contains(table)) {
                return "受限表 " + table + " 必须通过占位符表 "
                        + placeholderTable(table) + " 引用（宿主已下发该表的子查询模板）";
            }
        }
        return null;
    }

    /**
     * 残留占位符检查（替换后调用）：SQL 仍引用 {@code <前缀><表名>} 且该表没有宿主模板时，
     * 不能执行（会打到不存在的占位符表）。
     *
     * @return 错误信息；通过返回 null
     */
    public static String leftoverPlaceholderError(String sql, Map<String, String> tableSqlTemplates,
                                                  java.util.function.Function<String, List<String>> tablesOf) {
        if (sql == null || !sql.contains(DYNAMIC_SUB_SQL_PREFIX)) {
            return null;
        }
        for (String table : tablesOf.apply(sql)) {
            if (table.startsWith(DYNAMIC_SUB_SQL_PREFIX)
                    && (tableSqlTemplates == null
                        || !tableSqlTemplates.containsKey(table.substring(DYNAMIC_SUB_SQL_PREFIX.length())))) {
                return "SQL 引用了未知的动态表占位符: " + table;
            }
        }
        return null;
    }

    /** 宿主子查询模板内部引用的物理表（用于扩展表名白名单允许集）。 */
    public static List<String> templateInnerTables(Map<String, String> tableSqlTemplates,
                                                   java.util.function.Function<String, List<String>> tablesOf) {
        List<String> names = new ArrayList<>();
        if (tableSqlTemplates == null) {
            return names;
        }
        for (String sub : tableSqlTemplates.values()) {
            for (String t : tablesOf.apply(sub)) {
                if (!names.contains(t)) {
                    names.add(t);
                }
            }
        }
        return names;
    }

    /**
     * A18：行规则校验——规则文本会被原样内联进 SQL，故在边界上拒绝语句拼接与注释。
     *
     * @return 错误信息；合法返回 null
     */
    public static String rowRuleValidationError(String table, String rule) {
        if (rule == null || rule.trim().isEmpty()) {
            return "表 " + table + " 的行规则为空";
        }
        String text = rule.trim();
        for (String bad : new String[] {";", "--", "/*", "*/"}) {
            if (text.contains(bad)) {
                return "表 " + table + " 的行规则包含非法字符: " + bad;
            }
        }
        String upper = text.toUpperCase(Locale.ROOT);
        for (String keyword : new String[] {"UNION", "INSERT", "UPDATE", "DELETE", "DROP",
                "ALTER", "CREATE", "TRUNCATE", "GRANT", "REVOKE", "EXEC", "MERGE"}) {
            if (upper.matches(".*\\b" + keyword + "\\b.*")) {
                return "表 " + table + " 的行规则包含非法关键字: " + keyword;
            }
        }
        return null;
    }

    /**
     * A18：把宿主下发的表级行规则注入生成的 SQL。
     *
     * <p>规则命中（SQL 引用了带规则的表）时，用外层包装把过滤条件下推为最终结果的 WHERE：
     * {@code SELECT * FROM (<原SQL>) AS askdata_row_rule_t WHERE (<规则>)}。</p>
     *
     * <p>这样做的原因：宿主规则是<b>数据边界</b>而非查询优化提示，必须对模型输出的任意形态
     * （聚合、子查询、JOIN）都确定生效；外层包装无需解析 SQL 结构即可保证语义，
     * 且不改变原 SQL 的投影列名（前端按字段名渲染）。</p>
     *
     * @param sql       生成的 SQL（已做过占位符替换）
     * @param tableRules 表名 → WHERE 条件片段（如 {@code dept_id = 3}）
     * @param tablesOf   SQL → 引用表名列表（与其它校验共用同一解析）
     * @return 注入后的 SQL；无命中规则时原样返回
     */
    public static String applyRowRules(String sql, Map<String, String> tableRules,
                                       java.util.function.Function<String, List<String>> tablesOf) {
        if (sql == null || sql.trim().isEmpty() || tableRules == null || tableRules.isEmpty()) {
            return sql;
        }
        List<String> used = tablesOf.apply(sql);
        if (used == null || used.isEmpty()) {
            return sql;
        }
        List<String> conditions = new ArrayList<>();
        for (Map.Entry<String, String> entry : tableRules.entrySet()) {
            String table = entry.getKey();
            if (table == null || table.trim().isEmpty()) {
                continue;
            }
            if (!used.contains(table.trim()) && !used.contains(table.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String rule = entry.getValue();
            String error = rowRuleValidationError(table, rule);
            if (error != null) {
                throw new IllegalArgumentException(error);
            }
            conditions.add("(" + rule.trim() + ")");
        }
        if (conditions.isEmpty()) {
            return sql;
        }
        return "SELECT * FROM (" + sql.trim() + ") AS " + RULE_WRAPPER_ALIAS
                + " WHERE " + String.join(" AND ", conditions);
    }

    /**
     * A18：行规则提示词段——把生效中的行规则告知模型，避免模型生成与规则冲突的过滤条件。
     */
    public static String rowRulesPromptSection(Map<String, String> tableRules) {
        if (tableRules == null || tableRules.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : tableRules.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    private final DatasourceType type;

    private final DatasourceConf conf;

    private final String name;

    /** §2.4: 表级 SQL 模板（SQLBot dynamic_sql）——宿主按表下发的子查询模板，
     * 键为表名、值为子查询模板（可含占位符，由二次 LLM 改写为具体 SQL）。
     * 原 askdata 动态数据源只有 type/conf/name，缺少该维度，整条子查询改写链路不存在。 */
    private final Map<String, String> tableSqlTemplates;

    /** A18：宿主下发的表级行规则（表名 → WHERE 条件片段），执行前由 {@link #applyRowRules} 注入。 */
    private final Map<String, String> tableRules;

    public DynamicDatasource(DatasourceType type, DatasourceConf conf, String name) {
        this(type, conf, name, Collections.<String, String>emptyMap());
    }

    public DynamicDatasource(DatasourceType type, DatasourceConf conf, String name,
                            Map<String, String> tableSqlTemplates) {
        this(type, conf, name, tableSqlTemplates, Collections.<String, String>emptyMap());
    }

    /** A18：含表级行规则的构造（宿主在 dynamic_datasource 中下发 {@code table_rule}）。 */
    public DynamicDatasource(DatasourceType type, DatasourceConf conf, String name,
                            Map<String, String> tableSqlTemplates, Map<String, String> tableRules) {
        this.type = type;
        this.conf = conf == null ? new DatasourceConf() : conf;
        this.name = (name == null || name.trim().isEmpty()) ? type.getDbName() : name.trim();
        this.tableSqlTemplates = tableSqlTemplates == null ? Collections.<String, String>emptyMap()
                : tableSqlTemplates;
        this.tableRules = tableRules == null ? Collections.<String, String>emptyMap() : tableRules;
    }

    public DatasourceType getType() {
        return type;
    }

    public DatasourceConf getConf() {
        return conf;
    }

    /** 展示名（宿主未提供时回落到类型展示名）。 */
    public String getName() {
        return name;
    }

    /** §2.4: 表级 SQL 模板（表名 → 子查询模板）。 */
    public Map<String, String> getTableSqlTemplates() {
        return tableSqlTemplates;
    }

    /** 数据源类型字符串（落 chat_record.engine_type / 对外 type 字段）。 */
    public String typeName() {
        return type.getType();
    }

    /** A18：宿主下发的表级行规则（表名 → WHERE 条件片段）。 */
    public Map<String, String> getTableRules() {
        return tableRules;
    }
}

