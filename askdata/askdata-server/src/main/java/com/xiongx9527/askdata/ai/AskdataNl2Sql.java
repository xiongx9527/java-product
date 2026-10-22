package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.chat.service.ChatQuestionService;
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

    public AskdataNl2Sql(DataSource dataSource, ChatModel chatModel, JdbcTemplate jdbcTemplate,
                         SchemaContextBuilder schemaContextBuilder, DatasourceService datasourceService) {
        this.dataSource = dataSource;
        this.chatModel = chatModel;
        this.jdbcTemplate = jdbcTemplate;
        this.schemaContextBuilder = schemaContextBuilder;
        this.datasourceService = datasourceService;
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
                PromptTemplate.from(SQL_PROMPT), chatModel, MAX_RETRIES);
        retriever.retrieve(Query.from(question));
        String sql = retriever.lastSql();
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalStateException("模型未能生成合法 SQL，请调整问题表述或重试");
        }
        AskdataSqlRetriever.checkSql(sql);
        if (dsId != null) {
            // 与主聊天路径一致：只允许查询已勾选的表（防模型幻觉到未授权表）
            String tableError = ChatQuestionService.validateTables(sql,
                    schemaContextBuilder.checkedTableNames(dsId));
            if (tableError != null) {
                throw new IllegalArgumentException(tableError);
            }
        }
        List<Map<String, Object>> rows = execute(sql, dsId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("question", question);
        out.put("sql", sql);
        out.put("dialect", dialectType.getTemplateName());
        out.put("rows", rows);
        return out;
    }

    private List<Map<String, Object>> execute(String sql, Long dsId) {
        String limited = withRowLimit(sql);
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

    private static String withRowLimit(String sql) {
        String s = sql.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.contains(" LIMIT ") || upper.contains(" TOP ") || upper.contains(" ROWNUM ")) {
            return s;
        }
        return s + " LIMIT " + ROW_LIMIT;
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
