package com.xiongx9527.askdata.datasource.embedding;

import com.xiongx9527.askdata.common.util.Rows;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A15 表级关键词打分证据（移植 SQLBot {@code calc_keyword_score}）：
 * 表名 1.0、表注释 0.9、表名子串 0.7、字段注释 0.5、字段名 0.3，按覆盖率加权。
 */
class TableKeywordScoreTest {

    private static List<Map<String, Object>> fields(Object... nameCommentPairs) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (int i = 0; i < nameCommentPairs.length; i += 2) {
            result.add(Rows.of("field_name", nameCommentPairs[i], "field_comment", nameCommentPairs[i + 1]));
        }
        return result;
    }

    @Test
    void tableNameExactMatchScoresOne() {
        assertEquals(1.0D, TableEmbeddingService.keywordScore("orders", "orders", "订单表",
                Collections.emptyList()), 0.0001D);
    }

    @Test
    void tableCommentExactMatchScoresZeroPointNine() {
        assertEquals(0.9D, TableEmbeddingService.keywordScore("订单表", "orders", "订单表",
                Collections.emptyList()), 0.0001D);
    }

    @Test
    void tableNameSubstringMatchScoresZeroPointSeven() {
        assertEquals(0.7D, TableEmbeddingService.keywordScore("用户", "用户表", "",
                Collections.emptyList()), 0.0001D);
    }

    @Test
    void fieldCommentExactMatchScoresZeroPointFive() {
        double score = TableEmbeddingService.keywordScore("折扣率", "orders", "订单表",
                fields("discount", "折扣率"));
        assertEquals(0.5D, score, 0.0001D);
    }

    @Test
    void fieldCommentSubstringMatchScoresZeroPointFour() {
        double score = TableEmbeddingService.keywordScore("折扣", "orders", "订单表",
                fields("discount", "折扣率"));
        assertEquals(0.4D, score, 0.0001D);
    }

    @Test
    void fieldNameMatchScoresZeroPointThree() {
        double score = TableEmbeddingService.keywordScore("amount", "orders", "订单表",
                fields("amount", "金额"));
        assertEquals(0.3D, score, 0.0001D);
    }

    @Test
    void coverageWeightingApplies() {
        // 两个关键词只命中一个 → best(0.5) × 覆盖率(1/2) = 0.25
        double score = TableEmbeddingService.keywordScore("折扣率,不存在的词", "orders", "订单表",
                fields("discount", "折扣率"));
        assertEquals(0.25D, score, 0.0001D);
    }

    @Test
    void tableNamePartCoverageWeighting() {
        // 表名 user_profile 切分为 user/profile；命中 user → 0.25 + 0.25 × (1/2) = 0.38（四舍五入 0.38）
        double score = TableEmbeddingService.keywordScore("user", "user_profile", "",
                Collections.emptyList());
        assertEquals(0.38D, score, 0.0001D);
    }

    @Test
    void noMatchScoresZero() {
        assertEquals(0D, TableEmbeddingService.keywordScore("完全不相关", "orders", "订单表",
                fields("amount", "金额")), 0.0001D);
        assertEquals(0D, TableEmbeddingService.keywordScore("", "orders", "订单表",
                Collections.emptyList()), 0.0001D);
        assertEquals(0D, TableEmbeddingService.keywordScore(null, "orders", "订单表",
                Collections.emptyList()), 0.0001D);
    }

    @Test
    void customCommentTakesPriorityOverTableComment() {
        // custom_comment 优先于 table_comment：批量入口负责合并，再交给 keywordScore
        TableEmbeddingService service = new TableEmbeddingService(null, null, null, null, null, null);
        List<Map<String, Object>> tables = Collections.singletonList(
                Rows.of("id", 1L, "table_name", "orders", "custom_comment", "自定义注释",
                        "table_comment", "原始注释"));
        double[] scores = service.keywordScores(tables, "自定义注释");
        assertEquals(0.9D, scores[0], 0.0001D);
        double[] legacy = service.keywordScores(Collections.singletonList(
                Rows.of("id", 2L, "table_name", "orders", "table_comment", "原始注释")), "原始注释");
        assertEquals(0.9D, legacy[0], 0.0001D);
    }

    @Test
    void keywordScoresBatchCoversAllTables() {
        TableEmbeddingService service = new TableEmbeddingService(null, null, null, null, null, null);
        List<Map<String, Object>> tables = Arrays.asList(
                Rows.of("id", 1L, "table_name", "orders", "table_comment", "订单表"),
                Rows.of("id", 2L, "table_name", "users", "table_comment", "用户表"));
        double[] scores = service.keywordScores(tables, "orders");
        assertEquals(2, scores.length);
        assertEquals(1.0D, scores[0], 0.0001D);
        assertTrue(scores[1] >= 0D, "users 表应得到非负分");
    }
}
