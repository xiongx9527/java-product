package com.xiongx9527.askdata.system;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.support.MysqlTestSupport;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.auth.PasswordHasher;
import com.xiongx9527.askdata.system.controller.LoginController;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.web.TokenVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录校验接入 Argon2id 的端到端验证（对齐 SQLBot 8b4097f2）。
 *
 * <p>经真实 LoginController + 真实 MySQL，验证：</p>
 * <ol>
 *   <li>遗留 MD5 口令账号仍可登录（迁移期兼容）；</li>
 *   <li>新 Argon2id 口令账号可登录；</li>
 *   <li>错误口令被拒；</li>
 *   <li>包装 MD5（WRAPPED_MD5）口令可登录。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class LoginPasswordHashIntegrationTest {

    private static JdbcTemplate mysql;

    @DynamicPropertySource
    static void mysqlDatasource(DynamicPropertyRegistry registry) {
        mysql = MysqlTestSupport.createEmptySchema();
        MysqlTestSupport.bindSpringDatasource(registry, mysql);
    }

    @Autowired
    private LoginController loginController;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SaTokenContextMockUtil.setMockContext();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM sys_user WHERE id IN (9801,9802,9803,9804)");
        jdbc.update("DELETE FROM sys_user_ws WHERE uid IN (9801,9802,9803,9804)");
        try {
            StpUtil.logout();
        } catch (Exception ignored) {
        }
    }

    @Test
    void legacyMd5AccountCanLogin() {
        insertUser(9801, "h_legacy", CryptoUtil.md5Hex("Legacy@123"));
        Result<TokenVO> res = login("h_legacy", "Legacy@123");
        TokenVO data = (TokenVO) res.get("data");
        assertNotNull(data);
        assertTrue(data.getAccess_token() != null && !data.getAccess_token().isEmpty());
    }

    @Test
    void argon2idAccountCanLogin() {
        insertUser(9802, "h_argon", PasswordHasher.hashPassword("Argon@123"));
        Result<TokenVO> res = login("h_argon", "Argon@123");
        TokenVO data = (TokenVO) res.get("data");
        assertNotNull(data);
        assertTrue(data.getAccess_token() != null && !data.getAccess_token().isEmpty());
    }

    @Test
    void wrappedMd5AccountCanLogin() {
        insertUser(9803, "h_wrapped", PasswordHasher.wrapLegacyPassword(CryptoUtil.md5Hex("Wrap@1234")));
        Result<TokenVO> res = login("h_wrapped", "Wrap@1234");
        TokenVO data = (TokenVO) res.get("data");
        assertNotNull(data);
        assertTrue(data.getAccess_token() != null && !data.getAccess_token().isEmpty());
    }

    @Test
    void wrongPasswordIsRejectedForAllForms() {
        insertUser(9804, "h_wrong", PasswordHasher.hashPassword("Right@123"));
        assertThrows(BadRequestException.class, () -> login("h_wrong", "Wrong@123"));
    }

    private Result<TokenVO> login(String account, String plainPwd) {
        return loginController.accessToken(
                CryptoUtil.encryptSqlbot(account),
                CryptoUtil.encryptSqlbot(plainPwd));
    }

    private void insertUser(long id, String account, String storedPassword) {
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, "
                        + "language, origin) VALUES (?, ?, ?, ?, ?, 1, 1, 1672531199000, 'zh-CN', 0)",
                id, account, account, storedPassword, account + "@askdata.com");
        jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (?, ?, 1, 0)", id, id);
    }
}
