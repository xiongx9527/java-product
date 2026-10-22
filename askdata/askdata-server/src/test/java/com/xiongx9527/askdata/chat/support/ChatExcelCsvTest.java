package com.xiongx9527.askdata.chat.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问数结果 CSV 导出（验收 A5）：与 XLSX 同源的字段还原/列名解析/大数处理，
 * 输出带 UTF-8 BOM 的 CSV，字段内逗号/引号/换行正确转义。
 */
class ChatExcelCsvTest {

    private List<Map<String, Object>> fields() {
        return List.of(
                Map.of("name", "月份", "value", "month"),
                Map.of("name", "销售额", "value", "sales"));
    }

    private List<Map<String, Object>> rows() {
        return List.of(
                Map.of("month", "1月", "sales", 120),
                Map.of("month", "2月", "sales", 200));
    }

    @Test
    void buildsCsvWithBomAndContent() {
        byte[] csv = ChatExcel.buildResultCsv(fields(), rows());
        assertNotNull(csv);
        // UTF-8 BOM
        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
        String text = new String(csv, 3, csv.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("月份,销售额"), "header: " + text);
        assertTrue(text.contains("1月,120"), "row1: " + text);
        assertTrue(text.contains("2月,200"), "row2: " + text);
    }

    @Test
    void escapesCommasAndQuotes() {
        List<Map<String, Object>> f = List.of(Map.of("name", "note", "value", "note"));
        List<Map<String, Object>> r = List.of(Map.of("note", "a,b\"c"));
        byte[] csv = ChatExcel.buildResultCsv(f, r);
        String text = new String(csv, 3, csv.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        // value contains comma and quote -> must be wrapped and inner quote doubled
        assertTrue(text.contains("\"a,b\"\"c\""), "escaped: " + text);
    }

    @Test
    void exportResultReturnsCsvFile() {
        ChatExcel.ExportFile file = ChatExcel.exportResult(ChatExcel.Format.CSV, fields(), rows(), "销量");
        assertTrue(file.filename.endsWith(".csv"), file.filename);
        assertEquals((byte) 0xEF, file.content[0]);
    }

    @Test
    void exportResultReturnsXlsxFileKeepsExistingBehavior() {
        ChatExcel.ExportFile file = ChatExcel.exportResult(ChatExcel.Format.XLSX, fields(), rows(), "销量");
        assertTrue(file.filename.endsWith(".xlsx"), file.filename);
        // XLSX (zip) magic
        assertEquals((byte) 0x50, file.content[0]);
        assertEquals((byte) 0x4B, file.content[1]);
    }
}
