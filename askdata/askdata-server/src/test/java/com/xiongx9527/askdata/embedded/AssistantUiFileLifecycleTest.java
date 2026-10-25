package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;

/**
 * A24：助手 UI 图片生命周期（对齐 SQLBot apps/system/api/assistant.py 的 ui 端点）——
 * 上传仅 jpg/png 且 ≤10MB；替换时先删旧文件；本次未下发的图片键清空（置空 + 删文件）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:assistant_ui;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class AssistantUiFileLifecycleTest {

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private AssistantFileStorage fileStorage;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CurrentUserService currentUserService;

    private long assistantId;

    @BeforeEach
    void setUp() throws Exception {
        doNothing().when(currentUserService).requireWsAdmin();
        jdbc.update("DELETE FROM sys_assistant");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("oid", 3001L);
        config.put("name", "keep-me");
        assistantId = System.nanoTime();
        jdbc.update("INSERT INTO sys_assistant (id, name, type, domain, description, configuration, "
                        + "create_time, app_id, app_secret, oid, enable_custom_model, custom_model) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                assistantId, "app", 0, "example.com", "desc",
                objectMapper.writeValueAsString(config), 0L, "app", "secret", 3001L, false, null);
    }

    @Test
    void omittingImageFlagsClearsThemAndDeletesFiles() throws Exception {
        String oldLogo = saveAssistantFile("logo.png,logo");
        String oldIcon = saveAssistantFile("icon.png,float_icon");
        putConfig(config -> {
            config.put("logo", oldLogo);
            config.put("float_icon", oldIcon);
        });

        assistantService.updateUi("{\"id\":" + assistantId + ",\"name\":\"renamed\"}", null);

        Map<String, Object> after = readConfig();
        assertTrue(after.containsKey("logo"), "键应存在但置空: " + after);
        assertNull(after.get("logo"));
        assertNull(after.get("float_icon"));
        assertEquals("renamed", after.get("name"), "非图片字段仍应落库");
        assertNull(fileStorage.load(oldLogo), "清空后旧 logo 文件应已删除");
        assertNull(fileStorage.load(oldIcon), "清空后旧 float_icon 文件应已删除");
    }

    @Test
    void replacingFileDeletesPreviousOne() throws Exception {
        String oldLogo = saveAssistantFile("logo.png,logo");
        String oldIcon = saveAssistantFile("icon.png,float_icon");
        putConfig(config -> {
            config.put("logo", oldLogo);
            config.put("float_icon", oldIcon);
        });

        MockMultipartFile fresh = new MockMultipartFile("file", "new.png,logo", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 5, 5, 5});
        assistantService.updateUi("{\"id\":" + assistantId + "}",
                List.<MultipartFile>of(fresh));

        Map<String, Object> after = readConfig();
        String newLogo = String.valueOf(after.get("logo"));
        assertNotNull(newLogo);
        assertFalse(newLogo.isEmpty());
        assertTrue(newLogo.endsWith(".png"), newLogo);
        assertFalse(oldLogo.equals(newLogo), "应写入新文件");
        assertNull(fileStorage.load(oldLogo), "替换后旧 logo 文件应已删除");
        assertNotNull(fileStorage.load(newLogo), "新 logo 文件应可读");
        assertNull(after.get("float_icon"), "本次未下发 float_icon，应置空");
        assertNull(fileStorage.load(oldIcon), "旧 float_icon 文件应已删除");
    }

    @Test
    void unsupportedFileFlagIsRejected() throws Exception {
        MockMultipartFile bad = new MockMultipartFile("file", "x.png,background", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertThrows(BadRequestException.class,
                () -> assistantService.updateUi("{\"id\":" + assistantId + "}",
                        List.<MultipartFile>of(bad)));
    }

    @Test
    void svgUploadIsRejected() {
        MockMultipartFile svg = new MockMultipartFile("file", "evil.svg,logo", "image/svg+xml",
                "<svg onload=alert(1)/>".getBytes());
        assertThrows(RuntimeException.class,
                () -> assistantService.updateUi("{\"id\":" + assistantId + "}",
                        List.<MultipartFile>of(svg)));
    }

    private String saveAssistantFile(String originalName) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", originalName, "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4});
        return fileStorage.save(file, originalName);
    }

    private void putConfig(Consumer<Map<String, Object>> mutator) throws Exception {
        Map<String, Object> config = readConfig();
        mutator.accept(config);
        jdbc.update("UPDATE sys_assistant SET configuration = ? WHERE id = ?",
                objectMapper.writeValueAsString(config), assistantId);
    }

    private Map<String, Object> readConfig() throws Exception {
        String raw = jdbc.queryForObject("SELECT configuration FROM sys_assistant WHERE id = ?",
                String.class, assistantId);
        return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }
}
