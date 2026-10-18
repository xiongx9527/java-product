package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 与降级（D5 离线证据）：
 * <ul>
 *   <li>数据源选库（向量不可用时退化为词法召回）；</li>
 *   <li>{@code data_training} 缺失时优雅降级（<b>不报错</b>）——本子任务基线不含该表；</li>
 *   <li>表结构提示词包含表注释/字段注释/样例行。</li>
 * </ul>
 */
class ChatRagTest {

    private JdbcTemplate appJdbc;

    private ObjectMapper objectMapper = new ObjectMapper();

    private SchemaContextBuilder schemaContextBuilder;

    private DatasourceRepository datasourceRepository;

    /** 共享主键生成器（多次 nextId 在同一毫秒内也必须唯一）。 */
    private final IdGenerator ids = new IdGenerator();

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat_rag_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        IdGenerator idGenerator = new IdGenerator();
        datasourceRepository = new DatasourceRepository(appJdbc, idGenerator);
        TableRepository tableRepository = new TableRepository(appJdbc, idGenerator);
        FieldRepository fieldRepository = new FieldRepository(appJdbc, idGenerator);
        ChatModelRouter modelRouter = new ChatModelRouter(
                new com.xiongx9527.askdata.system.repository.AiModelRepository(appJdbc, idGenerator), objectMapper);
        schemaContextBuilder = new SchemaContextBuilder(datasourceRepository, tableRepository, fieldRepository,
                Mockito.mock(DatasourceService.class), new ChatModelClient(objectMapper), modelRouter, appJdbc,
                objectMapper);
    }

    // ------------------------------------------------------------------
    // data_training 缺失降级（依赖边界关键要求）
    // ------------------------------------------------------------------

    @Test
    void trainingTableMissingIsDetectedAndDegrades() {
        // 集成 value-add-modules 后 schema.sql 已含 data_training；本用例验证「缺表」分支，
        // 故先移除该表（本用例使用独立内存库，不影响其他用例），再断言降级行为。
        appJdbc.execute("DROP TABLE IF EXISTS data_training");
        assertFalse(schemaContextBuilder.trainingTableAvailable(),
                "data_training 不存在时 trainingTableAvailable 应为 false");
        // 缺表时召回不报错，返回空列表
        assertTrue(schemaContextBuilder.trainingSamples(1L, "每个状态的订单数").isEmpty());
        // SQL 提示词仍可构建（仅 schema 驱动）
        List<ChatModelClient.ModelMessage> messages =
                schemaContextBuilder.buildSqlMessages("每个状态的订单数", 1L);
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role);
        assertTrue(messages.get(1).content.contains("【数据库表结构】"));
        assertFalse(messages.get(1).content.contains("【可参考的 SQL 样例】"));
    }

    @Test
    void trainingTablePresentIsUsed() {
        appJdbc.execute("CREATE TABLE IF NOT EXISTS data_training ("
                + "id BIGINT PRIMARY KEY, oid BIGINT, datasource BIGINT, create_time BIGINT, "
                + "question VARCHAR(255), description VARCHAR(10000), embedding VARCHAR(10000), "
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE, advanced_application BIGINT)");
        appJdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "enabled) VALUES (?, ?, 1, 1, ?, ?, TRUE)",
                1001L, 1L, "每个状态的订单数", "SELECT status, COUNT(*) FROM orders GROUP BY status");
        appJdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "enabled) VALUES (?, ?, 1, 1, ?, ?, TRUE)",
                1002L, 1L, "订单总数", "SELECT COUNT(*) FROM orders");
        appJdbc.update("INSERT INTO data_training (id, oid, datasource, create_time, question, description, "
                        + "enabled) VALUES (?, ?, 1, 1, ?, ?, FALSE)",
                1003L, 1L, "停用的样例", "SELECT 1");

        assertTrue(schemaContextBuilder.trainingTableAvailable());
        List<String> samples = schemaContextBuilder.trainingSamples(1L, "每个状态的订单数");
        // 停用样例被排除；命中项按相关度排在首位
        assertEquals(2, samples.size(), "enabled=FALSE 的样例不参与召回");
        for (String sample : samples) {
            assertFalse(sample.contains("停用的样例"), sample);
        }
        assertTrue(samples.get(0).contains("每个状态的订单数"), samples.toString());
        // 命中样例进入提示词
        String content = schemaContextBuilder.buildSqlMessages("每个状态的订单数", 1L).get(1).content;
        assertTrue(content.contains("【可参考的 SQL 样例】"));
        assertTrue(content.contains("SELECT status, COUNT(*) FROM orders GROUP BY status"));
    }

    // ------------------------------------------------------------------
    // 词法召回（无 embedding 模型时的等价路径）
    // ------------------------------------------------------------------

    @Test
    void lexicalRecallSelectsMatchingDatasource() {
        // 排除演示数据源，只留两个自建候选：一个订单/用户，一个商品/库存
        appJdbc.update("DELETE FROM core_table WHERE ds_id = 1");
        appJdbc.update("DELETE FROM core_datasource WHERE id = 1");
        long orderDs = newDs("订单库", "orders_ds");
        long productDs = newDs("商品库", "products_ds");
        appJdbc.update("INSERT INTO core_table (id, ds_id, checked, table_name, table_comment, custom_comment) "
                + "VALUES (?, ?, TRUE, 'orders', '订单表', '订单表')", 9001L, orderDs);
        appJdbc.update("INSERT INTO core_table (id, ds_id, checked, table_name, table_comment, custom_comment) "
                + "VALUES (?, ?, TRUE, 'products', '商品表', '商品表')", 9002L, productDs);
        appJdbc.update("INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, "
                + "field_comment, custom_comment, field_index) VALUES (?, ?, 9001, TRUE, 'status', 'varchar', "
                + "'订单状态', '订单状态', 0)", 9101L, orderDs);
        appJdbc.update("INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, "
                + "field_comment, custom_comment, field_index) VALUES (?, ?, 9002, TRUE, 'price', 'decimal', "
                + "'商品单价', '商品单价', 0)", 9102L, productDs);

        assertEquals(orderDs, schemaContextBuilder.selectDatasource(1L, "每个状态的订单数").longValue());
        assertEquals(productDs, schemaContextBuilder.selectDatasource(1L, "商品价格是多少").longValue());
    }

    @Test
    void selectDatasourceHandlesEmptyWorkspace() {
        // 清空工作空间数据源后返回 null（由调用方发出明确错误帧）
        appJdbc.update("DELETE FROM core_datasource WHERE oid = 1");
        assertNull(schemaContextBuilder.selectDatasource(1L, "每个状态的订单数"));
    }

    @Test
    void lexicalScoreIsDeterministic() {
        double hit = schemaContextBuilder.lexicalScore("订单状态", "orders 订单表 status 订单状态");
        double miss = schemaContextBuilder.lexicalScore("订单状态", "products 商品表 price 商品单价");
        assertTrue(hit > miss, "命中项得分必须高于无关项");
        assertEquals(0D, schemaContextBuilder.lexicalScore("", "订单表"));
        assertTrue(SchemaContextBuilder.tokenize("每个状态的订单数").contains("订单"));
        // 标识符下划线视为整体（SQL 表名/字段名常用）
        assertTrue(SchemaContextBuilder.tokenize("orders_ds").contains("orders_ds"));
    }

    // ------------------------------------------------------------------
    // 表结构提示词
    // ------------------------------------------------------------------

    @Test
    void schemaTextContainsTablesFieldsAndComments() {
        String text = schemaContextBuilder.buildSchemaText(1L);
        assertTrue(text.contains("Table orders (订单表)"), text);
        assertTrue(text.contains("- status varchar(20)  订单状态"), text);
        assertTrue(text.contains("- amount decimal(10,2)  订单金额"), text);
        // 表名白名单（SQL 安全校验用）
        List<String> names = schemaContextBuilder.checkedTableNames(1L);
        assertEquals(3, names.size());
        assertTrue(names.containsAll(Arrays.asList("orders", "users", "products")), names.toString());
        // 消息构造无异常
        assertNotNull(schemaContextBuilder.buildChartMessages("q", "{}"));
        assertNotNull(schemaContextBuilder.buildAnalysisMessages("q", "{}", "{}"));
        assertNotNull(schemaContextBuilder.buildPredictMessages("q", "{}", "{}"));
        assertNotNull(schemaContextBuilder.buildRecommendMessages("q", 4));
        assertNotNull(schemaContextBuilder.buildSqlFixMessages("q", 1L, "SELECT 1", "boom"));
    }

    private long newDs(String name, String database) {
        long id = ids.nextId();
        String configuration = com.xiongx9527.askdata.system.auth.CryptoUtil.encryptSqlbot(
                "{\"host\":\"\",\"port\":0,\"username\":\"sa\",\"password\":\"\",\"database\":\"" + database
                        + "\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\",\"sheets\":[],\"mode\":\"\","
                        + "\"timeout\":10,\"lowVersion\":false,\"ssl\":false,\"poolSize\":5,\"engine\":\"hive\"}");
        datasourceRepository.insert(id, name, "", "h2", "H2", configuration, 1L, 1L);
        return id;
    }
}
