package com.xiongx9527.askdata.training;

import com.xiongx9527.askdata.common.util.Lists;

import com.xiongx9527.askdata.ai.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A14：SQL 示例双路召回——关键词命中（ILIKE 双向）+ 向量召回，两路合并去重。
 */
class TrainingDualRecallTest {

    private JdbcTemplate jdbc;

    private TrainingRecallService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:training_dual_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE data_training (id BIGINT PRIMARY KEY, oid BIGINT, datasource BIGINT, "
                + "create_time BIGINT, question VARCHAR(255), description VARCHAR(10000), "
                + "embedding VARCHAR(10000), enabled BOOLEAN, advanced_application BIGINT)");
        // 向量不可用：只验证关键词这一路（向量路由由既有用例覆盖）
        EmbeddingService embedding = Mockito.mock(EmbeddingService.class);
        Mockito.when(embedding.candidateRequiresEmbeddingColumn()).thenReturn(false);
        Mockito.when(embedding.recallByVector(Mockito.anyString(), Mockito.anyList(), Mockito.anyString(),
                Mockito.anyDouble(), Mockito.anyInt())).thenReturn(Lists.of());
        service = new TrainingRecallService(embedding, jdbc);
    }

    private void insert(long id, String question, String sql, boolean enabled, Long advancedApp) {
        jdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "embedding, enabled, advanced_application) VALUES (?, 1, 5, 0, ?, ?, NULL, ?, ?)",
                id, question, sql, enabled, advancedApp);
    }

    @Test
    void keywordHitWhenStoredQuestionContainsSentence() {
        insert(1L, "每个地区的销售额是多少", "SELECT area, SUM(amt) FROM t GROUP BY area", true, null);
        insert(2L, "用户注册数", "SELECT COUNT(*) FROM users", true, null);

        List<Map<String, Object>> hits = service.recallByDatasource("销售额", 5L, null);
        assertEquals(1, hits.size());
        assertTrue(String.valueOf(hits.get(0).get("question")).contains("销售额"));
    }

    @Test
    void keywordHitWhenSentenceContainsStoredQuestion() {
        insert(1L, "销售额", "SELECT SUM(amt) FROM t", true, null);
        insert(2L, "用户注册数", "SELECT COUNT(*) FROM users", true, null);

        // 反方向：用户问题包含存储问题（SQLBot 的 :sentence ILIKE '%' || question || '%'）
        List<Map<String, Object>> hits = service.recallByDatasource("上个月的销售额是多少", 5L, null);
        assertEquals(1, hits.size());
        assertEquals("销售额", hits.get(0).get("question"));
    }

    @Test
    void disabledAndOtherDatasourceExcluded() {
        insert(1L, "销售额", "SELECT 1", false, null);
        jdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "embedding, enabled, advanced_application) VALUES (2, 1, 6, 0, '销售额', 'SELECT 2', NULL, TRUE, NULL)");

        assertTrue(service.recallByDatasource("销售额", 5L, null).isEmpty());
    }

    @Test
    void advancedAppScopeLimitsKeywordRecall() {
        insert(1L, "销售额", "SELECT 1", true, 7L);
        insert(2L, "销售额", "SELECT 2", true, 8L);

        List<Map<String, Object>> hits = service.recallByDatasource("销售额", 5L, 7L);
        assertEquals(1, hits.size());
        assertEquals(1L, ((Number) hits.get(0).get("id")).longValue());
    }

    @Test
    void emptyQuestionReturnsEmpty() {
        insert(1L, "销售额", "SELECT 1", true, null);
        assertTrue(service.recallByDatasource("   ", 5L, null).isEmpty());
        assertFalse(service.recallByDatasource("销售额", 5L, null).isEmpty());
    }
}
