package com.xiongx9527.askdata.datasource.excel;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Excel/CSV 上传、解析、导入建表、schema 备注导入导出（对齐 SQLBot
 * apps/datasource/api/datasource.py 的 parseExcel/importToDb/uploadExcel/exportDsSchema/uploadDsSchema
 * + apps/datasource/utils/excel.py 的类型推断与读取）。
 *
 * <p>导入目标为应用元数据库（对齐 SQLBot 的 get_engine_conn：excel 类型数据表存放在应用库），
 * 建表/删表经 {@link JdbcEngine#executeAppDdl} 执行；类型映射对齐 SQLBot create_table：
 * object→text、int→bigint、float→numeric、datetime→timestamp（H2 下为 VARCHAR(1024)/BIGINT/
 * DOUBLE/TIMESTAMP）。表名与列名经 {@link JdbcEngine#appIdentifier} 规范化，与应用库未加引号
 * 标识符的折叠规则一致，避免建表后查询报 {@code Table "XXX" not found}。</p>
 */
@Service
public class ExcelService {

    /** 字段类型（对齐 SQLBot FIELD_TYPE_MAP 的用户侧取值）。 */
    private static final List<String> FIELD_TYPES = Arrays.asList("int", "float", "datetime", "string");

    private static final int PREVIEW_ROWS = 10;

    private static final int TYPE_SAMPLE_ROWS = 100;

    private static final int MAX_IDENTIFIER_BYTES = 63;

    /** 文本列的 VARCHAR 长度（string 类型的导入列上限）。 */
    private static final int STRING_COLUMN_LENGTH = 1024;

    private static final String TABLE_SHEET_NAME = "数据表列表";

    private final JdbcTemplate appJdbc;

    private final JdbcEngine engine;

    private final Path excelDir;

    public ExcelService(JdbcTemplate appJdbc, JdbcEngine engine,
                        @org.springframework.beans.factory.annotation.Value("${askdata.excel.path:./data/excel}")
                                String excelPath) {
        this.appJdbc = appJdbc;
        this.engine = engine;
        this.excelDir = Paths.get(excelPath == null || excelPath.isEmpty() ? "./data/excel" : excelPath)
                .toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // 解析预览（POST /datasource/parseExcel）
    // ------------------------------------------------------------------

    /**
     * 解析上传文件并返回各 sheet 的字段推断与前 10 行预览。
     * 返回 {@code {"filePath": 保存文件名, "data": [{sheetName, fields, data, rows}]}}。
     */
    public Map<String, Object> parseExcel(MultipartFile file) {
        String filename = saveUpload(file);
        String extension = extensionOf(filename);
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (SheetTable table : readAll(filename, extension)) {
            sheets.add(Rows.of(
                    "sheetName", table.sheetName,
                    "fields", table.fields(),
                    "data", table.previewRows(),
                    "rows", table.dataRows.size()));
        }
        return Rows.of("filePath", filename, "data", sheets);
    }

    // ------------------------------------------------------------------
    // 导入建表（POST /datasource/importToDb）
    // ------------------------------------------------------------------

    /**
     * 把已解析文件的各 sheet 导入为应用库数据表。
     * 返回 {@code {"filename": filePath, "sheets": [{sheetName, tableName, tableComment, rows}]}}。
     */
    public Map<String, Object> importToDb(String filePath, List<Map<String, Object>> sheets) {
        Path file = resolveStored(filePath);
        String extension = extensionOf(file.getFileName().toString());
        Map<String, SheetTable> tablesByName = new LinkedHashMap<>();
        for (SheetTable table : readAll(file.getFileName().toString(), extension)) {
            tablesByName.put(table.sheetName, table);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> sheet : sheets) {
            String sheetName = Rows.str(sheet, "sheetName");
            SheetTable table = tablesByName.get(sheetName);
            if (table == null) {
                throw new DatasourceException("上传文件解析失败: Sheet [" + sheetName + "] not found");
            }
            Map<String, String> fieldTypes = new LinkedHashMap<>();
            for (Map<String, Object> field : Rows.mapList(Rows.get(sheet, "fields"))) {
                String fieldName = Rows.str(field, "fieldName");
                String fieldType = Rows.str(field, "fieldType");
                if (fieldName != null && fieldType != null && FIELD_TYPES.contains(fieldType)) {
                    fieldTypes.put(fieldName, fieldType);
                }
            }
            String tableName = engine.appIdentifier("excel_" + filterString(sheetName) + "_" + randomSuffix());
            for (String column : table.headers) {
                byte[] bytes = column.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_IDENTIFIER_BYTES) {
                    throw new DatasourceException("上传文件解析失败: Sheet [" + sheetName + "] column name '" + column
                            + "' exceeds " + MAX_IDENTIFIER_BYTES + " bytes (actual: " + bytes.length + " bytes)");
                }
            }
            createAndInsert(tableName, table, fieldTypes);
            result.add(Rows.of("sheetName", sheetName, "tableName", tableName, "tableComment", "",
                    "rows", table.dataRows.size()));
        }
        return Rows.of("filename", filePath, "sheets", result);
    }

    // ------------------------------------------------------------------
    // 上传即建表（POST /datasource/uploadExcel，SQLBot 标记 deprecated 的路由）
    // ------------------------------------------------------------------

    /** 上传文件并直接以 sheet 名建表导入（对齐 SQLBot upload_excel）。 */
    public Map<String, Object> uploadExcel(MultipartFile file) {
        String filename = saveUpload(file);
        String extension = extensionOf(filename);
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (SheetTable table : readAll(filename, extension)) {
            String tableName = engine.appIdentifier(table.sheetName + "_" + randomSuffix());
            Map<String, String> fieldTypes = new LinkedHashMap<>();
            for (Map<String, Object> field : table.fields()) {
                fieldTypes.put(Rows.str(field, "fieldName"), Rows.str(field, "fieldType"));
            }
            createAndInsert(tableName, table, fieldTypes);
            sheets.add(Rows.of("tableName", tableName, "tableComment", ""));
        }
        return Rows.of("filename", filename, "sheets", sheets);
    }

    // ------------------------------------------------------------------
    // schema 备注导出/导入（exportDsSchema / uploadDsSchema）
    // ------------------------------------------------------------------

    /** 表备注导出条目（sheet 名由调用方按序号生成，对齐 SQLBot 的 "Sheet{index}"）。 */
    public static class SchemaTable {

        public final String sheetLabel;

        public final String tableName;

        public final String tableComment;

        public final List<Map<String, String>> fields;

        public SchemaTable(String sheetLabel, String tableName, String tableComment,
                           List<Map<String, String>> fields) {
            this.sheetLabel = sheetLabel;
            this.tableName = tableName;
            this.tableComment = tableComment;
            this.fields = fields;
        }
    }

    /** 导出 schema 备注工作簿（对齐 SQLBot export_ds_schema 的 df_list 结构）。 */
    public byte[] buildSchemaWorkbook(List<SchemaTable> tables) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet overview = workbook.createSheet(TABLE_SHEET_NAME);
            Row header = overview.createRow(0);
            header.createCell(0).setCellValue("Sheet名称");
            header.createCell(1).setCellValue("表名");
            header.createCell(2).setCellValue("表备注");
            for (int i = 0; i < tables.size(); i++) {
                SchemaTable table = tables.get(i);
                Row row = overview.createRow(i + 1);
                row.createCell(0).setCellValue(table.sheetLabel);
                row.createCell(1).setCellValue(table.tableName);
                row.createCell(2).setCellValue(nullToEmpty(table.tableComment));
                Sheet sheet = workbook.createSheet(table.sheetLabel);
                Row fieldHeader = sheet.createRow(0);
                fieldHeader.createCell(0).setCellValue("字段名");
                fieldHeader.createCell(1).setCellValue("字段备注");
                for (int j = 0; j < table.fields.size(); j++) {
                    Map<String, String> field = table.fields.get(j);
                    Row fieldRow = sheet.createRow(j + 1);
                    fieldRow.createCell(0).setCellValue(nullToEmpty(field.get("fieldName")));
                    fieldRow.createCell(1).setCellValue(nullToEmpty(field.get("fieldComment")));
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new DatasourceException("Export schema failed: " + e.getMessage(), e);
        }
    }

    /** 上传备注模板（id=0，对齐 SQLBot 固定样例数据）。 */
    public byte[] buildSchemaTemplate() {
        List<SchemaTable> tables = new ArrayList<>();
        tables.add(new SchemaTable("数据表1", "user", "用来存放用户信息的数据表", fields("id", "用户id", "name", "用户姓名")));
        tables.add(new SchemaTable("数据表2", "score", "用来存放用户课程信息的数据表",
                fields("course", "课程名称", "user_id", "用户ID", "score", "课程得分")));
        return buildSchemaWorkbook(tables);
    }

    /** 解析上传的 schema 备注文件：表 sheet + 各字段 sheet。 */
    public SchemaUpload readSchemaWorkbook(MultipartFile file) {
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!Arrays.asList("xlsx", "xls").contains(extensionOf(originalName))) {
            throw new DatasourceException("Only support .xlsx/.xls");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            List<Map<String, String>> tableRows = new ArrayList<>();
            List<Map<String, Object>> fieldSheets = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                List<Map<String, String>> rows = readSheetRecords(sheet);
                if (TABLE_SHEET_NAME.equals(sheet.getSheetName())) {
                    tableRows.addAll(rows);
                } else {
                    fieldSheets.add(Rows.of("sheetName", sheet.getSheetName(), "rows", rows));
                }
            }
            return new SchemaUpload(tableRows, fieldSheets);
        } catch (IOException e) {
            throw new DatasourceException("Parse Excel Failed: " + e.getMessage(), e);
        }
    }

    /** uploadDsSchema 解析结果。 */
    public static class SchemaUpload {

        public final List<Map<String, String>> tableRows;

        public final List<Map<String, Object>> fieldSheets;

        public SchemaUpload(List<Map<String, String>> tableRows, List<Map<String, Object>> fieldSheets) {
            this.tableRows = tableRows;
            this.fieldSheets = fieldSheets;
        }
    }

    private static List<Map<String, String>> fields(String... nameCommentPairs) {
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 0; i + 1 < nameCommentPairs.length; i += 2) {
            Map<String, String> field = new LinkedHashMap<>();
            field.put("fieldName", nameCommentPairs[i]);
            field.put("fieldComment", nameCommentPairs[i + 1]);
            result.add(field);
        }
        return result;
    }

    private List<Map<String, String>> readSheetRecords(Sheet sheet) {
        List<Map<String, String>> records = new ArrayList<>();
        Row header = sheet.getRow(0);
        if (header == null) {
            return records;
        }
        List<String> columns = new ArrayList<>();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            Cell cell = header.getCell(c);
            columns.add(cell == null ? "" : new DataFormatter().formatCellValue(cell).trim());
        }
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, String> record = new LinkedHashMap<>();
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = row.getCell(c);
                String value = cell == null ? "" : new DataFormatter().formatCellValue(cell);
                record.put(columns.get(c), value);
            }
            if (!record.values().stream().allMatch(String::isEmpty)) {
                records.add(record);
            }
        }
        return records;
    }

    // ------------------------------------------------------------------
    // 文件读取与建表
    // ------------------------------------------------------------------

    /** 单 sheet 的内存表结构（表头 + 原始数据行）。 */
    private static class SheetTable {

        private final String sheetName;

        private final List<String> headers;

        private final List<List<Object>> dataRows;

        private SheetTable(String sheetName, List<String> headers, List<List<Object>> dataRows) {
            this.sheetName = sheetName;
            this.headers = headers;
            this.dataRows = dataRows;
        }

        private List<Map<String, Object>> fields() {
            List<Map<String, Object>> result = new ArrayList<>();
            List<String> types = inferTypes();
            for (int i = 0; i < headers.size(); i++) {
                result.add(Rows.of("fieldName", headers.get(i), "fieldType", types.get(i)));
            }
            return result;
        }

        private List<Map<String, Object>> previewRows() {
            List<Map<String, Object>> result = new ArrayList<>();
            int limit = Math.min(PREVIEW_ROWS, dataRows.size());
            for (int r = 0; r < limit; r++) {
                List<Object> row = dataRows.get(r);
                Map<String, Object> record = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    record.put(headers.get(c), c < row.size() ? JdbcEngine.convertValue(row.get(c)) : null);
                }
                result.add(record);
            }
            return result;
        }

        /** 按列推断字段类型（对齐 SQLBot infer_field_type 归类：int/float/datetime/string）。 */
        private List<String> inferTypes() {
            List<String> types = new ArrayList<>();
            int sample = Math.min(TYPE_SAMPLE_ROWS, dataRows.size());
            for (int c = 0; c < headers.size(); c++) {
                boolean sawDate = false;
                boolean sawNumber = false;
                boolean sawOther = false;
                boolean allInt = true;
                for (int r = 0; r < sample; r++) {
                    Object value = c < dataRows.get(r).size() ? dataRows.get(r).get(c) : null;
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof Timestamp || value instanceof java.sql.Date) {
                        sawDate = true;
                    } else if (value instanceof Number) {
                        sawNumber = true;
                        double doubleValue = ((Number) value).doubleValue();
                        if (doubleValue != Math.floor(doubleValue) || Double.isInfinite(doubleValue)) {
                            allInt = false;
                        }
                    } else {
                        sawOther = true;
                    }
                }
                if (sawOther || (sawDate && sawNumber)) {
                    types.add("string");
                } else if (sawDate) {
                    types.add("datetime");
                } else if (sawNumber) {
                    types.add(allInt ? "int" : "float");
                } else {
                    types.add("string");
                }
            }
            return types;
        }
    }

    private List<SheetTable> readAll(String filename, String extension) {
        Path file = excelDir.resolve(filename);
        if (!Files.isRegularFile(file)) {
            throw new DatasourceException("File not found");
        }
        try {
            if ("csv".equals(extension)) {
                return readCsv(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            }
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(Files.readAllBytes(file)))) {
                List<SheetTable> result = new ArrayList<>();
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    Sheet sheet = workbook.getSheetAt(i);
                    result.add(readSheet(sheet.getSheetName(), sheet));
                }
                return result;
            }
        } catch (DatasourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DatasourceException("上传文件解析失败: " + e.getMessage(), e);
        }
    }

    private SheetTable readSheet(String sheetName, Sheet sheet) {
        List<String> headers = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        Row headerRow = sheet.getRow(0);
        if (headerRow != null) {
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String name = textValue(headerRow.getCell(c), formatter);
                if (name == null || name.trim().isEmpty()) {
                    name = "Unnamed: " + c;
                }
                headers.add(name.trim());
            }
        }
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            List<Object> values = new ArrayList<>();
            for (int c = 0; c < headers.size(); c++) {
                values.add(cellValue(row.getCell(c)));
            }
            if (!values.stream().allMatch(v -> v == null)) {
                rows.add(values);
            }
        }
        return new SheetTable(sheetName, headers, rows);
    }

    /** 单元格取值（公式取缓存值；日期取 Timestamp；布尔取 Boolean；数字取 Double；空取 null）。 */
    private Object cellValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case BLANK:
                return null;
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return new Timestamp(DateUtil.getJavaCalendar(cell.getNumericCellValue(), false).getTimeInMillis());
                }
                return cell.getNumericCellValue();
            case STRING:
                String text = cell.getStringCellValue();
                return text.isEmpty() ? null : text;
            default:
                return textValue(cell, new DataFormatter());
        }
    }

    private String textValue(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        String value = formatter.formatCellValue(cell);
        return value.isEmpty() ? null : value;
    }

    /** 最小 CSV 解析（RFC4180 引号规则）：首行为表头，单 sheet "Sheet1"。 */
    private List<SheetTable> readCsv(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                if (!current.equals(Arrays.asList(""))) {
                    records.add(current);
                }
                current = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        if (records.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> headers = new ArrayList<>();
        List<String> headerRecord = records.get(0);
        for (int c = 0; c < headerRecord.size(); c++) {
            String name = headerRecord.get(c);
            if (name == null || name.trim().isEmpty()) {
                name = "Unnamed: " + c;
            }
            headers.add(name.trim());
        }
        List<List<Object>> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> record = records.get(r);
            List<Object> values = new ArrayList<>();
            for (int c = 0; c < headers.size(); c++) {
                // 数值样式字符串转数值（对齐 pandas read_csv 的 dtype 推断）
                String text = c < record.size() ? record.get(c) : null;
                values.add(csvValue(text));
            }
            rows.add(values);
        }
        List<SheetTable> result = new ArrayList<>();
        result.add(new SheetTable("Sheet1", headers, rows));
        return result;
    }

    /** CSV 单元格取值：整数/小数样式的字符串转数值，其余保留文本（空为 null）。 */
    private static Object csvValue(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (text.matches("-?\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return text;
            }
        }
        if (text.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?")) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return text;
            }
        }
        return text;
    }

    /** 建表并批量插入（对齐 SQLBot insert_pg 的 to_sql(if_exists='replace') + copy 语义）。 */
    private void createAndInsert(String tableName, SheetTable table, Map<String, String> fieldTypes) {
        // 先做值转换（失败走 SQLBot 的 i18n_ds_upload_error 约定），再执行 DDL/插入
        List<List<Object>> coerced = new ArrayList<>();
        for (List<Object> row : table.dataRows) {
            List<Object> values = new ArrayList<>();
            for (int c = 0; c < table.headers.size(); c++) {
                String fieldType = fieldTypes.getOrDefault(table.headers.get(c), "string");
                values.add(coerce(c < row.size() ? row.get(c) : null, fieldType, table.headers.get(c)));
            }
            coerced.add(values);
        }
        // 表名/列名取应用库标识符的规范化形态，保证后续未加引号的查询（LLM 生成 SQL）能命中
        String storedTable = engine.appIdentifier(tableName);
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(engine.appQuote(storedTable)).append(" (");
        for (int c = 0; c < table.headers.size(); c++) {
            if (c > 0) {
                ddl.append(", ");
            }
            ddl.append(engine.appQuote(engine.appIdentifier(table.headers.get(c)))).append(" ")
                    .append(columnType(fieldTypes.getOrDefault(table.headers.get(c), "string")));
        }
        ddl.append(")");
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(engine.appQuote(storedTable)).append(" (");
        for (int c = 0; c < table.headers.size(); c++) {
            if (c > 0) {
                sql.append(", ");
            }
            sql.append(engine.appQuote(engine.appIdentifier(table.headers.get(c))));
        }
        sql.append(") VALUES (").append(repeat("?", table.headers.size())).append(")");
        try {
            engine.executeAppDdl("DROP TABLE IF EXISTS " + engine.appQuote(storedTable));
            engine.executeAppDdl(ddl.toString());
            if (!coerced.isEmpty()) {
                List<Object[]> batchArgs = new ArrayList<>();
                for (List<Object> row : coerced) {
                    batchArgs.add(row.toArray());
                }
                appJdbc.batchUpdate(sql.toString(), batchArgs);
            }
        } catch (Exception e) {
            throw new DatasourceException("Insert data failed for " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static String columnType(String fieldType) {
        switch (fieldType) {
            case "int":
                return "BIGINT";
            case "float":
                return "DOUBLE";
            case "datetime":
                return "TIMESTAMP";
            default:
                // 文本列用 VARCHAR：CLOB 在 H2 下 getObject 返回 Clob/Reader，序列化会出现
                // No serializer found for class java.io.BufferedReader
                return "VARCHAR(" + STRING_COLUMN_LENGTH + ")";
        }
    }

    /** 按用户选择的字段类型转换值（对齐 SQLBot read_import_dataframe 的 int 舍入/空值语义）。 */
    private Object coerce(Object value, String fieldType, String columnName) {
        if (value == null) {
            return null;
        }
        try {
            switch (fieldType) {
                case "int":
                    return roundInteger(value);
                case "float":
                    return toDouble(value);
                case "datetime":
                    return toTimestamp(value);
                default:
                    if (value instanceof Double && ((Double) value) == Math.floor((Double) value)) {
                        return String.valueOf(((Double) value).longValue());
                    }
                    return String.valueOf(value);
            }
        } catch (DatasourceException e) {
            throw new DatasourceException("上传文件解析失败: Column '" + columnName + "': " + e.getMessage());
        }
    }

    private Long roundInteger(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1L : 0L;
        }
        BigDecimal number;
        try {
            if (value instanceof Number) {
                number = BigDecimal.valueOf(((Number) value).doubleValue());
            } else {
                String text = String.valueOf(value).trim();
                if (text.isEmpty()) {
                    return null;
                }
                number = new BigDecimal(text);
            }
        } catch (NumberFormatException e) {
            throw new DatasourceException("Invalid numeric value for integer field");
        }
        BigDecimal rounded = number.setScale(0, RoundingMode.HALF_UP);
        try {
            return rounded.longValueExact();
        } catch (ArithmeticException e) {
            throw new DatasourceException("Rounded value is outside the signed 64-bit integer range");
        }
    }

    private Double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new DatasourceException("Invalid numeric value for float field");
        }
    }

    private Timestamp toTimestamp(Object value) {
        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }
        if (value instanceof java.sql.Date) {
            return new Timestamp(((java.sql.Date) value).getTime());
        }
        if (value instanceof Number) {
            return new Timestamp(DateUtil.getJavaCalendar(((Number) value).doubleValue(), false).getTimeInMillis());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"}) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern);
                format.setLenient(false);
                return new Timestamp(format.parse(text).getTime());
            } catch (Exception ignored) {
                // try next pattern
            }
        }
        throw new DatasourceException("Invalid datetime value: " + text);
    }

    // ------------------------------------------------------------------
    // 存储工具
    // ------------------------------------------------------------------

    /** 保存上传文件并返回存储名（{base}_{10 位随机}.{ext}，对齐 SQLBot）。 */
    private String saveUpload(MultipartFile file) {
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        if (!Arrays.asList("xlsx", "xls", "csv").contains(extension)) {
            throw new DatasourceException("Only support .xlsx/.xls/.csv");
        }
        String base = originalName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String filename = base + "_" + randomSuffix() + "." + extension;
        try {
            Files.createDirectories(excelDir);
            file.transferTo(excelDir.resolve(filename).toFile());
        } catch (IOException e) {
            throw new DatasourceException("Save excel failed: " + e.getMessage(), e);
        }
        return filename;
    }

    private Path resolveStored(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            throw new DatasourceException("File not found");
        }
        Path resolved = excelDir.resolve(filePath).normalize();
        if (!resolved.startsWith(excelDir) || !Files.isRegularFile(resolved)) {
            throw new DatasourceException("File not found");
        }
        return resolved;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private static String randomSuffix() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 10);
        }
    }

    /** 仅保留中英文与数字（对齐 SQLBot filter_string）。 */
    static String filterString(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u4e00' && c <= '\u9fa5') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
