package com.xiongx9527.askdata.system;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.controller.LoginController;
import com.xiongx9527.askdata.system.controller.UserController;
import com.xiongx9527.askdata.system.platform.PlatformRepository;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.system.service.UserImportService;
import com.xiongx9527.askdata.system.web.TokenVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户 Excel 批量导入验收（A2，@SpringBootTest + H2 内存库，对齐 DatasourceServiceSmokeTest/ChatContextLoadTest 写法）：
 * ① 模板非空且含正确表头；② 合法 Excel 导入后用户存在且可登录；
 * ③ 含错误行导入拿到 fileId 并下载到含失败行的记录；④ fileId 不存在抛 BadRequest；
 * ⑤ 三端点（template/batchImport/errorRecord）不再抛 not supported。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_batch_import;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "askdata.excel.path=target/user-import-test-excel",
        "askdata.user.default-password=InitPassw0rd123"
})
class UserBatchImportTest {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Path IMPORT_DIR =
            Paths.get("target/user-import-test-excel").toAbsolutePath().normalize();

    @Autowired
    private UserController userController;

    @Autowired
    private UserImportService userImportService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserWsRepository userWsRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private PlatformRepository platformRepository;

    @Autowired
    private LoginController loginController;

    @BeforeEach
    void setUp() throws Exception {
        // 非 Web 测试环境初始化 Sa-Token 上下文，并以种子管理员（id=1, account=admin, oid=1）登录
        SaTokenContextMockUtil.setMockContext();
        StpUtil.login(1L);
        // 失败原因文案走消息包（A4）：固定英文 locale，断言与 Accept-Language 解耦于运行环境
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
        if (Files.isDirectory(IMPORT_DIR)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(IMPORT_DIR)) {
                for (Path path : stream) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @AfterEach
    void tearDown() {
        StpUtil.logout();
        SaTokenContextMockUtil.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    // ① 模板非空且含正确表头
    @Test
    void templateIsDownloadableWithExpectedHeaders() throws Exception {
        ResponseEntity<ByteArrayResource> response = userController.template();
        assertNotNull(response.getBody());
        byte[] content = response.getBody().getByteArray();
        assertTrue(content.length > 0);

        List<String[]> rows = readAll(content);
        assertFalse(rows.isEmpty());
        // F-17 / A23：模板列集与 SQLBot get_i18n_head_list() 一致（8 列），表头按 locale 本地化
        // （英文 locale 下为 i18n_user.* 的英文文案）
        assertTrue(Arrays.asList("Account", "Name", "Email", "Workspace", "Role", "User Status", "User Source",
                "External User Unique Identifier").equals(Arrays.asList(rows.get(0))),
                Arrays.toString(rows.get(0)));
        // 示例行（对齐 SQLBot downTemplate 的两行样例）
        assertTrue(rows.size() >= 3, "template should contain sample rows");
        assertEquals("sqlbot1", rows.get(1)[0]);
        assertEquals("employee1@sqlbot.com", rows.get(1)[2]);
    }

    // ② 合法 Excel 导入后用户存在/可登录
    @Test
    void importValidExcelCreatesLoginableUsers() throws Exception {
        MockMultipartFile file = importFile(
                row("batchuser1", "Batch User 1", "batchuser1@askdata.com"),
                row("batchuser2", "Batch User 2", "batchuser2@askdata.com"));

        Map<String, Object> result = userImportService.batchImport(file);
        // A23：返回字段与 SQLBot UploadResultDTO 一致
        assertEquals(2, result.get("successCount"));
        assertEquals(0, result.get("errorCount"));
        assertNull(result.get("dataKey"), "no failure means no error record");

        Optional<Map<String, Object>> user = userRepository.findByAccount("batchuser1");
        assertTrue(user.isPresent(), "imported user should exist");
        // MD5 口令：SQLBot 列集不含 password，建号用 askdata.user.default-password
        assertEquals(CryptoUtil.md5Hex("InitPassw0rd123"), String.valueOf(user.get().get("password")));
        // oid 归属当前管理员工作空间，登录要求 oid≠0
        assertEquals(1L, ((Number) user.get().get("oid")).longValue());
        assertEquals(1, ((Number) user.get().get("status")).intValue());
        long uid = ((Number) user.get().get("id")).longValue();
        assertTrue(userWsRepository.find(uid, 1L).isPresent(), "imported user should be bound to the workspace");

        // 真实登录链路：加密账号/口令走 LoginController.accessToken
        Result<TokenVO> token = loginController.accessToken(
                CryptoUtil.encryptSqlbot("batchuser1"), CryptoUtil.encryptSqlbot("InitPassw0rd123"));
        assertNotNull(token.getData());
        assertNotNull(token.getData().getAccess_token());
    }

    // ③ 含错误行导入拿到 fileId 并下载到含失败行的记录
    @Test
    void importWithErrorRowsProducesDownloadableErrorRecord() throws Exception {
        // 先建一个账号，用于制造“重复账号”失败行
        Map<String, Object> first = userImportService.batchImport(importFile(
                row("dupuser", "Dup User", "dupuser@askdata.com")));
        assertEquals(1, first.get("successCount"));

        Map<String, Object> result = userImportService.batchImport(importFile(
                row("dupuser", "Dup User", "dupuser@askdata.com"),   // 重复账号
                row("bademail", "Bad Email", "not-an-email"),        // 邮箱格式错误
                row("noname", "", "noname@askdata.com"),             // 必填缺失
                row("gooduser", "Good User", "gooduser@askdata.com")));
        assertEquals(1, result.get("successCount"));
        assertEquals(3, result.get("errorCount"));
        String fileId = String.valueOf(result.get("dataKey"));
        assertNotNull(fileId);
        assertTrue(userRepository.findByAccount("gooduser").isPresent());

        byte[] errorBytes = userImportService.errorRecord(fileId);
        assertTrue(errorBytes.length > 0);
        List<String[]> errorRows = readAll(errorBytes);
        // 表头：行号 + 原文八列（本地化）+ 失败原因
        assertEquals("Row", errorRows.get(0)[0]);
        assertEquals("Account", errorRows.get(0)[1]);
        assertEquals("External User Unique Identifier", errorRows.get(0)[8]);
        assertEquals("Reason", errorRows.get(0)[9]);
        assertTrue(hasError(errorRows, "dupuser", "already exists"));
        assertTrue(hasError(errorRows, "bademail", "Invalid email format"));
        assertTrue(hasError(errorRows, "noname", "Missing name parameter"));
        // 成功行不出现在失败记录中
        assertTrue(errorRows.stream().skip(1).noneMatch(r -> "gooduser".equals(r[1])));
    }

    // ③b F-17：workspace/role/status/origin/platform_user_id 列生效
    @Test
    void importAppliesWorkspaceRoleStatusOriginAndPlatformBinding() throws Exception {
        long wsId = 9001L;
        workspaceRepository.insert(wsId, "分析空间", System.currentTimeMillis());

        Map<String, Object> result = userImportService.batchImport(importFile(new String[] {
                "wsuser2", "Ws User 2", "wsuser2@askdata.com", "分析空间", "ws_admin", "0", "3", "p-42"}));
        assertEquals(1, result.get("successCount"), String.valueOf(result));

        Map<String, Object> user = userRepository.findByAccount("wsuser2").orElseThrow();
        long uid = ((Number) user.get("id")).longValue();
        assertEquals(wsId, ((Number) user.get("oid")).longValue());
        assertEquals(0, ((Number) user.get("status")).intValue());
        assertEquals(3, ((Number) user.get("origin")).intValue());
        assertEquals(1, userWsRepository.weightOf(uid, wsId));
        assertTrue(platformRepository.findBinding(3, "p-42").isPresent(), "platform binding should be written");
    }

    // ③c F-17：workspace 不存在 / role 非法 逐行报错
    @Test
    void importRejectsUnknownWorkspaceAndInvalidRole() throws IOException {
        Map<String, Object> result = userImportService.batchImport(importFile(
                new String[] {"badws", "Bad Ws", "badws@askdata.com", "不存在的空间", "user", "1", "0", ""},
                new String[] {"badrole", "Bad Role", "badrole@askdata.com", "", "root", "1", "0", ""}));
        assertEquals(0, result.get("successCount"));
        assertEquals(2, result.get("errorCount"));

        List<String[]> rows = readAll(userImportService.errorRecord(String.valueOf(result.get("dataKey"))));
        assertTrue(hasError(rows, "badws", "Workspace does not exist"));
        assertTrue(hasError(rows, "badrole", "Invalid role"));
    }

    // ④ fileId 不存在抛 BadRequest
    @Test
    void errorRecordWithUnknownFileIdThrowsBadRequest() {
        BadRequestException exception =
                assertThrows(BadRequestException.class, () -> userImportService.errorRecord("nonexistentfileid"));
        assertTrue(exception.getMessage().contains("not found"), exception.getMessage());
        // 非法 fileId（路径穿越尝试）同样被拒绝
        assertThrows(BadRequestException.class, () -> userImportService.errorRecord("..%2F..%2Fschema"));
    }

    // ⑤ 三端点不再抛 not supported
    @Test
    void threeEndpointsNoLongerThrowNotSupported() throws Exception {
        ResponseEntity<ByteArrayResource> template =
                assertDoesNotThrow(() -> userController.template());
        assertTrue(template.getBody().getByteArray().length > 0);

        Map<String, Object> imported = assertDoesNotThrow(() -> userController.batchImport(importFile(
                row("wsuser", "Ws User", "wsuser@askdata.com")))).getData();
        assertEquals(1, imported.get("successCount"));

        Map<String, Object> failed = userController.batchImport(importFile(
                row("badmail2", "Bad Mail", "nope"))).getData();
        String fileId = String.valueOf(failed.get("dataKey"));
        assertNotNull(fileId);

        ResponseEntity<ByteArrayResource> errorRecord =
                assertDoesNotThrow(() -> userController.errorRecord(fileId));
        assertTrue(errorRecord.getBody().getByteArray().length > 0);
    }

    // ------------------------------------------------------------------
    // 测试工具
    // ------------------------------------------------------------------

    private static final List<String> HEADERS = Arrays.asList(
            "account", "name", "email", "workspace", "role", "status", "origin", "platform_user_id");

    private static MockMultipartFile importFile(String[]... dataRows) throws IOException {
        boolean withPassword = dataRows.length > 0 && dataRows[0].length > HEADERS.size();
        List<ExcelUtils.Column> columns = new java.util.ArrayList<>();
        for (String header : HEADERS) {
            columns.add(new ExcelUtils.Column(header, header));
        }
        if (withPassword) {
            columns.add(new ExcelUtils.Column("password", "password"));
        }
        List<ExcelUtils.MapStringArray> rows = Arrays.stream(dataRows)
                .map(values -> (ExcelUtils.MapStringArray) key -> {
                    int index = HEADERS.indexOf(key);
                    if (index >= 0) {
                        return index < values.length ? values[index] : "";
                    }
                    if ("password".equals(key)) {
                        return values.length > HEADERS.size() ? values[HEADERS.size()] : "";
                    }
                    return "";
                })
                .collect(Collectors.toList());
        byte[] content = ExcelUtils.writeXlsx(columns, rows);
        return new MockMultipartFile("file", "users.xlsx", XLSX_MEDIA_TYPE, content);
    }

    /** 8 列行（account/name/email/workspace/role/status/origin/platform_user_id）。 */
    private static String[] row(String account, String name, String email) {
        return new String[] {account, name, email, "", "user", "1", "0", ""};
    }

    private static List<String[]> readAll(byte[] content) {
        try {
            return ExcelUtils.readRows(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean hasError(List<String[]> errorRows, String account, String reasonPart) {
        return errorRows.stream().skip(1)
                .anyMatch(r -> account.equals(r[1]) && r.length > 9 && r[9].contains(reasonPart));
    }
}
