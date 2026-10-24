package com.xiongx9527.askdata.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.xiongx9527.askdata.common.i18n.I18n;

/**
 * askdata NL2SQL 检索器：覆写 langchain4j {@link SqlDatabaseContentRetriever} 扩展点——
 * clean() 记录最终 SQL（供上层把 SQL+结果一并返回）；validate() 追加只读/单语句/危险片段拦截；
 * generateSqlQuery() 支持按查询实时提供 schema（A2：top-N 选表，不再启动期全库 dump）。
 */
public class AskdataSqlRetriever extends SqlDatabaseContentRetriever {

    private static final String[] FORBIDDEN = {
            "INTO OUTFILE", "LOAD_FILE", "SLEEP(", "BENCHMARK(", "GET_LOCK(", "INTO DUMPFILE",
            "INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "TRUNCATE ", "CREATE ", "GRANT ", "REVOKE "
    };

    /** 按查询实时提供 schema 文本；返回 null 时回落到构造时传入的 databaseStructure。 */
    @FunctionalInterface
    public interface SchemaProvider {
        String structure(Query query);
    }

    private final ChatModel chatModel;

    private final PromptTemplate promptTemplate;

    private final String sqlDialect;

    private final SchemaProvider schemaProvider;

    private volatile String lastSql;

    public AskdataSqlRetriever(DataSource dataSource, String sqlDialect, String databaseStructure,
                               PromptTemplate promptTemplate, ChatModel chatModel, Integer maxRetries) {
        this(dataSource, sqlDialect, databaseStructure, promptTemplate, chatModel, maxRetries, null);
    }

    public AskdataSqlRetriever(DataSource dataSource, String sqlDialect, String databaseStructure,
                               PromptTemplate promptTemplate, ChatModel chatModel, Integer maxRetries,
                               SchemaProvider schemaProvider) {
        super(dataSource, sqlDialect, databaseStructure, promptTemplate, chatModel, maxRetries);
        this.sqlDialect = sqlDialect;
        this.promptTemplate = promptTemplate;
        this.chatModel = chatModel;
        this.schemaProvider = schemaProvider;
    }

    /**
     * 生成 SQL：schema 由 {@link SchemaProvider} 按本次查询实时给出（top-N 选表），
     * 保证提示词里只有与问题相关的表；未配置 provider 时沿用父类实现。
     */
    @Override
    protected String generateSqlQuery(Query query, String previousSql, String error) {
        String structure = schemaProvider == null ? null : schemaProvider.structure(query);
        if (structure == null) {
            return super.generateSqlQuery(query, previousSql, error);
        }
        Map<String, Object> variables = new HashMap<String, Object>();
        variables.put("sqlDialect", sqlDialect);
        variables.put("databaseStructure", structure);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(promptTemplate.apply(variables).toSystemMessage());
        messages.add(UserMessage.from(query.text()));
        if (previousSql != null && error != null) {
            messages.add(AiMessage.from(previousSql));
            messages.add(UserMessage.from(error));
        }
        return chatModel.chat(messages).aiMessage().text();
    }

    @Override
    protected String clean(String sqlQuery) {
        String cleaned = super.clean(sqlQuery);
        this.lastSql = cleaned;
        return cleaned;
    }

    /** 共享的只读/单语句/危险片段校验（验收 A2）；生成 SQL 后、执行前调用。 */
    public static void checkSql(String sqlQuery) {
        String t = sqlQuery == null ? "" : sqlQuery.trim();
        if (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.contains(";")) {
            throw new IllegalArgumentException(I18n.msg("sql.retriever.only.single.select"));
        }
        String upper = t.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new IllegalArgumentException(I18n.msg("sql.retriever.only.select"));
        }
        for (String fragment : FORBIDDEN) {
            if (upper.contains(fragment)) {
                throw new IllegalArgumentException(I18n.msg("sql.retriever.dangerous.fragment", fragment.trim()));
            }
        }
    }

    @Override
    protected void validate(String sqlQuery) {
        checkSql(sqlQuery);
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

