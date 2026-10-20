package com.xiongx9527.askdata.datasource.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.context.support.StaticMessageSource;

/**
 * Excel 上传/解析/导入建表/备注导入导出单测（A5 excel 类型的离线证据）。
 */
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.context.support.StaticMessageSource;

class ExcelServiceTest {

    private static final MessageResolver messageResolver = new MessageResolver(new StaticMessageSource());

    @TempDir
    Path tempDir;

    private JdbcTemplate appJdbc;

    private JdbcEngine engine;

    private ExcelService excelService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:excel_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        engine = new JdbcEngine(appJdbc, objectMapper);
        excelService = new ExcelService(appJdbc, engine, tempDir.toString(), messageResolver);
    }

    /**
     * 构造 xlsx：约定 sheetData[0] 为 sheet 名，其后每行为 "单元格,单元格,..."；
     * 数字样式的字符串写为数值单元格，日期样式（yyyy-MM-dd HH:mm:ss）写为日期单元格，其余为文本。
     */
    private MockMultipartFile xlsx(String filename, String[]... sheets) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            for (String[] sheetData : sheets) {
                Sheet sheet = workbook.createSheet(sheetData[0]);
                for (int i = 1; i < sheetData.length; i++) {
                    String[] cells = sheetData[i].split(",", -1);
                    Row row = sheet.createRow(i - 1);
                    for (int c = 0; c < cells.length; c++) {
                        String value = cells[c];
                        Cell cell = row.createCell(c);
                        if (value.matches("-?\\d+")) {
                            cell.setCellValue(Long.parseLong(value));
                        } else if (value.matches("-?\\d+\\.\\d+")) {
                            cell.setCellValue(Double.parseDouble(value));
                        } else if (value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                            cell.setCellValue(new java.sql.Timestamp(
                                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value).getTime()));
                            cell.setCellStyle(dateStyle);
                        } else {
                            cell.setCellValue(value);
                        }
                    }
                }
            }
            workbook.write(output);
            return new MockMultipartFile("file", filename,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    @Test
    void parseExcelInfersTypesAndPreview() throws Exception {
        MockMultipartFile file = xlsx("demo.xlsx",
                new String[]{"订单", "id,名称,金额,时间,备注",
                        "1,键盘,99.50,2026-01-15 10:30:00,x",
                        "2,鼠标,12,2026-02-20 08:00:00,",
                        "3,,7.25,2026-03-01 12:00:00,ok"});
        Map<String, Object> parsed = excelService.parseExcel(file);
        assertNotNull(parsed.get("filePath"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");
        assertEquals(1, data.size());
        Map<String, Object> sheet = data.get(0);
        assertEquals("订单", sheet.get("sheetName"));
        assertEquals(3, sheet.get("rows"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) sheet.get("fields");
        assertEquals("id", fields.get(0).get("fieldName"));
        assertEquals("int", fields.get(0).get("fieldType"));
        assertEquals("string", fields.get(1).get("fieldType"));
        assertEquals("float", fields.get(2).get("fieldType"));
        assertEquals("datetime", fields.get(3).get("fieldType"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) sheet.get("data");
        assertEquals(3, rows.size());
        assertEquals(1L, ((Number) rows.get(0).get("id")).longValue());
        assertEquals(99.5, ((Number) rows.get(0).get("金额")).doubleValue(), 0.0001);
        assertEquals("2026-01-15 10:30:00", rows.get(0).get("时间"));
    }

    @Test
    void importToDbCreatesAppTablesAndRows() throws Exception {
        MockMultipartFile file = xlsx("demo.xlsx",
                new String[]{"订单", "id,名称,金额,时间",
                        "1,键盘,99.50,2026-01-15 10:30:00",
                        "2,鼠标,12,2026-02-20 08:00:00"});
        Map<String, Object> parsed = excelService.parseExcel(file);
        String filePath = Rows.str(parsed, "filePath");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) parsed.get("data")).get(0).get("fields");

        Map<String, Object> result = excelService.importToDb(filePath,
                Collections.singletonList(Rows.of("sheetName", "订单", "fields", fields)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) result.get("sheets");
        assertEquals(1, created.size());
        String tableName = Rows.str(created.get(0), "tableName");
        assertTrue(tableName.startsWith(engine.appIdentifier("excel_订单_")), tableName);
        assertEquals(2, created.get(0).get("rows"));

        assertTrue(engine.appTableExists(tableName));
        assertEquals(2L, appJdbc.queryForObject("SELECT COUNT(*) FROM \"" + tableName + "\"", Long.class));
        // 列类型映射（int→BIGINT、string→VARCHAR、float→DOUBLE、datetime→TIMESTAMP）
        List<com.xiongx9527.askdata.datasource.engine.ColumnSchema> columns =
                engine.fields(DatasourceType.EXCEL, new DatasourceConf(), tableName);
        assertEquals(4, columns.size());
        assertTrue(columns.get(0).getFieldType().toUpperCase().contains("BIGINT"));
        assertTrue(columns.get(3).getFieldType().toUpperCase().contains("TIMESTAMP"));
        // 时间列可读回
        Object stored = appJdbc.queryForObject("SELECT MAX(" + engine.appIdentifier("时间") + ") FROM \""
                + tableName + "\"", Object.class);
        assertNotNull(stored);

        Files.delete(tempDir.resolve(filePath));
        assertThrows(DatasourceException.class, () -> excelService.importToDb(filePath,
                Collections.singletonList(Rows.of("sheetName", "订单", "fields", fields))));
    }

    @Test
    void uploadExcelCreatesTables() throws Exception {
        MockMultipartFile file = xlsx("直接上传.xlsx",
                new String[]{"用户", "id,name,score", "1,alice,9.5", "2,bob,8"});
        Map<String, Object> result = excelService.uploadExcel(file);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sheets = (List<Map<String, Object>>) result.get("sheets");
        assertEquals(1, sheets.size());
        String tableName = Rows.str(sheets.get(0), "tableName");
        assertTrue(tableName.startsWith(engine.appIdentifier("用户_")), tableName);
        assertEquals(2L, appJdbc.queryForObject("SELECT COUNT(*) FROM \"" + tableName + "\"", Long.class));
    }

    /**
     * 回归：Excel 导入建表后必须能被真实查询命中（A5 excel 链路核心）。
     *
     * <p>修复前建表用带引号的小写名，而 getTables 返回的名字经 execSql 走未加引号标识符时被 H2
     * 折叠为大写，报 {@code Table "EXCEL_XXX" not found}；此处用未加引号的 SQL（LLM 生成形态）
     * 覆盖 getTables → getFields → previewData → execSql 全链路。</p>
     */
    @Test
    void importedTableIsQueryableAndPreviewableWithoutQuoting() throws Exception {
        MockMultipartFile file = xlsx("query.xlsx",
                new String[]{"sales", "id,name,amount,note",
                        "1,alice,9.5,first",
                        "2,bob,8,second"});
        Map<String, Object> parsed = excelService.parseExcel(file);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) parsed.get("data")).get(0).get("fields");
        Map<String, Object> imported = excelService.importToDb(Rows.str(parsed, "filePath"),
                Collections.singletonList(Rows.of("sheetName", "sales", "fields", fields)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) imported.get("sheets");
        String tableName = Rows.str(created.get(0), "tableName");
        DatasourceConf conf = new DatasourceConf();

        // getTables / getFields（元数据面）
        assertTrue(engine.tables(DatasourceType.EXCEL, conf).stream()
                .anyMatch(item -> tableName.equals(item.getTableName())), tableName);
        List<com.xiongx9527.askdata.datasource.engine.ColumnSchema> columns =
                engine.fields(DatasourceType.EXCEL, conf, tableName);
        assertEquals(4, columns.size());

        // execSql：未加引号标识符（与 LLM 生成的 SQL 形态一致）
        Map<String, Object> executed = engine.execSql(DatasourceType.EXCEL, conf,
                "SELECT id, name, amount, note FROM " + tableName + " ORDER BY id");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) executed.get("data");
        assertEquals(2, rows.size());
        assertEquals("alice", rows.get(0).get("NAME"));

        // previewData：按 getFields 返回的字段名拼预览 SQL
        List<String> fieldNames = new ArrayList<>();
        for (com.xiongx9527.askdata.datasource.engine.ColumnSchema column : columns) {
            fieldNames.add(column.getFieldName());
        }
        Map<String, Object> preview = engine.execSql(DatasourceType.EXCEL, conf,
                DatasourceType.EXCEL.previewSql(fieldNames, tableName, conf, null));
        assertEquals(2, ((List<?>) preview.get("data")).size());

        // 结果必须可 JSON 序列化（CLOB 列曾导致 No serializer found for BufferedReader）
        String json = objectMapper.writeValueAsString(preview);
        assertTrue(json.contains("alice"), json);
        assertTrue(json.contains("first"), json);
    }

    @Test
    void csvParsedAndImported() throws Exception {
        String csv = "id,name,amount\n1,键盘,99.5\n2,鼠标,12\n";
        MockMultipartFile file = new MockMultipartFile("file", "data.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> parsed = excelService.parseExcel(file);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");
        assertEquals("Sheet1", data.get(0).get("sheetName"));
        assertEquals(2, data.get(0).get("rows"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get(0).get("fields");
        assertEquals("int", fields.get(0).get("fieldType"));
        assertEquals("float", fields.get(2).get("fieldType"));

        Map<String, Object> result = excelService.importToDb(Rows.str(parsed, "filePath"),
                Collections.singletonList(Rows.of("sheetName", "Sheet1", "fields", fields)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) result.get("sheets");
        String tableName = Rows.str(created.get(0), "tableName");
        assertTrue(tableName.startsWith(engine.appIdentifier("excel_Sheet1_")), tableName);
        assertEquals(2L, appJdbc.queryForObject("SELECT COUNT(*) FROM \"" + tableName + "\"", Long.class));
    }

    @Test
    void integerColumnRejectsNonNumeric() throws Exception {
        MockMultipartFile file = xlsx("bad.xlsx",
                new String[]{"t", "id,name", "1,ok", "abc,bad"});
        Map<String, Object> parsed = excelService.parseExcel(file);
        // 用户在详情弹窗选择 int 类型后提交（对齐前端 importToDb 请求）
        List<Map<String, Object>> fields = Arrays.asList(
                Rows.of("fieldName", "id", "fieldType", "int"),
                Rows.of("fieldName", "name", "fieldType", "string"));
        DatasourceException ex = assertThrows(DatasourceException.class,
                () -> excelService.importToDb(Rows.str(parsed, "filePath"),
                        Collections.singletonList(Rows.of("sheetName", "t", "fields", fields))));
        assertTrue(ex.getMessage().contains("Invalid numeric value for integer field"), ex.getMessage());
        assertFalse(ex.getMessage().contains("Insert data failed"));
    }

    @Test
    void schemaWorkbookExportAndUploadRoundTrip() throws Exception {
        List<ExcelService.SchemaTable> tables = Collections.singletonList(new ExcelService.SchemaTable("Sheet0", "orders", "订单表",
                Arrays.asList(field("id", "订单ID"), field("amount", "金额"))));
        byte[] workbook = excelService.buildSchemaWorkbook(tables);
        assertTrue(workbook.length > 0);
        assertTrue(excelService.buildSchemaTemplate().length > 0);

        List<ExcelService.SchemaTable> updated = Collections.singletonList(new ExcelService.SchemaTable("Sheet0", "orders", "新表备注",
                Collections.singletonList(field("id", "新字段备注"))));
        MockMultipartFile upload = new MockMultipartFile("file", "schema.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excelService.buildSchemaWorkbook(updated));
        ExcelService.SchemaUpload parsedUpload = excelService.readSchemaWorkbook(upload);
        assertEquals(1, parsedUpload.tableRows.size());
        assertEquals("orders", parsedUpload.tableRows.get(0).get("表名"));
        assertEquals("新表备注", parsedUpload.tableRows.get(0).get("表备注"));
        assertEquals(1, parsedUpload.fieldSheets.size());
        assertEquals("Sheet0", parsedUpload.fieldSheets.get(0).get("sheetName"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> fieldRows = (List<Map<String, String>>) parsedUpload.fieldSheets.get(0).get("rows");
        assertEquals("新字段备注", fieldRows.get(0).get("字段备注"));

        MockMultipartFile txt = new MockMultipartFile("file", "schema.txt", "text/plain", "x".getBytes());
        assertThrows(DatasourceException.class, () -> excelService.readSchemaWorkbook(txt));
    }

    @Test
    void filterStringKeepsChineseAlnum() {
        assertEquals("订单abc123", ExcelService.filterString("订单 abc-123!"));
        assertEquals("", ExcelService.filterString("---"));
    }

    @Test
    void longColumnNameRejected() throws Exception {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            longName.append('x');
        }
        MockMultipartFile file = xlsx("long.xlsx", new String[]{"t", longName + ",b", "1,2"});
        Map<String, Object> parsed = excelService.parseExcel(file);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) parsed.get("data")).get(0).get("fields");
        DatasourceException ex = assertThrows(DatasourceException.class,
                () -> excelService.importToDb(Rows.str(parsed, "filePath"),
                        Collections.singletonList(Rows.of("sheetName", "t", "fields", fields))));
        assertTrue(ex.getMessage().contains("exceeds 63 bytes"), ex.getMessage());
    }

    @Test
    void dateCellImportedAsTimestamp() throws Exception {
        MockMultipartFile file = xlsx("dates.xlsx",
                new String[]{"t", "id,ts", "1,2026-01-15 10:30:00"});
        Map<String, Object> parsed = excelService.parseExcel(file);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) parsed.get("data")).get(0).get("fields");
        assertEquals("datetime", fields.get(1).get("fieldType"));
        Map<String, Object> result = excelService.importToDb(Rows.str(parsed, "filePath"),
                Collections.singletonList(Rows.of("sheetName", "t", "fields", fields)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) result.get("sheets");
        String tableName = Rows.str(created.get(0), "tableName");
        Object ts = appJdbc.queryForObject("SELECT \"" + engine.appIdentifier("ts") + "\" FROM \""
                + tableName + "\"", Object.class);
        assertNotNull(ts);
        assertTrue(ts instanceof java.sql.Timestamp || ts instanceof Date, ts.getClass().getName());
    }

    private static Map<String, String> field(String name, String comment) {
        Map<String, String> field = new LinkedHashMap<>();
        field.put("fieldName", name);
        field.put("fieldComment", comment);
        return field;
    }

    @Test
    void importToDbLongTextUsesVarchar65535() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            sb.append('\u4e2d');
        }
        String longText = sb.toString();
        MockMultipartFile file = xlsx("long.xlsx",
                new String[]{"\u6570\u636e", "\u5907\u6ce8", longText});
        Map<String, Object> parsed = excelService.parseExcel(file);
        String filePath = Rows.str(parsed, "filePath");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) parsed.get("data")).get(0).get("fields");
        Map<String, Object> result = excelService.importToDb(filePath,
                Collections.singletonList(Rows.of("sheetName", "\u6570\u636e", "fields", fields)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) result.get("sheets");
        String tableName = Rows.str(created.get(0), "tableName");
        assertTrue(tableName.startsWith(engine.appIdentifier("excel_\u6570\u636e_")), tableName);
        List<com.xiongx9527.askdata.datasource.engine.ColumnSchema> columns =
                engine.fields(DatasourceType.EXCEL, new DatasourceConf(), tableName);
        assertEquals(1, columns.size());
        // 列类型为 VARCHAR 系（H2 下为 CHARACTER VARYING，非 CLOB）
        String fieldType = columns.get(0).getFieldType().toUpperCase();
        assertTrue(fieldType.contains("VARCHAR") || fieldType.contains("VARYING"),
                columns.get(0).getFieldType());
        String stored = appJdbc.queryForObject(
                "SELECT \"" + engine.appIdentifier("\u5907\u6ce8") + "\" FROM \"" + tableName + "\"", String.class);
        assertEquals(longText, stored);
    }

    @Test
    void importToDbRollsBackTableOnInsertFailure() throws Exception {
        JdbcTemplate failingJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:excel_rollback;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
                throw new RuntimeException("forced insert failure");
            }
        };
        JdbcEngine failingEngine = new JdbcEngine(failingJdbc, objectMapper);
        ExcelService failingService = new ExcelService(failingJdbc, failingEngine, tempDir.toString(), messageResolver);

        MockMultipartFile file = xlsx("demo.xlsx",
                new String[]{"\u8ba2\u5355", "id,\u540d\u79f0",
                        "1,\u952e\u76d8",
                        "2,\u9f20\u6807"});
        Map<String, Object> parsed = failingService.parseExcel(file);
        String filePath = Rows.str(parsed, "filePath");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) parsed.get("data")).get(0).get("fields");

        assertThrows(DatasourceException.class, () -> failingService.importToDb(filePath,
                Collections.singletonList(Rows.of("sheetName", "\u8ba2\u5355", "fields", fields))));

        Integer count = failingJdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE 'EXCEL%'", Integer.class);
        assertEquals(0, count == null ? 0 : count);
    }
}
