package com.xiongx9527.askdata.system;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.AuthChannelFilter;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.common.web.AuthWhitelist;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.controller.AiModelController;
import com.xiongx9527.askdata.system.controller.LoginController;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.UserService;
import com.xiongx9527.askdata.system.web.TokenVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * askdata-sqlbot-remaining-fidelity 验收回归：
 * A1 强制改密链路（F-03）、A3 支线通道审计 uid（F-06）、A6 schema 唯一 DDL（F-18）、
 * F-13 尾斜杠归一化、F-19 审计关联行写入条件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:remaining_fidelity;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "askdata.user.default-password=Default@Passw0rd"
})
class RemainingFidelityTest {

    @Autowired
    private LoginController loginController;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AiModelController aiModelController;

    @Autowired
    private AiModelRepository aiModelRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private AuthWhitelist whitelist;

    @Autowired
    private com.xiongx9527.askdata.system.controller.WorkspaceController workspaceController;

    @BeforeEach
    void setUp() {
        SaTokenContextMockUtil.setMockContext();
        // 种子管理员（id=1）登录：用户管理/审计等用例需要管理员身份
        StpUtil.login(1L);
    }

    @AfterEach
    void tearDown() {
        StpUtil.logout();
        SaTokenContextMockUtil.clearContext();
        // 复位强制改密开关，避免用例间串扰
        jdbc.update("UPDATE sys_arg SET pval = 'false' WHERE pkey = 'login.initial_pwd_disabled'");
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    // ------------------------------------------------------------------
    // A1：强制改密链路（F-03）
    // ------------------------------------------------------------------

    @Test
    void defaultPasswordLoginReturnsNeedChangePwdWithoutToken() {
        jdbc.update("UPDATE sys_arg SET pval = 'true' WHERE pkey = 'login.initial_pwd_disabled'");
        // 新建用户并置为默认口令
        Map<String, Object> created = userService.create(Rows.of(
                "account", "fidelity_default_user",
                "name", "Fidelity Default User",
                "email", "fidelity_default@askdata.com",
                "oid_list", java.util.List.of(1L)));
        long newUid = Rows.lng(created, "id", 0L);
        try {
            // 把该用户口令重置为默认口令
            userRepository.updatePassword(newUid, CryptoUtil.md5Hex("Default@Passw0rd"));

            String usernameCipher = CryptoUtil.encryptSqlbot("fidelity_default_user");
            String passwordCipher = CryptoUtil.encryptSqlbot("Default@Passw0rd");
            Result<TokenVO> resp = loginController.accessToken(usernameCipher, passwordCipher);
            TokenVO vo = resp.getData();
            assertNotNull(vo, "默认口令登录应返回 Token 引导体");
            assertEquals(Boolean.TRUE, vo.getNeed_change_pwd(), "应标记 need_change_pwd=true");
            // 空串 token（对齐 SQLBot access_token=''）：真实 token 尚未签发
            assertTrue(vo.getAccess_token() == null || vo.getAccess_token().isEmpty(),
                    "主 token 不应发放，实际: " + vo.getAccess_token());
        } finally {
            jdbc.update("DELETE FROM sys_user WHERE id = ?", newUid);
            jdbc.update("DELETE FROM sys_user_ws WHERE uid = ?", newUid);
        }
    }

    @Test
    void seedAdminExemptFromForceChangePwd() {
        jdbc.update("UPDATE sys_arg SET pval = 'true' WHERE pkey = 'login.initial_pwd_disabled'");
        // 种子管理员（id=1）豁免（对齐 SQLBot user.id != 1）
        // 注意：种子管理员口令不是默认口令，直接登录验证 need_change_pwd=false 分支
        String usernameCipher = CryptoUtil.encryptSqlbot("admin");
        String passwordCipher = CryptoUtil.encryptSqlbot("SQLBot@123456");
        Result<TokenVO> resp = loginController.accessToken(usernameCipher, passwordCipher);
        TokenVO vo = resp.getData();
        assertNotNull(vo);
        assertEquals(Boolean.FALSE, vo.getNeed_change_pwd(), "种子管理员不应触发改密引导");
        assertNotNull(vo.getAccess_token(), "种子管理员应拿到主 token");
    }

    @Test
    void changePwdRejectsDefaultPasswordWhenInitialDisabled() {
        // 固定英文 locale，断言实际文案而非消息键
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
        jdbc.update("UPDATE sys_arg SET pval = 'true' WHERE pkey = 'login.initial_pwd_disabled'");
        Map<String, Object> created = userService.create(Rows.of(
                "account", "fidelity_chg_user",
                "name", "Fidelity Chg User",
                "email", "fidelity_chg@askdata.com",
                "oid_list", java.util.List.of(1L)));
        long newUid = Rows.lng(created, "id", 0L);
        try {
            // 新建用户口令 = askdata.user.default-password（create 忽略 body.password）
            String account = CryptoUtil.encryptSqlbot("fidelity_chg_user");
            String oldPwd = CryptoUtil.encryptSqlbot("Default@Passw0rd");
            String newPwd = CryptoUtil.encryptSqlbot("Default@Passw0rd");
            BadRequestException ex = assertThrows(BadRequestException.class, () -> {
                Map<String, String> editor = new java.util.LinkedHashMap<>();
                editor.put("account", account);
                editor.put("pwd", oldPwd);
                editor.put("new_pwd", newPwd);
                loginController.changePwd(editor);
            });
            assertEquals("The new password cannot be the initial password", ex.getMessage(),
                    "拒绝文案应来自 i18n_login.new_pwd_is_initial（英文 locale）");
        } finally {
            jdbc.update("DELETE FROM sys_user WHERE id = ?", newUid);
            jdbc.update("DELETE FROM sys_user_ws WHERE uid = ?", newUid);
        }
    }

    @Test
    void changePwdAllowsNewPasswordWhenInitialDisabledOff() {
        // 开关关闭：默认口令语义不参与 changePwd 校验
        jdbc.update("UPDATE sys_arg SET pval = 'false' WHERE pkey = 'login.initial_pwd_disabled'");
        Map<String, Object> created = userService.create(Rows.of(
                "account", "fidelity_chg2_user",
                "name", "Fidelity Chg 2 User",
                "email", "fidelity_chg2@askdata.com"));
        long newUid = Rows.lng(created, "id", 0L);
        try {
            // 新建用户口令由 resolveDefaultPassword() 决定（= askdata.user.default-password）
            String account = CryptoUtil.encryptSqlbot("fidelity_chg2_user");
            String oldPwd = CryptoUtil.encryptSqlbot("Default@Passw0rd");
            String newPwd = CryptoUtil.encryptSqlbot("NewPassw0rd!1");
            Map<String, String> editor = new java.util.LinkedHashMap<>();
            editor.put("account", account);
            editor.put("pwd", oldPwd);
            editor.put("new_pwd", newPwd);
            loginController.changePwd(editor);
            // 新口令落库生效
            Optional<Map<String, Object>> row = userRepository.findById(newUid);
            assertEquals(CryptoUtil.md5Hex("NewPassw0rd!1"),
                    String.valueOf(row.orElseThrow().get("password")));
        } finally {
            jdbc.update("DELETE FROM sys_user WHERE id = ?", newUid);
            jdbc.update("DELETE FROM sys_user_ws WHERE uid = ?", newUid);
        }
    }

    // ------------------------------------------------------------------
    // A3：支线通道审计 uid（F-06，AuditRecorder.currentUid 回退 AuthContext）
    // ------------------------------------------------------------------

    @Test
    void sideChannelAuditUidFallsBackToAuthContext() {
        // 支线通道没有 Sa-Token 会话：先注销 setUp 里的管理员登录，才能验证 AuthContext 回退
        StpUtil.logout();
        AuthContext context = new AuthContext();
        Map<String, Object> user = Rows.of("id", 42L, "name", "side-channel-user");
        context.setCurrentUser(user);
        AuthContext.set(context);
        try {
            auditRecorder.record(AuditOperationType.CREATE, AuditModule.DASHBOARD,
                    "9001", "side-channel-test", "detail", null, "success", null);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT user_id FROM sys_logs WHERE remark = 'side-channel-test' ORDER BY id DESC");
            assertFalse(rows.isEmpty(), "审计行应写入");
            assertEquals(42L, Rows.lng(rows.get(0), "user_id", 0L),
                    "支线通道（无 Sa-Token 会话）的审计 uid 应从 AuthContext 回写");
        } finally {
            AuthContext.clear();
            jdbc.update("DELETE FROM sys_logs WHERE remark = 'side-channel-test'");
            jdbc.update("DELETE FROM sys_logs_resource WHERE resource_id = '9001'");
        }
    }

    // ------------------------------------------------------------------
    // F-19：审计关联行写入条件（resourceId 空串也写关联行 + 逗号多值拆分）
    // ------------------------------------------------------------------

    @Test
    void auditWritesResourceRelationEvenWithoutResourceId() {
        try {
            auditRecorder.record(AuditOperationType.CREATE_OR_UPDATE, AuditModule.SETTING,
                    "", "audit-relation-test", "save", null, "success", null);
            Long logId = jdbc.queryForObject(
                    "SELECT id FROM sys_logs WHERE remark = 'audit-relation-test' ORDER BY id DESC LIMIT 1",
                    Long.class);
            assertNotNull(logId);
            Integer relations = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_logs_resource WHERE log_id = ?",
                    Integer.class, logId);
            assertTrue(relations != null && relations >= 1,
                    "无 resourceId 时也应写 module 缺省关联行（F-19）");
        } finally {
            jdbc.update("DELETE FROM sys_logs_resource WHERE module = 'setting' AND resource_id = '' AND log_id IN "
                    + "(SELECT id FROM sys_logs WHERE remark = 'audit-relation-test')");
            jdbc.update("DELETE FROM sys_logs WHERE remark = 'audit-relation-test'");
        }
    }

