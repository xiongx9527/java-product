package com.xiongx9527.askdata.chat.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaContextBuilderRecommendTest {

    @Test
    void basePromptHasNoChartOrHistory() {
        String p = SchemaContextBuilder.buildRecommendUserPrompt("销售额是多少", 3, null, null);
        assertTrue(p.contains("销售额是多少"));
        assertTrue(p.contains("3 个"));
        assertFalse(p.contains("【当前图表"));
        assertFalse(p.contains("【历史提问】"));
    }

    @Test
    void injectsChartHintAndHistory() {
        List<String> history = Arrays.asList("上月销售额", "按地区拆分");
        String p = SchemaContextBuilder.buildRecommendUserPrompt("销售额是多少", 3,
                "{\"type\":\"bar\"}", history);
        assertTrue(p.contains("【当前图表/可视化类型】"));
        assertTrue(p.contains("bar"));
        assertTrue(p.contains("【历史提问】"));
        assertTrue(p.contains("- 上月销售额"));
        assertTrue(p.contains("- 按地区拆分"));
    }
}
