package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.datasource.embedding.DatasourceEmbeddingService;
import com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService;
import com.xiongx9527.askdata.embedded.DynamicDatasource;
import com.xiongx9527.askdata.embedded.DynamicDatasourceResolver;
import com.xiongx9527.askdata.training.TrainingRecallService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §2.4 验证：动态数据源表级 SQL 模板（SQLBot dynamic_sql）维度在 askdata 中从「不存在」变为
 * 可解析、可注入到 SQL 生成提示词——此前 DynamicDatasource 只有 type/conf/name。
 */
class DynamicDatasourceSqlTemplateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SchemaContextBuilder builderWithTemplates() {
        DatasourceService ds = Mockito.mock(DatasourceService.class);
        Mockito.when(ds.tablesTransient(Mockito.any(), Mockito.any())).thenReturn(Collections.emptyList());
        return new SchemaContextBuilder(
                Mockito.mock(DatasourceRepository.class),
                Mockito.mock(TableRepository.class),
                Mockito.mock(FieldRepository.class),
                ds,
                Mockito.mock(ChatModelClient.class),
                Mockito.mock(ChatModelRouter.class),
                Mockito.mock(JdbcTemplate.class),
                objectMapper,
                Mockito.mock(TableEmbeddingService.class),
                Mockito.mock(DatasourceEmbeddingService.class),
                Mockito.mock(TrainingRecallService.class));
    }

    @Test
    void sqlPromptIncludesTableSqlTemplate() {
        SchemaContextBuilder b = builderWithTemplates();
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put("orders", "SELECT id, amount FROM orders");
        List<ChatModelClient.ModelMessage> msgs = b.buildSqlMessagesForConf("本月订单", DatasourceType.MYSQL,
                new DatasourceConf(), "", "", "", "", templates);
        String user = msgs.get(msgs.size() - 1).content;
        assertTrue(user.contains("orders"), user);
        assertTrue(user.contains("SELECT id, amount FROM orders"), user);
        assertTrue(user.contains("WITH"), user);
    }

    @Test
    void sqlPromptOmitsTemplateSectionWhenAbsent() {
        SchemaContextBuilder b = builderWithTemplates();
        List<ChatModelClient.ModelMessage> msgs = b.buildSqlMessagesForConf("本月订单", DatasourceType.MYSQL,
                new DatasourceConf(), "", "", "", "", Collections.emptyMap());
        String user = msgs.get(msgs.size() - 1).content;
        assertFalse(user.contains("WITH 表名"), user);
    }

    @Test
    void resolverParsesDynamicSqlTemplates() {
        DynamicDatasourceResolver resolver = new DynamicDatasourceResolver(objectMapper,
                new com.xiongx9527.askdata.common.i18n.MessageResolver(
                        new org.springframework.context.support.StaticMessageSource()));
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("type", "mysql");
        hostConfig.put("name", "宿主库");
        Map<String, Object> dyn = new LinkedHashMap<>();
        dyn.put("orders", "SELECT id FROM orders");
        dyn.put("users", "SELECT id, name FROM users");
        hostConfig.put("dynamic_sql", dyn);

        DynamicDatasource ds = resolver.resolve(1, hostConfig);
        assertEquals("SELECT id FROM orders", ds.getTableSqlTemplates().get("orders"));
        assertEquals("SELECT id, name FROM users", ds.getTableSqlTemplates().get("users"));
        assertEquals(2, ds.getTableSqlTemplates().size());
    }

    @Test
    void resolverIgnoresAbsentDynamicSql() {
        DynamicDatasourceResolver resolver = new DynamicDatasourceResolver(objectMapper,
                new com.xiongx9527.askdata.common.i18n.MessageResolver(
                        new org.springframework.context.support.StaticMessageSource()));
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("type", "h2");
        hostConfig.put("name", "宿主库");
        DynamicDatasource ds = resolver.resolve(3, hostConfig);
        assertTrue(ds.getTableSqlTemplates().isEmpty());
    }
}
