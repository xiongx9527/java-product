package com.xiongx9527.askdata.system.license;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.web.AuthFailureException;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * A28：许可证上传/读取/版本——验签、期限判定、持久化与非管理员拦截。
 */
class LicenseServiceTest {

    private static final String SIGN_KEY = "unit-test-sign-key";

    private JdbcTemplate jdbc;

    private LicenseService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:license_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        CurrentUserService users = mock(CurrentUserService.class);
        service = new LicenseService(jdbc, objectMapper, users, "9.9.9", SIGN_KEY);
    }

    private String license(String edition, String version, String corporation, long expiresAt) throws Exception {
        TreeMap<String, Object> license = new TreeMap<>();
        license.put("product", "askdata");
        license.put("edition", edition);
        license.put("version", version);
        license.put("corporation", corporation);
        license.put("count", 50);
        license.put("serialNo", "SN-0001");
        license.put("remark", "unit test");
        license.put("isv", "fit2cloud");
        license.put("expires_at", expiresAt);
        String canonical = objectMapper.writeValueAsString(license);
        license.put("signature", hmac(canonical));
        return objectMapper.writeValueAsString(license);
    }

    private String hmac(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGN_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            int v = b & 0xFF;
            sb.append(hex[v >>> 4]).append(hex[v & 0x0F]);
        }
        return sb.toString();
    }

    @Test
    void noLicenseReportsCommunity() {
        Map<String, Object> status = service.status();
        assertEquals("community", status.get("status"));
        assertNull(status.get("license"));
        assertEquals("9.9.9", service.version(), "无许可证时返回产品版本");
    }

    @Test
    void validLicensePersistsAndIsReadBack() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L + 86400;
        Map<String, Object> uploaded = service.upload(license("Enterprise", "2.0.0", "ACME", expiresAt));
        assertEquals("valid", uploaded.get("status"));

        Map<String, Object> status = service.status();
        assertEquals("valid", status.get("status"));
        Map<?, ?> view = (Map<?, ?>) status.get("license");
        assertEquals("ACME", view.get("corporation"));
        assertEquals("2.0.0", view.get("version"));
        assertEquals("Enterprise", view.get("edition"));
        assertEquals("SN-0001", view.get("serialNo"));
        assertEquals("fit2cloud", view.get("isv"));
        assertEquals(50, view.get("count"));
        assertNotNull(view.get("expired"));
        assertFalse("-".equals(view.get("expired")));

        assertEquals("2.0.0", service.version(), "有许可证时返回许可证版本");

        // 重新构造 service（模拟重启）仍能读回持久化状态
        LicenseService reloaded = new LicenseService(jdbc, objectMapper,
                mock(CurrentUserService.class), "9.9.9", SIGN_KEY);
        assertEquals("valid", reloaded.status().get("status"));
    }

    @Test
    void expiredLicenseIsReportedAsExpired() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L - 60;
        Map<String, Object> uploaded = service.upload(license("Professional", "1.5.0", "ACME", expiresAt));
        assertEquals("expired", uploaded.get("status"));
        assertEquals("expired", service.status().get("status"));
    }

    @Test
    void permanentLicenseHasNoExpiry() throws Exception {
        Map<String, Object> uploaded = service.upload(license("Embedded", "3.0.0", "ACME", 0L));
        assertEquals("valid", uploaded.get("status"));
        Map<?, ?> view = (Map<?, ?>) uploaded.get("license");
        assertEquals("-", view.get("expired"));
    }

    @Test
    void tamperedLicenseIsRejected() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L + 86400;
        String text = license("Enterprise", "2.0.0", "ACME", expiresAt);
        String tampered = text.replace("\"ACME\"", "\"EVIL\"");
        assertTrue(tampered.contains("EVIL"));
        Map<String, Object> result = service.upload(tampered);
        assertEquals("invalid", result.get("status"));
        assertNull(result.get("license"));
        assertNotNull(result.get("message"));
        assertEquals("community", service.status().get("status"), "被拒绝的上传不得落库");
    }

    @Test
    void unsignedLicenseIsRejected() throws Exception {
        TreeMap<String, Object> license = new TreeMap<>();
        license.put("product", "askdata");
        license.put("edition", "Enterprise");
        Map<String, Object> result = service.upload(objectMapper.writeValueAsString(license));
        assertEquals("invalid", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("signature"));
    }

    @Test
    void emptyLicenseIsRejected() {
        Map<String, Object> result = service.upload("  ");
        assertEquals("invalid", result.get("status"));
    }

    @Test
    void nonAdminUploadIsRejected() throws Exception {
        CurrentUserService users = mock(CurrentUserService.class);
        doThrow(new AuthFailureException("forbidden")).when(users).requireWsAdmin();
        LicenseService guarded = new LicenseService(jdbc, objectMapper, users, "9.9.9", SIGN_KEY);
        assertThrows(AuthFailureException.class,
                () -> guarded.upload(license("Enterprise", "2.0.0", "ACME",
                        System.currentTimeMillis() / 1000L + 60)));
    }
}
