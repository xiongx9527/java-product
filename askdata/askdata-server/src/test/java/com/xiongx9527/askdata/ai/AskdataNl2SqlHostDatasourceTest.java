package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.engine.TableSchema;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.embedded.DynamicDatasource;
import com.xiongx9527.askdata.permission.PermissionEnforcer;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A24：宿主动态数据源问数（对齐 SQLBot {@code mcp_assistant} 第三方数据接口链路）——
 * schema 来自宿主连接、SQL 生成后注入表级子查询模板与行规则、经 execSqlTransient 只读执行；
 * 生成阶段不在应用库上执行（generateOnly）。
 */
class AskdataNl2SqlHostDatasourceTest {

    private DataSource appDataSource;

    private AskdataNl2Sql build(String llmSql) {
        appDataSource = mock(DataSource.class);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(llmSql)).build());

        SchemaContextBuilder schema = mock(SchemaContextBuilder.class);
        when(schema.buildSchemaTextForConf(eq(DatasourceType.MYSQL), any(DatasourceConf.class), anyString()))
                .thenReturn("# Table: orders\n(id:int, dept_id:int)");

        DatasourceService dsService = mock(DatasourceService.class);
        when(dsService.tablesTransient(eq(DatasourceType.MYSQL), any(DatasourceConf.class)))
                .thenReturn(List.of(new TableSchema("orders", "订单")));
        when(dsService.execSqlTransient(eq(DatasourceType.MYSQL), any(DatasourceConf.class), anyString()))
                .thenAnswer(inv -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("data", new ArrayList<>(List.of(Rows.of("id", 1, "dept_id", 3))));
                    return out;
                });

        return new AskdataNl2Sql(appDataSource, model, mock(JdbcTemplate.class), schema, dsService,
                mock(PermissionEnforcer.class), mock(CurrentUserService.class));
    }

    private DynamicDatasource dynamic(Map<String, String> tableSqlTemplates, Map<String, String> tableRules) {
        DatasourceConf conf = new DatasourceConf();
        conf.setHost("10.0.0.11");
        conf.setPort(3306);
        conf.setUsername("u");
        conf.setPassword("p");
        conf.setDatabase("crm");
        return new DynamicDatasource(DatasourceType.MYSQL, conf, "crm-mysql",
                tableSqlTemplates == null ? new LinkedHashMap<String, String>() : tableSqlTemplates,
                tableRules == null ? new LinkedHashMap<String, String>() : tableRules);
    }

    @Test
    void generatesAndExecutesOnHostConnection() throws Exception {
        AskdataNl2Sql nl2sql = build("SELECT id, dept_id FROM orders");
        Map<String, Object> result = nl2sql.ask("每个部门的订单数", dynamic(null, null), null);

        assertEquals("SELECT id, dept_id FROM orders", result.get("sql"));
        assertEquals("MySQL", result.get("dialect"));
        List<?> rows = (List<?>) result.get("rows");
        assertEquals(1, rows.size());
        assertEquals("每个部门的订单数", result.get("question"));
    }

    @Test
    void rowRulesAreInjectedBeforeExecution() throws Exception {
        AskdataNl2Sql nl2sql = build("SELECT id, dept_id FROM orders");
        DynamicDatasource ds = dynamic(null, Map.of("orders", "dept_id = 3"));
        Map<String, Object> result = nl2sql.ask("订单", ds, null);

        String sql = String.valueOf(result.get("sql"));
        assertTrue(sql.startsWith("SELECT * FROM (SELECT id, dept_id FROM orders"), sql);
        assertTrue(sql.endsWith(") AS askdata_row_rule_t WHERE (dept_id = 3)"), sql);
    }

    @Test
    void tableSqlTemplatesAreReplacedBeforeExecution() throws Exception {
        AskdataNl2Sql nl2sql = build("SELECT id FROM askdata_dynamic_temp_table_orders");
        DynamicDatasource ds = dynamic(Map.of("orders", "SELECT id FROM orders WHERE dept_id = 3"), null);
        Map<String, Object> result = nl2sql.ask("订单", ds, null);

        String sql = String.valueOf(result.get("sql"));
        assertTrue(sql.contains("(SELECT id FROM orders WHERE dept_id = 3)"), sql);
        assertTrue(!sql.contains("askdata_dynamic_temp_table_orders"), sql);
    }

    @Test
    void directReferenceToRestrictedTableIsRejected() throws Exception {
        AskdataNl2Sql nl2sql = build("SELECT id FROM orders");
        DynamicDatasource ds = dynamic(Map.of("orders", "SELECT id FROM orders WHERE dept_id = 3"), null);
        assertThrows(IllegalArgumentException.class, () -> nl2sql.ask("订单", ds, null));
    }

    @Test
    void unknownTableIsRejectedByWhitelist() throws Exception {
        AskdataNl2Sql nl2sql = build("SELECT id FROM ghost_table");
        assertThrows(IllegalArgumentException.class, () -> nl2sql.ask("订单", dynamic(null, null), null));
    }

    @Test
    void appDataSourceIsNeverTouchedInGenerateOnlyMode() throws Exception {
        AskdataNl2Sql nl2sql = build("SELECT id FROM orders");
        nl2sql.ask("订单", dynamic(null, null), null);
        verify(appDataSource, never()).getConnection();
    }

    @Test
    void nonSelectOutputIsRejected() {
        AskdataNl2Sql nl2sql = build("DELETE FROM orders");
        assertThrows(IllegalArgumentException.class, () -> nl2sql.ask("删库", dynamic(null, null), null));
    }

    @Test
    void resultCarriesQuestionSqlDialectAndRows() throws Exception {
        AskdataNl2Sql nl2sql = build("SELECT id FROM orders");
        Map<String, Object> result = nl2sql.ask("q", dynamic(null, null), null);
        assertNotNull(result.get("rows"));
        assertNotNull(result.get("sql"));
        assertEquals("MySQL", result.get("dialect"));
    }
}
