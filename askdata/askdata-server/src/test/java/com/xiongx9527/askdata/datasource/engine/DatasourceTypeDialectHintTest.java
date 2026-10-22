package com.xiongx9527.askdata.datasource.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收 A1：NL2SQL 注入的方言提示必须随数据源类型变化，并说明标识符引用与分页约定，
 * 使模型不会默认按 MySQL 语法生成错误 SQL。
 */
class DatasourceTypeDialectHintTest {

    @Test
    void pgMentionsDialectAndDoubleQuoteAndLimit() {
        String hint = DatasourceType.PG.sqlDialectHint();
        assertTrue(hint.contains("PostgreSQL"), hint);
        assertTrue(hint.contains("\""), hint);
        assertTrue(hint.contains("LIMIT"), hint);
    }

    @Test
    void mysqlMentionsBacktickQuoting() {
        String hint = DatasourceType.MYSQL.sqlDialectHint();
        assertTrue(hint.contains("MySQL"), hint);
        assertTrue(hint.contains("`"), hint);
    }

    @Test
    void oracleMentionsRownumPagination() {
        String hint = DatasourceType.ORACLE.sqlDialectHint();
        assertTrue(hint.contains("Oracle"), hint);
        assertTrue(hint.contains("ROWNUM"), hint);
    }

    @Test
    void sqlServerMentionsTopPagination() {
        String hint = DatasourceType.SQLSERVER.sqlDialectHint();
        assertTrue(hint.contains("TOP"), hint);
    }

    @Test
    void dmAlignsWithOracleConvention() {
        String hint = DatasourceType.DM.sqlDialectHint();
        assertTrue(hint.contains("ROWNUM"), hint);
    }

    @Test
    void esIsNotSqlDialect() {
        String hint = DatasourceType.ES.sqlDialectHint();
        assertTrue(hint.contains("Elasticsearch"), hint);
        assertFalse(hint.contains("LIMIT"), hint);
    }

    @Test
    void allJdbcDialectsCarryADialectName() {
        for (DatasourceType t : DatasourceType.values()) {
            if (t == DatasourceType.ES) {
                continue;
            }
            String hint = t.sqlDialectHint();
            assertFalse(hint.trim().isEmpty(), "dialect hint empty for " + t);
            assertTrue(hint.contains(t.getTemplateName()), "hint missing templateName for " + t);
        }
    }
}