    @Test
    void auditWritesRelationPerCommaSeparatedResourceId() {
        try {
            auditRecorder.record(AuditOperationType.DELETE, AuditModule.USER,
                    "7001,7002", "audit-multi-relation-test", "batch", null, "success", null);
            Integer relations = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_logs_resource WHERE resource_id IN ('7001','7002')",
                    Integer.class);
            assertTrue(relations != null && relations == 2,
                    "逗号分隔 resource_id 应各写一条关联行（SQLBot list 语义）");
        } finally {
            jdbc.update("DELETE FROM sys_logs_resource WHERE resource_id IN ('7001','7002')");
            jdbc.update("DELETE FROM sys_logs WHERE remark = 'audit-multi-relation-test'");
        }
    }

    // ------------------------------------------------------------------
    // A6/A9：Flyway baseline DDL 唯一（F-18）+ 全量结构断言
    // ------------------------------------------------------------------

    @Test
    void hostnameSourceTableOnlyDefinedOnce() throws Exception {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "CREATE TABLE(?: IF NOT EXISTS)? sys_logs_resource");
        for (String vendor : java.util.List.of("h2", "postgresql", "mysql")) {
            String schema = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/resources/db/migration/" + vendor + "/V1__baseline.sql"));
            long count = pattern.matcher(schema).results().count();
            assertEquals(1, count, vendor + " 方言中 sys_logs_resource 应只定义一次（F-18）");
        }
    }

    // ------------------------------------------------------------------
    // F-13：多重尾斜杠归一化
    // ------------------------------------------------------------------

    @Test
    void multipleTrailingSlashesNormalized() {
        assertTrue(whitelist.isWhitelistedPath("/mcp"));
        assertTrue(whitelist.isWhitelistedPath("/mcp/"));
        assertTrue(whitelist.isWhitelistedPath("/mcp///"), "多重尾斜杠应归一化命中通配条目");
        assertTrue(whitelist.isWhitelistedPath("/login/access-token/"));
        // 精确条目语义不变
        assertFalse(whitelist.isWhitelistedPath("/system/license/"));
    }

    // ------------------------------------------------------------------
    // A2：system/ 管理端点审计行（uid + 资源名 + 请求路径）
    // ------------------------------------------------------------------

    @Test
    void memberRemovalWritesAuditRowWithResourceName() {
        Map<String, Object> created = userService.create(Rows.of(
                "account", "fidelity_member_user",
                "name", "Fidelity Member User",
                "email", "fidelity_member@askdata.com",
                "oid_list", java.util.List.of(1L)));
        long uid = Rows.lng(created, "id", 0L);
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("DELETE", "/api/v1/system/workspace/uws");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            workspaceController.uwsDelete(Rows.of("oid", 1L, "uid_list", java.util.List.of(uid)));
            List<Map<String, Object>> logs = jdbc.queryForList(
                    "SELECT * FROM sys_logs WHERE module = ? AND operation_type = ? AND resource_id = ?",
                    "member", "delete", String.valueOf(uid));
            assertFalse(logs.isEmpty(), "成员移除应写入审计行");
            Map<String, Object> row = logs.get(0);
            assertEquals(1L, Rows.lng(row, "user_id", 0L), "审计行应带 uid");
            assertEquals("Fidelity Member User", Rows.str(row, "resource_name"), "资源名应取成员姓名");
            assertEquals("/api/v1/system/workspace/uws", Rows.str(row, "request_path"));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            jdbc.update("DELETE FROM sys_user_ws WHERE uid = ?", uid);
            jdbc.update("DELETE FROM sys_user WHERE id = ?", uid);
            jdbc.update("DELETE FROM sys_logs WHERE module = 'member' AND resource_id = ?", String.valueOf(uid));
            jdbc.update("DELETE FROM sys_logs_resource WHERE module = 'member' AND resource_id = ?",
                    String.valueOf(uid));
        }
    }

    @Test
    void systemManagementEndpointWritesAuditRow() {
        long modelId = 991001L;
        jdbc.update("INSERT INTO ai_model (id, api_domain, protocol, supplier, name, model_type, base_model, "
                        + "default_model, config, status, create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                modelId, "https://example.invalid", 1, 1, "audit-probe-model", 1, "gpt-4o-mini",
                false, "[]", 1, System.currentTimeMillis());
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("DELETE", "/api/v1/system/aimodel/" + modelId);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            aiModelController.delete(modelId);
            List<Map<String, Object>> logs = jdbc.queryForList(
                    "SELECT * FROM sys_logs WHERE module = ? AND operation_type = ? AND resource_id = ?",
                    "ai_model", "delete", String.valueOf(modelId));
            assertFalse(logs.isEmpty(), "AI 模型删除应写入审计行");
            Map<String, Object> row = logs.get(0);
            assertEquals(1L, Rows.lng(row, "user_id", 0L), "审计行应带 uid");
            assertEquals("audit-probe-model", Rows.str(row, "resource_name"), "资源名应取模型名");
            assertEquals("DELETE", Rows.str(row, "request_method"), "应记录请求方法");
            assertEquals("/api/v1/system/aimodel/" + modelId, Rows.str(row, "request_path"), "应记录请求路径");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            jdbc.update("DELETE FROM ai_model WHERE id = ?", modelId);
            jdbc.update("DELETE FROM sys_logs WHERE module = 'ai_model' AND resource_id = ?", String.valueOf(modelId));
            jdbc.update("DELETE FROM sys_logs_resource WHERE module = 'ai_model' AND resource_id = ?",
                    String.valueOf(modelId));
        }
    }
}
