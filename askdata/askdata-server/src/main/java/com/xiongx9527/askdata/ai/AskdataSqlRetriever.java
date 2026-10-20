package com.xiongx9527.askdata.ai;

import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * askdata NL2SQL 检索器：覆写 langchain4j {@link SqlDatabaseContentRetriever} 扩展点——
 * clean() 记录最终 SQL（供上层把 SQL+结果一并返回）；validate() 追加只读/单语句/危险片段拦截。
 */
public class AskdataSqlRetriever extends SqlDatabaseContentRetriever {

    private static final String[] FORBIDDEN = {
            "INTO OUTFILE", "LOAD_FILE", "SLEEP(", "BENCHMARK(", "GET_LOCK(", "INTO DUMPFILE",
            "INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "TRUNCATE ", "CREATE ", "GRANT ", "REVOKE "
    };

    private volatile String lastSql;

    public AskdataSqlRetriever(DataSource dataSource, String sqlDialect, String databaseStructure,
                               PromptTemplate promptTemplate, ChatModel chatModel, Integer maxRetries) {
        super(dataSource, sqlDialect, databaseStructure, promptTemplate, chatModel, maxRetries);
    }

    @Override
    protected String clean(String sqlQuery) {
        String cleaned = super.clean(sqlQuery);
        this.lastSql = cleaned;
        return cleaned;
    }

    @Override
    protected void validate(String sqlQuery) {
        String t = sqlQuery == null ? "" : sqlQuery.trim();
        if (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.contains(";")) {
            throw new IllegalArgumentException("仅允许单条 SELECT 语句，禁止多语句拼接");
        }
        String upper = t.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new IllegalArgumentException("仅允许 SELECT 查询");
        }
        for (String fragment : FORBIDDEN) {
            if (upper.contains(fragment)) {
                throw new IllegalArgumentException("检测到危险语句片段: " + fragment.trim());
            }
        }
    }

    /** 最近一次成功生成的 SQL（clean 之后、执行之前的最终值）。 */
    public String lastSql() {
        return lastSql;
    }

    /**
     * 作为 Assistant 的 RAG 检索器时需稳健：模型未生成合法 SQL 或执行失败不应让整轮对话失败，
     * 降级为“无检索上下文”，交由对话模型直接回答。
     */
    @Override
    public List<Content> retrieve(Query query) {
        try {
            return super.retrieve(query);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
