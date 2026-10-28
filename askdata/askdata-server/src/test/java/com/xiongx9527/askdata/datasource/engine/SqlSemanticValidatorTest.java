package com.xiongx9527.askdata.datasource.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A10：SQL 语义校验的 10 组构造输入（引用不存在的表/列、错误聚合、维度缺失）。
 *
 * <p>校验器只依赖已勾选表/字段的元数据快照，因此可用纯单测覆盖；真实链路里命中后由
 * {@code ChatQuestionService} 走既有的一次修复轮，修复仍失败则返回可读错误且不执行 SQL。</p>
 */
class SqlSemanticValidatorTest {

    private final SqlSemanticValidator.Schema schema = schema();

    private static SqlSemanticValidator.Schema schema() {
        Map<String, String> orders = new LinkedHashMap<>();
        orders.put("id", "int");
        orders.put("region", "varchar(64)");
        orders.put("amount", "decimal(18,2)");
        orders.put("created_at", "datetime");
        Map<String, String> customers = new LinkedHashMap<>();
        customers.put("id", "int");
        customers.put("name", "varchar(64)");
        customers.put("city", "varchar(64)");
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        tables.put("orders", orders);
        tables.put("customers", customers);
        return new SqlSemanticValidator.Schema(tables);
    }

    // 1：引用不存在的表
    @Test
    void rejectsUnknownTable() {
        String error = SqlSemanticValidator.validate("SELECT * FROM unknown_table", schema);
        assertNotNull(error);
        assertTrue(error.contains("unknown_table"), error);
    }

    // 2：引用不存在的列（无表限定）
    @Test
    void rejectsUnknownUnqualifiedColumn() {
        String error = SqlSemanticValidator.validate("SELECT id, bogus FROM orders", schema);
        assertNotNull(error);
        assertTrue(error.contains("bogus"), error);
    }

    // 3：引用不存在的列（表限定）
    @Test
    void rejectsUnknownQualifiedColumn() {
        String error = SqlSemanticValidator.validate("SELECT o.bogus FROM orders o", schema);
        assertNotNull(error);
        assertTrue(error.contains("bogus"), error);
    }

    // 4：错误聚合（对文本列求和）
    @Test
    void rejectsSumOverTextColumn() {
        String error = SqlSemanticValidator.validate("SELECT SUM(region) FROM orders", schema);
        assertNotNull(error);
        assertTrue(error.contains("region"), error);
    }

    // 5：错误聚合（对时间列求和）
    @Test
    void rejectsSumOverTimeColumn() {
        String error = SqlSemanticValidator.validate("SELECT SUM(created_at) FROM orders", schema);
        assertNotNull(error);
        assertTrue(error.contains("created_at"), error);
    }

    // 6：维度缺失（聚合 + 非聚合列但没有 GROUP BY）
    @Test
    void rejectsAggregateWithBareColumnWithoutGroupBy() {
        String error = SqlSemanticValidator.validate("SELECT region, SUM(amount) FROM orders", schema);
        assertNotNull(error);
        assertTrue(error.contains("GROUP BY"), error);
    }

    // 7：JOIN 中引用另一张表不存在的列
    @Test
    void rejectsUnknownColumnAcrossJoin() {
        String error = SqlSemanticValidator.validate(
                "SELECT o.id, c.bogus FROM orders o JOIN customers c ON o.id = c.id", schema);
        assertNotNull(error);
        assertTrue(error.contains("bogus"), error);
    }

    // 8：错误聚合（对 JOIN 表文本列求平均）
    @Test
    void rejectsAvgOverJoinedTextColumn() {
        String error = SqlSemanticValidator.validate(
                "SELECT AVG(c.name) FROM customers c", schema);
        assertNotNull(error);
        assertTrue(error.contains("name"), error);
    }

    // 9：维度缺失（GROUP BY 的列与 SELECT 的非聚合列不一致）
    @Test
    void rejectsGroupByMismatch() {
        String error = SqlSemanticValidator.validate(
                "SELECT city, COUNT(*) FROM customers GROUP BY id", schema);
        assertNotNull(error);
        assertTrue(error.contains("city"), error);
    }

    // 10：WHERE 中引用不存在的列
    @Test
    void rejectsUnknownColumnInWhere() {
        String error = SqlSemanticValidator.validate(
                "SELECT id FROM orders WHERE nonexistent = 1", schema);
        assertNotNull(error);
        assertTrue(error.contains("nonexistent"), error);
    }

    // 正例：合法聚合 / 别名 / COUNT(*) 不应被误杀
    @Test
    void acceptsValidAggregateQuery() {
        assertNull(SqlSemanticValidator.validate(
                "SELECT region, SUM(amount) AS total, COUNT(*) FROM orders GROUP BY region "
                        + "ORDER BY total DESC", schema));
    }

    @Test
    void acceptsAliasAndDateFormatExpressions() {
        assertNull(SqlSemanticValidator.validate(
                "SELECT DATE_FORMAT(created_at, '%Y-%m') AS month, SUM(amount) FROM orders "
                        + "GROUP BY DATE_FORMAT(created_at, '%Y-%m')", schema));
    }

    // 回归：表已勾选但字段元数据为空（未同步字段）时不得误杀任何列
    @Test
    void skipsColumnChecksWhenTableHasNoFieldMetadata() {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        tables.put("orders", new LinkedHashMap<>());
        SqlSemanticValidator.Schema noFields = new SqlSemanticValidator.Schema(tables);
        assertNull(SqlSemanticValidator.validate(
                "SELECT \"status\", COUNT(*) AS \"cnt\" FROM \"orders\" GROUP BY \"status\"", noFields));
    }

    @Test
    void skipsCteAndUnparsableSql() {
        assertNull(SqlSemanticValidator.validate(
                "WITH x AS (SELECT id FROM orders) SELECT * FROM x", schema));
        assertNull(SqlSemanticValidator.validate("SELECT FROM WHERE", schema));
    }
}
