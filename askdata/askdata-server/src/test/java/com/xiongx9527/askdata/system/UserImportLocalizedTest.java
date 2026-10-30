package com.xiongx9527.askdata.system;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.controller.UserController;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.system.service.UserImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A23 用户导入兼容验收（@SpringBootTest + H2）：
 * ① 模板表头与示例值本地化（中文枚举）；② 模板下载后直接填写中文枚举即可导入；
 * ③ 返回字段为 SQLBot 的 successCount/errorCount/dataKey；④ 非法行返回行号与原因、合法行照常导入；
 * ⑤ 导入后的状态/来源/角色映射正确；⑥ 5 个 locale 的表头都能识别。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_import_localized;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "askdata.excel.path=target/user-import-localized-excel",
        "askdata.user.default-password=InitPassw0rd123"
})
class UserImportLocalizedTest {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 中文模板表头（对齐 SQLBot locales/zh-CN.json 的 i18n_user.*）。 */
    private static final List<String> ZH_HEADERS = Arrays.asList(
            "账号", "姓名", "邮箱", "工作空间", "角色", "用户状态", "用户来源", "外部用户唯一标识");

    @Autowired
    private UserController userController;

    @Autowired
    private UserImportService userImportService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserWsRepository userWsRepository;

    @Autowired
    private MessageResolver messageResolver;

