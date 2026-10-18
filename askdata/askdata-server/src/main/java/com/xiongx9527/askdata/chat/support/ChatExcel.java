package com.xiongx9527.askdata.chat.support;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 对话结果导出（对齐 SQLBot {@code apps/chat/api/chat.py::export_excel}）：
 * 表格数据 + 预测数据写入单 Sheet 工作簿，列序取查询结果的 fields。
 *
 * <p>与 {@code datasource/excel/ExcelService} 的备注模板工作簿职责不同（本类按结果集导出），
 * 故独立实现，避免改动 datasource 模块。</p>
 */
public final class ChatExcel {

    /** 单 Sheet 行数上限（对齐前端展示量，避免超大数据集导出卡死）。 */
    private static final int MAX_ROWS = 100000;

    private ChatExcel() {
    }

    /** 导出文件载体（文件名 + 字节）。 */
    public static class ExportFile {

        public final String filename;

        public final byte[] content;

        public ExportFile(String filename, byte[] content) {
            this.filename = filename;
            this.content = content;
        }
    }

    /**
     * 构造结果工作簿。
     *
     * @param columns 列字段（与 {@code fields} 顺序一致）
     * @param rows    数据行（表格数据 + 预测数据）
     * @param title   图表标题（空时用 Excel）
     */
    public static byte[] buildResultWorkbook(List<String> columns, List<Map<String, Object>> rows, String title) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName(title));
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i) == null ? "" : columns.get(i));
            }
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                if (rowIndex > MAX_ROWS) {
                    break;
                }
                Row sheetRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < columns.size(); i++) {
                    Object value = row == null ? null : row.get(columns.get(i));
                    sheetRow.createCell(i).setCellValue(cellText(value));
                }
            }
            workbook.write(buffer);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("导出 Excel 失败: " + e.getMessage(), e);
        }
    }

    private static String sheetName(String title) {
        String name = title == null || title.trim().isEmpty() ? "Excel" : title.trim();
        // Excel 工作表名限制：≤31 字符且不含 []:*?/\
        String safe = name.replaceAll("[\\[\\]:*?/\\\\]", "");
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private static String cellText(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }
}
