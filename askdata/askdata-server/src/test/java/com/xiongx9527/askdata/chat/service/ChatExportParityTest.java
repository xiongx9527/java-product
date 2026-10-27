package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.repository.RecommendedQuestionRepository;
import com.xiongx9527.askdata.chat.support.ChatExcel;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.xiongx9527.askdata.permission.DsGrantService;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7 导出与图表配置对齐（端到端，离线证据）：{@code ChatService.exportExcel} 按
 * <b>完整图表配置</b>还原导出字段（对齐 SQLBot {@code export_excel}）：
 * columns → axis.x → axis.y（多指标）→ axis.series，取值支持 {@code alias.column}
 * 短列名解析，大数无科学计数法，预测数据按同列序追加。
 */
class ChatExportParityTest {

    private JdbcTemplate appJdbc;

    private ObjectMapper objectMapper = new ObjectMapper();

    private IdGenerator ids = new IdGenerator();

    private ChatRepository chatRepository;

    private ChatService chatService;

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat_export_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        chatRepository = new ChatRepository(appJdbc, ids, objectMapper);
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", 1L, "account", "admin", "oid", 1L));
        Mockito.when(currentUserService.isAdmin(Mockito.any())).thenReturn(true);
        Mockito.when(currentUserService.oidOf(Mockito.any())).thenReturn(1L);
        chatService = new ChatService(chatRepository, new RecommendedQuestionRepository(appJdbc, ids),
                Mockito.mock(DatasourceRepository.class), Mockito.mock(DatasourceService.class),
                currentUserService, objectMapper, Mockito.mock(DsGrantService.class),
                new com.xiongx9527.askdata.common.i18n.MessageResolver(new org.springframework.context.support.StaticMessageSource()));
    }

    /** 建会话 + 记录，写入 data / chart / predict_data 快照。 */
    private long newRecord(String dataJson, String chartJson, String predictDataJson) {
        long chatId = chatRepository.insertChat(1L, 1L, "导出测试", 7L, "H2", "h2", 1, "各城市销售额");
        long recordId = chatRepository.insertRecord(chatId, "各城市销售额", 1L, 1L, 7L, false, null);
        chatRepository.updateRecordField(recordId, "data", dataJson);
        if (chartJson != null) {
            chatRepository.updateRecordField(recordId, "chart", chartJson);
        }
        if (predictDataJson != null) {
            chatRepository.updateRecordField(recordId, "predict_data", predictDataJson);
        }
        return recordId;
    }

    private static Sheet readSheet(byte[] content) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            return workbook.getSheetAt(0);
        }
    }

    @Test
    void exportRestoresFieldsFromFullChartConfiguration() throws Exception {
        String data = "{\"fields\":[\"status\",\"_u2.city\",\"cnt\",\"amount\"],"
                + "\"data\":[{\"status\":\"PAID\",\"_u2.city\":\"北京\",\"cnt\":2,"
                + "\"amount\":1.23e20}]}";
        String chart = "{\"type\":\"column\",\"title\":\"各城市销售额\","
                + "\"columns\":[{\"name\":\"状态\",\"value\":\"status\"},"
                + "{\"name\":\"城市\",\"value\":\"_u2.city\"}],"
                + "\"axis\":{\"x\":{\"name\":\"城市\",\"value\":\"_u2.city\"},"
                + "\"y\":[{\"name\":\"订单数\",\"value\":\"cnt\"},"
                + "{\"name\":\"金额\",\"value\":\"amount\"}],"
                + "\"series\":{\"name\":\"状态\",\"value\":\"status\"}}}";
        long recordId = newRecord(data, chart,
                "[{\"status\":\"FORECAST\",\"city\":\"上海\",\"cnt\":3,\"amount\":2.5e11}]");

        ChatExcel.ExportFile file = chatService.exportExcel(recordId);

        assertEquals("各城市销售额.xlsx", file.filename);
        Sheet sheet = readSheet(file.content);
        // 列序 = 完整图表配置还原（columns → x → y 多指标 → series，去重保序）
        assertEquals("状态", sheet.getRow(0).getCell(0).getStringCellValue());
        assertEquals("城市", sheet.getRow(0).getCell(1).getStringCellValue());
        assertEquals("订单数", sheet.getRow(0).getCell(2).getStringCellValue());
        assertEquals("金额", sheet.getRow(0).getCell(3).getStringCellValue());
        // 表格数据行：alias.column 按短列名解析；大数无科学计数法
        assertEquals("PAID", sheet.getRow(1).getCell(0).getStringCellValue());
        assertEquals("北京", sheet.getRow(1).getCell(1).getStringCellValue());
        // A19：数字列写为数值单元格
        assertEquals(2D, sheet.getRow(1).getCell(2).getNumericCellValue(), 0.0001);
        assertEquals("123000000000000000000", sheet.getRow(1).getCell(3).getStringCellValue());
        // 预测数据按同列序追加（city 短名同样可解析；2.5e11 ≥ 导出阈值 1e11 → 十进制字符串）
        assertEquals("FORECAST", sheet.getRow(2).getCell(0).getStringCellValue());
        assertEquals("上海", sheet.getRow(2).getCell(1).getStringCellValue());
        assertEquals(3D, sheet.getRow(2).getCell(2).getNumericCellValue(), 0.0001);
        assertEquals("250000000000", sheet.getRow(2).getCell(3).getStringCellValue());
    }

    @Test
    void exportFallsBackToResultFieldsWhenChartMissing() throws Exception {
        String data = "{\"fields\":[\"status\",\"amount\"],"
                + "\"data\":[{\"status\":\"PAID\",\"amount\":1.23e20}]}";
        long recordId = newRecord(data, null, null);

        Sheet sheet = readSheet(chatService.exportExcel(recordId).content);

        assertEquals("status", sheet.getRow(0).getCell(0).getStringCellValue());
        assertEquals("amount", sheet.getRow(0).getCell(1).getStringCellValue());
        assertEquals("123000000000000000000", sheet.getRow(1).getCell(1).getStringCellValue());
    }

    @Test
    void exportFallsBackWhenChartJsonIsInvalid() throws Exception {
        String data = "{\"fields\":[\"status\"],\"data\":[{\"status\":\"PAID\"}]}";
        long recordId = newRecord(data, "not-a-json", null);
        Sheet sheet = readSheet(chatService.exportExcel(recordId).content);
        assertEquals("status", sheet.getRow(0).getCell(0).getStringCellValue());
        assertEquals("PAID", sheet.getRow(1).getCell(0).getStringCellValue());
    }

    @Test
    void exportEmptyDataIsRejected() {
        long recordId = newRecord("{\"fields\":[\"status\"],\"data\":[]}", null, null);
        BadRequestException error = assertThrows(BadRequestException.class, () -> chatService.exportExcel(recordId));
        assertTrue(error.getMessage().contains("数据为空"), error.getMessage());
    }

    @Test
    void exportedChartConfigRoundTripsThroughChartFields() throws Exception {
        // 图表配置（含多指标 y）→ 落库 → 导出列序与 chartFields 一致
        String chart = "{\"type\":\"line\",\"title\":\"t\","
                + "\"columns\":[{\"name\":\"d\",\"value\":\"d\"}],"
                + "\"axis\":{\"x\":{\"name\":\"d\",\"value\":\"d\"},"
                + "\"y\":[{\"name\":\"a\",\"value\":\"a\"},{\"name\":\"b\",\"value\":\"b\"}]}}";
        long recordId = newRecord(
                "{\"fields\":[\"d\",\"a\",\"b\"],\"data\":[{\"d\":\"1\",\"a\":1,\"b\":2}]}",
                chart, null);
        Sheet sheet = readSheet(chatService.exportExcel(recordId).content);
        assertEquals("d", sheet.getRow(0).getCell(0).getStringCellValue());
        assertEquals("a", sheet.getRow(0).getCell(1).getStringCellValue());
        assertEquals("b", sheet.getRow(0).getCell(2).getStringCellValue());
        assertEquals("1", sheet.getRow(1).getCell(0).getStringCellValue());
        assertEquals(1D, sheet.getRow(1).getCell(1).getNumericCellValue(), 0.0001);
        assertEquals(2D, sheet.getRow(1).getCell(2).getNumericCellValue(), 0.0001);
    }
}
