package com.xiongx9527.askdata.system;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.controller.LoginController;
import com.xiongx9527.askdata.system.controller.UserController;
import com.xiongx9527.askdata.system.repository.UserRepository;
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
        "askdata.excel.path=target/user-import-test-excel"
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
    private LoginController loginController;

    @BeforeEach
    void setUp() throws Exception {
        // 非 Web 测试环境初始化 Sa-Token 上下文，并以种子管理员（id=1, account=admin, oid=1）登录
        SaTokenContextMockUtil.setMockContext();
        StpUtil.login(1L);
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
        assertTrue(Arrays.asList("account", "name", "password", "email")
                .equals(Arrays.asList(rows.get(0))), Arrays.toString(rows.get(0)));
        // 示例行（对齐 SQLBot downTemplate 的两行样例）
        assertTrue(rows.size() >= 3, "template should contain sample rows");
        assertEquals("sqlbot1", rows.get(1)[0]);
        assertEquals("employee1@sqlbot.com", rows.get(1)[3]);
    }

    // ② 合法 Excel 导入后用户存在/可登录
    @Test
    void importValidExcelCreatesLoginableUsers() throws Exception {
        MockMultipartFile file = importFile(
                row("batchuser1", "Batch User 1", "Passw0rd123", "batchuser1@askdata.com"),
                row("batchuser2", "Batch User 2", "Passw0rd123", "batchuser2@askdata.com"));

        Map<String, Object> result = userImportService.batchImport(file);
        assertEquals(2, result.get("total"));
        assertEquals(2, result.get("success"));
        assertEquals(0, result.get("failed"));
        assertNull(result.get("fileId"), "no failure means no error record");

        Optional<Map<String, Object>> user = userRepository.findByAccount("batchuser1");
        assertTrue(user.isPresent(), "imported user should exist");
        // MD5 口令（与 LoginController 登录校验一致）
        assertEquals(CryptoUtil.md5Hex("Passw0rd123"), String.valueOf(user.get().get("password")));
        // oid 归属当前管理员工作空间，登录要求 oid≠0
        assertEquals(1L, ((Number) user.get().get("oid")).longValue());
        assertEquals(1, ((Number) user.get().get("status")).intValue());
        long uid = ((Number) user.get().get("id")).longValue();
        assertTrue(userWsRepository.find(uid, 1L).isPresent(), "imported user should be bound to the workspace");

        // 真实登录链路：加密账号/口令走 LoginController.accessToken
        Result<TokenVO> token = loginController.accessToken(
                CryptoUtil.encryptSqlbot("batchuser1"), CryptoUtil.encryptSqlbot("Passw0rd123"));
        assertNotNull(token.getData());
        assertNotNull(token.getData().getAccess_token());
    }

    // ③ 含错误行导入拿到 fileId 并下载到含失败行的记录
    @Test
    void importWithErrorRowsProducesDownloadableErrorRecord() throws Exception {
        // 先建一个账号，用于制造“重复账号”失败行
        Map<String, Object> first = userImportService.batchImport(importFile(
                row("dupuser", "Dup User", "Passw0rd123", "dupuser@askdata.com")));
        assertEquals(1, first.get("success"));

        Map<String, Object> result = userImportService.batchImport(importFile(
                row("dupuser", "Dup User", "Passw0rd123", "dupuser@askdata.com"),   // 重复账号
                row("bademail", "Bad Email", "Passw0rd123", "not-an-email"),        // 邮箱格式错误
                row("noname", "", "Passw0rd123", "noname@askdata.com"),             // 必填缺失
                row("gooduser", "Good User", "Passw0rd123", "gooduser@askdata.com")));
        assertEquals(4, result.get("total"));
        assertEquals(1, result.get("success"));
        assertEquals(3, result.get("failed"));
        String fileId = String.valueOf(result.get("fileId"));
        assertNotNull(fileId);
        assertTrue(userRepository.findByAccount("gooduser").isPresent());

        byte[] errorBytes = userImportService.errorRecord(fileId);
        assertTrue(errorBytes.length > 0);
        List<String[]> errorRows = readAll(errorBytes);
        // 表头：行号 + 原文四列 + 失败原因
        assertEquals("row", errorRows.get(0)[0]);
        assertEquals("account", errorRows.get(0)[1]);
        assertEquals("reason", errorRows.get(0)[5]);
        assertTrue(hasError(errorRows, "dupuser", "already exists"));
        assertTrue(hasError(errorRows, "bademail", "Email format is invalid"));
        assertTrue(hasError(errorRows, "noname", "name is required"));
        // 成功行不出现在失败记录中
        assertTrue(errorRows.stream().skip(1).noneMatch(r -> "gooduser".equals(r[1])));
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
                row("wsuser", "Ws User", "Passw0rd123", "wsuser@askdata.com")))).getData();
        assertEquals(1, imported.get("success"));

        Map<String, Object> failed = userController.batchImport(importFile(
                row("badmail2", "Bad Mail", "Passw0rd123", "nope"))).getData();
        String fileId = String.valueOf(failed.get("fileId"));
        assertNotNull(fileId);

        ResponseEntity<ByteArrayResource> errorRecord =
                assertDoesNotThrow(() -> userController.errorRecord(fileId));
        assertTrue(errorRecord.getBody().getByteArray().length > 0);
    }

    // ------------------------------------------------------------------
    // 测试工具
    // ------------------------------------------------------------------

    private static MockMultipartFile importFile(String[]... dataRows) throws IOException {
        List<ExcelUtils.Column> columns = Arrays.asList(
                new ExcelUtils.Column("account", "account"),
                new ExcelUtils.Column("name", "name"),
                new ExcelUtils.Column("password", "password"),
                new ExcelUtils.Column("email", "email"));
        List<ExcelUtils.MapStringArray> rows = Arrays.stream(dataRows)
                .map(values -> (ExcelUtils.MapStringArray) key -> {
                    if ("account".equals(key)) {
                        return values[0];
                    }
                    if ("name".equals(key)) {
                        return values[1];
                    }
                    if ("password".equals(key)) {
                        return values[2];
                    }
                    return values[3];
                })
                .collect(Collectors.toList());
        byte[] content = ExcelUtils.writeXlsx(columns, rows);
        return new MockMultipartFile("file", "users.xlsx", XLSX_MEDIA_TYPE, content);
    }

    private static String[] row(String account, String name, String password, String email) {
        return new String[] {account, name, password, email};
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
                .anyMatch(r -> account.equals(r[1]) && r.length > 5 && r[5].contains(reasonPart));
    }
}
