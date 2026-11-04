package com.xiongx9527.askdata.chat.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    // ------------------------------------------------------------------
    // A3：静态数据源白名单只放行「本轮 schema 实际召回」的表（retrieved-only）
    // ------------------------------------------------------------------

    @Test
    void validateSqlRejectsCheckedButNotRecalledTable() {
        SchemaContextBuilder schemaContextBuilder = mock(SchemaContextBuilder.class);
        // orders 与 ghost 都是勾选表，但本轮仅 orders 进入 schema 提示词
        when(schemaContextBuilder.selectedTableNames(99L, "订单状态", "订单状态"))
                .thenReturn(Arrays.asList("orders"));
        when(schemaContextBuilder.checkedTableColumns(99L))
                .thenReturn(Collections.<String, java.util.Map<String, String>>emptyMap());
        ChatQuestionService service = new ChatQuestionService(null, null, null, null, null,
                schemaContextBuilder, null, null, null, null, null, null, null, null, null, null, null, null);

        String error = service.validateSql("SELECT * FROM ghost", "mysql", 99L, null, "订单状态", "订单状态");
        assertNotNull(error, "勾选但未进入本轮 schema 的表必须被拒绝");
        assertTrue(error.contains("ghost"), error);
        assertNull(service.validateSql("SELECT id FROM orders", "mysql", 99L, null, "订单状态", "订单状态"),
                "进入本轮 schema 的表必须放行");
    }
}
