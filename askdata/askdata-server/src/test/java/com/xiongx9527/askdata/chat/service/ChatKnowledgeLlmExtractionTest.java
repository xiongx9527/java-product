package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2/2.1 验证：LLM 业务实体抽取（带历史上下文）与词法命中合并、降级回退与输出归一化。
 */
class ChatKnowledgeLlmExtractionTest {

    private static ChatKnowledgeService serviceWithLlm(String llmOutput) {
        return build(false, llmOutput);
    }

    private static ChatKnowledgeService build(boolean noChatModel, String llmOutput) {
        TerminologyRecallService recall = Mockito.mock(TerminologyRecallService.class);
        Map<String, Object> group = new HashMap<>();
        group.put("words", Arrays.asList("销售额", "GMV"));
        Mockito.when(recall.recallGroups(Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any()))
                .thenReturn(Collections.singletonList(group));

        SchemaContextBuilder schema = Mockito.mock(SchemaContextBuilder.class);
        Mockito.when(schema.trainingTableAvailable()).thenReturn(false);

        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForList(Mockito.anyString(), Mockito.eq(String.class), Mockito.any()))
                .thenReturn(Collections.emptyList());

        ChatModelRouter router = Mockito.mock(ChatModelRouter.class);
        Mockito.when(router.embeddingModel()).thenReturn(Optional.empty());
        if (noChatModel) {
            Mockito.when(router.defaultChatModel()).thenReturn(Optional.empty());
        } else {
            ChatModelRouter.ModelRef model =
                    new ChatModelRouter.ModelRef(1L, "chat", "gpt-4", "https://api", "key", 0.2);
            Mockito.when(router.defaultChatModel()).thenReturn(Optional.of(model));
        }

        ChatModelClient client = Mockito.mock(ChatModelClient.class);
        Mockito.when(client.generate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(llmOutput);

        return new ChatKnowledgeService(recall, schema, jdbc, router, client);
    }

    @Test
    void llmEntitiesMergedWhenLexicalMisses() {
        ChatKnowledgeService svc = serviceWithLlm("营收, 订单");
        ChatKnowledgeService.KnowledgeContext ctx = svc.prepare("本月营收多少", 1L, 1L);
        // 问题中无术语词，词法命中为空；LLM 抽到的实体必须被纳入，否则同义表述无法命中术语
        assertTrue(ctx.getExtractedKeywords().contains("营收"), ctx.getExtractedKeywords());
        assertTrue(ctx.getExtractedKeywords().contains("订单"), ctx.getExtractedKeywords());
        assertTrue(ctx.isKeywordLlmUsed());
        assertFalse(ctx.getExpandedKeywords().isEmpty());
    }

    @Test
    void noChatModelFallsBackToLexical() {
        ChatKnowledgeService svc = build(true, null);
        ChatKnowledgeService.KnowledgeContext ctx = svc.prepare("每个状态的销售额", 1L, 1L);
        assertEquals("销售额", ctx.getExtractedKeywords());
        assertFalse(ctx.isKeywordLlmUsed());
    }

    @Test
    void messyLlmOutputNormalized() {
        ChatKnowledgeService svc = serviceWithLlm("```\n销售额, 营收\n```");
        ChatKnowledgeService.KnowledgeContext ctx = svc.prepare("本月情况", 1L, 1L);
        assertTrue(ctx.getExtractedKeywords().contains("销售额"), ctx.getExtractedKeywords());
        assertTrue(ctx.getExtractedKeywords().contains("营收"), ctx.getExtractedKeywords());
    }
}
