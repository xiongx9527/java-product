package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.fw.plus.ai.autoconfigure.langchain4j.support.assistant.AssistantBuilder;
import com.xiongx9527.fw.plus.ai.autoconfigure.langchain4j.support.assistant.SimpleAssistant;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 问数助手：用框架 {@link AssistantBuilder} 装配 langchain4j {@link SimpleAssistant}。
 *
 * <p>配齐：对话模型、流式模型（可选）、系统提示、多轮记忆、NL2SQL 检索增强。
 * 记忆存储复用框架自动装配的 {@link ChatMemoryStore}（属性前缀
 * {@code fw.plus.ai.langchain4j.chat-memory}：type=in-memory 缺省 / mem0 持久化 REST），
 * 不再自定义 bean（避免顶掉框架的 mem0 选项）。由 {@code askdata.assistant.enabled=true} 门控。</p>
 */
@Configuration
@ConditionalOnProperty(name = "askdata.assistant.enabled", havingValue = "true")
public class AskdataAssistantConfig {

    static final String SYSTEM_PROMPT =
            "你是 askdata 问数助手，基于结构化数据回答问题。回答需准确、简洁；涉及数据时说明口径；"
                    + "不确定时直说，不要编造数值。";

    private static final int MAX_RETRIES = 3;

    /**
     * NL2SQL 检索器：schema 在每次检索时按问题 top-N 选表（A2），不在启动期做全库 schema dump；
     * 作为 Assistant 的 RAG content retriever。
     */
    @Bean
    public AskdataSqlRetriever askdataSqlRetriever(DataSource dataSource, ChatModel chatModel,
                                                   SchemaContextBuilder schemaContextBuilder) {
        PromptTemplate promptTemplate = PromptTemplate.from(AskdataNl2Sql.SQL_PROMPT);
        // 验收 A2：方言由应用库连接动态探测，不再硬编码 MySQL
        String dialect = AskdataNl2Sql.detectDialect(dataSource).getTemplateName();
        return new AskdataSqlRetriever(dataSource, dialect, "", promptTemplate, chatModel, MAX_RETRIES,
                query -> schemaContextBuilder.buildSchemaTextForConnection(dataSource, query.text()));
    }

    @Bean
    public SimpleAssistant askdataAssistant(ChatModel chatModel,
                                            ObjectProvider<StreamingChatModel> streamingChatModel,
                                            AskdataSqlRetriever sqlRetriever,
                                            ObjectProvider<ChatMemoryStore> memoryStore) {
        AssistantBuilder builder = new AssistantBuilder()
                .chatModel(chatModel)
                .systemMessage(SYSTEM_PROMPT)
                .memory(20)
                .contentRetriever(sqlRetriever);
        streamingChatModel.ifAvailable(builder::streamingModel);
        memoryStore.ifAvailable(builder::memoryStore);
        return builder.build();
    }
}
