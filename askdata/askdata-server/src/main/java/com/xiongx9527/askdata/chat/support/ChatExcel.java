package com.xiongx9527.askdata.chat.support;

import com.xiongx9527.askdata.common.util.DataFormatUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话结果导出（对齐 SQLBot {@code apps/chat/api/chat.py::export_excel}）：
 * <b>按完整图表配置还原字段</b>（columns -> axis.x -> axis.y 多指标 -> axis.series），
 * 表格数据 + 预测数据写入工作簿（XLSX）或 CSV。
 *
 * <ul>
 *   <li><b>大数去科学计数法</b>：导出按 SQLBot 的 {@code int_threshold=1e11} 阈值转换
 *       （{@code 1.23e20 -> 123000000000000000000}）；</li>
 *   <li><b>限定列名解析</b>：图表配置写 {@code alias.column} 时按短列名
 *       （{@code column}）兜底取值（数据行已被 JdbcEngine 归一补短名）；</li>
 *   <li>表头取字段显示名（name，缺省回落 value）。</li>
 * </ul>
 *
 * <p>与 {@code datasource/excel/ExcelService} 的备注模板工作簿职责不同（本类按结果集导出），
 * 故独立实现，避免改动 datasource 模块。</p>
 */
public final class ChatExcel {

    /** 单 Sheet 行数上限（对齐前端展示量，避免超大数据集导出卡死）。 */
    private static final int MAX_ROWS = 100000;

    /** 导出大数转换阈值（对齐 SQLBot export_excel 的 int_threshold=1e11）。 */
    private static final double EXPORT_INT_THRESHOLD = 1e11;

    /** CSV 文件 UTF-8 BOM（Excel 正确识别中文）。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ChatExcel() {
    }

    /** 导出格式枚举。 */
    public enum Format {
        XLSX, CSV
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
     * 导出结果集为指定格式文件。
     *
     * @param format   XLSX 或 CSV
     * @param fields   字段列表（{@code [{name: 显示名, value: 数据键}]}，顺序即导出列序）
     * @param rows     数据行（表格数据 + 预测数据）
     * @param title    图表标题（空时用默认名）
     * @return 导出文件载体（文件名 + 字节）
     */
    public static ExportFile exportResult(Format format, List<Map<String, Object>> fields,
                                          List<Map<String, Object>> rows, String title) {
        String base = (title == null || title.trim().isEmpty()) ? "export" : title.trim();
        if (format == Format.CSV) {
            return new ExportFile(base + ".csv", buildResultCsv(fields, rows));
        }
        return new ExportFile(base + ".xlsx", buildResultWorkbook(fields, rows, title));
    }

    /** 导出 XLSX 工作簿字节。 */
    public static byte[] buildResultWorkbook(List<Map<String, Object>> fields, List<Map<String, Object>> rows,
                                             String title) {
        List<Map<String, Object>> normalizedFields = normalizeFields(fields);
        List<Map<String, Object>> safeRows = rows == null ? new ArrayList<>() : rows;
        List<Map<String, Object>> data = DataFormatUtil.convertLargeNumbers(safeRows,
                EXPORT_INT_THRESHOLD, DataFormatUtil.FLOAT_THRESHOLD);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName(title));
            Row header = sheet.createRow(0);
            for (int i = 0; i < normalizedFields.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(String.valueOf(normalizedFields.get(i).get("name")));
            }
            int rowIndex = 1;
            for (Map<String, Object> row : data) {
                if (rowIndex > MAX_ROWS) {
                    break;
                }
                Row sheetRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < normalizedFields.size(); i++) {
                    Object value = row == null ? null
                            : resolveColumnValue(row, String.valueOf(normalizedFields.get(i).get("value")));
                    sheetRow.createCell(i).setCellValue(cellText(value));
                }
            }
            workbook.write(buffer);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("导出 Excel 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 导出 CSV 字节（验收 A5）：与 XLSX 同源字段还原、列名解析与大数处理，
     * 输出带 UTF-8 BOM 的 CSV（逗号分隔、字段内逗号/引号/换行转义），便于在 Excel 直接打开。
     */
    public static byte[] buildResultCsv(List<Map<String, Object>> fields, List<Map<String, Object>> rows) {
        List<Map<String, Object>> normalizedFields = normalizeFields(fields);
        List<Map<String, Object>> safeRows = rows == null ? new ArrayList<>() : rows;
        List<Map<String, Object>> data = DataFormatUtil.convertLargeNumbers(safeRows,
                EXPORT_INT_THRESHOLD, DataFormatUtil.FLOAT_THRESHOLD);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalizedFields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvField(String.valueOf(normalizedFields.get(i).get("name"))));
        }
        sb.append('\n');
        int rowIndex = 0;
        for (Map<String, Object> row : data) {
            if (rowIndex++ >= MAX_ROWS) {
                break;
            }
            for (int i = 0; i < normalizedFields.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object value = row == null ? null
                        : resolveColumnValue(row, String.valueOf(normalizedFields.get(i).get("value")));
                sb.append(csvField(cellText(value)));
            }
            sb.append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 字段归一：name 缺省回落 value；null 项剔除。 */
    private static List<Map<String, Object>> normalizeFields(List<Map<String, Object>> fields) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (fields == null) {
            return result;
        }
        for (Map<String, Object> field : fields) {
            if (field == null) {
                continue;
            }
            Object value = field.get("value");
            Object name = field.get("name");
            String fieldValue = value == null ? (name == null ? null : String.valueOf(name))
                    : String.valueOf(value);
            if (fieldValue == null) {
                continue;
            }
            String fieldName = name == null || String.valueOf(name).isEmpty() ? fieldValue : String.valueOf(name);
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("name", fieldName);
            normalized.put("value", fieldValue);
            result.add(normalized);
        }
        return result;
    }

    /**
     * 按字段取值（图表绑定解析）：先精确匹配 {@code value}；{@code alias.column} 形态
     * 再按短列名 {@code column} 兜底；最后按 {@code *.column} 后缀匹配。
     *
     * @return 取到的值；无匹配返回 null
     */
    public static Object resolveColumnValue(Map<String, Object> row, String value) {
        if (row == null || value == null) {
            return null;
        }
        if (row.containsKey(value)) {
            return row.get(value);
        }
        int dot = value.lastIndexOf('.');
        if (dot < 0) {
            String suffix = "." + value;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().endsWith(suffix)) {
                    return entry.getValue();
                }
            }
            return null;
        }
        String shortName = value.substring(dot + 1);
        if (!shortName.isEmpty() && row.containsKey(shortName)) {
            return row.get(shortName);
        }
        String suffix = "." + shortName;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().endsWith(suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String sheetName(String title) {
        String name = title == null || title.trim().isEmpty() ? "Excel" : title.trim();
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
