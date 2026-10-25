package com.xiongx9527.askdata.system.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.system.authentication.AuthenticationRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * A30/A31/A33：企业 IM 平台客户端信息、组织树、扫码登录换用户。
 *
 * <p>第三方 API 用本地 HttpServer（IPv6 回环）打桩，平台 config 的 {@code api_base} 指向桩服务；
 * 覆盖企业微信、钉钉、飞书三类形态，以及未配置/已禁用/缺 code 的可读错误。</p>
 */
class EnterprisePlatformServiceTest {

    private JdbcTemplate jdbc;

    private AuthenticationRepository authenticationRepository;

    private EnterprisePlatformService service;

    private HttpServer server;

    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:platform_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        authenticationRepository = new AuthenticationRepository(jdbc);
        CurrentUserService users = mock(CurrentUserService.class);
        doNothing().when(users).requireAdmin();
        service = new EnterprisePlatformService(authenticationRepository, objectMapper, users);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String apiBase() {
        return "http://[::1]:" + port;
    }

    private void stub(String path, String json) {
        server.createContext(path, exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
    }

    private void savePlatform(int type, String config, boolean enable) {
        authenticationRepository.upsert(type, EnterprisePlatformService.platformName(type), type, config,
                enable, true, 0L);
    }

    // ---------------- A30：客户端信息 ----------------

    @Test
    void unconfiguredPlatformReturnsDisabledClientInfo() {
        Map<String, Object> info = service.clientInfo(EnterprisePlatformService.ORIGIN_LARK);
        assertEquals(EnterprisePlatformService.ORIGIN_LARK, info.get("origin"));
        assertNull(info.get("client_id"));
        assertFalse((Boolean) info.get("enable"));
    }

    @Test
    void larkClientInfoReturnsRealClientId() {
        savePlatform(EnterprisePlatformService.ORIGIN_LARK,
                "{\"client_id\":\"cli_abc\",\"client_secret\":\"sec\"}", true);
        Map<String, Object> info = service.clientInfo(EnterprisePlatformService.ORIGIN_LARK);
        assertEquals("cli_abc", info.get("client_id"));
        assertEquals(true, info.get("enable"));
        assertEquals(true, info.get("valid"));
    }

    @Test
    void wecomClientInfoReturnsCorpIdAndAgentId() {
        savePlatform(EnterprisePlatformService.ORIGIN_WECOM,
                "{\"corpid\":\"ww123\",\"agent_id\":\"1000002\",\"corpsecret\":\"sec\"}", true);
        Map<String, Object> info = service.clientInfo(EnterprisePlatformService.ORIGIN_WECOM);
        assertEquals("ww123", info.get("corpid"));
        assertEquals("1000002", info.get("agent_id"));
    }

    @Test
    void disabledPlatformClientInfoReportsDisabled() {
        savePlatform(EnterprisePlatformService.ORIGIN_DINGTALK,
                "{\"corpid\":\"ding1\",\"client_id\":\"k\",\"client_secret\":\"s\"}", false);
        Map<String, Object> info = service.clientInfo(EnterprisePlatformService.ORIGIN_DINGTALK);
        assertEquals("ding1", info.get("corpid"));
        assertEquals(false, info.get("enable"));
    }

    // ---------------- A33：组织树 ----------------

    @Test
    void wecomOrgTreeContainsDepartmentsAndUsers() {
        savePlatform(EnterprisePlatformService.ORIGIN_WECOM,
                "{\"corpid\":\"ww1\",\"agent_id\":\"1\",\"corpsecret\":\"s\",\"api_base\":\"" + apiBase() + "\"}",
                true);
        stub("/cgi-bin/gettoken", "{\"errcode\":0,\"access_token\":\"tk-1\",\"expires_in\":7200}");
        stub("/cgi-bin/department/simplelist",
                "{\"errcode\":0,\"department_id\":[{\"id\":10,\"name\":\"研发部\",\"parentid\":1}]}");
        stub("/cgi-bin/user/simplelist",
                "{\"errcode\":0,\"userlist\":[{\"userid\":\"u1\",\"name\":\"张三\",\"email\":\"z@x.com\"}]}");

        Map<String, Object> result = service.orgTree(EnterprisePlatformService.ORIGIN_WECOM, true, null);
        List<?> tree = (List<?>) result.get("tree");
        assertEquals(1, tree.size());
        Map<?, ?> dept = (Map<?, ?>) tree.get(0);
        assertEquals("10", dept.get("id"));
        assertEquals("研发部", dept.get("name"));
        Map<?, ?> options = (Map<?, ?>) dept.get("options");
        assertEquals(false, options.get("is_user"));
        List<?> children = (List<?>) dept.get("children");
        assertEquals(1, children.size());
        Map<?, ?> user = (Map<?, ?>) children.get(0);
        assertEquals("u1", user.get("id"));
        Map<?, ?> userOptions = (Map<?, ?>) user.get("options");
        assertEquals(true, userOptions.get("is_user"));
        assertEquals("z@x.com", userOptions.get("email"));
    }

    @Test
    void larkOrgTreeMapsOpenIds() {
        savePlatform(EnterprisePlatformService.ORIGIN_LARK,
                "{\"client_id\":\"cli\",\"client_secret\":\"sec\",\"api_base\":\"" + apiBase() + "\"}", true);
        stub("/open-apis/auth/v3/app_access_token/internal",
                "{\"code\":0,\"app_access_token\":\"app-tk\",\"expire\":7200}");
        stub("/open-apis/contact/v3/departments/0/children",
                "{\"code\":0,\"data\":{\"items\":[{\"open_department_id\":\"od-1\",\"name\":\"总部\"}]}}");
        stub("/open-apis/contact/v3/users/find_by_department",
                "{\"code\":0,\"data\":{\"items\":[{\"open_id\":\"ou-1\",\"name\":\"李四\",\"email\":\"l@x.com\"}]}}");

        Map<String, Object> result = service.orgTree(EnterprisePlatformService.ORIGIN_LARK, true, null);
        List<?> tree = (List<?>) result.get("tree");
        assertEquals(1, tree.size());
        Map<?, ?> dept = (Map<?, ?>) tree.get(0);
        assertEquals("od-1", dept.get("id"));
        List<?> children = (List<?>) dept.get("children");
        Map<?, ?> user = (Map<?, ?>) children.get(0);
        assertEquals("ou-1", user.get("id"));
    }

    @Test
    void dingtalkOrgTreeListsSubDepartments() {
        savePlatform(EnterprisePlatformService.ORIGIN_DINGTALK,
                "{\"corpid\":\"d1\",\"client_id\":\"k\",\"client_secret\":\"s\",\"api_base\":\"" + apiBase() + "\"}",
                true);
        stub("/v1.0/oauth2/accessToken", "{\"accessToken\":\"dt-tk\",\"expireIn\":7200}");
        stub("/topapi/v2/department/listsub",
                "{\"errcode\":0,\"result\":[{\"dept_id\":5,\"name\":\"销售部\",\"parent_id\":1}]}");
        stub("/topapi/v2/user/list",
                "{\"errcode\":0,\"result\":{\"has_more\":false,\"list\":[{\"userid\":\"dt1\",\"name\":\"王五\","
                        + "\"email\":\"w@x.com\"}]}}");

        Map<String, Object> result = service.orgTree(EnterprisePlatformService.ORIGIN_DINGTALK, true, null);
        List<?> tree = (List<?>) result.get("tree");
        assertEquals(1, tree.size());
        assertEquals("5", ((Map<?, ?>) tree.get(0)).get("id"));
    }

    @Test
    void orgTreeOfUnconfiguredPlatformFailsReadably() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.orgTree(EnterprisePlatformService.ORIGIN_LARK, true, null));
        assertTrue(ex.getMessage().contains("lark"), ex.getMessage());
    }

    @Test
    void orgTreeOfDisabledPlatformFailsReadably() {
        savePlatform(EnterprisePlatformService.ORIGIN_LARK,
                "{\"client_id\":\"c\",\"client_secret\":\"s\"}", false);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.orgTree(EnterprisePlatformService.ORIGIN_LARK, true, null));
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
    }

    // ---------------- A31：扫码登录换用户 ----------------

    @Test
    void wecomCodeExchangesToPlatformUser() {
        savePlatform(EnterprisePlatformService.ORIGIN_WECOM,
                "{\"corpid\":\"ww1\",\"agent_id\":\"1\",\"corpsecret\":\"s\",\"api_base\":\"" + apiBase() + "\"}",
                true);
        stub("/cgi-bin/gettoken", "{\"errcode\":0,\"access_token\":\"tk\",\"expires_in\":7200}");
        stub("/cgi-bin/user/getuserinfo", "{\"errcode\":0,\"userid\":\"u9\"}");
        stub("/cgi-bin/user/get",
                "{\"errcode\":0,\"userid\":\"u9\",\"name\":\"赵六\",\"email\":\"zhao@x.com\"}");

        Map<String, Object> user = service.platformUserByCode(EnterprisePlatformService.ORIGIN_WECOM,
                Map.of("code", "scan-code"));
        assertEquals("u9", user.get("platform_uid"));
        assertEquals("u9", user.get("account"));
        assertEquals("赵六", user.get("name"));
        assertEquals("zhao@x.com", user.get("email"));
    }

    @Test
    void larkCodeExchangesToPlatformUser() {
        savePlatform(EnterprisePlatformService.ORIGIN_LARK,
                "{\"client_id\":\"cli\",\"client_secret\":\"sec\",\"api_base\":\"" + apiBase() + "\"}", true);
        stub("/open-apis/auth/v3/app_access_token/internal",
                "{\"code\":0,\"app_access_token\":\"app-tk\",\"expire\":7200}");
        stub("/open-apis/authen/v1/oidc/access_token",
                "{\"code\":0,\"data\":{\"access_token\":\"user-tk\"}}");
        stub("/open-apis/authen/v1/user_info",
                "{\"code\":0,\"data\":{\"open_id\":\"ou-9\",\"name\":\"孙七\",\"email\":\"s@x.com\"}}");

        Map<String, Object> user = service.platformUserByCode(EnterprisePlatformService.ORIGIN_LARK,
                Map.of("code", "scan-code"));
        assertEquals("ou-9", user.get("platform_uid"));
        assertEquals("孙七", user.get("name"));
    }

    @Test
    void missingCodeFailsReadably() {
        savePlatform(EnterprisePlatformService.ORIGIN_DINGTALK,
                "{\"corpid\":\"d\",\"client_id\":\"k\",\"client_secret\":\"s\",\"api_base\":\"" + apiBase() + "\"}",
                true);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.platformUserByCode(EnterprisePlatformService.ORIGIN_DINGTALK, Map.of()));
        assertTrue(ex.getMessage().contains("code"), ex.getMessage());
    }

    @Test
    void ssoOfUnconfiguredPlatformFailsReadably() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.platformUserByCode(EnterprisePlatformService.ORIGIN_WECOM,
                        Map.of("code", "x")));
        assertTrue(ex.getMessage().contains("wecom"), ex.getMessage());
        assertNotNull(ex.getMessage());
    }
}
