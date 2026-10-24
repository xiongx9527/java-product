package com.xiongx9527.askdata.chat.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatQuestionServiceTest {

    @Test
    void extractsChineseTableName() {
        List<String> tables = ChatQuestionService.usedTables(
                "SELECT * FROM EXCEL_销售数据_319C13A236 WHERE id = 1");
        assertEquals(Arrays.asList("excel_销售数据_319c13a236"), tables);
    }

    @Test
    void asciiTableNameUnchanged() {
        List<String> tables = ChatQuestionService.usedTables(
                "SELECT * FROM excel_sales_23c13ee62a JOIN other ON 1=1");
        // §2.7: AST 提取不保证顺序（LinkedHashSet 按遍历序），此处只比对集合
        assertEquals(new HashSet<>(Arrays.asList("excel_sales_23c13ee62a", "other")),
                new HashSet<>(tables));
    }

    @Test
    void dropsPunctuationAroundName() {
        List<String> tables = ChatQuestionService.usedTables(
                "FROM (weird_name);");
        assertEquals(Arrays.asList("weird_name"), tables);
    }

    // ------------------------------------------------------------------
    // §2.7（本次新增）：AST 解析路径语义对齐 SQLBot extract_tables_from_sql
    // ------------------------------------------------------------------

    @Test
    void cteAliasNotTreatedAsTable() {
        // 旧分词实现会把 cte 当成「未授权表」误报；AST 实现只留真实基表
        List<String> tables = ChatQuestionService.usedTables(
                "WITH cte AS (SELECT id FROM orders) SELECT * FROM cte");
        assertEquals(Arrays.asList("orders"), tables);
    }

    @Test
    void validateTablesAllowsCteOverAllowedBase() {
        List<String> allowed = Arrays.asList("orders");
        assertNull(ChatQuestionService.validateTables(
                "WITH cte AS (SELECT id FROM orders) SELECT * FROM cte", allowed));
        // 子查询里的表也必须被抓出来
        assertTrue(ChatQuestionService.validateTables(
                "SELECT * FROM (SELECT id FROM secrets) t", allowed) != null);
    }

    @Test
    void quotedIdentifierUnquoted() {
        assertEquals("user", ChatQuestionService.unquoteIdentifier("\"user\""));
        assertEquals("user", ChatQuestionService.unquoteIdentifier("`user`"));
        assertEquals("user", ChatQuestionService.unquoteIdentifier("[user]"));
        assertEquals("orders", ChatQuestionService.unquoteIdentifier("myschema.orders"));
        List<String> tables = ChatQuestionService.usedTables("SELECT * FROM \"orders\"");
        assertEquals(Arrays.asList("orders"), tables);
    }

    @Test
    void subQueryInnerTableCollected() {
        List<String> tables = ChatQuestionService.usedTables(
                "SELECT * FROM (SELECT id FROM secrets UNION ALL SELECT id FROM logs) t");
        assertEquals(new HashSet<>(Arrays.asList("secrets", "logs")), new HashSet<>(tables));
    }
}
