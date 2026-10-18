package com.xiongx9527.askdata.common.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Excel（xlsx）读写工具，替代 SQLBot 的 pandas/xlsxwriter 导入导出链路
 * （对齐 {@code apps/data_training/api/data_training.py}、{@code apps/terminology/api/terminology.py} 行为）。
 *
 * <ul>
 *   <li>导出/模板：按列定义生成 xlsx 字节（表头 + 数据行，全部按文本写入）；</li>
 *   <li>导入：读取首个工作表全部行为字符串矩阵（空单元格为空串）。</li>
 * </ul>
 */
public final class ExcelUtils {

    private ExcelUtils() {
    }

    /** 一列定义：表头名 + 数据取值键。 */
    public static final class Column {
        private final String header;
        private final String key;

        public Column(String header, String key) {
            this.header = header;
            this.key = key;
        }

        public String getHeader() {
            return header;
        }

        public String getKey() {
            return key;
        }
    }

    /** 生成 xlsx 字节（Sheet1，无索引列；单元格一律按文本写入，避免长数字/前导零被数值化）。 */
    public static byte[] writeXlsx(List<Column> columns, List<MapStringArray> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);

            int rowIndex = 0;
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellStyle(headerStyle);
                cell.setCellValue(columns.get(i).getHeader());
            }
            for (MapStringArray row : rows) {
                Row dataRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = dataRow.createCell(i);
                    cell.setCellStyle(textStyle);
                    String value = row.get(columns.get(i).getKey());
                    cell.setCellValue(value == null ? "" : value);
                }
            }
            for (int i = 0; i < columns.size(); i++) {
                sheet.setColumnWidth(i, 256 * Math.max(12, columns.get(i).getHeader().length() * 2));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 读取 xlsx 全部行为字符串矩阵（跳过整行空白；读取首个工作表）。 */
    public static List<String[]> readRows(InputStream inputStream) throws IOException {
        List<String[]> result = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                int last = row.getLastCellNum();
                if (last < 0) {
                    last = 0;
                }
                String[] values = new String[last];
                boolean blank = true;
                for (int i = 0; i < last; i++) {
                    Cell cell = row.getCell(i);
                    String text = "";
                    if (cell != null && cell.getCellType() != CellType.BLANK) {
                        text = formatter.formatCellValue(cell);
                        if (text == null) {
                            text = "";
                        }
                        text = text.trim();
                    }
                    values[i] = text;
                    if (!text.isEmpty()) {
                        blank = false;
                    }
                }
                if (!blank) {
                    result.add(values);
                }
            }
        }
        return result;
    }

    /** 键值行（导出用；保持列顺序由 columns 决定）。 */
    public interface MapStringArray {
        String get(String key);
    }
}
