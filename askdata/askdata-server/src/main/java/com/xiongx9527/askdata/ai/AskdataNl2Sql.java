package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.embedded.DynamicDatasource;
import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.xiongx9527.askdata.common.i18n.I18n;

/**
 * 问数 NL2SQL：自然语言 -> SQL -> 只读执行 -> 返回 SQL 与结果行。
 *
 * <p>验收 A2：不再硬编码 MySQL、不再每次问数全库 DatabaseMetaData 扫描。
 * 方言由数据源类型（或应用库连接的元数据）动态解析；schema 文本复用主路径的
 * SchemaContextBuilder top-N 选表（{@code buildSchemaText(long, String)} 或
 * {@code buildSchemaTextForConnection(DataSource, String)}），而非实时全库扫描。
 * 选定数据源（dsId）时，SQL 在该数据源上执行（通过 DatasourceService），
 * 因此非 MySQL 数据源也能产出并执行正确方言的 SQL。</p>
 *
 * <p>由 askdata.assistant.enabled=true 门控（需默认对话模型）。</p>
 */
@Service
@ConditionalOnProperty(name = "askdata.assistant.enabled", havingValue = "true")
public class AskdataNl2Sql {

    private static final Logger log = LoggerFactory.getLogger(AskdataNl2Sql.class);

    private static final int MAX_RETRIES = 3;

    private static final int ROW_LIMIT = 200;

    /** 与 langchain4j SqlDatabaseContentRetriever 默认提示词一致（变量 {{sqlDialect}}/{{databaseStructure}}）。 */
    public static final String SQL_PROMPT = """
            You are an expert in writing SQL queries.
            You have access to a {{sqlDialect}} database with the following structure:
            {{databaseStructure}}
            If a user asks a question that can be answered by querying this database, generate an SQL SELECT query.
            Do not output anything else aside from a valid SQL statement!""";

    private final DataSource dataSource;

    private final ChatModel chatModel;

    private final JdbcTemplate jdbcTemplate;

    private final SchemaContextBuilder schemaContextBuilder;

    private final DatasourceService datasourceService;

    private final com.xiongx9527.askdata.permission.PermissionEnforcer permissionEnforcer;

    private final com.xiongx9527.askdata.system.service.CurrentUserService currentUserService;

    public AskdataNl2Sql(DataSource dataSource, ChatModel chatModel, JdbcTemplate jdbcTemplate,
                         SchemaContextBuilder schemaContextBuilder, DatasourceService datasourceService,
                         com.xiongx9527.askdata.permission.PermissionEnforcer permissionEnforcer,
                         com.xiongx9527.askdata.system.service.CurrentUserService currentUserService) {
        this.dataSource = dataSource;
        this.chatModel = chatModel;
        this.jdbcTemplate = jdbcTemplate;
        this.schemaContextBuilder = schemaContextBuilder;
        this.datasourceService = datasourceService;
        this.permissionEnforcer = permissionEnforcer;
        this.currentUserService = currentUserService;
    }

    /** 问数（应用库 fallback，无选定数据源）。 */
    public Map<String, Object> ask(String question) throws Exception {
        return ask(question, null);
    }

    /**
     * 问数：生成 SQL 并只读执行，返回 question/sql/dialect/rows。
     *
     * @param question 用户问题
     * @param dsId     选定数据源 id；为 null 时使用应用库连接（fallback）
     */
    public Map<String, Object> ask(String question, Long dsId) throws Exception {
        return ask(question, dsId, null);
    }

