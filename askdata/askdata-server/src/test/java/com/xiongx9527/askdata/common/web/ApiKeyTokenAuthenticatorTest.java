package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.system.auth.JwtCodec;
import com.xiongx9527.askdata.system.repository.ApiKeyRepository;
import com.xiongx9527.askdata.system.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * API Key（sk）鉴权通道回归（对齐 SQLBot {@code apps/system/middleware/auth.py} 的
 * {@code validateAskToken}，以及 A4 验收项「合法 key 通过、非法/停用 key 401」）。
 */
class ApiKeyTokenAuthenticatorTest {

    private static final String SECRET_KEY = "unit-test-secret-key";

    private ApiKeyRepository apiKeyRepository;
    private UserRepository userRepository;
    private ApiKeyTokenAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        apiKeyRepository = Mockito.mock(ApiKeyRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        authenticator = new ApiKeyTokenAuthenticator(apiKeyRepository, userRepository);
    }

    private void givenApiKey(String accessKey, String secretKey, long uid, boolean status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("access_key", accessKey);
        row.put("secret_key", secretKey);
        row.put("uid", uid);
        row.put("status", status);
        when(apiKeyRepository.findByAccessKey(accessKey)).thenReturn(Optional.of(row));
    }

    private void givenUser(long id, String account, int status, long oid) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("account", account);
        row.put("name", account);
        row.put("email", account + "@example.com");
        row.put("status", status);
        row.put("oid", oid);
        row.put("origin", 0);
        when(userRepository.findById(id)).thenReturn(Optional.of(row));
    }

    private String askToken(String accessKey, String secret) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_key", accessKey);
        return "sk " + JwtCodec.encode(payload, secret, 3600L);
    }

    @Test
    @DisplayName("合法 key（access_key + secret_key 匹配）→ 通过")
    void validKeyAccepted() {
        givenApiKey("ak-valid", "sk-secret-valid", 20L, true);
        givenUser(20L, "alice", 1, 5L);

        Map<String, Object> user = authenticator.authenticate(askToken("ak-valid", "sk-secret-valid"));

        assertNotNull(user);
        assertEquals("alice", user.get("account"));
        assertEquals(5L, ((Number) user.get("oid")).longValue());
    }

    @Test
    @DisplayName("access_key 不存在 → 拒绝")
    void unknownAccessKeyRejected() {
        givenApiKey("ak-valid", "sk-secret-valid", 20L, true);
        givenUser(20L, "alice", 1, 5L);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(askToken("ak-unknown", "sk-secret-valid")));
        assertTrue(e.getMessage().contains("Invalid access_key"), "message: " + e.getMessage());
    }

    @Test
    @DisplayName("停用的 key（status=false）→ 拒绝")
    void disabledKeyRejected() {
        givenApiKey("ak-disabled", "sk-secret", 20L, false);
        givenUser(20L, "alice", 1, 5L);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(askToken("ak-disabled", "sk-secret")));
        assertTrue(e.getMessage().contains("Disabled access_key"), "message: " + e.getMessage());
    }

    @Test
    @DisplayName("secret_key 不匹配 → 验签失败 → 拒绝")
    void wrongSecretRejected() {
        givenApiKey("ak-valid", "sk-secret-valid", 20L, true);
        givenUser(20L, "alice", 1, 5L);

        assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(askToken("ak-valid", "wrong-secret")));
    }

    @Test
    @DisplayName("scheme 不是 sk → 拒绝")
    void wrongSchemeRejected() {
        givenApiKey("ak-valid", "sk-secret-valid", 20L, true);
        givenUser(20L, "alice", 1, 5L);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate("Bearer some-token"));
        assertTrue(e.getMessage().contains("Token schema error"), "message: " + e.getMessage());
    }

    @Test
    @DisplayName("载荷缺 access_key → 拒绝")
    void missingAccessKeyRejected() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("foo", "bar");
        String token = "sk " + JwtCodec.encode(payload, SECRET_KEY, 3600L);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(token));
        assertTrue(e.getMessage().contains("Miss access_key payload"), "message: " + e.getMessage());
    }

    @Test
    @DisplayName("key 所属用户被停用 → 拒绝")
    void disabledUserRejected() {
        givenApiKey("ak-valid", "sk-secret-valid", 20L, true);
        givenUser(20L, "alice", 0, 5L);

        assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(askToken("ak-valid", "sk-secret-valid")));
    }

    @Test
    @DisplayName("key 所属用户未绑定工作空间 → 拒绝")
    void userWithoutWorkspaceRejected() {
        givenApiKey("ak-valid", "sk-secret-valid", 20L, true);
        givenUser(20L, "alice", 1, 0L);

        assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(askToken("ak-valid", "sk-secret-valid")));
    }

    @Test
    @DisplayName("空请求头 → 拒绝")
    void missingHeaderRejected() {
        assertThrows(AuthFailureException.class, () -> authenticator.authenticate(null));
        assertThrows(AuthFailureException.class, () -> authenticator.authenticate("  "));
    }
}
