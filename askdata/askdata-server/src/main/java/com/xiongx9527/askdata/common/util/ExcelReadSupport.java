package com.xiongx9527.askdata.common.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel/CSV 读取健壮性支持（对齐 SQLBot {@code apps/datasource/utils/excel.py} 的
 * pandas + calamine 栈）。
 *
 * <p>相对 {@link ExcelUtils#readRows(InputStream)}（只读首个 sheet）的补齐：</p>
 * <ul>
 *   <li>读取工作簿的<b>全部 sheet</b>，而非仅第一个；</li>
 *   <li><b>列数不足时明确报错</b>（不静默补空列）——对齐 SQLBot
 *       {@code get_excel_column_count} 的校验；</li>
 *   <li>支持旧版 {@code .xls}（BIFF8）——POI 的 {@link WorkbookFactory} 按内容嗅探格式，
 *       {@code XSSFWorkbook} 只认 OOXML，故此处改用 {@code WorkbookFactory}；</li>
 *   <li>支持 {@code .csv} 数据源上传（UTF-8，逗号分隔，支持引号转义）。</li>
 * </ul>
 */
public final class ExcelReadSupport {

    /** 允许的扩展名（对齐 SQLBot 的上传白名单，含 csv）。 */
    public static final String[] ALLOWED_EXTENSIONS = {"xlsx", "xls", "csv"};

    private ExcelReadSupport() {
    }

    /** 一个 sheet 的读取结果。 */
    public static class SheetData {

        /** sheet 名。 */
        public final String sheetName;

        /** 首个非空行作为表头。 */
        public final String[] headers;

        /** 数据行（不含表头）。 */
        public final List<String[]> rows;

        public SheetData(String sheetName, String[] headers, List<String[]> rows) {
            this.sheetName = sheetName;
            this.headers = headers;
            this.rows = rows;
        }

        /** 表头列数。 */
        public int columnCount() {
            return headers == null ? 0 : headers.length;
        }
    }

    /**
     * 读取工作簿的<b>全部 sheet</b>。
     *
     * @param inputStream 输入流（xlsx / xls）
     * @return 各 sheet 的数据（按工作簿顺序）
     * @throws IOException 解析失败
     */
    public static List<SheetData> readAllSheets(InputStream inputStream) throws IOException {
        List<SheetData> result = new ArrayList<>();
        byte[] content = readAll(inputStream);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                result.add(readSheet(sheet.getSheetName(), sheet));
            }
        }
        return result;
    }

    /**
     * 读取单个 sheet（首个非空行为表头）。
     *
     * @param sheetName sheet 名
     * @param sheet     sheet
     * @return sheet 数据
     */
    public static SheetData readSheet(String sheetName, Sheet sheet) {
        DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
        List<String[]> all = new ArrayList<>();
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
                all.add(values);
            }
        }
        if (all.isEmpty()) {
            return new SheetData(sheetName, new String[0], new ArrayList<String[]>());
        }
        String[] headers = all.get(0);
        return new SheetData(sheetName, headers, new ArrayList<>(all.subList(1, all.size())));
    }

    /**
     * 读取 CSV（UTF-8，逗号分隔，支持双引号转义与字段内换行）。
     *
     * @param content CSV 文本
     * @return sheet 数据（sheet 名固定为 {@code "Sheet1"}）
     */
    public static SheetData readCsv(String content) {
        List<String[]> rows = parseCsv(content);
        if (rows.isEmpty()) {
            return new SheetData("Sheet1", new String[0], new ArrayList<String[]>());
        }
        String[] headers = rows.get(0);
        return new SheetData("Sheet1", headers, new ArrayList<>(rows.subList(1, rows.size())));
    }

    /**
     * 解析 CSV 文本为行矩阵。
     *
     * <p>支持双引号包裹的字段（{@code ""} 为转义引号）与字段内换行。</p>
     */
    static List<String[]> parseCsv(String content) {
        List<String[]> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        // 去掉 UTF-8 BOM
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(field.toString().trim());
                field.setLength(0);
                if (!isBlank(fields)) {
                    rows.add(fields.toArray(new String[0]));
                }
                fields = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString().trim());
        if (!isBlank(fields)) {
            rows.add(fields.toArray(new String[0]));
        }
        return rows;
    }

    private static boolean isBlank(List<String> fields) {
        for (String value : fields) {
            if (value != null && !value.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验数据行的列数是否与表头一致。
     *
     * <p>列数不足时<b>明确报错</b>（不静默补空列）——对齐 SQLBot 的列数校验。</p>
     *
     * @param sheet sheet 数据
     * @return 校验失败时的错误信息；通过返回 null
     */
    public static String validateColumnCount(SheetData sheet) {
        if (sheet == null) {
            return "Sheet data is null";
        }
        int expected = sheet.columnCount();
        for (int i = 0; i < sheet.rows.size(); i++) {
            int actual = sheet.rows.get(i).length;
            if (actual < expected) {
                return "Sheet [" + sheet.sheetName + "] row " + (i + 2) + " has " + actual
                        + " columns, expected " + expected;
            }
        }
        return null;
    }

    /**
     * 上传文件名的 basename 消毒（防路径穿越）。
     *
     * <p>去掉目录分隔符与 {@code ..}，只保留文件名本体。</p>
     */
    public static String sanitizeBaseName(String originalName) {
        if (originalName == null) {
            return "";
        }
        String base = originalName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replace("..", "_");
        base = base.replaceAll("[\\p{Cntrl}]", "_");
        return base.trim();
    }

    /**
     * 随机化落盘文件名（防覆盖）。
     *
     * @param originalName 原文件名
     * @return 形如 {@code {基名}_{随机后缀}.{扩展名}} 的文件名
     */
    public static String randomizeFileName(String originalName) {
        String base = sanitizeBaseName(originalName);
        String extension = extensionOf(base);
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        stem = stem.replaceAll("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]", "_");
        if (stem.isEmpty()) {
            stem = "upload";
        }
        if (stem.length() > 64) {
            stem = stem.substring(0, 64);
        }
        return stem + "_" + randomSuffix() + (extension.isEmpty() ? "" : "." + extension);
    }

    /** 扩展名（小写，不含点）。 */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 随机后缀（10 位十六进制）。 */
    public static String randomSuffix() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(java.util.UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString().substring(0, 10);
        }
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }
        return out.toByteArray();
    }
}