    @BeforeEach
    void setUp() {
        SaTokenContextMockUtil.setMockContext();
        StpUtil.login(1L);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void tearDown() {
        StpUtil.logout();
        SaTokenContextMockUtil.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    // ① 模板本地化表头 + 中文示例枚举
    @Test
    void templateUsesLocalizedHeadersAndChineseEnums() throws Exception {
        List<String[]> rows = readAll(userController.template().getBody().getByteArray());
        assertFalse(rows.isEmpty());
        assertEquals(ZH_HEADERS, Arrays.asList(rows.get(0)), "模板表头应为中文本地化文案");
        assertTrue(rows.size() >= 3, "模板应含两行示例");
        // 示例行：角色 普通成员/管理员、状态 已启用/已禁用、来源 本地创建、工作空间 默认工作空间
        assertEquals("普通成员", rows.get(1)[4]);
        assertEquals("已启用", rows.get(1)[5]);
        assertEquals("本地创建", rows.get(1)[6]);
        assertEquals("默认工作空间", rows.get(1)[3]);
        assertEquals("管理员", rows.get(2)[4]);
        assertEquals("已禁用", rows.get(2)[5]);
    }

    // ② 模板可直接导入（中文枚举不再被判非法）
    @Test
    void templateRoundTripWithChineseEnumsImportsSuccessfully() throws Exception {
        List<String[]> templateRows = readAll(userController.template().getBody().getByteArray());
        List<String> headers = Arrays.asList(templateRows.get(0));

        // 用模板自身的表头（中文）+ 模板示例里的中文枚举构造导入文件
        Map<String, Object> result = userImportService.batchImport(file(headers, new String[][] {
                {"zh_user1", "张三", "zh_user1@askdata.com", "默认工作空间", "管理员", "已启用", "本地创建", ""},
                {"zh_user2", "李四", "zh_user2@askdata.com", "", "普通成员", "已禁用", "本地创建", ""}
        }));

        // ③ 返回字段与 SQLBot UploadResultDTO 一致
        assertEquals(2, result.get("successCount"), String.valueOf(result));
        assertEquals(0, result.get("errorCount"), String.valueOf(result));
        assertNull(result.get("dataKey"), "无失败行时 dataKey 为空");
        assertFalse(result.containsKey("success"), "不再返回 success（SQLBot 字段名为 successCount）");
        assertFalse(result.containsKey("fileId"), "不再返回 fileId（SQLBot 字段名为 dataKey）");

        // ⑤ 状态 / 来源 / 角色映射
        Map<String, Object> admin = userRepository.findByAccount("zh_user1").orElseThrow(IllegalStateException::new);
        long adminUid = ((Number) admin.get("id")).longValue();
        assertEquals(1, ((Number) admin.get("status")).intValue(), "已启用 → status=1");
        assertEquals(0, ((Number) admin.get("origin")).intValue(), "本地创建 → origin=0");
        assertEquals(1L, ((Number) admin.get("oid")).longValue(), "默认工作空间 → 调用者工作空间");
        assertEquals(1, userWsRepository.weightOf(adminUid, 1L), "管理员 → weight=1");

        Map<String, Object> member = userRepository.findByAccount("zh_user2").orElseThrow(IllegalStateException::new);
        long memberUid = ((Number) member.get("id")).longValue();
        assertEquals(0, ((Number) member.get("status")).intValue(), "已禁用 → status=0");
        assertEquals(0, userWsRepository.weightOf(memberUid, 1L), "普通成员 → weight=0");
    }

    // ④ 非法行返回行号与原因，合法行仍导入
    @Test
    void invalidRowReportsRowNumberAndReasonWhileValidRowImports() throws Exception {
        List<String> headers = ZH_HEADERS;
        Map<String, Object> result = userImportService.batchImport(file(headers, new String[][] {
                {"zh_ok", "合法用户", "zh_ok@askdata.com", "", "普通成员", "已启用", "本地创建", ""},
                {"zh_bad", "非法用户", "zh_bad@askdata.com", "", "普通成员", "未知状态", "本地创建", ""}
        }));
        assertEquals(1, result.get("successCount"), String.valueOf(result));
        assertEquals(1, result.get("errorCount"), String.valueOf(result));
        String dataKey = (String) result.get("dataKey");
        assertNotNull(dataKey, "有失败行时必须返回 dataKey");
        assertTrue(userRepository.findByAccount("zh_ok").isPresent(), "合法行仍应导入");

        List<String[]> errorRows = readAll(userImportService.errorRecord(dataKey));
        assertEquals("行号", errorRows.get(0)[0], "失败记录首列为行号");
        assertEquals("账号", errorRows.get(0)[1], "失败记录沿用本地化表头");
        assertEquals("失败原因", errorRows.get(0)[9], "失败记录末列为原因");
        String[] failed = errorRows.get(1);
        assertEquals("3", failed[0], "非法行在 Excel 中的行号（含表头）");
        assertEquals("zh_bad", failed[1]);
        assertTrue(failed[9].contains("状态不合法"), "失败原因应可读: " + failed[9]);
    }

    // ⑥ 5 个 locale 的本地化表头都能识别（前端语言与模板语言不一致时不整体失败）
    @Test
    void headersFromOtherLocalesAreAccepted() throws Exception {
        List<String> traditional = new ArrayList<>();
        for (String key : UserImportService.IMPORT_HEADER_KEYS) {
            traditional.add(messageResolver.get(key, Locale.TRADITIONAL_CHINESE));
        }
        assertFalse(traditional.equals(ZH_HEADERS), "繁体表头应与简体不同");
        Map<String, Object> result = userImportService.batchImport(file(traditional, new String[][] {
                {"tw_user", "王五", "tw_user@askdata.com", "默認工作空間", "管理員",
                        messageResolver.get("i18n_user.status_enabled", Locale.TRADITIONAL_CHINESE),
                        messageResolver.get("i18n_user.local_creation", Locale.TRADITIONAL_CHINESE), ""}
        }));
        assertEquals(1, result.get("successCount"), String.valueOf(result));
        Map<String, Object> user = userRepository.findByAccount("tw_user").orElseThrow(IllegalStateException::new);
        assertEquals(1, ((Number) user.get("status")).intValue());
        assertEquals(1, userWsRepository.weightOf(((Number) user.get("id")).longValue(), 1L));
    }

    // 表头非法时整体拒绝（对齐 SQLBot validate_head）
    @Test
    void unknownHeadersRejected() throws Exception {
        List<String> broken = new ArrayList<>(ZH_HEADERS);
        broken.set(0, "user_name_x");
        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> userImportService.batchImport(file(broken, new String[][] {
                        {"u", "n", "u@askdata.com", "", "普通成员", "已启用", "本地创建", ""}
                })));
        assertNotNull(exception.getMessage());
    }

    // ------------------------------------------------------------------

    private static MockMultipartFile file(List<String> headers, String[][] dataRows) throws IOException {
        List<ExcelUtils.Column> columns = new ArrayList<>();
        for (String header : headers) {
            columns.add(new ExcelUtils.Column(header, header));
        }
        List<ExcelUtils.MapStringArray> rows = Arrays.stream(dataRows)
                .map(values -> (ExcelUtils.MapStringArray) key -> {
                    int index = headers.indexOf(key);
                    return index >= 0 && index < values.length ? values[index] : "";
                })
                .collect(Collectors.toList());
        byte[] content = ExcelUtils.writeXlsx(columns, rows);
        return new MockMultipartFile("file", "users.xlsx", XLSX_MEDIA_TYPE, content);
    }

    private static List<String[]> readAll(byte[] content) {
        try {
            return ExcelUtils.readRows(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
