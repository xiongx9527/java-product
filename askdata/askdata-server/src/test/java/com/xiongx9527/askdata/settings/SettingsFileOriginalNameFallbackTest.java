package com.xiongx9527.askdata.settings;

import com.xiongx9527.askdata.common.util.Maps;

import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.common.storage.FileStorageRecordRepository;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Io;
import org.dromara.x.file.storage.core.Downloader;
import org.dromara.x.file.storage.core.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 失败明细下载的跨实例回源（验收 A5）。
 *
 * <p>真实缺陷回归：x-file-storage 上传时把存储名重写成随机名（{@code filename}），
 * 展示名（{@code original_filename}）才是 {@code <原文件名>_<随机后缀>_error.xlsx}；
 * 下载接口只拿得到展示名，因此按 {@code filename} 反查必然落空，必须回退到
 * {@code original_filename}。本测试用真实 H2 {@code file_storage} 行，只 mock 对象存储的字节下载。</p>
 */
class SettingsFileOriginalNameFallbackTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;

    private FileStorageService fileStorageService;

    private AskdataFileStorage storage;

    private SettingsFileController controller;

    private static final String URL = "minio://askdata/excel/6ae21586e4b0f8d149e3ee2d.xlsx";

    private static final String STORED_NAME = "6ae21586e4b0f8d149e3ee2d.xlsx";

    private static final String DISPLAY_NAME = "verifier_terms_2026_22e7c936-f_error.xlsx";

    private static final byte[] CONTENT = "error-rows".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:file_storage_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE file_storage (id BIGINT PRIMARY KEY, url VARCHAR(2000) NOT NULL, "
                + "platform VARCHAR(64), path VARCHAR(1000), filename VARCHAR(512), "
                + "original_filename VARCHAR(512), content_type VARCHAR(255), size BIGINT, "
                + "create_time BIGINT NOT NULL DEFAULT 0)");
        // 上传后的真实形态：随机存储名 + 展示名（与生产/容器内数据一致）
        jdbc.update("INSERT INTO file_storage (id, url, platform, path, filename, original_filename, "
                        + "content_type, size, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                947229267427392L, URL, "minio", "excel", STORED_NAME, DISPLAY_NAME,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", (long) CONTENT.length,
                System.currentTimeMillis());

        fileStorageService = Mockito.mock(FileStorageService.class);
        Downloader downloader = Mockito.mock(Downloader.class);
        Mockito.when(downloader.bytes()).thenReturn(CONTENT);
        Mockito.when(fileStorageService.download(eq(URL))).thenReturn(downloader);

        storage = new AskdataFileStorage(fileStorageService);
        FileStorageRecordRepository repository = new FileStorageRecordRepository(jdbc, new IdGenerator());
        setField(storage, "recordRepository", repository);

        controller = new SettingsFileController(new MessageResolver(new StaticMessageSource()));
        setField(controller, "excelPath", tempDir.toString());
        controller.setFileStorage(storage);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void downloadsByDisplayNameEvenWhenStoredNameIsRandomized() throws Exception {
        // 本机没有该文件（请求落到其它实例），只能按展示名回源共享存储
        ResponseEntity<Resource> response = controller.downloadFailInfo(Maps.of("file", DISPLAY_NAME));

        assertArrayEquals(CONTENT, Io.readAll(response.getBody().getInputStream()),
                "应按 original_filename 命中共享存储并返回同一份字节");
    }

    @Test
    void storedNameStillResolvesFirst() {
        // 老路径不回归：上传接口返回的存储名同样可反查
        assertArrayEquals(CONTENT, storage.readByName("excel", STORED_NAME));
        assertArrayEquals(CONTENT, storage.readByName("excel", DISPLAY_NAME));
    }
}
