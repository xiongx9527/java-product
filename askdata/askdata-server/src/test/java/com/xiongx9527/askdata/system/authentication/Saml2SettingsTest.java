package com.xiongx9527.askdata.system.authentication;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A25 SAML2 配置端点验收：
 * ① 三个前端端点真实存在（不再 404）；② info 未配置时返回空默认体；
 * ③ save 校验必填项与证书格式并持久化；④ validate 校验证书与元数据并返回明确成功/失败原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:saml2_settings;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class Saml2SettingsTest {

    /** 测试用自签名 X.509 证书（仅公钥证书，无私钥；用于证书解析校验）。 */
    private static final String TEST_CERTIFICATE = "-----BEGIN CERTIFICATE-----\n"
            + "MIIDGTCCAgGgAwIBAgIUOWduJNqTihq2zO48tWscbZ65998wDQYJKoZIhvcNAQEL\n"
            + "BQAwHDEaMBgGA1UEAwwRYXNrZGF0YS1zYW1sLXRlc3QwHhcNMjYxMDI3MDMwMjI5\n"
            + "WhcNMzYxMDI0MDMwMjI5WjAcMRowGAYDVQQDDBFhc2tkYXRhLXNhbWwtdGVzdDCC\n"
            + "ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBANluOub6wooaFNKuK6j01krF\n"
            + "QjXLBC6Soxlw0YHa712PrcTqD9UozXnEKJuSubTImteTGmJeVhxpEugewO8bXKeK\n"
            + "o5fDc2+tZaOh14GKdRoO/ZSaFBtX+RER3rgj5C/rt0iyGsNbnXJUQ5nOpPhq+DF7\n"
            + "glhU5hxnXNGLKHIIJhhKDBRGrTA9xFHM3IpjvzSbmTHsmwfunhgqYqEBZImI/H3b\n"
            + "kjH8NGkChyYfgD7ewUz2uikYdcBfDE+6U0ALSi+Na8O9i23T7hi3pEjty+yCpe5G\n"
            + "70dxMXvTbzTNDX1+Z4g2p04WU2AdI/+QTcE8fokyu5UI/FdVazHa2gYXdEaVpmEC\n"
            + "AwEAAaNTMFEwHQYDVR0OBBYEFMg9U5sAquglQXsxK3DcyY5ByNdCMB8GA1UdIwQY\n"
            + "MBaAFMg9U5sAquglQXsxK3DcyY5ByNdCMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZI\n"
            + "hvcNAQELBQADggEBAEWSbVJugVDePqMh5sdtO6JCfbl4azSertyScNER8bN3PIan\n"
            + "b92iPOANTFJTHkXaZQlcyEwg+1X7Mt2X9xKkI2BjAmFgeR/V5fWYCIAb4FLJHYAW\n"
            + "GydCtaLKknXNC4+1Vb0IirGH/rHnekdl4G0nN7llSPbrn8gOc0488cxOjVO6OGRQ\n"
            + "UlnzfPfY04hTu3p5rVRyyXQmYVOR/K5vV2hV2SU7uqF2LBqbkw1O0G2SJks/TPkn\n"
            + "OPJDrhrJXMjqnIhj7CbfhWWJSNN6M1I0I18fAJTIu95z0E0zRkwNMOjxH/uh9OCp\n"
            + "OL+X1X0bSUqpKOpqq91WPEr8eK312mVozeKYwmw=\n"
            + "-----END CERTIFICATE-----";

    /** 合法的 IdP 元数据（含 SingleSignOnService 与 SingleLogoutService）。 */
    private static final String METADATA_XML = "<EntityDescriptor xmlns=\"urn:oasis:names:tc:SAML:2.0:metadata\">"
            + "<IDPSSODescriptor>"
            + "<SingleSignOnService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" "
            + "Location=\"https://idp.example.com/sso\"/>"
            + "<SingleLogoutService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" "
            + "Location=\"https://idp.example.com/slo\"/>"
            + "</IDPSSODescriptor></EntityDescriptor>";

    @Autowired
    private Saml2SettingsController controller;

    @Autowired
    private Saml2SettingsService service;

    @Autowired
    private AuthenticationRepository authenticationRepository;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private UserWsRepository userWsRepository;

    @Autowired
    private MessageResolver messageResolver;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void setUp() {
        SaTokenContextMockUtil.setMockContext();
        StpUtil.login(1L);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        jdbc.update("DELETE FROM sys_authentication WHERE type = ?", SsoAuthenticator.TYPE_SAML2);
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/setting/authentication/save/saml");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        StpUtil.logout();
        SaTokenContextMockUtil.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        jdbc.update("DELETE FROM sys_authentication WHERE type = ?", SsoAuthenticator.TYPE_SAML2);
    }

    // ① 三个端点真实存在（前端不再 404）
    @Test
    void threeEndpointsAreMapped() throws Exception {
        assertNotNull(handlerMapping.getHandler(new MockHttpServletRequest("GET",
                "/api/v1/setting/authentication/info/saml")), "GET info/saml 必须存在");
        assertNotNull(handlerMapping.getHandler(new MockHttpServletRequest("POST",
                "/api/v1/setting/authentication/save/saml")), "POST save/saml 必须存在");
        assertNotNull(handlerMapping.getHandler(new MockHttpServletRequest("POST",
                "/api/v1/setting/authentication/validate/saml2")), "POST validate/saml2 必须存在");
    }

    // ② 未配置时返回空默认体（不是 404 / 无响应）
    @Test
    void infoReturnsEmptyDefaultsWhenNotConfigured() {
        Map<String, Object> info = controller.info().getData();
        assertNotNull(info);
        assertEquals("", info.get("idpMetaUrl"));
        assertEquals("", info.get("certificate"));
        assertEquals(false, info.get("wantAuthnRequestsSigned"));
        assertEquals(false, info.get("wantAssertionsSigned"));
    }

    // ③ save：必填项 / 证书格式校验 + 持久化
    @Test
    void saveValidatesRequiredFieldsAndPersists() {
        // 缺 IdP 元数据地址
        BadRequestException missing = assertThrows(BadRequestException.class,
                () -> controller.save(Map.of("idpMetaUrl", "")));
        assertEquals(messageResolver.get("saml.idp.meta.required"), missing.getMessage());

        // 非 http(s) 地址
        BadRequestException invalidUrl = assertThrows(BadRequestException.class,
                () -> controller.save(Map.of("idpMetaUrl", "idp.example.com/metadata")));
        assertTrue(invalidUrl.getMessage().contains("idp.example.com"), invalidUrl.getMessage());

        // 开启签名但缺私钥/证书
        BadRequestException signing = assertThrows(BadRequestException.class,
                () -> controller.save(Map.of("idpMetaUrl", "https://idp.example.com/metadata",
                        "wantAssertionsSigned", true)));
        assertEquals(messageResolver.get("saml.signing.required"), signing.getMessage());

        // 证书格式非法
        BadRequestException badCert = assertThrows(BadRequestException.class,
                () -> controller.save(Map.of("idpMetaUrl", "https://idp.example.com/metadata",
                        "wantAssertionsSigned", true,
                        "certificate", "not-a-certificate",
                        "privateKey", "not-a-key")));
        assertFalse(badCert.getMessage().isEmpty());

        // 合法配置：持久化并可从 info 读回
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idpMetaUrl", "https://idp.example.com/metadata");
        body.put("certificate", TEST_CERTIFICATE);
        body.put("wantAuthnRequestsSigned", false);
        body.put("wantAssertionsSigned", false);
        body.put("mapping", "{\"account\":\"uid\"}");
        body.put("spEntityId", "https://app.example.com/saml/metadata");
        body.put("spAcs", "https://app.example.com/saml/sso");
        Map<String, Object> saved = controller.save(body).getData();
        assertEquals("https://idp.example.com/metadata", saved.get("idpMetaUrl"));

        Map<String, Object> info = controller.info().getData();
        assertEquals("https://idp.example.com/metadata", info.get("idpMetaUrl"));
        assertEquals("https://app.example.com/saml/sso", info.get("spAcs"));
        assertTrue(authenticationRepository.findByType(SsoAuthenticator.TYPE_SAML2).isPresent(),
                "配置应持久化到 sys_authentication（type=5）");
    }

    // ④ validate：证书与元数据校验，返回明确成功/失败原因
    @Test
    void validateChecksCertificateAndMetadata() {
        // 缺必填项 → 可读错误
        BadRequestException missing = assertThrows(BadRequestException.class,
                () -> controller.validate(Map.of()));
        assertEquals(messageResolver.get("saml.idp.meta.required"), missing.getMessage());

        // 元数据不可读 → valid=false 且带原因
        Map<String, Object> failed = validateWithMetadataFetcher(url -> {
            throw new IllegalStateException("connection refused");
        });
        assertEquals("false", failed.get("data"), "前端以 res.data === 'true' 判定成功");
        assertEquals(false, failed.get("valid"));
        assertTrue(String.valueOf(failed.get("message")).contains("connection refused"),
                "失败原因应可读: " + failed.get("message"));

        // 元数据未声明 SingleSignOnService → valid=false
        Map<String, Object> noSso = validateWithMetadataFetcher(
                url -> "<EntityDescriptor xmlns=\"urn:oasis:names:tc:SAML:2.0:metadata\"/>");
        assertEquals("false", noSso.get("data"));
        assertTrue(String.valueOf(noSso.get("message")).contains("SingleSignOnService"),
                String.valueOf(noSso.get("message")));

        // 合法证书 + 合法元数据 → 成功
        Map<String, Object> ok = validateWithMetadataFetcher(url -> METADATA_XML);
        assertEquals("true", ok.get("data"));
        assertEquals(true, ok.get("valid"));
        assertEquals(messageResolver.get("saml.validate.ok"), ok.get("message"));
    }

    /** 用离线元数据实现构造服务，避免测试依赖外网。 */
    private Map<String, Object> validateWithMetadataFetcher(
            java.util.function.Function<String, String> fetcher) {
        Saml2SsoClient offlineClient = new Saml2SsoClient(objectMapper) {
            @Override
            public String metadata(String idpMetaUrl) {
                return fetcher.apply(idpMetaUrl);
            }
        };
        Saml2SettingsService offline = new Saml2SettingsService(authenticationRepository, offlineClient,
                currentUserService, messageResolver, objectMapper);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idpMetaUrl", "https://idp.example.com/metadata");
        body.put("certificate", TEST_CERTIFICATE);
        body.put("wantAuthnRequestsSigned", false);
        body.put("wantAssertionsSigned", false);
        return offline.validate(body);
    }

    // 非管理员不可读写 SAML2 配置（配置含 SP 私钥）
    @Test
    void nonAdminCannotReadOrWriteSamlConfig() {
        jdbc.update("DELETE FROM sys_user WHERE id = 9951");
        jdbc.update("DELETE FROM sys_user_ws WHERE uid = 9951");
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, "
                + "language, origin) VALUES (9951, 'saml_plain_user', 'Plain', 'x', 'plain@askdata.com', "
                + "1, 1, 1672531199000, 'zh-CN', 0)");
        jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (9951, 9951, 1, 0)");
        try {
            StpUtil.logout();
            StpUtil.login(9951L);
            assertThrows(com.xiongx9527.askdata.common.web.ForbiddenException.class, () -> controller.info());
            assertThrows(com.xiongx9527.askdata.common.web.ForbiddenException.class,
                    () -> controller.save(Map.of("idpMetaUrl", "https://idp.example.com/metadata")));
        } finally {
            StpUtil.logout();
            StpUtil.login(1L);
            jdbc.update("DELETE FROM sys_user_ws WHERE uid = 9951");
            jdbc.update("DELETE FROM sys_user WHERE id = 9951");
        }
    }
}
