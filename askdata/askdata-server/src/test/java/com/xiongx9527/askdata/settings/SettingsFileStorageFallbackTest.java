package com.xiongx9527.askdata.settings;

import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 失败明细下载的跨实例回源（验收 A5）：本机没有文件时按文件名从共享存储读取。
 */
class SettingsFileStorageFallbackTest {

    @TempDir
    Path tempDir;

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void downloadsFromSharedStorageWhenLocalFileMissing() throws Exception {
        SettingsFileController controller = new SettingsFileController(
                new MessageResolver(new StaticMessageSource()));
        setField(controller, "excelPath", tempDir.toString());

        AskdataFileStorage storage = Mockito.mock(AskdataFileStorage.class);
        Mockito.when(storage.readByName(eq("excel"), eq("term_error.xlsx")))
                .thenReturn("shared-bytes".getBytes(StandardCharsets.UTF_8));
        controller.setFileStorage(storage);

        ResponseEntity<Resource> response = controller.downloadFailInfo(
                Map.of("file", "term_error.xlsx"));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("shared-bytes", new String(response.getBody().getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
    }
}
