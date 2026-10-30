package com.xiongx9527.askdata.system;

import com.xiongx9527.askdata.common.util.Lists;
import com.xiongx9527.askdata.common.util.Io;
import com.xiongx9527.askdata.common.util.Maps;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.LogoutRequest;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.embedded.AssistantFileStorage;
import com.xiongx9527.askdata.system.authentication.SsoAuthenticator;
import com.xiongx9527.askdata.system.controller.LoginController;
import com.xiongx9527.askdata.system.controller.UserController;
import com.xiongx9527.askdata.system.service.AiModelService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.service.UserService;
import com.xiongx9527.askdata.system.service.VariableService;
import com.xiongx9527.askdata.system.web.UserInfoVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A24 会话与系统细节验收：
 * ① 访问 token 默认 8 天且可通过配置调整；② SSO 登出返回 IdP 登出地址；
 * ③ 用户搜索保留相关性排序；④ 内置系统变量可删除；⑤ 助手图片校验类型与大小；
 * ⑥ 模型连通性使用 protocol/Azure 配置；⑦ isAdmin 按角色判定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:system_details;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "askdata.excel.path=target/system-details-excel",
        "askdata.user.default-password=InitPassw0rd123",
        // 覆盖默认 8 天：验证 sa-token.timeout 由配置驱动
        "sa-token.timeout=1209600"
})
class SystemDetailsTest {

    private static final long EIGHT_DAYS_SECONDS = 8L * 24 * 3600;

    @Autowired
    private LoginController loginController;

    @Autowired
    private UserController userController;

    @Autowired
    private UserService userService;

    @Autowired
    private VariableService variableService;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private AssistantFileStorage assistantFileStorage;

