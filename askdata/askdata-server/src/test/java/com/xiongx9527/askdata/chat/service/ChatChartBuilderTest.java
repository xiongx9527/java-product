package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.common.util.Rows;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图表配置构造（A7 离线证据）：模型产出归一化 + 无模型时的确定性启发式构造，
 * 输出形状对齐 {@code views/chat/chat-block/ChartBlock.vue} 的 G2 配置契约。
 */
class ChatChartBuilderTest {

    private ObjectMapper objectMapper = new ObjectMapper();

    private ChatChartBuilder builder = new ChatChartBuilder(objectMapper);

    // ------------------------------------------------------------------
    // 模型产出归一化
    // ------------------------------------------------------------------

    @Test
    void normalizeModelOutput() {
        Map<String, Object> chart = builder.normalize(
                "```json\n{\"type\":\"pie\",\"title\":\"各状态占比\",\"data\":[{\"status\":\"PAID\"}],"
                        + "\"columns\":[{\"name\":\"status\",\"value\":\"status\"}],"
                        + "\"axis\":{\"x\":{\"name\":\"status\",\"value\":\"status\"},"
                        + "\"y\":{\"name\":\"数量\",\"value\":\"cnt\"}}}\n```");
        assertNotNull(chart);
        assertEquals("pie", chart.get("type"));
        assertEquals("各状态占比", chart.get("title"));
        // data 不随配置下发（数据由 /chat/record/{rid}/data 单独取）
        assertNull(chart.get("data"));
        @SuppressWarnings("unchecked")
        Map<String, Object> axis = (Map<String, Object>) chart.get("axis");
        assertNotNull(axis.get("x"));
        assertNotNull(axis.get("y"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) chart.get("columns");
        assertEquals(1, columns.size());
        assertEquals("status", columns.get(0).get("value"));
    }

    @Test
    void normalizeFallsBackOnInvalidOutput() {
        assertNull(builder.normalize("抱歉，我无法生成图表"));
        assertNull(builder.normalize(""));
        assertNull(builder.normalize(null));
        // A4：未知 chart type 必须报错（而非静默降级为 table）
        assertThrows(IllegalArgumentException.class, () -> builder.normalize("{\"type\":\"donut\"}"));
        // 数组形态的 y 轴保留
        Map<String, Object> chart = builder.normalize(
                "{\"type\":\"line\",\"axis\":{\"y\":[{\"name\":\"a\",\"value\":\"a\"},"
                        + "{\"name\":\"b\",\"value\":\"b\"}]}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> axis = (Map<String, Object>) chart.get("axis");
        @SuppressWarnings("unchecked")
        List<Object> y = (List<Object>) axis.get("y");
        assertEquals(2, y.size());
    }

    // ------------------------------------------------------------------
    // 启发式构造（模型不可用时）
    // ------------------------------------------------------------------

    @Test
    void buildChartFromResult() {
        Map<String, Object> result = Rows.of(
                "fields", Arrays.asList("status", "cnt", "amount"),
                "fields_info", Arrays.asList(
                        Rows.of("name", "status", "is_numeric", false),
                        Rows.of("name", "cnt", "is_numeric", true),
                        Rows.of("name", "amount", "is_numeric", true)),
                "data", Arrays.asList(Rows.of("status", "PAID", "cnt", 2, "amount", 111.5)));
        Map<String, Object> chart = builder.build(result, "每个状态的订单数");
        assertEquals("column", chart.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> axis = (Map<String, Object>) chart.get("axis");
        @SuppressWarnings("unchecked")
        Map<String, Object> x = (Map<String, Object>) axis.get("x");
        assertEquals("status", x.get("value"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> y = (List<Map<String, Object>>) axis.get("y");
        assertEquals(2, y.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) chart.get("columns");
        assertEquals(3, columns.size());

        // 无数值列 → 表格
        Map<String, Object> textOnly = Rows.of("fields", Arrays.asList("status", "name"),
                "fields_info", Arrays.asList(Rows.of("name", "status", "is_numeric", false),
                        Rows.of("name", "name", "is_numeric", false)),
                "data", new ArrayList<>());
        assertEquals("column", builder.build(textOnly, "订单列表").get("type"));

        // 空结果 → 表格
        Map<String, Object> empty = builder.build(Rows.of("fields", new ArrayList<>(),
                "fields_info", new ArrayList<>(), "data", new ArrayList<>()), "订单");
        assertEquals("table", empty.get("type"));
        assertTrue(((List<?>) empty.get("columns")).isEmpty());
    }

    @Test
    void buildChartTypeFollowsQuestionHints() {
        Map<String, Object> result = Rows.of(
                "fields", Arrays.asList("status", "cnt"),
                "fields_info", Arrays.asList(Rows.of("name", "status", "is_numeric", false),
                        Rows.of("name", "cnt", "is_numeric", true)),
                "data", new ArrayList<>());
        assertEquals("pie", builder.build(result, "各状态订单占比").get("type"));
        assertEquals("line", builder.build(result, "按月订单趋势").get("type"));
        assertEquals("bar", builder.build(result, "订单量排名 top10").get("type"));
        assertEquals("column", builder.build(result, "每个状态的订单数").get("type"));
        assertEquals("table", ChatChartBuilder.emptyTable().get("type"));
    }

    // ------------------------------------------------------------------
    // 图表配置字段还原（A7：导出/绑定按完整图表配置取字段，支持多指标）
    // ------------------------------------------------------------------

    @Test
    void chartFieldsRestoreFullConfigOrderWithMultiQuota() {
        Map<String, Object> chart = Rows.of(
                "type", "column",
                "title", "各城市销售额",
                "columns", Arrays.asList(
                        Rows.of("name", "状态", "value", "status"),
                        Rows.of("name", "城市", "value", "_u2.city")),
                "axis", Rows.of(
                        "x", Rows.of("name", "城市", "value", "_u2.city"),
                        "y", Arrays.asList(
                                Rows.of("name", "订单数", "value", "cnt"),
                                Rows.of("name", "金额", "value", "amount")),
                        "series", Rows.of("name", "状态", "value", "status")));

        List<Map<String, Object>> fields = ChatChartBuilder.chartFields(chart);

        // columns → axis.x → axis.y（多指标数组）→ axis.series，按 value 去重保序
        assertEquals(4, fields.size());
        assertEquals("status", fields.get(0).get("value"));
        assertEquals("状态", fields.get(0).get("name"));
        assertEquals("_u2.city", fields.get(1).get("value"));
        assertEquals("cnt", fields.get(2).get("value"));
        assertEquals("订单数", fields.get(2).get("name"));
        assertEquals("amount", fields.get(3).get("value"));
    }

    @Test
    void chartFieldsSupportSingleQuotaAndNameFallback() {
        Map<String, Object> chart = Rows.of(
                "columns", Arrays.asList(Rows.of("value", "status")),
                "axis", Rows.of("x", Rows.of("value", "status"), "y", Rows.of("value", "cnt")));
        List<Map<String, Object>> fields = ChatChartBuilder.chartFields(chart);
        // x 与 columns 的 status 去重；name 缺省回落 value
        assertEquals(2, fields.size());
        assertEquals("status", fields.get(0).get("name"));
        assertEquals("status", fields.get(0).get("value"));
        assertEquals("cnt", fields.get(1).get("value"));
    }

    @Test
    void chartFieldsHandleEmptyAndInvalidConfig() {
        assertTrue(ChatChartBuilder.chartFields(null).isEmpty());
        assertTrue(ChatChartBuilder.chartFields(Rows.of("type", "table")).isEmpty());
        assertTrue(ChatChartBuilder.chartFields(Rows.of("axis", "not-a-map")).isEmpty());
        assertTrue(ChatChartBuilder.chartFields(Rows.of("columns", "not-a-list")).isEmpty());
    }

    @Test
    void chartBuilderOutputFeedsChartFields() {
        // 启发式构造（多数值列）产出的多指标配置可被 chartFields 还原
        Map<String, Object> result = Rows.of(
                "fields", Arrays.asList("status", "cnt", "amount"),
                "fields_info", Arrays.asList(Rows.of("name", "status", "is_numeric", false),
                        Rows.of("name", "cnt", "is_numeric", true),
                        Rows.of("name", "amount", "is_numeric", true)),
                "data", new ArrayList<>());
        Map<String, Object> chart = builder.build(result, "每个状态的订单数");
        List<Map<String, Object>> fields = ChatChartBuilder.chartFields(chart);
        assertEquals(3, fields.size());
        assertEquals("status", fields.get(0).get("value"));
        assertEquals("cnt", fields.get(1).get("value"));
        assertEquals("amount", fields.get(2).get("value"));
    }
}
