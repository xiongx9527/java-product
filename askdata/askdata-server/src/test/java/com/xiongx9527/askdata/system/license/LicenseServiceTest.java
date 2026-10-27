package com.xiongx9527.askdata.system.license;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.web.AuthFailureException;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
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
 * A3 License 校验与门控：验签、期限、版本、授权量、enforcement 门控，以及 issue-license.sh 签发。
 */
class LicenseServiceTest {

    private static final String SIGN_KEY = "unit-test-sign-key";
    private static final String APP_VERSION = "9.9.9";

    private JdbcTemplate jdbc;
    private LicenseService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:license_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        CurrentUserService users = mock(CurrentUserService.class);
        service = new LicenseService(jdbc, objectMapper, users, APP_VERSION, SIGN_KEY, "bypass", 1000000L);
    }

    private LicenseService service(String enforcement, long maxCount) {
        CurrentUserService users = mock(CurrentUserService.class);
        return new LicenseService(jdbc, objectMapper, users, APP_VERSION, SIGN_KEY, enforcement, maxCount);
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
        assertEquals(APP_VERSION, service.version(), "无许可证时返回产品版本");
        assertEquals("bypass", status.get("enforcement"));
        assertEquals(Boolean.TRUE, status.get("enterprise"));
    }

    @Test
    void validLicensePersistsAndIsReadBack() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L + 86400;
        Map<String, Object> uploaded = service.upload(license("Enterprise", APP_VERSION, "ACME", expiresAt));
        assertEquals("valid", uploaded.get("status"));

        Map<String, Object> status = service.status();
        assertEquals("valid", status.get("status"));
        Map<?, ?> view = (Map<?, ?>) status.get("license");
        assertEquals("ACME", view.get("corporation"));
        assertEquals(APP_VERSION, view.get("version"));
        assertEquals("Enterprise", view.get("edition"));
        assertEquals("SN-0001", view.get("serialNo"));
        assertEquals("fit2cloud", view.get("isv"));
        assertEquals(50, view.get("count"));
        assertNotNull(view.get("expired"));
        assertFalse("-".equals(view.get("expired")));

        assertEquals(APP_VERSION, service.version(), "有许可证时返回许可证版本");

        LicenseService reloaded = service("bypass", 1000000L);
        assertEquals("valid", reloaded.status().get("status"));
    }

    @Test
    void expiredLicenseIsReportedAsExpired() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L - 60;
        Map<String, Object> uploaded = service.upload(license("Professional", APP_VERSION, "ACME", expiresAt));
        assertEquals("expired", uploaded.get("status"));
        assertEquals("expired", service.status().get("status"));
    }

    @Test
    void permanentLicenseHasNoExpiry() throws Exception {
        Map<String, Object> uploaded = service.upload(license("Embedded", APP_VERSION, "ACME", 0L));
        assertEquals("valid", uploaded.get("status"));
        Map<?, ?> view = (Map<?, ?>) uploaded.get("license");
        assertEquals("-", view.get("expired"));
    }

    @Test
    void tamperedLicenseIsRejected() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L + 86400;
        String text = license("Enterprise", APP_VERSION, "ACME", expiresAt);
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
        LicenseService guarded = new LicenseService(jdbc, objectMapper, users, APP_VERSION, SIGN_KEY, "bypass", 1000000L);
        assertThrows(AuthFailureException.class,
                () -> guarded.upload(license("Enterprise", APP_VERSION, "ACME",
                        System.currentTimeMillis() / 1000L + 60)));
    }

    @Test
    void versionMismatchIsRejected() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L + 86400;
        Map<String, Object> result = service.upload(license("Enterprise", "1.0.0", "ACME", expiresAt));
        assertEquals("invalid", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("version"),
                "应说明版本不匹配原因: " + result.get("message"));
    }

    @Test
    void overQuotaIsRejected() throws Exception {
        long expiresAt = System.currentTimeMillis() / 1000L + 86400;
        LicenseService limited = service("bypass", 10L);
        TreeMap<String, Object> lic = new TreeMap<>();
        lic.put("product", "askdata");
        lic.put("edition", "Enterprise");
        lic.put("version", APP_VERSION);
        lic.put("corporation", "ACME");
        lic.put("count", 50);
        lic.put("serialNo", "SN-0001");
        lic.put("remark", "over quota");
        lic.put("isv", "fit2cloud");
        lic.put("expires_at", expiresAt);
        String canonical = objectMapper.writeValueAsString(lic);
        lic.put("signature", hmac(canonical));
        Map<String, Object> result = limited.upload(objectMapper.writeValueAsString(lic));
        assertEquals("invalid", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("count"),
                "应说明授权量超限原因: " + result.get("message"));
    }

    @Test
    void missingSignKeyFailsClosed() {
        LicenseService noKey = new LicenseService(jdbc, objectMapper, mock(CurrentUserService.class),
                APP_VERSION, "", "bypass", 1000000L);
        // 提供签名以便走到验签分支（缺失签名会先短路为 signature is missing）
        String withSignature = "{\"product\":\"askdata\",\"edition\":\"Enterprise\","
                + "\"version\":\"" + APP_VERSION + "\",\"corporation\":\"ACME\","
                + "\"count\":50,\"serialNo\":\"SN-0001\",\"remark\":\"x\","
                + "\"isv\":\"fit2cloud\",\"expires_at\":123,\"signature\":\"deadbeef\"}";
        Map<String, Object> result = noKey.upload(withSignature);
        assertEquals("invalid", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("sign-key"),
                "未配置签名密钥应 fail-closed 并提示配置: " + result.get("message"));
    }

    @Test
    void enforcementBypassAlwaysEnabled() throws Exception {
        assertEquals(Boolean.TRUE, service.status().get("enterprise"));
        long expiresAt = System.currentTimeMillis() / 1000L + 86400;
        service.upload(license("Enterprise", APP_VERSION, "ACME", expiresAt));
        assertTrue(service.enterpriseEnabled());
        LicenseService bypass = service("bypass", 1000000L);
        assertTrue(bypass.enterpriseEnabled());
    }

    @Test
    void enforcementEnforceGatesOnLicense() throws Exception {
        LicenseService enforce = service("enforce", 1000000L);
        assertFalse(enforce.enterpriseEnabled(), "enforce 且无许可证时企业能力应被拦截");

        long expiresAt = System.currentTimeMillis() / 1000L + 86400;
        enforce.upload(license("Enterprise", APP_VERSION, "ACME", expiresAt));
        assertTrue(enforce.enterpriseEnabled(), "enforce 且合法许可证时企业能力应可用");

        LicenseService enforce2 = service("enforce", 1000000L);
        enforce2.upload(license("Enterprise", APP_VERSION, "ACME", System.currentTimeMillis() / 1000L - 60));
        assertFalse(enforce2.enterpriseEnabled(), "enforce 且过期许可证时企业能力应被拦截");
    }

    @Test
    void issuedByScriptIsAccepted() throws Exception {
        File script = findScript();
        assertNotNull(script, "scripts/issue-license.sh 应存在");
        ProcessBuilder pb = new ProcessBuilder("bash", script.getAbsolutePath());
        pb.environment().put("SIGN_KEY", SIGN_KEY);
        pb.environment().put("VERSION", APP_VERSION);
        pb.environment().put("CORPORATION", "ACME");
        pb.environment().put("COUNT", "50");
        pb.environment().put("EXPIRES_AT", String.valueOf(System.currentTimeMillis() / 1000L + 86400));
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = reader.readLine();
        }
        int rc = p.waitFor();
        assertEquals(0, rc, "issue-license.sh 应成功执行");
        assertNotNull(out, "脚本应输出许可证 JSON");
        Map<String, Object> result = service.upload(out);
        assertEquals("valid", result.get("status"), "脚本签发的许可证应被接受: " + result.get("message"));
    }

    private static File findScript() {
        File dir = new File(System.getProperty("maven.multiModuleProjectDirectory", ".")).getAbsoluteFile();
        for (int i = 0; i < 5 && dir != null; i++) {
            File candidate = new File(dir, "scripts/issue-license.sh");
            if (candidate.exists()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