    @Autowired
    private MessageResolver messageResolver;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SaTokenContextMockUtil.setMockContext();
        StpUtil.login(1L);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        StpUtil.logout();
        SaTokenContextMockUtil.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    // ① token 有效期：默认 8 天（application.yaml）+ 可配置
    @Test
    void accessTokenTtlDefaultsToEightDaysAndIsConfigurable() throws Exception {
        // 默认值：application.yaml 中 sa-token.timeout 的缺省表达式
        String yaml;
        try (InputStream in = new ClassPathResource("application.yaml").getInputStream()) {
            yaml = new String(Io.readAll(in), StandardCharsets.UTF_8);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("timeout:\\s*\\$\\{ASKDATA_TOKEN_TIMEOUT:(\\d+)}")
                .matcher(yaml);
        assertTrue(matcher.find(), "application.yaml 应提供 ASKDATA_TOKEN_TIMEOUT 覆盖点");
        assertEquals(EIGHT_DAYS_SECONDS, Long.parseLong(matcher.group(1)),
                "token 默认有效期应为 8 天（SQLBot ACCESS_TOKEN_EXPIRE_MINUTES = 60*24*8）");
        assertTrue(yaml.contains("ASKDATA_TOKEN_TIMEOUT"), "应可通过环境变量调整");

        // 配置生效：本测试上下文覆盖为 14 天
        assertEquals(1209600, SaManager.getConfig().getTimeout(),
                "sa-token.timeout 应驱动实际会话有效期");
        // 过期后前端按 401 重新登录（token 超时来自同一配置）
        assertTrue(StpUtil.getTokenTimeout() > 0);
    }

    // ② SSO 登出联动 IdP 登出端点
    @Test
    void ssoLogoutReturnsIdpLogoutUrl() {
        // 本地登录：不跳转 IdP
        assertNull(loginController.logout(logoutRequest(0L)).getData(), "本地登出无 IdP 跳转地址");

        // CAS 来源：按 idpUri 派生 /logout
        jdbc.update("DELETE FROM sys_authentication WHERE id = ?", (long) SsoAuthenticator.TYPE_CAS);
        jdbc.update("INSERT INTO sys_authentication (id, name, type, config, enable, valid, create_time) "
                        + "VALUES (?,?,?,?,?,?,?)",
                (long) SsoAuthenticator.TYPE_CAS, "CAS", SsoAuthenticator.TYPE_CAS,
                "{\"idpUri\":\"https://cas.example.com/cas/\",\"casCallbackDomain\":\"https://app.example.com/cb\"}",
                true, true, System.currentTimeMillis());
        try {
            StpUtil.login(1L);
            String casUrl = loginController.logout(logoutRequest((long) SsoAuthenticator.TYPE_CAS)).getData();
            assertNotNull(casUrl, "SSO 来源登出应返回 IdP 登出地址");
            assertTrue(casUrl.startsWith("https://cas.example.com/cas/logout"), casUrl);
            assertTrue(casUrl.contains("service="), "CAS 登出应带 service 回跳参数");
            assertFalse(StpUtil.isLogin(), "本地会话仍应被清理");
        } finally {
            jdbc.update("DELETE FROM sys_authentication WHERE id = ?", (long) SsoAuthenticator.TYPE_CAS);
        }

        // OIDC 来源：显式登出地址优先（对齐前端 end_session_endpoint 场景）
        jdbc.update("INSERT INTO sys_authentication (id, name, type, config, enable, valid, create_time) "
                        + "VALUES (?,?,?,?,?,?,?)",
                (long) SsoAuthenticator.TYPE_OIDC, "OIDC", SsoAuthenticator.TYPE_OIDC,
                "{\"logoutUrl\":\"https://idp.example.com/oidc/logout?post_logout_redirect_uri=x\"}",
                true, true, System.currentTimeMillis());
        try {
            StpUtil.login(1L);
            assertEquals("https://idp.example.com/oidc/logout?post_logout_redirect_uri=x",
                    loginController.logout(logoutRequest((long) SsoAuthenticator.TYPE_OIDC)).getData());
        } finally {
            jdbc.update("DELETE FROM sys_authentication WHERE id = ?", (long) SsoAuthenticator.TYPE_OIDC);
        }
    }

    // ③ 用户搜索保留相关性排序
    @Test
    void userSearchKeepsRelevanceOrdering() {
        jdbc.update("DELETE FROM sys_user WHERE id IN (9901,9902,9903,9904,9905)");
        // 命中字段数：rel_other 同时命中 name 与 email
        insertUser(9901L, "rel_zhang", "Other Name", "other1@askdata.com");
        insertUser(9902L, "rel_zhangsan", "Other Name", "other2@askdata.com");
        insertUser(9903L, "rel_other", "zhang san", "zhang@askdata.com");
        // 相似度梯度：精确 / 前缀 / 包含
        insertUser(9904L, "solo_kw", "Solo", "solo1@askdata.com");
        insertUser(9905L, "xsolo_kw", "Solo", "solo3@askdata.com");
        insertUser(9906L, "solo_kw_ext", "Solo", "solo2@askdata.com");
        try {
            List<String> accounts = new java.util.ArrayList<>();
            for (Map<String, Object> item : userService.pager(1, 10, "zhang", null, null, null,
                    null, false).getItems()) {
                accounts.add(Rows.str(item, "account"));
            }
            assertTrue(accounts.containsAll(Lists.of("rel_zhang", "rel_zhangsan", "rel_other")),
                    "关键字应命中三个测试账号: " + accounts);
            assertEquals("rel_other", accounts.get(0),
                    "命中字段更多的用户应排在最前（match_count 降序）: " + accounts);
            assertTrue(accounts.indexOf("rel_zhang") < accounts.indexOf("rel_zhangsan"),
                    "精确匹配应排在前缀匹配之前: " + accounts);

            List<String> solo = new java.util.ArrayList<>();
            for (Map<String, Object> item : userService.pager(1, 10, "solo_kw", null, null, null,
                    null, false).getItems()) {
                solo.add(Rows.str(item, "account"));
            }
            // solo_kw 精确 → solo_kw_ext 前缀 → xsolo_kw 包含
            assertTrue(solo.indexOf("solo_kw") < solo.indexOf("solo_kw_ext"), "精确优先: " + solo);
            assertTrue(solo.indexOf("solo_kw_ext") < solo.indexOf("xsolo_kw"), "前缀优于包含: " + solo);
        } finally {
            jdbc.update("DELETE FROM sys_user WHERE id IN (9901,9902,9903,9904,9905,9906)");
        }
    }

    // ④ 内置系统变量可删除
    @Test
    void builtinSystemVariableCanBeDeleted() {
        jdbc.update("DELETE FROM system_variable WHERE id = 9901");
        // value 是保留字：沿用种子脚本的无列名写法
        jdbc.update("INSERT INTO system_variable VALUES "
                + "(9901, 'i18n_variable.account', 'text', 'system', '[\"account\"]', NULL, NULL)");
        try {
            variableService.delete(Lists.of(9901L));
            Integer remaining = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM system_variable WHERE id = 9901", Integer.class);
            assertEquals(0, remaining, "内置（system）变量应可删除（对齐 SQLBot delete）");
        } finally {
            jdbc.update("DELETE FROM system_variable WHERE id = 9901");
        }
    }

    // ⑤ 助手图片校验类型与大小
    @Test
    void assistantImageValidatesTypeAndSize() throws Exception {
        byte[] tiny = "png-bytes".getBytes(StandardCharsets.UTF_8);
        String saved = assistantFileStorage.save(new MockMultipartFile("file", "logo.png,logo",
                "image/png", tiny), "logo.png,logo");
        assertNotNull(saved, "合法 png 应保存成功");
        assertTrue(saved.length() > 0);

        IllegalArgumentException format = assertThrows(IllegalArgumentException.class,
                () -> assistantFileStorage.save(new MockMultipartFile("file", "logo.gif,logo",
                        "image/gif", tiny), "logo.gif,logo"));
        assertTrue(format.getMessage().contains(".jpg"), "非法类型应返回格式错误: " + format.getMessage());

        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        IllegalArgumentException size = assertThrows(IllegalArgumentException.class,
                () -> assistantFileStorage.save(new MockMultipartFile("file", "big.png,float_icon",
                        "image/png", oversized), "big.png,float_icon"));
        assertTrue(size.getMessage().toLowerCase(Locale.ROOT).contains("10m"),
                "超限应返回大小错误: " + size.getMessage());
    }

    // ⑥ 模型连通性使用 protocol / Azure 配置
    @Test
    void modelConnectivityUsesProtocolAndAzureConfig() {
        Map<String, Object> azureParams = new LinkedHashMap<>();
        azureParams.put("deployment_name", "gpt4o-deploy");
        azureParams.put("api_version", "2024-06-01");
        assertEquals("https://res.openai.azure.com/openai/deployments/gpt4o-deploy/chat/completions"
                        + "?api-version=2024-06-01",
                AiModelService.connectivityUrl("https://res.openai.azure.com", "gpt-4o", azureParams, 1),
                "Azure 域名应走 deployment 形态并使用 config_list 的 api_version");

        assertEquals("http://localhost:8000/v1/chat/completions",
                AiModelService.connectivityUrl("http://localhost:8000", "qwen2", Maps.of(), 2),
                "protocol=2（vLLM）端点固定在 /v1 下");
        assertEquals("http://localhost:8000/v1/chat/completions",
                AiModelService.connectivityUrl("http://localhost:8000/v1", "qwen2", Maps.of(), 2),
                "vLLM 已带 /v1 时不重复拼接");
        assertEquals("http://localhost:8000/api/chat/completions",
                AiModelService.connectivityUrl("http://localhost:8000/api", "gpt-4o-mini", Maps.of(), 1),
                "protocol=1（OpenAI）按 api_domain 原样规范化，自定义前缀保留");
        assertEquals("http://localhost:8000/api/v1/chat/completions",
                AiModelService.connectivityUrl("http://localhost:8000/api", "qwen2", Maps.of(), 2),
                "protocol=2 在自定义前缀下仍落到 /v1");
    }

    // ⑦ isAdmin 按角色判定
    @Test
    void isAdminFollowsRoleNotHardcodedIdentity() {
        // 种子管理员（id=1，默认工作空间 weight=100）
        assertTrue(currentUserService.isAdmin(userRepositoryRow(1L)), "种子管理员应为管理员");

        // 账号名 admin 但 id≠1 且无管理员角色 → 不是管理员（证明不再依赖 id==1 && account=='admin'）
        jdbc.update("DELETE FROM sys_user WHERE id = 9911");
        jdbc.update("DELETE FROM sys_user_ws WHERE uid = 9911");
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, "
                + "language, origin) VALUES (9911, 'admin', 'Fake Admin', 'x', 'fake@askdata.com', 1, 1, "
                + "1672531199000, 'zh-CN', 0)");
        jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (9911, 9911, 1, 0)");
        try {
            assertFalse(currentUserService.isAdmin(userRepositoryRow(9911L)),
                    "仅账号名为 admin 不应被判定为管理员");

            // 普通账号 + 默认工作空间管理员角色 → 管理员
            jdbc.update("UPDATE sys_user_ws SET weight = 1 WHERE uid = 9911");
            assertTrue(currentUserService.isAdmin(userRepositoryRow(9911L)),
                    "默认工作空间的管理员角色应判定为系统管理员");

            // 仅在其他工作空间是管理员 → 不获得系统管理员权限（权限边界不扩大）
            jdbc.update("DELETE FROM sys_workspace WHERE id = 9912");
            jdbc.update("INSERT INTO sys_workspace (id, name, create_time) VALUES (9912, 'role-ws', 0)");
            jdbc.update("UPDATE sys_user_ws SET weight = 0 WHERE uid = 9911");
            jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (9912, 9911, 9912, 1)");
            assertFalse(currentUserService.isAdmin(userRepositoryRow(9911L)),
                    "其他工作空间的管理员不应成为系统管理员");
        } finally {
            jdbc.update("DELETE FROM sys_user_ws WHERE uid = 9911");
            jdbc.update("DELETE FROM sys_user WHERE id = 9911");
            jdbc.update("DELETE FROM sys_workspace WHERE id = 9912");
        }

        // /user/info 的 isAdmin 同样按角色（登录被提升为管理员的普通账号）
        jdbc.update("DELETE FROM sys_user WHERE id = 9921");
        jdbc.update("DELETE FROM sys_user_ws WHERE uid = 9921");
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, "
                + "language, origin) VALUES (9921, 'role_admin_user', 'Role Admin', 'x', "
                + "'role_admin@askdata.com', 1, 1, 1672531199000, 'zh-CN', 0)");
        jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (9921, 9921, 1, 1)");
        try {
            StpUtil.logout();
            StpUtil.login(9921L);
            UserInfoVO info = userController.info().getData();
            assertTrue(info.getIsAdmin(), "/user/info 的 is_admin 应按角色判定");
        } finally {
            StpUtil.logout();
            StpUtil.login(1L);
            jdbc.update("DELETE FROM sys_user_ws WHERE uid = 9921");
            jdbc.update("DELETE FROM sys_user WHERE id = 9921");
        }
    }

    // ------------------------------------------------------------------

    private Map<String, Object> userRepositoryRow(long id) {
        return jdbc.queryForList("SELECT * FROM sys_user WHERE id = ?", id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("user not found: " + id));
    }

    private void insertUser(long id, String account, String name, String email) {
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, "
                + "language, origin) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, account, name, "x", email, 1, 1, 1672531199000L, "zh-CN", 0);
    }

    private static LogoutRequest logoutRequest(long origin) {
        LogoutRequest request = new LogoutRequest();
        request.setOrigin(origin);
        return request;
    }
}
