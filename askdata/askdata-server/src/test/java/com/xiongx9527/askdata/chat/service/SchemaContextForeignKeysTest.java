package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A13：表关系（table_relation）进入提示词：外键段 a.x=b.y + 未入选关联表补全。
 */
class SchemaContextForeignKeysTest {

    private SchemaContextBuilder builder;

    private DatasourceRepository datasourceRepository;

    private TableRepository tableRepository;

    private FieldRepository fieldRepository;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        datasourceRepository = mock(DatasourceRepository.class);
        tableRepository = mock(TableRepository.class);
        fieldRepository = mock(FieldRepository.class);
        jdbc = mock(JdbcTemplate.class);
        builder = new SchemaContextBuilder(datasourceRepository, tableRepository, fieldRepository,
                mock(DatasourceService.class), null, null, jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null, null);
    }

    private static Map<String, Object> table(long id, String name) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("table_name", name);
        row.put("table_comment", name + " 注释");
        return row;
    }

    /** jointjs edge 列表：user.id = order.user_id。 */
    private static String relationJson() {
        return "["
                + "{\"shape\":\"edge\",\"source\":{\"cell\":\"11\",\"port\":\"21\"},"
                + " \"target\":{\"cell\":\"12\",\"port\":\"22\"}},"
                + "{\"shape\":\"basic.Rect\",\"id\":\"11\"}"
                + "]";
    }

    @Test
    void foreignKeysRenderedAndRelatedTableCompleted() {
        List<Map<String, Object>> checked = new ArrayList<>(Arrays.asList(table(11L, "user"), table(12L, "orders")));
        when(tableRepository.listByDsIdChecked(anyLong())).thenReturn(checked);
        when(datasourceRepository.findTableRelation(anyLong())).thenReturn(relationJson());

        Map<Long, String> tableNames = new LinkedHashMap<>();
        tableNames.put(11L, "user");
        tableNames.put(12L, "orders");
        when(tableRepository.tableNamesByIds(any())).thenReturn(tableNames);

        Map<Long, String> fieldNames = new LinkedHashMap<>();
        fieldNames.put(21L, "id");
        fieldNames.put(22L, "user_id");
        when(fieldRepository.fieldNamesByIds(any())).thenReturn(fieldNames);

        String schema = builder.buildSchemaText(7L, "查一下用户订单");
        assertTrue(schema.contains("【Foreign keys】"), schema);
        assertTrue(schema.contains("user.id=orders.user_id"), schema);
    }

    @Test
    void noRelationJsonOmitsSection() {
        when(tableRepository.listByDsIdChecked(anyLong()))
                .thenReturn(new ArrayList<>(Arrays.asList(table(11L, "user"))));
        when(datasourceRepository.findTableRelation(anyLong())).thenReturn(null);
        String schema = builder.buildSchemaText(7L, "查用户");
        assertFalse(schema.contains("【Foreign keys】"));
    }

    @Test
    void unrelatedRelationIsFilteredOut() {
        when(tableRepository.listByDsIdChecked(anyLong()))
                .thenReturn(new ArrayList<>(Arrays.asList(table(11L, "user"))));
        // 关系只涉及未入选的 99 号表 → 过滤掉，不产生外键段
        when(datasourceRepository.findTableRelation(anyLong())).thenReturn(
                "[{\"shape\":\"edge\",\"source\":{\"cell\":\"99\",\"port\":\"91\"},"
                + "\"target\":{\"cell\":\"98\",\"port\":\"92\"}}]");
        String schema = builder.buildSchemaText(7L, "查用户");
        assertFalse(schema.contains("【Foreign keys】"), schema);
    }

    @Test
    void brokenRelationJsonIsIgnored() {
        when(tableRepository.listByDsIdChecked(anyLong()))
                .thenReturn(new ArrayList<>(Arrays.asList(table(11L, "user"))));
        when(datasourceRepository.findTableRelation(anyLong())).thenReturn("{not-json");
        String schema = builder.buildSchemaText(7L, "查用户");
        assertFalse(schema.contains("【Foreign keys】"), schema);
    }

    @Test
    void missingTableInRelationCompletesSchema() {
        // 只勾选 user（入选提示词），关系指向未勾选的 orders → 补全 orders 结构
        when(tableRepository.listByDsIdChecked(anyLong()))
                .thenReturn(new ArrayList<>(Arrays.asList(table(11L, "user"))));
        when(datasourceRepository.findTableRelation(anyLong())).thenReturn(relationJson());
        Map<Long, String> tableNames = new LinkedHashMap<>();
        tableNames.put(11L, "user");
        tableNames.put(12L, "orders");
        when(tableRepository.tableNamesByIds(any())).thenReturn(tableNames);
        Map<Long, String> fieldNames = new LinkedHashMap<>();
        fieldNames.put(21L, "id");
        fieldNames.put(22L, "user_id");
        when(fieldRepository.fieldNamesByIds(any())).thenReturn(fieldNames);
        when(tableRepository.listByIds(any())).thenReturn(new ArrayList<>(Arrays.asList(table(12L, "orders"))));

        String schema = builder.buildSchemaText(7L, "查一下用户订单");
        assertTrue(schema.contains("Table orders"), schema);
        assertTrue(schema.contains("user.id=orders.user_id"), schema);
    }
}
