package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.chat.service.ChatKnowledgeService.KnowledgeContext;
import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验收 A3：术语库/SQL 示例在 embedding 模型缺失或 data_training 表缺失时，
 * 降级必须显式登记（KnowledgeContext 标志 + 原因清单），而非静默失效。
 */
class ChatKnowledgeServiceDegradationTest {

    private ChatKnowledgeService serviceWith(boolean embeddingPresent, boolean trainingTablePresent) {
        TerminologyRecallService recall = mock(TerminologyRecallService.class);
        when(recall.recallGroups(Mockito.anyString(), Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(Collections.emptyList());

        SchemaContextBuilder schemaContextBuilder = mock(SchemaContextBuilder.class);
        when(schemaContextBuilder.trainingTableAvailable()).thenReturn(trainingTablePresent);
        when(schemaContextBuilder.trainingSamples(Mockito.anyLong(), Mockito.anyString()))
                .thenReturn(Collections.emptyList());

        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(Mockito.anyString(), Mockito.any(Class.class), Mockito.anyLong()))
                .thenReturn(Collections.emptyList());
        when(jdbc.queryForList(Mockito.anyString(), Mockito.any(Class.class), Mockito.anyLong(), Mockito.anyString()))
                .thenReturn(Collections.emptyList());

        ChatModelRouter router = mock(ChatModelRouter.class);
        when(router.embeddingModel()).thenReturn(
                embeddingPresent ? Optional.of(mock(ChatModelRouter.ModelRef.class)) : Optional.empty());

        return new ChatKnowledgeService(recall, schemaContextBuilder, jdbc, router, Mockito.mock(ChatModelClient.class));
    }

    @Test
    void degradedWhenEmbeddingAbsentAndTrainingMissing() {
        ChatKnowledgeService svc = serviceWith(false, false);
        KnowledgeContext ctx = svc.prepare("查询销售额", 1L, 100L);
        assertTrue(ctx.isTerminologyDegraded(), "embedding 缺失应标记术语降级");
        assertTrue(ctx.isSqlExampleDegraded(), "data_training 缺失应标记示例降级");
        assertEquals(2, ctx.degradationReasons().size());
    }

    @Test
    void notDegradedWhenBothAvailable() {
        ChatKnowledgeService svc = serviceWith(true, true);
        KnowledgeContext ctx = svc.prepare("查询销售额", 1L, 100L);
        assertFalse(ctx.isTerminologyDegraded());
        assertFalse(ctx.isSqlExampleDegraded());
        assertTrue(ctx.degradationReasons().isEmpty());
    }
}
