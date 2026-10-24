package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.embedded.DynamicDatasource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §2.4 / A3：动态数据源表级 SQL 占位符机制（对齐 SQLBot dynamic_subsql_prefix 形态）：
 * 占位符注入 → 确定性替换 → 直接物理表引用/残留占位符拦截 → 模板内部表扩展白名单。
 */
class DynamicSubSqlRewriteTest {

    private static final String PH = DynamicDatasource.DYNAMIC_SUB_SQL_PREFIX;

    private static Map<String, String> templates() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("orders", "SELECT id, amount FROM orders WHERE status = 1");
        map.put("users", "SELECT id, name FROM users WHERE deleted = 0");
        return map;
    }

    @Test
    void placeholderNameFollowsPrefixConvention() {
        assertEquals(PH + "orders", DynamicDatasource.placeholderTable("orders"));
    }

    @Test
    void replacesPlaceholderWithHostSubQuery() {
        String sql = "SELECT o.id FROM " + PH + "orders o JOIN " + PH + "users u ON o.uid = u.id";
        String replaced = DynamicDatasource.replaceSubSqlPlaceholders(sql, templates());
        assertTrue(replaced.contains("(SELECT id, amount FROM orders WHERE status = 1)"), replaced);
        assertTrue(replaced.contains("(SELECT id, name FROM users WHERE deleted = 0)"), replaced);
        // 大小写不敏感匹配
        String mixed = "SELECT * FROM " + PH.toUpperCase(Locale.ROOT).replace("ASKDATA", "AskData") + "Orders x";
        String replaced2 = DynamicDatasource.replaceSubSqlPlaceholders(mixed, templates());
        assertTrue(replaced2.contains("(SELECT id, amount FROM orders WHERE status = 1)"), replaced2);
        // 无模板时原样返回
        assertEquals(sql, DynamicDatasource.replaceSubSqlPlaceholders(sql, null));
        assertEquals(null, DynamicDatasource.replaceSubSqlPlaceholders(null, templates()));
    }

    @Test
    void directPhysicalReferenceIsRejected() {
        String sql = "SELECT id FROM orders WHERE status = 1";
        String err = DynamicDatasource.directTableReferenceError(sql, templates(),
                ChatQuestionService::usedTables);
        assertNotNull(err, "直接引用受限表必须报错");
        assertTrue(err.contains("orders"), err);
        // 引用占位符（而非物理表）则通过
        String viaPlaceholder = "SELECT id FROM " + PH + "orders";
        assertNull(DynamicDatasource.directTableReferenceError(viaPlaceholder, templates(),
                ChatQuestionService::usedTables));
    }

    @Test
    void leftoverUnknownPlaceholderIsRejected() {
        String sql = "SELECT id FROM " + PH + "secret_table";
        String err = DynamicDatasource.leftoverPlaceholderError(sql, templates(),
                ChatQuestionService::usedTables);
        assertNotNull(err, "未知占位符表必须报错");
        assertTrue(err.contains(PH + "secret_table"), err);
        // 已知占位符在宿主模板下不报错（替换后自然消失）
        String known = "SELECT id FROM " + PH + "orders";
        assertNull(DynamicDatasource.leftoverPlaceholderError(known, templates(),
                ChatQuestionService::usedTables));
        assertNull(DynamicDatasource.leftoverPlaceholderError("SELECT 1", templates(),
                ChatQuestionService::usedTables));
    }

    @Test
    void templateInnerTablesExtendAllowedSet() {
        List<String> inner = DynamicDatasource.templateInnerTables(templates(),
                ChatQuestionService::usedTables);
        assertTrue(inner.contains("orders"), String.valueOf(inner));
        assertTrue(inner.contains("users"), String.valueOf(inner));
    }
}
