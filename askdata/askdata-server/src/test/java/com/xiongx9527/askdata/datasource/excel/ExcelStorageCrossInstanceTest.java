package com.xiongx9527.askdata.datasource.excel;

import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.common.storage.StoredFile;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Excel 上传/导入的跨实例语义（验收 A4）：
 * 上传落共享文件存储、导入请求落到另一实例时按文件名回源读取。
 */
class ExcelStorageCrossInstanceTest {

    private static final MessageResolver MESSAGES =
            new MessageResolver(new StaticMessageSource());

    @TempDir
    Path tempDir;

    private JdbcTemplate appJdbc;

    private JdbcEngine engine;

    private AskdataFileStorage storage;

    private ExcelService service;

    @BeforeEach
    void setUp() {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:excel_cluster_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        engine = new JdbcEngine(appJdbc, new ObjectMapper());
        storage = Mockito.mock(AskdataFileStorage.class);
        Mockito.when(storage.enabled()).thenReturn(true);
        service = new ExcelService(appJdbc, engine, tempDir.toString(), MESSAGES, storage);
    }

    private static MockMultipartFile csv(String filename) {
        String content = "id,name\n1,alpha\n2,beta\n";
        return new MockMultipartFile("file", filename, "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadGoesToSharedStorageInsteadOfLocalDir() throws Exception {
        MockMultipartFile file = csv("orders.csv");
        Mockito.when(storage.save(any(byte[].class), anyString(), eq("excel"), anyString()))
                .thenReturn(new StoredFile("/f/excel/x.csv", "x.csv", "orders.csv",
                        "text/csv", 20L, "minio"));
        // 共享存储里已有刚上传的内容（解析预览按文件名回源读取）
        Mockito.when(storage.readByName(eq("excel"), anyString()))
                .thenReturn("id,name\n1,alpha\n2,beta\n".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> parsed = service.parseExcel(file);
        String filePath = Rows.str(parsed, "filePath");
        assertNotNull(filePath);
        // 返回的是平台实际存储名（可能被重命名），导入时按该名回源
        assertEquals("x.csv", filePath);

        // 上传后本机目录不应存在该文件（共享存储才是唯一副本）
        assertTrue(Files.notExists(tempDir.resolve(filePath)),
                "存储可用时不应再写本地上传目录");
        Mockito.verify(storage).save(any(byte[].class), anyString(), eq("excel"), anyString());
    }

    @Test
    void importReadsFileFromSharedStorageWhenLocalCopyMissing() {
        // 模拟另一实例：本机没有该文件，只能按文件名回源共享存储
        Mockito.when(storage.save(any(byte[].class), anyString(), eq("excel"), anyString()))
                .thenReturn(new StoredFile("/f/excel/x.csv", "x.csv", "orders.csv",
                        "text/csv", 20L, "minio"));
        Mockito.when(storage.readByName(eq("excel"), anyString()))
                .thenReturn("id,name\n1,alpha\n2,beta\n".getBytes(StandardCharsets.UTF_8));

        // 先解析拿到真实 sheet 名与字段类型（前端就是这样把预览结果回传给导入接口的）
        Map<String, Object> parsed = service.parseExcel(csv("orders.csv"));
        String filename = Rows.str(parsed, "filePath");
        assertTrue(Files.notExists(tempDir.resolve(filename)));

        List<Map<String, Object>> sheets = new java.util.ArrayList<>();
        for (Map<String, Object> sheet : Rows.mapList(parsed.get("data"))) {
            List<Map<String, Object>> fields = new java.util.ArrayList<>();
            for (Map<String, Object> field : Rows.mapList(sheet.get("fields"))) {
                fields.add(Maps.of("fieldName", Rows.str(field, "fieldName"),
                        "fieldType", Rows.str(field, "fieldType")));
            }
            sheets.add(Maps.of("sheetName", Rows.str(sheet, "sheetName"), "fields", fields));
        }

        Map<String, Object> result = service.importToDb(filename, sheets);

        List<Map<String, Object>> imported = Rows.mapList(result.get("sheets"));
        assertEquals(1, imported.size());
        assertEquals(2, ((Number) imported.get(0).get("rows")).intValue());
    }
}
