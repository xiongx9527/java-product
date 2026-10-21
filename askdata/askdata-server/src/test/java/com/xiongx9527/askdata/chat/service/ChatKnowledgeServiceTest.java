package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.ai.EmbeddingService;
import com.xiongx9527.askdata.ai.LocalEmbeddingModel;
import com.xiongx9527.askdata.chat.service.ChatKnowledgeService.KnowledgeContext;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import com.xiongx9527.askdata.terminology.TerminologyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 问数知识增强链路（离线证据）：
 * <ul>
 *   <li>问题命中术语库 → 提取关键词、同义词扩展、术语模板（释义 + 同义词）进入上下文；</li>
 *   <li>SQL 示例（data_training）与自定义提示词（custom_prompt）按作用域召回；</li>
 *   <li><b>未配置 embedding 模型</b>（本地模型目录为空、无 model_type=1 模型）时
 *       术语召回退化为词法匹配且不报错；</li>
 *   <li>未命中术语时上下文为空，不抛异常。</li>
 * </ul>
 */
class ChatKnowledgeServiceTest {

    @TempDir
    Path modelDir;

    private JdbcTemplate appJdbc;

    private ObjectMapper objectMapper = new ObjectMapper();

    private IdGenerator ids = new IdGenerator();

    private TerminologyRepository terminologyRepository;

    private ChatKnowledgeService chatKnowledgeService;

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat_knowledge_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        terminologyRepository = new TerminologyRepository(appJdbc, objectMapper, ids);
        DatasourceRepository datasourceRepository = new DatasourceRepository(appJdbc, ids);
        TableRepository tableRepository = new TableRepository(appJdbc, ids);
        FieldRepository fieldRepository = new FieldRepository(appJdbc, ids);
        ChatModelClient modelClient = new ChatModelClient(objectMapper);
        ChatModelRouter modelRouter = new ChatModelRouter(new AiModelRepository(appJdbc, ids), objectMapper);
        SchemaContextBuilder schemaContextBuilder = new SchemaContextBuilder(datasourceRepository, tableRepository,
                fieldRepository, Mockito.mock(DatasourceService.class), modelClient, modelRouter, appJdbc,
                objectMapper, new TableEmbeddingService(tableRepository, fieldRepository, modelRouter, modelClient,
                        appJdbc, objectMapper));
        // 本地 embedding 模型目录为空 → 不可用；ai_model 无 model_type=1 → 无远程 embedding：
        // 整条测试运行在「无向量」环境，验证词法降级路径。
        EmbeddingService embeddingService = new EmbeddingService(
                new LocalEmbeddingModel(modelDir.toString(), 128), modelRouter, modelClient, appJdbc, objectMapper);
        assertFalse(embeddingService.available(), "测试环境不应有可用向量模型");
        chatKnowledgeService = new ChatKnowledgeService(
                new TerminologyRecallService(embeddingService, appJdbc), schemaContextBuilder, appJdbc);

