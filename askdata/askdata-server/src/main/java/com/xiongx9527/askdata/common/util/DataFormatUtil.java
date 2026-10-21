package com.xiongx9527.askdata.common.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询结果数据格式化（对齐 SQLBot {@code common/utils/data_format.py} 的 DataFormat）。
 *
 * <p>三件事：</p>
 * <ol>
 *   <li><b>大数去科学计数法</b>：大整数（|v| ≥ 1e15）与大浮点（|v| ≥ 1e10 或 |v| &lt; 1e-6）
 *       转十进制字符串（{@code 1.23e20 → 123000000000000000000}），阈值以下保持原类型；</li>
 *   <li><b>限定列名归一</b>：{@code alias.column} 形式的行额外补充短列名 {@code column}
 *       （仅当短名不存在时），使图表按列名绑定在 Hive/MySQL 下可用；</li>
 *   <li><b>逐列格式标记</b>：{@code text} / {@code number}，供图表渲染使用。</li>
 * </ol>
 *
 * <p>浮点格式化用 {@link BigDecimal#toPlainString()}（等价 SQLBot 的
 * {@code format(Decimal(str(value)), 'f')}），保证不出现科学计数法；
 * 末尾的 {@code 0} 与 {@code .} 按 SQLBot 语义裁剪。</p>
 */
public final class DataFormatUtil {

    /** 大整数阈值（|v| ≥ 1e15 转字符串）。 */
    public static final double INT_THRESHOLD = 1e15;

    /** 大浮点阈值（|v| ≥ 1e10 转字符串）。 */
    public static final double FLOAT_THRESHOLD = 1e10;

    /** 极小浮点阈值（0 &lt; |v| &lt; 1e-6 转字符串）。 */
    public static final double SMALL_FLOAT_THRESHOLD = 1e-6;

    private DataFormatUtil() {
    }

    // ------------------------------------------------------------------
    // 大数格式化
    // ------------------------------------------------------------------

    /**
     * 对象数组内的大数转十进制字符串（递归处理嵌套 dict / list）。
     *
     * <p>对齐 SQLBot {@code convert_large_numbers_in_object_array}：
     * {@code int} 与 {@code float} 分流；{@code bool} 不算数值（Python 中 bool 是 int 的子类，
     * 但 SQLBot 的 {@code isinstance(value, (int, float))} 对 bool 命中后
     * {@code abs(True) >= 1e15} 为假，故等价于保持原值——Java 侧显式排除 boolean）。</p>
     *
     * @param objectArray 行数组（元素为 Map）；null/空返回原值
     * @return 新的行数组（原对象不被修改）
     */
    public static List<Map<String, Object>> convertLargeNumbers(List<Map<String, Object>> objectArray) {
        return convertLargeNumbers(objectArray, INT_THRESHOLD, FLOAT_THRESHOLD);
    }

    /** 带自定义阈值的大数格式化（对齐 SQLBot 的 int_threshold / float_threshold 参数）。 */
    public static List<Map<String, Object>> convertLargeNumbers(List<Map<String, Object>> objectArray,
                                                                double intThreshold, double floatThreshold) {
        if (objectArray == null || objectArray.isEmpty()) {
            return objectArray;
        }
        List<Map<String, Object>> result = new ArrayList<>(objectArray.size());
        for (Object item : objectArray) {
            result.add(processObject(item, intThreshold, floatThreshold));
        }
        return result;
    }

    /** 单个值的大数格式化（非 Map 的容器元素保持原样）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> processObject(Object value, double intThreshold, double floatThreshold) {
        if (!(value instanceof Map)) {
            return (Map<String, Object>) value;
        }
        Map<String, Object> source = (Map<String, Object>) value;
        Map<String, Object> processed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object item = entry.getValue();
            processed.put(entry.getKey(), convertValue(item, intThreshold, floatThreshold));
        }
        return processed;
    }

    /**
     * 单值转换：达到阈值则转十进制字符串，否则保持原类型。
     *
     * <p>数值类型判定按 Java 语义：{@code Integer/Long/Short/Byte/BigInteger} 视为 int，
     * {@code Float/Double/BigDecimal} 视为 float。</p>
     */
    public static Object convertValue(Object value, double intThreshold, double floatThreshold) {
        if (value == null || value instanceof Boolean || value instanceof CharSequence) {
            return value;
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) value;
            return processObject(nested, intThreshold, floatThreshold);
        }
        if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            List<Object> converted = new ArrayList<>(list.size());
            for (Object item : list) {
                converted.add(convertValue(item, intThreshold, floatThreshold));
            }
            return converted;
        }
        if (isIntegral(value)) {
            long number = ((Number) value).longValue();
            if (Math.abs((double) number) >= intThreshold) {
                return String.valueOf(number);
            }
            return value;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Math.abs(number) >= floatThreshold) {
                return formatFloatWithoutScientific(value);
            }
            // SQLBot：abs(value) < 1e-6 一律转字符串（0.0/-0.0 → "0"）
            if (Math.abs(number) < SMALL_FLOAT_THRESHOLD) {
                return formatFloatWithoutScientific(value);
            }
            return value;
        }
        return value;
    }

    /** 浮点 → 无科学计数法的十进制字符串（{@code 0.0 → "0"}、{@code -0.0 → "0"}）。 */
    public static String formatFloatWithoutScientific(Object value) {
        double number = ((Number) value).doubleValue();
        if (number == 0D) {
            return "0";
        }
        BigDecimal decimal;
        if (value instanceof BigDecimal) {
            decimal = (BigDecimal) value;
        } else if (value instanceof Float) {
            decimal = new BigDecimal(String.valueOf(((Float) value).doubleValue()));
        } else {
            decimal = new BigDecimal(String.valueOf(number));
        }
        String formatted = decimal.toPlainString();
        if (formatted.indexOf('.') >= 0) {
            formatted = stripTrailingZeros(formatted);
        }
        return formatted;
    }

    /** 裁剪十进制末尾的 0 与孤立的 {@code .}（对齐 SQLBot 的 rstrip('0').rstrip('.')）。 */
    private static String stripTrailingZeros(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof java.math.BigInteger;
    }

    // ------------------------------------------------------------------
    // 限定列名归一
    // ------------------------------------------------------------------

    /**
     * 限定列名归一（对齐 SQLBot {@code normalize_qualified_sql_column_keys}）：
     * 对 {@code alias.column} 形式的键补一份短列名 {@code column}（仅在短名不存在时）。
     *
     * @return 新的行 Map；入参为 null 时返回 null
     */
    public static Map<String, Object> normalizeQualifiedColumnKeys(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return row;
        }
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.indexOf('.') < 0) {
                continue;
            }
            String shortName = key.substring(key.lastIndexOf('.') + 1);
            if (!shortName.isEmpty() && !out.containsKey(shortName)) {
                out.put(shortName, entry.getValue());
            }
        }
        return out;
    }

    /** 行数组的限定列名归一（非 Map 元素保持原样）。 */
    public static List<Map<String, Object>> normalizeQualifiedColumnKeysInRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(normalizeQualifiedColumnKeys(row));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 逐列格式标记
    // ------------------------------------------------------------------

    /** 列格式标记取值。 */
    public static final class ColumnFormats {

        /** 文本列（长数字被转字符串）。 */
        public static final String TEXT = "text";

        /** 数字列。 */
        public static final String NUMBER = "number";

        /** 默认（不特殊处理）。 */
        public static final String DEFAULT = "default";

        private ColumnFormats() {
        }
    }

    /**
     * 逐列格式标记（对齐 SQLBot {@code format_pd_data} 的 col_formats）。
     *
     * <p>整数超过 15 位（或浮点有效数字超过 15 位）→ 标记为 {@code text}（值本身已由
     * {@link #convertLargeNumbers} 转字符串）；其余数值列标记为 {@code number}。</p>
     *
     * @param columns 列名数组（顺序与 data 行一致）
     * @param data    数据行
     * @return 列格式数组（与 columns 同序）
     */
    public static String[] columnFormats(List<String> columns, List<Map<String, Object>> data) {
        if (columns == null) {
            return new String[0];
        }
        String[] formats = new String[columns.size()];
        for (int i = 0; i < formats.length; i++) {
            formats[i] = ColumnFormats.DEFAULT;
        }
        if (data == null || data.isEmpty()) {
            return formats;
        }
        for (Map<String, Object> row : data) {
            if (row == null) {
                continue;
            }
            for (int i = 0; i < columns.size(); i++) {
                Object value = row.get(columns.get(i));
                if (value == null) {
                    continue;
                }
                if (value instanceof CharSequence) {
                    // 已被大数规则转成字符串的列 → 文本
                    if (ColumnFormats.DEFAULT.equals(formats[i])
                            && isNumericText(String.valueOf(value))) {
                        formats[i] = ColumnFormats.TEXT;
                    }
                    continue;
                }
                if (value instanceof Number && !(value instanceof Boolean)) {
                    if (ColumnFormats.TEXT.equals(formats[i])) {
                        continue;
                    }
                    if (isIntegral(value)) {
                        long number = ((Number) value).longValue();
                        if (Math.abs((double) number) >= INT_THRESHOLD) {
                            formats[i] = ColumnFormats.TEXT;
                        } else {
                            formats[i] = ColumnFormats.NUMBER;
                        }
                    } else {
                        double number = ((Number) value).doubleValue();
                        if (Math.abs(number) >= FLOAT_THRESHOLD
                                || (number != 0D && Math.abs(number) < SMALL_FLOAT_THRESHOLD)) {
                            formats[i] = ColumnFormats.TEXT;
                        } else {
                            formats[i] = ColumnFormats.NUMBER;
                        }
                    }
                }
            }
        }
        return formats;
    }

    /** 数值文本判定（供列格式标记使用；非数值文本返回 false）。 */
    private static boolean isNumericText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
