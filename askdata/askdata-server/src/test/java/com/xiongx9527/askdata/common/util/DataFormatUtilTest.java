package com.xiongx9527.askdata.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大数格式化与限定列名归一单测。
 *
 * <p>期望值逐条取自 SQLBot {@code tests/test_data_format.py} 的参数化用例
 * （{@code convert_large_numbers_in_object_array}），语义逐字对齐。</p>
 */
class DataFormatUtilTest {

    private static List<Map<String, Object>> convert(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            row.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return new ArrayList<>(Arrays.asList(row));
    }

    private static Object convertOne(Object value) {
        List<Map<String, Object>> result = DataFormatUtil.convertLargeNumbers(convert("value", value));
        return result.get(0).get("value");
    }

    // ------------------------------------------------------------------
    // 大数格式化（对照 SQLBot test_data_format.py）
    // ------------------------------------------------------------------

    @Test
    void floatConversionPreservesValueWithoutScientificNotation() {
        assertEquals("123000000000000000000", convertOne(1.23e20));
        assertEquals("-123000000000000000000", convertOne(-1.23e20));
        assertEquals("0.000000000123", convertOne(1.23e-10));
        assertEquals("-0.000000000123", convertOne(-1.23e-10));
        assertEquals("100000000000000000000", convertOne(1e20));
        assertEquals("0.0000001", convertOne(1e-7));
        assertEquals("10000000000", convertOne(1e10));
        assertEquals("12345678901.25", convertOne(12345678901.25));
        assertEquals("0", convertOne(0.0));
        assertEquals("0", convertOne(-0.0));
    }

    @Test
    void valuesOutsideConversionThresholdsKeepTheirTypes() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("regular_float", 123.4);
        row.put("small_float_boundary", 1e-6);
        row.put("below_float_threshold", 9999999999.5);
        row.put("below_int_threshold", 999999999999999L);
        row.put("large_integer", 1000000000000000L);
        row.put("negative_integer", -1000000000000000L);
        row.put("zero", 0);
        row.put("flag", Boolean.TRUE);
        row.put("text", "1.23e20");
        row.put("missing", null);

        List<Map<String, Object>> converted = DataFormatUtil.convertLargeNumbers(
                new ArrayList<>(Arrays.asList(row)));

        assertEquals("1000000000000000", converted.get(0).get("large_integer"));
        assertEquals("-1000000000000000", converted.get(0).get("negative_integer"));
        assertEquals(123.4, converted.get(0).get("regular_float"));
        assertEquals(1e-6, converted.get(0).get("small_float_boundary"));
        assertEquals(9999999999.5, converted.get(0).get("below_float_threshold"));
        assertEquals(999999999999999L, converted.get(0).get("below_int_threshold"));
        assertEquals(0, converted.get(0).get("zero"));
        assertEquals(Boolean.TRUE, converted.get(0).get("flag"));
        assertEquals("1.23e20", converted.get(0).get("text"));
        assertNull(converted.get(0).get("missing"));
        // 原对象不被修改
        assertEquals(1000000000000000L, row.get("large_integer"));
    }

    @Test
    void scientificNotationIsConvertedInNestedRows() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("amount", 1.23e20);
        Map<String, Object> seriesItem = new LinkedHashMap<>();
        seriesItem.put("value", 1.23e-10);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metrics", metrics);
        row.put("series", new ArrayList<>(Arrays.asList(seriesItem)));

        List<Map<String, Object>> converted = DataFormatUtil.convertLargeNumbers(
                new ArrayList<>(Arrays.asList(row)));

        @SuppressWarnings("unchecked")
        Map<String, Object> outMetrics = (Map<String, Object>) converted.get(0).get("metrics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outSeries = (List<Map<String, Object>>) converted.get(0).get("series");
        assertEquals("123000000000000000000", outMetrics.get("amount"));
        assertEquals("0.000000000123", outSeries.get(0).get("value"));
        // 原对象不被修改
        assertEquals(1.23e20, metrics.get("amount"));
    }

    @Test
    void excelIntegerThresholdRemainsSupported() {
        List<Map<String, Object>> converted = DataFormatUtil.convertLargeNumbers(
                convert("below", 99999999999L, "boundary", 100000000000L, "amount", 1.23e20),
                1e11, 1e10);
        assertEquals(99999999999L, converted.get(0).get("below"));
        assertEquals("100000000000", converted.get(0).get("boundary"));
        assertEquals("123000000000000000000", converted.get(0).get("amount"));
    }

    @Test
    void bigDecimalValuesAreHandledWithoutScientificNotation() {
        assertEquals("123000000000000000000", convertOne(new BigDecimal("1.23E+20")));
        assertEquals("0.000000000123", convertOne(new BigDecimal("1.23E-10")));
    }

    // ------------------------------------------------------------------
    // 限定列名归一（对照 SQLBot normalize_qualified_sql_column_keys）
    // ------------------------------------------------------------------

    @Test
    void qualifiedColumnKeysGetBareShortName() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_u2.table_name", "订单");
        row.put("other", 1);

        Map<String, Object> normalized = DataFormatUtil.normalizeQualifiedColumnKeys(row);

        assertEquals("订单", normalized.get("table_name"));
        assertEquals("订单", normalized.get("_u2.table_name"));
        assertEquals(1, normalized.get("other"));
        // 原对象不被修改
        assertFalse(row.containsKey("table_name"));
    }

    @Test
    void bareShortNameIsNotClobbered() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_u2.table_name", "订单");
        row.put("table_name", "真实值");

        Map<String, Object> normalized = DataFormatUtil.normalizeQualifiedColumnKeys(row);

        assertEquals("真实值", normalized.get("table_name"));
        assertEquals("订单", normalized.get("_u2.table_name"));
    }

    @Test
    void nonQualifiedKeysAreLeftAlone() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table_name", "订单");
        Map<String, Object> normalized = DataFormatUtil.normalizeQualifiedColumnKeys(row);
        assertEquals(1, normalized.size());
        assertEquals("订单", normalized.get("table_name"));
    }

    @Test
    void nullAndEmptyRowsAreHandled() {
        assertNull(DataFormatUtil.normalizeQualifiedColumnKeys(null));
        assertTrue(DataFormatUtil.normalizeQualifiedColumnKeys(new LinkedHashMap<>()).isEmpty());
        assertTrue(DataFormatUtil.normalizeQualifiedColumnKeysInRows(null) == null);
    }

    // ------------------------------------------------------------------
    // 逐列格式标记
    // ------------------------------------------------------------------

    @Test
    void columnFormatsMarkTextAndNumber() {
        List<String> columns = Arrays.asList("amount", "small", "label");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", 1000000000000000L);
        row.put("small", 12.5);
        row.put("label", "文本");
        rows.add(row);

        String[] formats = DataFormatUtil.columnFormats(columns, rows);

        assertEquals(3, formats.length);
        assertEquals(DataFormatUtil.ColumnFormats.TEXT, formats[0]);
        assertEquals(DataFormatUtil.ColumnFormats.NUMBER, formats[1]);
        assertEquals(DataFormatUtil.ColumnFormats.DEFAULT, formats[2]);
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }
}
