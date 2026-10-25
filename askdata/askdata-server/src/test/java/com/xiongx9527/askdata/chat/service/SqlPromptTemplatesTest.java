package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A9 SQL 提示词移植证据：classpath 上的 template.yaml 与方言样例被装载，
 * 生成的 system 提示词包含标识符保持、多表限定、时间格式、JSON 输出契约与 few-shot 示例。
 */
class SqlPromptTemplatesTest {

    private final SqlPromptTemplates templates = new SqlPromptTemplates();

    @Test
    void baseAndDialectTemplatesLoad() {
        assertNotNull(templates.baseSql());
        assertFalse(templates.baseSql().isEmpty());
        for (DatasourceType type : DatasourceType.values()) {
            assertNotNull(templates.dialectTemplate(type), type.getType());
        }
        // 未知方言回退 PostgreSQL
        assertNotNull(templates.dialectTemplate(null));
    }

    @Test
    void sqlPromptCarriesAllContracts() {
        String prompt = templates.sqlSystemPrompt(DatasourceType.MYSQL, true, "SQLBot");
        // 标识符保持
        assertTrue(prompt.contains("严格保持数据库标识符原样"), "identifier preservation");
        // 多表限定
        assertTrue(prompt.contains("multi_table_condition") == false, "multi_table_condition placeholder resolved");
        assertTrue(prompt.contains("所有字段引用必须明确限定表名"), "multi table qualification");
        // 时间格式
        assertTrue(prompt.contains("yyyy-MM-dd HH:mm:ss"), "time format");
        // JSON 输出契约
        assertTrue(prompt.contains("\"success\":true"), "json contract");
        assertTrue(prompt.contains("chart-type"), "chart type contract");
        // few-shot 示例
        assertTrue(prompt.contains("<basic-examples>"), "few-shot examples");
        assertTrue(prompt.contains("MySQL 8.0"), "dialect example engine");
        // 数据量限制（query_limit 分支）
        assertTrue(prompt.contains("data-limit-policy"), "row limit policy");
        assertTrue(prompt.contains("最外层查询必须包含数据量限制"), "outermost limit");
    }

    @Test
    void noQueryLimitBranchWhenDisabled() {
        String prompt = templates.sqlSystemPrompt(DatasourceType.MYSQL, false, "SQLBot");
        assertTrue(prompt.contains("默认不限制数据量"), "no_query_limit branch");
        assertFalse(prompt.contains("最外层查询必须包含数据量限制"), "query_limit branch not used");
    }

    @Test
    void dialectSpecificQuotingIsUsed() {
        String mysql = templates.sqlSystemPrompt(DatasourceType.MYSQL, true, "SQLBot");
        assertTrue(mysql.contains("反引号"), "mysql backtick rule");
        String pg = templates.sqlSystemPrompt(DatasourceType.PG, true, "SQLBot");
        assertTrue(pg.contains("双引号"), "pg double-quote rule");
        String oracle = templates.sqlSystemPrompt(DatasourceType.ORACLE, true, "SQLBot");
        assertTrue(oracle.contains("Oracle"), "oracle example engine");
        String kingbase = templates.sqlSystemPrompt(DatasourceType.KINGBASE, true, "SQLBot");
        assertTrue(kingbase.contains("Kingbase"), "kingbase example engine");
        String dm = templates.sqlSystemPrompt(DatasourceType.DM, true, "SQLBot");
        assertTrue(dm.contains("DM"), "dm example engine");
        String hive = templates.sqlSystemPrompt(DatasourceType.HIVE, true, "SQLBot");
        assertTrue(hive.contains("Hive"), "hive example engine");
        String ck = templates.sqlSystemPrompt(DatasourceType.CK, true, "SQLBot");
        assertTrue(ck.contains("ClickHouse"), "clickhouse example engine");
        String mssql = templates.sqlSystemPrompt(DatasourceType.SQLSERVER, true, "SQLBot");
        assertTrue(mssql.contains("SQL Server"), "sqlserver example engine");
    }

    @Test
    void noUnresolvedPlaceholders() {
        String prompt = templates.sqlSystemPrompt(DatasourceType.PG, true, "SQLBot");
        assertFalse(prompt.contains("{sqlbot_name}"), "sqlbot_name resolved");
        assertFalse(prompt.contains("{process_check}"), "process_check resolved");
        assertFalse(prompt.contains("{base_sql_rules}"), "base_sql_rules resolved");
        assertFalse(prompt.contains("{basic_sql_examples}"), "basic_sql_examples resolved");
        assertFalse(prompt.contains("{example_engine}"), "example_engine resolved");
        assertFalse(prompt.contains("{multi_table_condition}"), "multi_table_condition resolved");
        assertFalse(prompt.contains("{example_answer_1}"), "example_answer_1 resolved");
    }

    @Test
    void bracesAreUnescapedForJsonExamples() {
        String prompt = templates.sqlSystemPrompt(DatasourceType.MYSQL, true, "SQLBot");
        // Python str.format 的 {{ }} 转义必须还原为单花括号，否则模型看到的是错误 JSON
        assertFalse(prompt.contains("{{\"success\""), "double braces must be unescaped");
        assertTrue(prompt.contains("{\"success\":true"), "single brace json example");
    }

    @Test
    void chartPromptIsPorted() {
        String prompt = templates.chartSystemPrompt("SQLBot");
        assertTrue(prompt.contains("multi-quota"), "multi-quota rule");
        assertTrue(prompt.contains("series"), "series rule");
        assertTrue(prompt.contains("Chart-Generation-Process"), "chart process");
        assertFalse(prompt.contains("{lang}"), "lang placeholder resolved/absent");
    }

    @Test
    void extractKeywordsPromptPorted() {
        String prompt = templates.extractKeywordsPrompt("SQLBot");
        assertTrue(prompt.contains("ERROR:NOT_DATA_QUERY"), "keyword extraction contract");
    }
}
