package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import com.xiongx9527.askdata.training.TrainingRecallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A9：高级应用知识范围隔离测试。
 *
 * <p>验证高级应用上下文（assistant_id）下，术语/训练样例/自定义提示词只召回该应用的知识，
 * 其他应用或全局知识不会进入本次提示词；无高级应用上下文时保持数据源级召回。</p>
 */
class AdvancedAppKnowledgeScopeTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge_scope_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE data_training (id BIGINT PRIMARY KEY, oid BIGINT, datasource BIGINT, "
                + "create_time BIGINT, question VARCHAR(255), description VARCHAR(10000), "
                + "embedding VARCHAR(10000), enabled BOOLEAN, advanced_application BIGINT)");
        jdbc.execute("CREATE TABLE custom_prompt (id BIGINT PRIMARY KEY, oid BIGINT, type VARCHAR(20), "
                + "create_time BIGINT, name VARCHAR(255), prompt VARCHAR(10000), specific_ds BOOLEAN, "
                + "datasource_ids VARCHAR(10000), advanced_application BIGINT)");
    }

    @Test
    void trainingSamplesAreIsolatedByAdvancedApplication() {
        SchemaContextBuilder builder = builder();
        insertTraining(1, 1L, "订单总额", "SELECT SUM(amount) FROM orders", 7L);
        insertTraining(2, 1L, "用户数", "SELECT COUNT(*) FROM users", 8L);
        insertTraining(3, 1L, "全局样例", "SELECT 1", null);

        List<String> app7 = builder.trainingSamples(1L, "订单总额", 7L);
        assertTrue(app7.stream().anyMatch(s -> s.contains("订单总额")), app7.toString());
        assertFalse(app7.stream().anyMatch(s -> s.contains("用户数")), app7.toString());
        assertFalse(app7.stream().anyMatch(s -> s.contains("全局样例")), app7.toString());

        List<String> app8 = builder.trainingSamples(1L, "用户数", 8L);
        assertTrue(app8.stream().anyMatch(s -> s.contains("用户数")), app8.toString());
        assertFalse(app8.stream().anyMatch(s -> s.contains("订单总额")), app8.toString());
    }

    @Test
    void noAdvancedAppKeepsDatasourceScope() {
        SchemaContextBuilder builder = builder();
        insertTraining(1, 1L, "订单总额", "SELECT SUM(amount) FROM orders", 7L);
        insertTraining(2, 1L, "用户数", "SELECT COUNT(*) FROM users", 8L);

        List<String> all = builder.trainingSamples(1L, "订单总额", null);
        assertTrue(all.stream().anyMatch(s -> s.contains("订单总额")), all.toString());
    }

    @Test
    void preparePassesAdvancedAppToTerminologyAndFiltersCustomPrompts() {
        TerminologyRecallService recall = Mockito.mock(TerminologyRecallService.class);
        Mockito.when(recall.recallGroups(Mockito.anyString(), Mockito.anyLong(), Mockito.any(), Mockito.eq(7L)))
                .thenReturn(Collections.emptyList());
        SchemaContextBuilder schema = Mockito.mock(SchemaContextBuilder.class);
        Mockito.when(schema.trainingTableAvailable()).thenReturn(true);
        Mockito.when(schema.trainingSamples(Mockito.anyLong(), Mockito.anyString(), Mockito.eq(7L)))
                .thenReturn(Collections.emptyList());
        ChatModelRouter router = Mockito.mock(ChatModelRouter.class);
        Mockito.when(router.embeddingModel()).thenReturn(Optional.empty());

        ChatKnowledgeService service = new ChatKnowledgeService(recall, schema, jdbc, router);
        insertPrompt(1, 1L, "app7 prompt", 7L);
        insertPrompt(2, 1L, "app8 prompt", 8L);
        insertPrompt(3, 1L, "global prompt", null);

        ChatKnowledgeService.KnowledgeContext context = service.prepare("q", 1L, 10L, 7L);
        assertTrue(context.getCustomPrompts().contains("app7 prompt"), context.getCustomPrompts().toString());
        assertFalse(context.getCustomPrompts().contains("app8 prompt"), context.getCustomPrompts().toString());
        assertFalse(context.getCustomPrompts().contains("global prompt"), context.getCustomPrompts().toString());
        Mockito.verify(recall).recallGroups("q", 1L, 10L, 7L);
        Mockito.verify(schema).trainingSamples(10L, "q", 7L);
    }

    private SchemaContextBuilder builder() {
        TrainingRecallService trainingRecallService = Mockito.mock(TrainingRecallService.class);
        Mockito.when(trainingRecallService.recallByDatasource(Mockito.anyString(), Mockito.anyLong(), Mockito.any()))
                .thenReturn(Collections.emptyList());
        ChatModelRouter modelRouter = Mockito.mock(ChatModelRouter.class);
        Mockito.when(modelRouter.embeddingModel()).thenReturn(Optional.empty());
        return new SchemaContextBuilder(
                Mockito.mock(DatasourceRepository.class), Mockito.mock(TableRepository.class),
                Mockito.mock(FieldRepository.class), Mockito.mock(DatasourceService.class),
                Mockito.mock(ChatModelClient.class), modelRouter, jdbc, new ObjectMapper(),
                Mockito.mock(TableEmbeddingService.class), null, trainingRecallService);
    }

    private void insertTraining(long id, long oid, String question, String sql, Long advancedApp) {
        jdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "embedding, enabled, advanced_application) VALUES (?, ?, 1, 0, ?, ?, NULL, TRUE, ?)",
                id, oid, question, sql, advancedApp);
    }

    private void insertPrompt(long id, long oid, String prompt, Long advancedApp) {
        jdbc.update("INSERT INTO custom_prompt (id, oid, type, create_time, name, prompt, specific_ds, "
                        + "datasource_ids, advanced_application) VALUES (?, ?, 'GENERATE_SQL', 0, ?, ?, FALSE, '[]', ?)",
                id, oid, "p" + id, prompt, advancedApp);
    }
}

