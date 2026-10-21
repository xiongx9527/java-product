package com.xiongx9527.askdata.common.util;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel/CSV 读取健壮性单测（对齐 SQLBot 的 pandas+calamine 栈语义）。
 *
 * <p>覆盖：读全部 sheet、列数不足明确报错、旧版 .xls（BIFF8）可读、
 * CSV 解析、文件名 basename 消毒与随机化。</p>
 */
class ExcelReadSupportTest {

    /** 构造 xlsx（多 sheet）。 */
    /** 构造 xlsx：每个 sheet 一组行（行 = 单元格数组）。 */
    private static byte[] xlsx(String[] sheetNames, java.util.List<java.util.List<String[]>> sheetRows)
            throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int s = 0; s < sheetNames.length; s++) {
                Sheet sheet = workbook.createSheet(sheetNames[s]);
                java.util.List<String[]> rowsOfSheet = sheetRows.get(s);
                for (int r = 0; r < rowsOfSheet.size(); r++) {
                    Row row = sheet.createRow(r);
                    String[] cells = rowsOfSheet.get(r);
                    for (int c = 0; c < cells.length; c++) {
                        row.createCell(c).setCellValue(cells[c]);
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static java.util.List<String[]> rows(String[]... rows) {
        return java.util.Arrays.asList(rows);
    }

    @Test
    void readsAllSheetsNotJustTheFirst() throws Exception {
        byte[] bytes = xlsx(new String[]{"S1", "S2"},
                java.util.Arrays.asList(rows(new String[]{"a", "b"}, new String[]{"1", "2"}),
                        rows(new String[]{"c", "d"})));
        List<ExcelReadSupport.SheetData> sheets =
                ExcelReadSupport.readAllSheets(new ByteArrayInputStream(bytes));
        assertEquals(2, sheets.size());
        assertEquals("S1", sheets.get(0).sheetName);
        assertEquals("S2", sheets.get(1).sheetName);
        // S1：首行为表头（a,b），其后一行是数据
        assertEquals(2, sheets.get(0).columnCount());
        assertEquals("a", sheets.get(0).headers[0]);
        assertEquals(1, sheets.get(0).rows.size());
        assertEquals("1", sheets.get(0).rows.get(0)[0]);
        // S2：只有一行 → 该行即表头，无数据行（对齐 pandas header=0 语义）
        assertEquals("c", sheets.get(1).headers[0]);
        assertTrue(sheets.get(1).rows.isEmpty());
    }

    @Test
    void columnCountShortageIsReportedExplicitly() throws Exception {
        byte[] bytes = xlsx(new String[]{"S1"},
                java.util.Arrays.asList(rows(new String[]{"a", "b", "c"}, new String[]{"1"})));
        List<ExcelReadSupport.SheetData> sheets =
                ExcelReadSupport.readAllSheets(new ByteArrayInputStream(bytes));
        String error = ExcelReadSupport.validateColumnCount(sheets.get(0));
        assertNotNull(error, "列数不足必须明确报错，不能静默补空列");
        assertTrue(error.contains("expected 3"));
    }

    @Test
    void consistentColumnCountPassesValidation() throws Exception {
        byte[] bytes = xlsx(new String[]{"S1"},
                java.util.Arrays.asList(rows(new String[]{"a", "b"}, new String[]{"1", "2"})));
        List<ExcelReadSupport.SheetData> sheets =
                ExcelReadSupport.readAllSheets(new ByteArrayInputStream(bytes));
        assertNull(ExcelReadSupport.validateColumnCount(sheets.get(0)));
    }

    @Test
    void legacyXlsBiff8IsReadable() throws Exception {
        byte[] bytes;
        try (Workbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Legacy");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("问题");
            header.createCell(1).setCellValue("SQL");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("订单数");
            data.createCell(1).setCellValue("SELECT 1");
            workbook.write(out);
            bytes = out.toByteArray();
        }
        List<ExcelReadSupport.SheetData> sheets =
                ExcelReadSupport.readAllSheets(new ByteArrayInputStream(bytes));
        assertEquals(1, sheets.size());
        assertEquals("Legacy", sheets.get(0).sheetName);
        assertEquals("问题", sheets.get(0).headers[0]);
        assertEquals("SELECT 1", sheets.get(0).rows.get(0)[1]);
    }

    @Test
    void csvIsParsedWithQuotesAndEmbeddedNewlines() {
        ExcelReadSupport.SheetData sheet = ExcelReadSupport.readCsv(
                "a,b,c\n1,\"x,y\",\"line1\nline2\"\n");
        assertEquals(3, sheet.columnCount());
        assertEquals("1", sheet.rows.get(0)[0]);
        assertEquals("x,y", sheet.rows.get(0)[1]);
        assertEquals("line1\nline2", sheet.rows.get(0)[2]);
    }

    @Test
    void csvEscapedQuotesAreUnescaped() {
        ExcelReadSupport.SheetData sheet = ExcelReadSupport.readCsv("a\n\"say \"\"hi\"\"\"\n");
        assertEquals("say \"hi\"", sheet.rows.get(0)[0]);
    }

    @Test
    void baseNameSanitizationStripsPathTraversal() {
        assertEquals("evil.txt", ExcelReadSupport.sanitizeBaseName("../../evil.txt"));
        assertEquals("evil.txt", ExcelReadSupport.sanitizeBaseName("..\\..\\evil.txt"));
        assertEquals("a.txt", ExcelReadSupport.sanitizeBaseName("/etc/a.txt"));
        assertEquals("", ExcelReadSupport.sanitizeBaseName(null));
    }

    @Test
    void storedFileNameIsRandomized() {
        String name = ExcelReadSupport.randomizeFileName("../../订单.xlsx");
        assertTrue(name.startsWith("订单_"), "应保留 basename：" + name);
        assertTrue(name.endsWith(".xlsx"), "应保留扩展名：" + name);
        assertTrue(!name.contains("/"), "不应包含路径分隔符：" + name);
        assertTrue(!name.contains(".."), "不应包含 ..：" + name);
        // 两次随机化应不同（防覆盖）
        assertTrue(!ExcelReadSupport.randomizeFileName("a.xlsx")
                .equals(ExcelReadSupport.randomizeFileName("a.xlsx")));
    }

    @Test
    void extensionExtraction() {
        assertEquals("xlsx", ExcelReadSupport.extensionOf("a.XLSX"));
        assertEquals("", ExcelReadSupport.extensionOf("noext"));
        assertEquals("", ExcelReadSupport.extensionOf(null));
    }
}
