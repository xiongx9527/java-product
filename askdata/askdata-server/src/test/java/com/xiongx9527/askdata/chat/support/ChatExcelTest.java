package com.xiongx9527.askdata.chat.support;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7 导出格式化（离线证据）：
 * <ul>
 *   <li>大数按 SQLBot 导出阈值（{@code int_threshold=1e11}）转十进制字符串，
 *       {@code 1.23e20 → 123000000000000000000}（无科学计数法）；</li>
 *   <li>{@code alias.column} 形态的图表字段按短列名兜底取值（图表绑定解析）；</li>
 *   <li>表头取字段显示名（name，缺省回落 value）；</li>
 *   <li>字段序列（顺序/去重）由完整图表配置还原（多指标 y 为数组）。</li>
 * </ul>
 */
class ChatExcelTest {

    private static List<Map<String, Object>> fields(Object... nameValue) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i + 1 < nameValue.length; i += 2) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", nameValue[i]);
            field.put("value", nameValue[i + 1]);
            result.add(field);
        }
        return result;
    }

    private static Sheet readSheet(byte[] content) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            return workbook.getSheetAt(0);
        }
    }

    // ------------------------------------------------------------------
    // 大数去科学计数法（A7 验收核心）
    // ------------------------------------------------------------------

    @Test
    void largeNumberIsExportedWithoutScientificNotation() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", 1.23e20);
        rows.add(row);

        Sheet sheet = readSheet(ChatExcel.buildResultWorkbook(fields("金额", "amount"), rows, "金额表"));

        assertEquals("金额", sheet.getRow(0).getCell(0).getStringCellValue());
        assertEquals("123000000000000000000", sheet.getRow(1).getCell(0).getStringCellValue());
    }

    @Test
    void exportIntegerThresholdMatchesSqlBot() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at_threshold", 100000000000L);   // 1e11 → 转字符串（SQLBot 导出阈值）
        row.put("below_threshold", 99999999999L); // 1e11-1 → 保持数值
        row.put("big_float", 1.23e20);
        rows.add(row);

        Sheet sheet = readSheet(ChatExcel.buildResultWorkbook(
                fields("阈值", "at_threshold", "阈值下", "below_threshold", "大浮点", "big_float"), rows, "t"));

        assertEquals("100000000000", sheet.getRow(1).getCell(0).getStringCellValue());
        assertEquals("99999999999", sheet.getRow(1).getCell(1).getStringCellValue());
        assertEquals("123000000000000000000", sheet.getRow(1).getCell(2).getStringCellValue());
    }

    // ------------------------------------------------------------------
    // 限定列名解析（alias.column → 短列名）
    // ------------------------------------------------------------------

    @Test
    void qualifiedColumnValueResolvesByShortName() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_u2.city", "北京");   // 驱动返回限定名（JdbcEngine 已补短名 city）
        row.put("city", "北京");
        rows.add(row);

        Sheet sheet = readSheet(ChatExcel.buildResultWorkbook(fields("城市", "_u2.city"), rows, "城市"));
        assertEquals("北京", sheet.getRow(1).getCell(0).getStringCellValue());
    }

    @Test
    void shortColumnNameResolvesByQualifiedSuffix() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_u2.city", "上海");   // 图表配置写短名，数据行只有限定名
        rows.add(row);

        Sheet sheet = readSheet(ChatExcel.buildResultWorkbook(fields("城市", "city"), rows, "城市"));
        assertEquals("上海", sheet.getRow(1).getCell(0).getStringCellValue());
    }

    @Test
    void resolveColumnValueCoversExactShortAndSuffixMatching() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_u2.city", "北京");
        row.put("amount", 10);
        assertEquals("北京", ChatExcel.resolveColumnValue(row, "_u2.city"));
        assertEquals("北京", ChatExcel.resolveColumnValue(row, "city"));
        assertEquals(10, ChatExcel.resolveColumnValue(row, "amount"));
        assertNull(ChatExcel.resolveColumnValue(row, "missing"));
        assertNull(ChatExcel.resolveColumnValue(row, "other.missing"));
        assertNull(ChatExcel.resolveColumnValue(null, "city"));
    }

    @Test
    void missingFieldIsWrittenAsEmptyCell() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>());
        Sheet sheet = readSheet(ChatExcel.buildResultWorkbook(fields("列", "col"), rows, "t"));
        assertEquals("", sheet.getRow(1).getCell(0).getStringCellValue());
    }

    // ------------------------------------------------------------------
    // 工作簿结构
    // ------------------------------------------------------------------

    @Test
    void sheetNameIsSanitizedAndTruncated() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>());
        byte[] content = ChatExcel.buildResultWorkbook(fields("a", "a"), rows,
                "a[b]c:d*e?f/g" + "h".repeat(40));
        Sheet sheet = readSheet(content);
        assertTrue(sheet.getSheetName().startsWith("abcdefg"));
        assertTrue(sheet.getSheetName().length() <= 31, sheet.getSheetName());
    }

    @Test
    void nullRowsAndFieldsAreHandled() throws Exception {
        byte[] content = ChatExcel.buildResultWorkbook(null, null, null);
        Sheet sheet = readSheet(content);
        assertEquals("Excel", sheet.getSheetName());
        assertEquals(1, sheet.getPhysicalNumberOfRows());
    }

    @Test
    void multipleRowsAndColumnsKeepFieldOrder() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("status", "PAID");
        first.put("cnt", 2);
        rows.add(first);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("status", "NEW");
        second.put("cnt", 1);
        rows.add(second);

        Sheet sheet = readSheet(ChatExcel.buildResultWorkbook(
                fields("状态", "status", "数量", "cnt"), rows, "t"));

        assertEquals(3, sheet.getPhysicalNumberOfRows());
        Row row2 = sheet.getRow(2);
        assertEquals("NEW", row2.getCell(0).getStringCellValue());
        assertEquals("1", row2.getCell(1).getStringCellValue());
    }

    @Test
    void predictStyleRowsAreAppendedInOrder() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(Arrays.asList(
                new LinkedHashMap<>(Map.of("status", "PAID")),
                new LinkedHashMap<>(Map.of("status", "FORECAST"))));
        Sheet sheet = readSheet(ChatExcel.buildResultWorkbook(fields("状态", "status"), rows, "t"));
        assertEquals("PAID", sheet.getRow(1).getCell(0).getStringCellValue());
        assertEquals("FORECAST", sheet.getRow(2).getCell(0).getStringCellValue());
    }
}
