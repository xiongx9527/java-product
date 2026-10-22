package com.xiongx9527.askdata.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用文件存储（x-file-storage 本地平台，离线）：save / read / exists / delete round-trip，
 * MultipartFile 保存、扩展名/大小校验、contentType 推断。
 */
class AskdataFileStorageTest {

    @TempDir
    Path tempDir;

    private AskdataFileStorage storage() {
        return FileStorageTestFactory.askdata(tempDir);
    }

    @Test
    void saveBytesReadDeleteRoundTrip() {
        AskdataFileStorage storage = storage();
        byte[] content = "hello askdata".getBytes(StandardCharsets.UTF_8);
        StoredFile saved = storage.save(content, "greeting.txt", "docs", "text/plain");
        assertNotNull(saved.url());
        assertTrue(saved.filename().endsWith(".txt"), saved.filename());
        assertEquals("local", saved.platform());

        assertTrue(storage.exists(saved.url()));
        assertArrayEquals(content, storage.read(saved.url()));

        assertNotNull(storage.info(saved.url()));
        assertEquals(content.length, storage.info(saved.url()).getSize().intValue());

        assertTrue(storage.delete(saved.url()));
        assertFalse(storage.exists(saved.url()));
    }

    @Test
    void saveMultipartFile() {
        AskdataFileStorage storage = storage();
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", png);
        StoredFile saved = storage.save(file, "assistant");
        assertTrue(saved.filename().endsWith(".png"), saved.filename());
        assertEquals("logo.png", saved.originalFilename());
        assertArrayEquals(png, storage.read(saved.url()));
    }

    @Test
    void urlMustBelongToStorage() {
        AskdataFileStorage storage = storage();
        assertEquals(null, storage.info("https://evil.example.com/secret"));
        assertFalse(storage.exists("https://evil.example.com/secret"));
    }

    @Test
    void contentTypeOf() {
        assertEquals("image/png", AskdataFileStorage.contentTypeOf("a.png"));
        assertEquals("image/svg+xml", AskdataFileStorage.contentTypeOf("a.SVG"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                AskdataFileStorage.contentTypeOf("book.xlsx"));
        assertEquals("application/octet-stream", AskdataFileStorage.contentTypeOf("noext"));
        assertEquals("", AskdataFileStorage.extensionOf("noext"));
        assertEquals("png", AskdataFileStorage.extensionOf("/a/b/c.png"));
    }

    @Test
    void requireExtensionRejectsDisallowed() {
        assertThrows(AskdataFileStorage.StorageException.class,
                () -> AskdataFileStorage.requireExtension("evil.exe", AskdataFileStorage.IMAGE_EXT));
        AskdataFileStorage.requireExtension("ok.png", AskdataFileStorage.IMAGE_EXT);
    }

    @Test
    void requireSizeRejectsOversize() {
        assertThrows(AskdataFileStorage.StorageException.class,
                () -> AskdataFileStorage.requireSize(11L * 1024 * 1024, 10L * 1024 * 1024));
        AskdataFileStorage.requireSize(1024L, 10L * 1024 * 1024);
    }
}
