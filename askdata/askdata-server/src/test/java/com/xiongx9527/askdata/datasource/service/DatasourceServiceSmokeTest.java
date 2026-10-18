package com.xiongx9527.askdata.datasource.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import com.xiongx9527.askdata.datasource.excel.ExcelService;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.RecommendedProblemRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.support.DatasourceCrypto;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源服务全周期 smoke（A5/A12 离线证据，H2 引擎）：
 * schema.sql 种子 → 演示数据源可读 → H2 数据源 add/get/list/check/表字段/勾选/预览/执行/注释/同步/
 * 更新/删除 → excel 上传建表全周期 → 表关系 → 推荐问题 → schema 备注导入导出。
 */
class DatasourceServiceSmokeTest {

    private static final String TARGET_URL = "jdbc:h2:mem:svc_target;MODE=MySQL;DB_CLOSE_DELAY=-1";

    @TempDir
    Path excelDir;

    private JdbcTemplate appJdbc;

    private DatasourceService datasourceService;

    private TableRelationService tableRelationService;

    private RecommendedProblemService recommendedProblemService;

    private JdbcEngine engine;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // 每个测试方法独立内存库（DB_CLOSE_DELAY=-1 下同名库跨方法共享，故加唯一后缀）
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:svc_app_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        // 目标 H2 数据源库
        try (Connection connection = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS \"orders\" ("
                    + "\"id\" BIGINT PRIMARY KEY, \"user_id\" BIGINT, \"amount\" DECIMAL(10,2), "
                    + "\"status\" VARCHAR(20))");
            statement.execute("COMMENT ON TABLE \"orders\" IS '订单表'");
            statement.execute("DELETE FROM \"orders\"");
            statement.execute("INSERT INTO \"orders\" VALUES (1, 100, 99.50, 'PAID'), (2, 101, 12.00, 'NEW')");
        }

        engine = new JdbcEngine(appJdbc, objectMapper);
        ExcelService excelService = new ExcelService(appJdbc, engine, excelDir.toString());
        DatasourceRepository datasourceRepository = new DatasourceRepository(appJdbc, new com.xiongx9527.askdata.common.util.IdGenerator());
        TableRepository tableRepository = new TableRepository(appJdbc, new com.xiongx9527.askdata.common.util.IdGenerator());
        FieldRepository fieldRepository = new FieldRepository(appJdbc, new com.xiongx9527.askdata.common.util.IdGenerator());
        RecommendedProblemRepository problemRepository = new RecommendedProblemRepository(appJdbc,
                new com.xiongx9527.askdata.common.util.IdGenerator());
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", 1L, "account", "admin", "oid", 1L));
        Mockito.when(currentUserService.isAdmin(Mockito.any())).thenReturn(true);
        Mockito.when(currentUserService.oidOf(Mockito.any())).thenReturn(1L);
        Mockito.when(currentUserService.uid()).thenReturn(1L);
        Mockito.doNothing().when(currentUserService).requireAdmin();
        Mockito.doNothing().when(currentUserService).requireWsAdmin();

        datasourceService = new DatasourceService(datasourceRepository, tableRepository, fieldRepository,
                excelService, engine, currentUserService, objectMapper);
        tableRelationService = new TableRelationService(datasourceRepository, objectMapper);
        recommendedProblemService = new RecommendedProblemService(problemRepository, datasourceRepository,
                currentUserService, objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS \"orders\"");
        }
    }

    private String frontendConfiguration(String database) {
        String plain = "{\"host\":\"\",\"port\":0,\"username\":\"sa\",\"password\":\"\",\"database\":\"" + database
                + "\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\",\"sheets\":[],\"mode\":\"\","
                + "\"timeout\":10,\"lowVersion\":false,\"ssl\":false,\"poolSize\":5,\"engine\":\"hive\"}";
        return CryptoUtil.encryptSqlbot(plain);
    }

    // ------------------------------------------------------------------
    // 演示数据源种子（brief D8）
    // ------------------------------------------------------------------

    @Test
    void seededDemoDatasourceReadable() {
        Map<String, Object> ds = datasourceService.get(1);
        assertEquals("askdata_demo 演示库", ds.get("name"));
        assertEquals("mysql", ds.get("type"));
        assertEquals("MySQL", ds.get("type_name"));
        assertEquals("3/3", ds.get("num"));
        // configuration 回前端形态，前端 decrypted() 可还原
        String frontend = String.valueOf(ds.get("configuration"));
        String plain = CryptoUtil.decryptSqlbot(frontend);
        assertTrue(plain.contains("192.168.1.100"), plain);
        assertTrue(plain.contains("askdata_demo"), plain);

        // list / tableList / fieldList
        List<Map<String, Object>> list = datasourceService.list(null);
        assertTrue(list.stream().anyMatch(item -> "askdata_demo 演示库".equals(item.get("name"))));
        List<Map<String, Object>> tables = datasourceService.tableList(1);
        assertEquals(3, tables.size());
        assertEquals("orders", tables.get(0).get("table_name"));
        List<Map<String, Object>> fields = datasourceService.fieldList(1, 1L, Rows.of("fieldName", ""));
        assertEquals(7, fields.size());
        assertEquals("id", fields.get(0).get("field_name"));
        assertEquals("bigint", fields.get(0).get("field_type"));

        // ws 端点
        assertEquals(1, datasourceService.ws(1).size());

        // 演示库不可达：check 按 SQLBot 错误约定抛 500 文本（种子记录不受影响）
        DatasourceException ex = assertThrows(DatasourceException.class, () -> datasourceService.checkById(1));
        assertTrue(ex.getMessage().startsWith("数据源连接失败:"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // H2 数据源全周期（A5）
    // ------------------------------------------------------------------

    @Test
    void h2DatasourceLifecycle() {
        String configuration = frontendConfiguration(TARGET_URL);
        Map<String, Object> created = datasourceService.add(Rows.of(
                "name", "H2 测试库",
                "description", "smoke",
                "type", "h2",
                "configuration", configuration,
                "tables", Collections.singletonList(Rows.of("table_name", "orders", "table_comment", "订单表"))));
        long dsId = Rows.lng(created, "id", 0L);
        assertTrue(dsId > 0);
        assertEquals("1/1", created.get("num"));
        assertEquals("H2", created.get("type_name"));

        // get：configuration 前端形态可解密
        Map<String, Object> got = datasourceService.get(dsId);
        assertTrue(CryptoUtil.decryptSqlbot(String.valueOf(got.get("configuration"))).contains(TARGET_URL));

        // list / getTablesByConf / check
        assertTrue(datasourceService.list(null).stream()
                .anyMatch(item -> "H2 测试库".equals(item.get("name"))));
        List<Map<String, Object>> confTables = datasourceService.getTablesByConf(
                Rows.of("type", "h2", "configuration", configuration));
        assertEquals(1, confTables.size());
        assertEquals("orders", confTables.get(0).get("tableName"));
        assertTrue(datasourceService.check(Rows.of("type", "h2", "configuration", configuration)));

        // 表/字段浏览
        List<Map<String, Object>> tables = datasourceService.tableList(dsId);
        assertEquals(1, tables.size());
        long tableId = Rows.lng(tables.get(0), "id", 0L);
        assertEquals("订单表", tables.get(0).get("table_comment"));
        List<Map<String, Object>> fields = datasourceService.fieldList(dsId, tableId, Rows.of("fieldName", ""));
        assertEquals(4, fields.size());
        assertEquals("amount", fields.get(2).get("field_name"));
        // 字段名模糊匹配
        assertEquals(1, datasourceService.fieldList(dsId, tableId, Rows.of("fieldName", "STAT")).size());

        // 预览数据
        Map<String, Object> preview = datasourceService.previewData(dsId, Rows.of("table", Rows.of("id", tableId)));
        assertEquals(2, ((List<?>) preview.get("data")).size());
        assertTrue(String.valueOf(preview.get("sql")).length() > 0);

        // 执行 SQL（只读）
        Map<String, Object> exec = datasourceService.execSql(dsId, Rows.of("sql", "SELECT COUNT(*) AS \"c\" FROM \"orders\""));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> execRows = (List<Map<String, Object>>) exec.get("data");
        assertEquals(2, ((Number) execRows.get(0).get("c")).intValue());
        // 写操作被拒绝
        assertThrows(DatasourceException.class,
                () -> datasourceService.execSql(dsId, Rows.of("sql", "DELETE FROM \"orders\"")));

        // 编辑表/字段注释与勾选
        datasourceService.editTable(Rows.of("id", tableId, "ds_id", dsId, "checked", false,
                "custom_comment", "订单表(自定义)"));
        tables = datasourceService.tableList(dsId);
        assertEquals("订单表(自定义)", tables.get(0).get("custom_comment"));
        assertFalse((Boolean) tables.get(0).get("checked"));
        long fieldId = Rows.lng(fields.get(0), "id", 0L);
        datasourceService.editField(Rows.of("id", fieldId, "ds_id", dsId, "table_id", tableId, "checked", false,
                "custom_comment", "主键ID"));
        fields = datasourceService.fieldList(dsId, tableId, Rows.of("fieldName", ""));
        assertEquals("主键ID", fields.get(0).get("custom_comment"));

        // 重新勾选后 syncFields 刷新元数据
        datasourceService.editTable(Rows.of("id", tableId, "ds_id", dsId, "checked", true, "custom_comment", "订单表"));
        datasourceService.syncFields(dsId, tableId);
        assertEquals(4, datasourceService.fieldList(dsId, tableId, Rows.of("fieldName", "")).size());

        // chooseTables 重新同步
        datasourceService.chooseTables(dsId, Collections.singletonList(Rows.of("table_name", "orders", "table_comment", "订单表")));
        assertEquals("1/1", datasourceService.get(dsId).get("num"));

        // 更新连接配置（名称变更）
        datasourceService.update(Rows.of("id", dsId, "name", "H2 测试库(改)", "type", "h2",
                "configuration", configuration));
        assertEquals("H2 测试库(改)", datasourceService.get(dsId).get("name"));

        // schema 备注导出/导入
        DatasourceService.ExportFile exported = datasourceService.exportDsSchema(dsId);
        assertTrue(exported.content.length > 0);
        MockMultipartFile schemaFile = new MockMultipartFile("file", "schema.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", exported.content);
        // 用导出的文件回传（幂等）
        assertTrue(datasourceService.uploadDsSchema(dsId, schemaFile));

        // 删除
        datasourceService.delete(dsId, "H2 测试库(改)");
        assertThrows(BadRequestException.class, () -> datasourceService.get(dsId));
        assertThrows(BadRequestException.class, () -> datasourceService.tableList(dsId));
    }

    // ------------------------------------------------------------------
    // Excel 数据源全周期（A5 excel 类型）
    // ------------------------------------------------------------------

    @Test
    void excelDatasourceLifecycle() throws Exception {
        // 构造 xlsx：sheet「订单」，id/名称/金额
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("订单");
            String[] header = {"id", "名称", "金额"};
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                headerRow.createCell(i).setCellValue(header[i]);
            }
            Object[][] rows = {{1L, "键盘", 99.5}, {2L, "鼠标", 12.0}, {3L, "显示器", 1299.0}};
            for (int r = 0; r < rows.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                row.createCell(0).setCellValue(((Number) rows[r][0]).doubleValue());
                row.createCell(1).setCellValue(String.valueOf(rows[r][1]));
                row.createCell(2).setCellValue(((Number) rows[r][2]).doubleValue());
            }
            workbook.write(output);
            MockMultipartFile file = new MockMultipartFile("file", "orders.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());

            // parseExcel → importToDb → add(excel)
            Map<String, Object> parsed = datasourceService.parseExcel(file);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sheetData = (List<Map<String, Object>>) parsed.get("data");
            Map<String, Object> imported = datasourceService.importToDb(Rows.of(
                    "filePath", parsed.get("filePath"),
                    "sheets", Collections.singletonList(Rows.of("sheetName", sheetData.get(0).get("sheetName"),
                            "fields", sheetData.get(0).get("fields")))));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> createdSheets = (List<Map<String, Object>>) imported.get("sheets");
            String tableName = String.valueOf(createdSheets.get(0).get("tableName"));
            // 表名取应用库标识符规范化形态（H2 未加引号标识符折叠为大写）
            assertTrue(tableName.startsWith(engine.appIdentifier("excel_订单_")), tableName);

            String configuration = CryptoUtil.encryptSqlbot("{\"host\":\"\",\"port\":0,\"username\":\"\","
                    + "\"password\":\"\",\"database\":\"\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\","
                    + "\"sheets\":[{\"sheetName\":\"订单\",\"tableName\":\"" + tableName
                    + "\",\"tableComment\":\"\"}],\"mode\":\"\",\"timeout\":30,\"lowVersion\":false,"
                    + "\"ssl\":false,\"poolSize\":5,\"engine\":\"hive\"}");
            Map<String, Object> ds = datasourceService.add(Rows.of(
                    "name", "Excel 订单",
                    "description", "上传导入",
                    "type", "excel",
                    "configuration", configuration,
                    "tables", Collections.singletonList(Rows.of("table_name", tableName, "table_comment", ""))));
            long dsId = Rows.lng(ds, "id", 0L);
            assertEquals("1/1", ds.get("num"));
            assertEquals("Excel/CSV", ds.get("type_name"));

            // excel 表清单以 configuration.sheets 为准
            List<Map<String, Object>> tables = datasourceService.getTables(dsId);
            assertEquals(1, tables.size());
            assertEquals(tableName, tables.get(0).get("tableName"));

            // 字段浏览（应用库元数据）
            List<Map<String, Object>> tableRows = datasourceService.tableList(dsId);
            long tableId = Rows.lng(tableRows.get(0), "id", 0L);
            List<Map<String, Object>> fields = datasourceService.fieldList(dsId, tableId, Rows.of("fieldName", ""));
            assertEquals(3, fields.size());

            // 预览与执行（走应用库）
            Map<String, Object> preview = datasourceService.previewData(dsId, Rows.of("table", Rows.of("id", tableId)));
            assertEquals(3, ((List<?>) preview.get("data")).size());
            Map<String, Object> exec = datasourceService.execSql(dsId,
                    Rows.of("sql", "SELECT COUNT(*) AS \"c\" FROM \"" + tableName + "\""));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> execRows = (List<Map<String, Object>>) exec.get("data");
            assertEquals(3, ((Number) execRows.get(0).get("c")).intValue());

            // 删除 excel 数据源：应用库表被 drop
            datasourceService.delete(dsId, "Excel 订单");
            assertFalse(engine.appTableExists(tableName));
        }
    }

    // ------------------------------------------------------------------
    // 表关系与推荐问题
    // ------------------------------------------------------------------

    @Test
    void tableRelationAndRecommendedProblem() {
        String configuration = frontendConfiguration(TARGET_URL);
        long dsId = Rows.lng(datasourceService.add(Rows.of(
                "name", "关系库", "description", "", "type", "h2", "configuration", configuration,
                "tables", Collections.singletonList(Rows.of("table_name", "orders", "table_comment", "")))), "id", 0L);

        // 表关系 save/get
        List<Object> empty = tableRelationService.get(dsId);
        assertTrue(empty.isEmpty());
        List<Map<String, Object>> relation = Collections.singletonList(Rows.of("shape", "edge", "source", Rows.of("cell", 1)));
        tableRelationService.save(dsId, relation);
        assertEquals(1, tableRelationService.get(dsId).size());

        // 推荐问题：关闭态 → 开启态
        Map<String, Object> baseOff = recommendedProblemService.base(dsId);
        assertEquals(1, baseOff.get("recommended_config"));
        assertEquals(null, baseOff.get("questions"));
        recommendedProblemService.save(Rows.of("datasource_id", dsId, "recommended_config", 2,
                "problemInfo", Arrays.asList(Rows.of("question", "每个状态的订单数", "sort", 0), Rows.of("question", "总金额"))));
        List<Map<String, Object>> problems = recommendedProblemService.list(dsId);
        assertEquals(2, problems.size());
        assertEquals("每个状态的订单数", problems.get(0).get("question"));
        Map<String, Object> baseOn = recommendedProblemService.base(dsId);
        assertEquals(2, baseOn.get("recommended_config"));
        assertTrue(String.valueOf(baseOn.get("questions")).contains("总金额"));
        // 保存即覆盖
        recommendedProblemService.save(Rows.of("datasource_id", dsId, "recommended_config", 1, "problemInfo", new ArrayList<>()));
        assertEquals(0, recommendedProblemService.list(dsId).size());
        assertEquals(1, recommendedProblemService.base(dsId).get("recommended_config"));
    }

    // ------------------------------------------------------------------
    // 错误约定
    // ------------------------------------------------------------------

    @Test
    void errorConventions() {
        // 重名
        String configuration = frontendConfiguration(TARGET_URL);
        datasourceService.add(Rows.of("name", "重名库", "description", "", "type", "h2",
                "configuration", configuration, "tables", new ArrayList<>()));
        assertThrows(DatasourceException.class, () -> datasourceService.add(
                Rows.of("name", "重名库", "description", "", "type", "h2", "configuration", configuration,
                        "tables", new ArrayList<>())));
        // 未知类型
        assertThrows(DatasourceException.class, () -> datasourceService.add(
                Rows.of("name", "坏类型", "description", "", "type", "oracle_x",
                        "configuration", configuration, "tables", new ArrayList<>())));
        // 不存在的数据源
        assertThrows(BadRequestException.class, () -> datasourceService.get(999999L));
        // 预览空表 id
        Map<String, Object> empty = datasourceService.previewData(1, Rows.of("table", Rows.of()));
        assertEquals(0, ((List<?>) empty.get("data")).size());
        // 非归属数据源（模拟普通用户）
        CurrentUserService normal = Mockito.mock(CurrentUserService.class);
        Mockito.when(normal.currentUser()).thenReturn(Rows.of("id", 2L, "account", "u", "oid", 2L));
        Mockito.when(normal.isAdmin(Mockito.any())).thenReturn(false);
        Mockito.when(normal.oidOf(Mockito.any())).thenReturn(2L);
        DatasourceService normalService = new DatasourceService(
                new DatasourceRepository(appJdbc, new com.xiongx9527.askdata.common.util.IdGenerator()),
                new TableRepository(appJdbc, new com.xiongx9527.askdata.common.util.IdGenerator()),
                new FieldRepository(appJdbc, new com.xiongx9527.askdata.common.util.IdGenerator()),
                new ExcelService(appJdbc, engine, excelDir.toString()), engine, normal, objectMapper);
        assertThrows(BadRequestException.class, () -> normalService.get(1));
    }
}
