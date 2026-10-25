package com.xiongx9527.askdata.embedded;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A18：宿主下发的表级行规则——生成过滤 SQL 并注入；行权限 like 条件由
 * {@code DsPermissionQueryServiceTest} 覆盖（{@code '%value%'}）。
 */
class DynamicDatasourceRowRuleTest {

    /** 与 ChatQuestionService::usedTables 同形态的简易表名提取（按空格/标点切分后过滤关键字）。 */
    private static List<String> tablesOf(String sql) {
        return Arrays.asList(sql.replaceAll("[(),;]", " ").split("\\s+"));
    }

    private static Map<String, String> rules(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void injectsFilterWhenReferencedTableHasRule() {
        String sql = "SELECT id, amount FROM orders WHERE status = 1";
        String out = DynamicDatasource.applyRowRules(sql, rules("orders", "dept_id = 3"),
                DynamicDatasourceRowRuleTest::tablesOf);
        assertTrue(out.startsWith("SELECT * FROM ("), out);
        assertTrue(out.contains(") AS " + DynamicDatasource.RULE_WRAPPER_ALIAS), out);
        assertTrue(out.contains("WHERE (dept_id = 3)"), out);
        assertTrue(out.contains("SELECT id, amount FROM orders WHERE status = 1"), out);
    }

    @Test
    void multipleRulesAreAnded() {
        String sql = "SELECT o.id FROM orders o JOIN users u ON o.uid = u.id";
        String out = DynamicDatasource.applyRowRules(sql,
                rules("orders", "dept_id = 3", "users", "status = 1"),
                DynamicDatasourceRowRuleTest::tablesOf);
        assertTrue(out.contains("WHERE (dept_id = 3) AND (status = 1)"), out);
    }

    @Test
    void unreferencedTableRuleIsSkipped() {
        String sql = "SELECT id FROM users";
        assertEquals(sql, DynamicDatasource.applyRowRules(sql, rules("orders", "dept_id = 3"),
                DynamicDatasourceRowRuleTest::tablesOf));
    }

    @Test
    void noRulesReturnsSqlUnchanged() {
        String sql = "SELECT 1";
        assertEquals(sql, DynamicDatasource.applyRowRules(sql, Collections.emptyMap(),
                DynamicDatasourceRowRuleTest::tablesOf));
        assertEquals(sql, DynamicDatasource.applyRowRules(sql, null,
                DynamicDatasourceRowRuleTest::tablesOf));
    }

    @Test
    void invalidRulesAreRejected() {
        assertNull(DynamicDatasource.rowRuleValidationError("orders", "dept_id = 3"));
        assertTrue(DynamicDatasource.rowRuleValidationError("orders", "dept_id = 3; DROP TABLE t")
                .contains("非法字符"));
        assertTrue(DynamicDatasource.rowRuleValidationError("orders", "dept_id = 3 -- x")
                .contains("非法字符"));
        assertTrue(DynamicDatasource.rowRuleValidationError("orders", "1 = 1 UNION SELECT 1")
                .contains("非法关键字"));
        assertTrue(DynamicDatasource.rowRuleValidationError("orders", "   ")
                .contains("行规则为空"));
    }

    @Test
    void invalidRuleThrowsInsteadOfInjecting() {
        assertThrows(IllegalArgumentException.class,
                () -> DynamicDatasource.applyRowRules("SELECT id FROM orders",
                        rules("orders", "1 = 1; DELETE FROM orders"),
                        DynamicDatasourceRowRuleTest::tablesOf));
    }

    @Test
    void promptSectionListsRules() {
        String section = DynamicDatasource.rowRulesPromptSection(rules("orders", "dept_id = 3"));
        assertTrue(section.contains("orders: dept_id = 3"), section);
        assertTrue(DynamicDatasource.rowRulesPromptSection(null).isEmpty());
        assertTrue(DynamicDatasource.rowRulesPromptSection(Collections.emptyMap()).isEmpty());
    }

    @Test
    void resolverParsesTableRuleFromHostPayload() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("type", "mysql");
        hostConfig.put("name", "宿主数据源");
        hostConfig.put("configuration", mapper.createObjectNode());
        hostConfig.put("table_rule", rules("orders", "dept_id = 3"));

        DynamicDatasourceResolver resolver =
                new DynamicDatasourceResolver(mapper,
                new com.xiongx9527.askdata.common.i18n.MessageResolver(
                        new org.springframework.context.support.StaticMessageSource()));
        DynamicDatasource ds = resolver.resolve(1, hostConfig);
        assertEquals("dept_id = 3", ds.getTableRules().get("orders"));
        assertEquals(1, ds.getTableRules().size());
    }
}
