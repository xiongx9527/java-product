package com.xiongx9527.askdata.ai;

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
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问数 NL2SQL：自然语言 → SQL（langchain4j {@link AskdataSqlRetriever}）→ 只读执行 → 返回 SQL 与结果行。
 *
 * <p>复用框架的 SqlDatabaseContentRetriever 扩展点；databaseStructure 由元数据库实时结构生成。
 * 由 {@code askdata.assistant.enabled=true} 门控（需默认对话模型）。</p>
 */
@Service
@ConditionalOnProperty(name = "askdata.assistant.enabled", havingValue = "true")
public class AskdataNl2Sql {

    private static final Logger log = LoggerFactory.getLogger(AskdataNl2Sql.class);

    private static final int MAX_RETRIES = 3;

    private static final int ROW_LIMIT = 200;

    /** 与 langchain4j SqlDatabaseContentRetriever 默认提示词一致（变量 {{sqlDialect}}/{{databaseStructure}}）。 */
    public static final String SQL_PROMPT =
            "You are an expert in writing SQL queries.\n" +
            "You have access to a {{sqlDialect}} database with the following structure:\n" +
            "{{databaseStructure}}\n" +
            "If a user asks a question that can be answered by querying this database, generate an SQL SELECT query.\n" +
            "Do not output anything else aside from a valid SQL statement!";

    private final DataSource dataSource;

    private final ChatModel chatModel;

    private final JdbcTemplate jdbcTemplate;

    public AskdataNl2Sql(DataSource dataSource, ChatModel chatModel, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.chatModel = chatModel;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 问数：生成 SQL 并只读执行，返回 question/sql/rows。 */
    public Map<String, Object> ask(String question) throws Exception {
        String structure = buildDatabaseStructure(dataSource);
        PromptTemplate promptTemplate = PromptTemplate.from(SQL_PROMPT);
        AskdataSqlRetriever retriever = new AskdataSqlRetriever(
                dataSource, "MySQL", structure, promptTemplate, chatModel, MAX_RETRIES);
        retriever.retrieve(Query.from(question));
        String sql = retriever.lastSql();
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalStateException("模型未能生成合法 SQL，请调整问题表述或重试");
        }
        List<Map<String, Object>> rows = execute(sql);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("question", question);
        out.put("sql", sql);
        out.put("rows", rows);
        return out;
    }

    /** 只读执行（行数上限保护）。 */
    private List<Map<String, Object>> execute(String sql) {
        String limited = withRowLimit(sql);
        return jdbcTemplate.queryForList(limited);
    }

    private static String withRowLimit(String sql) {
        String s = sql.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        String upper = s.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains(" LIMIT ")) {
            return s;
        }
        return s + " LIMIT " + ROW_LIMIT;
    }

    /** 由元数据库实时结构生成 databaseStructure（表 + 列）。 */
    public static String buildDatabaseStructure(DataSource dataSource) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet tables = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    sb.append("CREATE TABLE ").append(table).append(" (");
                    List<String> cols = new ArrayList<String>();
                    try (ResultSet columns = md.getColumns(null, null, table, "%")) {
                        while (columns.next()) {
                            cols.add(columns.getString("COLUMN_NAME") + " "
                                    + columns.getString("TYPE_NAME"));
                        }
                    }
                    sb.append(String.join(", ", cols)).append(");\n");
                }
            }
        }
        String structure = sb.toString();
        log.info("NL2SQL databaseStructure 表数生成完成，长度={}", structure.length());
        return structure;
    }
}
