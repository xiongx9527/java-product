package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.storage.FileStorageTestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 助手图片存储：经通用文件存储（x-file-storage 本地）落地；save 返回存储 url，load 凭 url 读回；
 * 扩展名/大小校验、",<flag>" 后缀剥离、未知 fileId 返回 null。
 */
class AssistantFileStorageTest {

    @TempDir
    Path tempDir;

    private AssistantFileStorage storage;

    @BeforeEach
    void setUp() {
        MessageResolver msg = mock(MessageResolver.class);
        when(msg.get(anyString())).thenAnswer(inv -> inv.getArgument(0));
        AssistantFileStorage sfs = new AssistantFileStorage(FileStorageTestFactory.askdata(tempDir),
                tempDir.resolve("legacy").toString());
        // 通过反射注入 mock MessageResolver（@Autowired 字段）
        try {
            java.lang.reflect.Field f = AssistantFileStorage.class.getDeclaredField("messageResolver");
            f.setAccessible(true);
            f.set(sfs, msg);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.storage = sfs;
    }

    @Test
    void saveReturnsStorageUrlAndLoadReadsBack() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "logo.png,1", "image/png", png);
        String fileId = storage.save(file, "logo.png,1");
        assertTrue(fileId.startsWith("assistant"), "fileId 应为存储 url: " + fileId);
        assertTrue(fileId.endsWith(".png"), fileId);

        Resource res = storage.load(fileId);
        assertNotNull(res);
        assertArrayEquals(png, res.getInputStream().readAllBytes());
    }

    @Test
    void rejectsDisallowedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "evil.exe",
                "application/octet-stream", new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> storage.save(file, "evil.exe"));
    }

    @Test
    void rejectsOversize() {
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);
        assertThrows(IllegalArgumentException.class, () -> storage.save(file, "big.png"));
    }

    @Test
    void loadUnknownReturnsNull() {
        assertNull(storage.load("does-not-exist"));
        assertNull(storage.load(null));
        assertNull(storage.load(""));
    }

    @Test
    void contentTypeOf() {
        assertEquals("image/png", AssistantFileStorage.contentTypeOf("assistant/x.png"));
        assertEquals("image/svg+xml", AssistantFileStorage.contentTypeOf("x.svg"));
        assertEquals("image/jpeg", AssistantFileStorage.contentTypeOf("x.jpg"));
    }
}