        // 术语库：父术语「销售额」（释义）+ 同义词子术语「GMV」
        long termId = terminologyRepository.insertParent(1L, "销售额", "指成交总金额（含税）", false, null, true, null,
                System.currentTimeMillis());
        terminologyRepository.insertChild(1L, termId, "GMV", false, null, true, null, System.currentTimeMillis());
        // 数据源专属术语（specific_ds=TRUE，仅 ds=99 命中）
        long scopedTermId = terminologyRepository.insertParent(1L, "客单价", "平均每单金额", true, "[99]", true, null,
                System.currentTimeMillis());
        terminologyRepository.insertChild(1L, scopedTermId, "AOV", true, "[99]", true, null,
                System.currentTimeMillis());
        // SQL 示例（data_training）
        appJdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "enabled) VALUES (?, 1, 1, 1, ?, ?, TRUE)",
                5001L, "每个状态的销售额", "SELECT status, SUM(amount) FROM orders GROUP BY status");
        // 自定义提示词（GENERATE_SQL）：全局 + 数据源专属（ds=99）
        appJdbc.update("INSERT INTO custom_prompt (id, oid, type, create_time, name, prompt, specific_ds, "
                        + "datasource_ids) VALUES (?, 1, 'GENERATE_SQL', 1, 'global', ?, FALSE, NULL)",
                5002L, "金额类问题默认以元为单位");
        appJdbc.update("INSERT INTO custom_prompt (id, oid, type, create_time, name, prompt, specific_ds, "
                        + "datasource_ids) VALUES (?, 1, 'GENERATE_SQL', 2, 'scoped', ?, TRUE, '[99]')",
                5003L, "客单价按去重订单计算");
    }

    // ------------------------------------------------------------------
    // 术语命中：关键词 / 同义词扩展 / 术语模板
    // ------------------------------------------------------------------

    @Test
    void terminologyHitExtractsKeywordsExpandsSynonymsAndBuildsTemplate() {
        KnowledgeContext context = chatKnowledgeService.prepare("每个状态的销售额是多少", 1L, 1L);

        assertTrue(context.hasTerms());
        // 提取到的关键词：问题中真实出现的业务词
        assertEquals("销售额", context.getExtractedKeywords());
        // 同义词扩展：提取词 + 同义词（上限 10）
        assertEquals("销售额,GMV", context.getExpandedKeywords());
        // 术语模板含主词、同义词与释义（SQLBot to_xml_string 形态）
        String terminology = context.getTerminologyText();
        assertTrue(terminology.startsWith("<terminologies>"), terminology);
        assertTrue(terminology.contains("<word>销售额</word>"), terminology);
        assertTrue(terminology.contains("<word>GMV</word>"), terminology);
        assertTrue(terminology.contains("<description>指成交总金额（含税）</description>"), terminology);
        // SQL 示例与自定义提示词按作用域召回
        assertEquals(1, context.getSqlExamples().size());
        assertTrue(context.getSqlExamples().get(0).contains("SELECT status, SUM(amount) FROM orders GROUP BY status"));
        assertEquals(Arrays.asList("金额类问题默认以元为单位"), context.getCustomPrompts());
    }

    @Test
    void scopedTerminologyAndPromptFollowDatasourceScope() {
        // ds=99：专属术语与专属提示词都命中
        KnowledgeContext scoped = chatKnowledgeService.prepare("各门店客单价排名", 1L, 99L);
        assertTrue(scoped.hasTerms());
        assertTrue(scoped.getExtractedKeywords().contains("客单价"));
        assertTrue(scoped.getExpandedKeywords().contains("AOV"));
        assertTrue(scoped.getCustomPrompts().contains("客单价按去重订单计算"));
        // ds=1：专属术语（客单价）不命中，专属提示词不召回
        KnowledgeContext other = chatKnowledgeService.prepare("各门店客单价排名", 1L, 1L);
        assertFalse(other.getExtractedKeywords().contains("客单价"));
        assertFalse(other.getCustomPrompts().contains("客单价按去重订单计算"));
    }

    @Test
    void noTermHitDegradesToEmptyContextWithoutError() {
        KnowledgeContext context = chatKnowledgeService.prepare("今天天气怎么样", 1L, 1L);
        assertFalse(context.hasTerms());
        assertEquals("", context.getExtractedKeywords());
        assertEquals("", context.getExpandedKeywords());
        assertEquals("", context.getTerminologyText());
        assertTrue(context.getTermGroups().isEmpty());
        // 无术语不影响其他过滤步骤（问题与训练样例无关时词法打分为 0，示例为空；自定义提示词与问题无关，仍召回）
        assertTrue(context.getSqlExamples().isEmpty());
        assertEquals(1, context.getCustomPrompts().size());
    }

    @Test
    void blankQuestionAndMissingTablesDegradeGracefully() {
        assertFalse(chatKnowledgeService.prepare("   ", 1L, 1L).hasTerms());
        assertFalse(chatKnowledgeService.prepare(null, 1L, 1L).hasTerms());
        // custom_prompt / data_training 表缺失时同样不抛错（各自降级为空）
        appJdbc.execute("DROP TABLE IF EXISTS custom_prompt");
        appJdbc.execute("DROP TABLE IF EXISTS data_training");
        KnowledgeContext context = chatKnowledgeService.prepare("每个状态的销售额是多少", 1L, 1L);
        assertTrue(context.hasTerms());
        assertTrue(context.getSqlExamples().isEmpty());
        assertTrue(context.getCustomPrompts().isEmpty());
    }

    // ------------------------------------------------------------------
    // 纯函数：关键词提取 / 同义词扩展 / 模板与转义
    // ------------------------------------------------------------------

    @Test
    void matchedWordsOnlyKeepWordsPresentInQuestion() {
        List<Map<String, Object>> groups = Arrays.asList(
                Rows.of("words", Arrays.asList("销售额", "GMV"), "description", "成交总金额"),
                Rows.of("words", Arrays.asList("订单"), "description", "交易单据"));
        assertEquals(Arrays.asList("订单"),
                ChatKnowledgeService.matchedWords(groups, "各状态订单数"));
        assertTrue(ChatKnowledgeService.matchedWords(groups, "完全无关的问题").isEmpty());
        assertTrue(ChatKnowledgeService.matchedWords(groups, null).isEmpty());
    }

    @Test
    void expandKeywordsCapsAtTenAndKeepsSeedFirst() {
        List<Map<String, Object>> groups = Arrays.asList(
                Rows.of("words", Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"),
                        "description", ""));
        List<String> expanded = ChatKnowledgeService.expandKeywords(Arrays.asList("a"), groups);
        assertEquals(ChatKnowledgeService.MAX_EXPANDED_KEYWORDS, expanded.size());
        assertEquals("a", expanded.get(0));
    }

    @Test
    void terminologyTemplateEscapesXmlSpecialCharacters() {
        String template = ChatKnowledgeService.terminologyTemplate(Arrays.asList(
                Rows.of("words", Arrays.asList("a&b"), "description", "<script>")));
        assertTrue(template.contains("<word>a&amp;b</word>"), template);
        assertTrue(template.contains("<description>&lt;script&gt;</description>"), template);
        assertEquals("", ChatKnowledgeService.terminologyTemplate(null));
        assertEquals("", ChatKnowledgeService.terminologyTemplate(Arrays.asList()));
    }

    @Test
    void preparedContextCarriesTermGroupsForLogging() {
        KnowledgeContext context = chatKnowledgeService.prepare("每个状态的销售额是多少", 1L, 1L);
        assertNotNull(context.getTermGroups());
        assertEquals(1, context.getTermGroups().size());
        assertTrue(context.getTermGroups().get(0).get("words") instanceof List);
    }
}