    /**
     * 问数（可指定模型覆盖，对齐 SQLBot MCP {@code custom_model}）。
     *
     * @param modelOverride 非空时用该模型替代默认 ChatModel；为空沿用注入的默认模型
     */
    public Map<String, Object> ask(String question, Long dsId, ChatModel modelOverride) throws Exception {
        ChatModel model = modelOverride != null ? modelOverride : chatModel;
        DatasourceType dialectType;
        String structure;
        if (dsId != null) {
            dialectType = datasourceService.type(dsId);
            structure = schemaContextBuilder.buildSchemaText(dsId, question);
        } else {
            // A2：应用库 fallback 不再全库 dump 完整 schema，改为 top-N 选表（按问题相关性）
            dialectType = detectDialect(dataSource);
            structure = schemaContextBuilder.buildSchemaTextForConnection(dataSource, question);
        }
        AskdataSqlRetriever retriever = new AskdataSqlRetriever(
                dataSource, dialectType.getTemplateName(), structure,
                PromptTemplate.from(SQL_PROMPT), model, MAX_RETRIES);
        retriever.retrieve(Query.from(question));
        String sql = retriever.lastSql();
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalStateException(I18n.msg("nl2sql.no.valid.sql"));
        }
        AskdataSqlRetriever.checkSql(sql);
        // A1/A2: 与主聊天路径一致的权限链——先表白名单，再行/列权限强制（MCP 问数此前只查表白名单，
        // 行/列权限完全旁路）。普通数据源（dsId != null）必须走 PermissionEnforcer；
        // 应用库 fallback（dsId == null）与 SQLBot 行为一致，不做数据源级权限。
        com.xiongx9527.askdata.permission.PermissionEnforcer.EnforcedQuery enforced = null;
        if (dsId != null) {
            // 与主聊天路径一致：只允许查询已勾选的表（防模型幻觉到未授权表）
            String tableError = ChatQuestionService.validateTables(sql,
                    schemaContextBuilder.checkedTableNames(dsId));
            if (tableError != null) {
                throw new IllegalArgumentException(tableError);
            }
            if (permissionEnforcer != null) {
                enforced = enforceForMcp(permissionEnforcer, currentUserService, dsId, sql);
                if (enforced != null) {
                    sql = enforced.getSql();
                }
            }
        }
        List<Map<String, Object>> rows = execute(sql, dsId, dialectType);
        if (enforced != null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("data", rows);
            result = permissionEnforcer.applyHiddenColumns(result, enforced.getHiddenColumns());
            rows = (List<Map<String, Object>>) result.get("data");
            if (rows == null) {
                rows = new ArrayList<Map<String, Object>>();
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("question", question);
        out.put("sql", sql);
        out.put("dialect", dialectType.getTemplateName());
        out.put("rows", rows);
        return out;
    }

    /**
     * A24：宿主下发动态数据源问数（对齐 SQLBot {@code mcp_assistant} 的第三方数据接口链路）。
     *
     * <p>{@code url} + {@code authorization} 指向宿主数据源 API，返回的数据源仅在本次请求内
     * 以 {@link DynamicDatasource} 存在：schema 取自该连接的实时元数据，SQL 生成后、执行前
     * 注入宿主下发的表级子查询模板与行规则，执行走
     * {@link DatasourceService#execSqlTransient}（不写入 core_datasource）。</p>
     *
     * <p>与静态数据源路径的差异：表白名单来自宿主连接实时元数据；数据边界由宿主行规则表达
     * （SQLBot 侧高级应用同样不走 askdata 的行/列权限表）。</p>
     *
     * @param dynamic       宿主下发的动态数据源（type/conf/表级模板/行规则）
     * @param modelOverride 非空时用该模型替代默认 ChatModel
     */
    public Map<String, Object> ask(String question, DynamicDatasource dynamic, ChatModel modelOverride)
            throws Exception {
        ChatModel model = modelOverride != null ? modelOverride : chatModel;
        DatasourceType dialectType = dynamic.getType();
        String structure = schemaContextBuilder.buildSchemaTextForConf(dialectType, dynamic.getConf(), question);
        // generateOnly：SQL 的目标库是宿主连接而非应用库，不能让父类在应用库上执行
        AskdataSqlRetriever retriever = new AskdataSqlRetriever(
                dataSource, dialectType.getTemplateName(), structure,
                PromptTemplate.from(SQL_PROMPT), model, MAX_RETRIES, true);
        retriever.retrieve(Query.from(question));
        String sql = retriever.lastSql();
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalStateException(I18n.msg("nl2sql.no.valid.sql"));
        }
        AskdataSqlRetriever.checkSql(sql);
        // 宿主表级子查询模板：受限表必须经占位符引用，执行前确定性替换
        if (!dynamic.getTableSqlTemplates().isEmpty()) {
            String directErr = DynamicDatasource.directTableReferenceError(sql, dynamic.getTableSqlTemplates(),
                    ChatQuestionService::usedTables);
            if (directErr != null) {
                throw new IllegalArgumentException(directErr);
            }
            sql = DynamicDatasource.replaceSubSqlPlaceholders(sql, dynamic.getTableSqlTemplates());
        }
        // 宿主行规则：数据边界，执行前强制注入（对齐 SQLBot generate_assistant_filter）
        if (!dynamic.getTableRules().isEmpty()) {
            sql = DynamicDatasource.applyRowRules(sql, dynamic.getTableRules(), ChatQuestionService::usedTables);
        }
        // 表白名单：宿主连接实时元数据（防模型幻觉到不存在的表）
        List<String> allowed = transientTableNames(dynamic);
        if (allowed != null) {
            String tableError = ChatQuestionService.validateTables(sql, allowed);
            if (tableError != null) {
                throw new IllegalArgumentException(tableError);
            }
        }
        List<Map<String, Object>> rows = executeTransient(dynamic, withRowLimit(sql, dialectType));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("question", question);
        out.put("sql", sql);
        out.put("dialect", dialectType.getTemplateName());
        out.put("rows", rows);
        return out;
    }

