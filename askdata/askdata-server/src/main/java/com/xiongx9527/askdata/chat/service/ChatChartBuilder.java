package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图表配置构造（对齐 SQLBot {@code apps/chat/curd/chat.py::get_chart_config} 与前端
 * {@code views/chat/chat-block/ChartBlock.vue} 消费的 G2 配置形状）：
 *
 * <pre>
 * {"type":"column|bar|line|pie|table","title":"标题",
 *  "columns":[{"name":"列名","value":"列名"}],
 *  "axis":{"x":{"name","value"},"y":{"name","value"},"series":{"name","value"}}}
 * </pre>
 *
 * <p>模型产出优先（A7：G2 图表配置由模型产出）；模型不可用/产出非法 JSON 时，
 * 用本类的确定性启发式构造等效配置，保证离线也能拿到可渲染的柱/饼图配置。</p>
 */
@Service
public class ChatChartBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChatChartBuilder.class);

    private static final Set<String> SUPPORTED_TYPES =
            new HashSet<>(Arrays.asList("column", "bar", "line", "pie", "area", "table"));

    private static final int MAX_COLUMNS = 20;

    private final ObjectMapper objectMapper;

    public ChatChartBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 归一化模型产出的图表配置：保留 type/title/columns/axis，剔除 data（数据由
     * {@code /chat/record/{rid}/data} 单独下发，避免重复与脏数据）。
     *
     * @param raw 模型输出原文（可能带 ```json 围栏或前后废话）
     * @return 归一化后的配置；无法解析时返回 null（调用方回退到 {@link #build}）
     */
    public Map<String, Object> normalize(String raw) {
        String json = extractJson(raw);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            Map<String, Object> chart = new LinkedHashMap<>();
            String type = text(node, "type");
            chart.put("type", type == null || !SUPPORTED_TYPES.contains(type.toLowerCase(Locale.ROOT))
                    ? "table" : type.toLowerCase(Locale.ROOT));
            String title = text(node, "title");
            chart.put("title", title == null || title.isEmpty() ? "" : title);
            List<Map<String, Object>> columns = axisList(node, "columns");
            chart.put("columns", columns.isEmpty() ? defaultColumns(node) : columns);
            Map<String, Object> axis = new LinkedHashMap<>();
            JsonNode axisNode = node.get("axis");
            if (axisNode != null && axisNode.isObject()) {
                Object x = axisValue(axisNode, "x");
                Object y = axisValue(axisNode, "y");
                Object series = axisValue(axisNode, "series");
                if (x != null) {
                    axis.put("x", x);
                }
                if (y != null) {
                    axis.put("y", y);
                }
                if (series != null) {
                    axis.put("series", series);
                }
            }
            chart.put("axis", axis);
            return chart;
        } catch (Exception e) {
            log.info("图表配置解析失败（回退启发式构造）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 启发式构造图表配置：数值列作 Y、首个非数值列作 X；含占比类关键词时用饼图，
     * 含趋势/时间类关键词时用折线，否则柱状；无数值列时退化为表格。
     *
     * @param result 执行结果（含 fields/fields_info/data）
     * @param question 用户问题（用于图表类型提示）
     */
    public Map<String, Object> build(Map<String, Object> result, String question) {
        List<String> fields = new ArrayList<>();
        Object rawFields = result == null ? null : result.get("fields");
        if (rawFields instanceof List) {
            for (Object item : (List<?>) rawFields) {
                if (item != null) {
                    fields.add(String.valueOf(item));
                }
            }
        }
        List<Map<String, Object>> numericFlags = Rows.mapList(result == null ? null : result.get("fields_info"));
        Map<String, Object> chart = new LinkedHashMap<>();
        if (fields.isEmpty()) {
            chart.put("type", "table");
            chart.put("title", "");
            chart.put("columns", new ArrayList<>());
            chart.put("axis", new LinkedHashMap<>());
            return chart;
        }
        List<String> columns = fields.subList(0, Math.min(MAX_COLUMNS, fields.size()));
        List<String> numeric = new ArrayList<>();
        List<String> nonNumeric = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i);
            boolean isNumeric = i < numericFlags.size() && Rows.bool(numericFlags.get(i), "is_numeric", false);
            if (isNumeric) {
                numeric.add(name);
            } else {
                nonNumeric.add(name);
            }
        }
        String x = nonNumeric.isEmpty() ? columns.get(0) : nonNumeric.get(0);
        if (numeric.isEmpty()) {
            numeric.add(nonNumeric.size() > 1 ? nonNumeric.get(1) : columns.get(columns.size() - 1));
        }
        Map<String, Object> axis = new LinkedHashMap<>();
        axis.put("x", Rows.of("name", x, "value", x));
        Object y = numeric.size() == 1
                ? Rows.of("name", numeric.get(0), "value", numeric.get(0))
                : yList(numeric);
        axis.put("y", y);
        if (nonNumeric.size() > 1) {
            String series = nonNumeric.get(1);
            axis.put("series", Rows.of("name", series, "value", series));
        }
        Map<String, Object> built = new LinkedHashMap<>();
        built.put("type", guessType(question, numeric, columns));
        built.put("title", "");
        built.put("columns", columnList(columns));
        built.put("axis", axis);
        return built;
    }

    /**
     * 从完整图表配置还原字段序列（对齐 SQLBot {@code export_excel} 的字段还原与
     * {@code convert_object_array_for_pandas} 的取值口径）：
     * {@code columns → axis.x → axis.y（多指标为数组）→ axis.series}，
     * 按 {@code value} 去重保序；每项为 {@code {name, value}}（name 缺省回落 value）。
     *
     * <p>图表绑定的列名可能写作 {@code alias.column}（Hive/MySQL 驱动返回形态），
     * 数据行已由 {@code DataFormatUtil.normalizeQualifiedColumnKeys} 补短名，
     * 取值侧（ChatExcel）再按短名兜底解析。</p>
     *
     * @param chart 图表配置（type/title/columns/axis）；null 或非法时返回空列表
     * @return 有序字段列表；无可用字段时为空列表
     */
    public static List<Map<String, Object>> chartFields(Map<String, Object> chart) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (chart == null) {
            return fields;
        }
        Set<String> seen = new HashSet<>();
        addFields(fields, seen, axisEntries(chart.get("columns")));
        Object axis = chart.get("axis");
        if (axis instanceof Map) {
            Map<?, ?> axisMap = (Map<?, ?>) axis;
            addFields(fields, seen, axisEntries(axisMap.get("x")));
            addFields(fields, seen, axisEntries(axisMap.get("y")));
            addFields(fields, seen, axisEntries(axisMap.get("series")));
        }
        return fields;
    }

    /** 轴/列节点 → {name, value} 列表（兼容单对象与多指标数组两种形态）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> axisEntries(Object node) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                addAxisEntry(result, item);
            }
        } else {
            addAxisEntry(result, node);
        }
        return result;
    }

    private static void addAxisEntry(List<Map<String, Object>> result, Object item) {
        if (!(item instanceof Map)) {
            return;
        }
        Map<?, ?> entry = (Map<?, ?>) item;
        Object value = entry.get("value");
        Object name = entry.get("name");
        if (value == null && name == null) {
            return;
        }
        String fieldValue = value == null ? String.valueOf(name) : String.valueOf(value);
        String fieldName = name == null || String.valueOf(name).isEmpty() ? fieldValue : String.valueOf(name);
        result.add(Rows.of("name", fieldName, "value", fieldValue));
    }

    private static void addFields(List<Map<String, Object>> fields, Set<String> seen,
                                  List<Map<String, Object>> candidates) {
        for (Map<String, Object> candidate : candidates) {
            String value = String.valueOf(candidate.get("value"));
            if (seen.add(value)) {
                fields.add(candidate);
            }
        }
    }

    /** 从模型输出中截取最外层 JSON 对象（兼容 ```json 围栏与前后说明文字）。 */
    static String extractJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('{', fence);
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return text.substring(start, end + 1);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            // 顶层是数组时（如推荐问题 ["a","b"]）
            int arrayStart = text.indexOf('[');
            int arrayEnd = text.lastIndexOf(']');
            return arrayStart < 0 || arrayEnd <= arrayStart ? null : text.substring(arrayStart, arrayEnd + 1);
        }
        return text.substring(start, end + 1);
    }

    private static String guessType(String question, List<String> numeric, List<String> columns) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (q.contains("占比") || q.contains("比例") || q.contains("构成") || q.contains("分布")
                || q.contains("percent") || q.contains("ratio")) {
            return "pie";
        }
        if (q.contains("趋势") || q.contains("按月") || q.contains("按天") || q.contains("增长")
                || q.contains("time") || q.contains("trend")) {
            return "line";
        }
        if (q.contains("排名") || q.contains("排行") || q.contains("top") || q.contains("最高")) {
            return "bar";
        }
        return numeric.isEmpty() && columns.isEmpty() ? "table" : "column";
    }

    private static List<Object> yList(List<String> numeric) {
        List<Object> list = new ArrayList<>();
        for (String name : numeric) {
            list.add(Rows.of("name", name, "value", name));
        }
        return list;
    }

    private static List<Map<String, Object>> columnList(List<String> names) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(Rows.of("name", name, "value", name));
        }
        return columns;
    }

    private static List<Map<String, Object>> axisList(JsonNode node, String key) {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode array = node.get(key);
        if (array == null || !array.isArray()) {
            return result;
        }
        for (int i = 0; i < array.size() && i < MAX_COLUMNS; i++) {
            JsonNode item = array.get(i);
            String name = item.isObject() ? text(item, "name") : item.asText();
            String value = item.isObject() ? text(item, "value") : item.asText();
            if (name == null && value == null) {
                continue;
            }
            result.add(Rows.of("name", name == null ? value : name, "value", value == null ? name : value));
        }
        return result;
    }

    private static Object axisValue(JsonNode axisNode, String key) {
        JsonNode node = axisNode.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                Object item = singleAxis(node.get(i));
                if (item != null) {
                    list.add(item);
                }
            }
            return list.isEmpty() ? null : list;
        }
        return singleAxis(node);
    }

    private static Object singleAxis(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String name = text(node, "name");
        String value = text(node, "value");
        if (name == null && value == null) {
            return null;
        }
        return Rows.of("name", name == null ? value : name, "value", value == null ? name : value);
    }

    private static List<Map<String, Object>> defaultColumns(JsonNode node) {
        List<Map<String, Object>> columns = new ArrayList<>();
        JsonNode data = node.get("data");
        if (data != null && data.isArray() && data.size() > 0 && data.get(0).isObject()) {
            java.util.Iterator<String> names = data.get(0).fieldNames();
            int count = 0;
            while (names.hasNext() && count < MAX_COLUMNS) {
                String name = names.next();
                columns.add(Rows.of("name", name, "value", name));
                count++;
            }
        }
        return columns;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            JsonNode inner = value.get("text");
            return inner == null || inner.isNull() ? null : inner.asText();
        }
        return value.asText();
    }

    /** 空配置（表格）：无结果或链路失败时使用。 */
    public static Map<String, Object> emptyTable() {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "table");
        chart.put("title", "");
        chart.put("columns", Collections.emptyList());
        chart.put("axis", Collections.emptyMap());
        return chart;
    }
}