    /** 宿主动态数据源当前表名（实时元数据）；读取失败返回 null（不阻断，交由执行阶段报真实错误）。 */
    private List<String> transientTableNames(DynamicDatasource dynamic) {
        try {
            List<String> names = new ArrayList<String>();
            for (com.xiongx9527.askdata.datasource.engine.TableSchema table
                    : datasourceService.tablesTransient(dynamic.getType(), dynamic.getConf())) {
                names.add(table.getTableName());
            }
            return names;
        } catch (Exception e) {
            log.warn("宿主数据源表名读取失败（不阻断）: {}", e.getMessage());
            return null;
        }
    }

    /** 动态数据源只读执行（不落库，连接池键按连接配置派生）。 */
    private List<Map<String, Object>> executeTransient(DynamicDatasource dynamic, String limited) {
        Map<String, Object> result = datasourceService.execSqlTransient(
                dynamic.getType(), dynamic.getConf(), limited);
        Object data = result.get("data");
        if (data instanceof List) {
            return (List<Map<String, Object>>) data;
        }
        return new ArrayList<Map<String, Object>>();
    }

    /**
     * A1/A2: MCP 问数权限入口（可测 seam）：以当前登录用户身份对生成 SQL 施加行/列权限，
     * 与 Chat 主链路共用 {@link com.xiongx9527.askdata.permission.PermissionEnforcer}。
     */
    public static com.xiongx9527.askdata.permission.PermissionEnforcer.EnforcedQuery enforceForMcp(
            com.xiongx9527.askdata.permission.PermissionEnforcer enforcer,
            com.xiongx9527.askdata.system.service.CurrentUserService currentUserService,
            long dsId, String sql) {
        Map<String, Object> user = currentUserService.currentUser();
        return enforcer.enforce(currentUserService.oidOf(user),
                com.xiongx9527.askdata.common.util.Rows.lng(user, "id", 0L), dsId, sql);
    }

    private List<Map<String, Object>> execute(String sql, Long dsId, DatasourceType dialect) {
        String limited = withRowLimit(sql, dialect);
        if (dsId != null) {
            Map<String, Object> result = datasourceService.execSql(dsId, limited);
            Object data = result.get("data");
            if (data instanceof List) {
                List<Map<String, Object>> rows = (List<Map<String, Object>>) data;
                return rows;
            }
            return new ArrayList<Map<String, Object>>();
        }
        return jdbcTemplate.queryForList(limited);
    }

    /**
     * 追加行上限，<b>按方言</b>选择分页语法（A6：MySQL/PG/SQLite/H2/CK 等用 {@code LIMIT}；
     * Oracle/达梦 用 {@code FETCH FIRST n ROWS ONLY}；SQL Server 用 {@code OFFSET 0 ROWS FETCH NEXT n ROWS ONLY}）。
     * 已含分页（LIMIT/TOP/ROWNUM/FETCH/OFFSET）则原样返回。
     */
    static String withRowLimit(String sql, DatasourceType dialect) {
        String s = sql == null ? "" : sql.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.contains(" LIMIT ") || upper.contains(" TOP ") || upper.contains(" ROWNUM ")
                || upper.contains(" FETCH ") || upper.contains(" OFFSET ")) {
            return s;
        }
        DatasourceType type = dialect == null ? DatasourceType.MYSQL : dialect;
        switch (type) {
            case ORACLE:
            case DM:
                return s + " FETCH FIRST " + ROW_LIMIT + " ROWS ONLY";
            case SQLSERVER:
                return s + " OFFSET 0 ROWS FETCH NEXT " + ROW_LIMIT + " ROWS ONLY";
            default:
                return s + " LIMIT " + ROW_LIMIT;
        }
    }

    public static DatasourceType detectDialect(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String product = md.getDatabaseProductName();
            String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
            if (name.contains("postgre")) {
                return DatasourceType.PG;
            } else if (name.contains("oracle")) {
                return DatasourceType.ORACLE;
            } else if (name.contains("sql server")) {
                return DatasourceType.SQLSERVER;
            } else if (name.contains("h2")) {
                return DatasourceType.H2;
            } else if (name.contains("clickhouse")) {
                return DatasourceType.CK;
            } else if (name.contains("sqlite")) {
                return DatasourceType.SQLITE;
            } else if (name.contains("mysql") || name.contains("maria")) {
                return DatasourceType.MYSQL;
            }
            return DatasourceType.MYSQL;
        } catch (Exception e) {
            log.warn("方言探测失败，退化为 MySQL: {}", e.getMessage());
            return DatasourceType.MYSQL;
        }
    }
}
