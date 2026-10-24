package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.embedded.AssistantRepository;
import com.xiongx9527.askdata.system.auth.JwtCodec;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 嵌入助手 token 通道回归（对齐 SQLBot {@code backend/tests/test_embedded_auth_bypass_fix.py} 的
 * {@code TestSourceLevelGuards} 中 validateEmbedded 的两条红线，以及 A4 的验收项）。
 *
 * <p>钉住的行为：</p>
 * <ol>
 *   <li><b>app_secret 正确 → 通过</b>；</li>
 *   <li><b>管理员账号 → 拒绝</b>（app_secret 由集成方持有，拿到即可伪造 account=admin）；</li>
 *   <li><b>非 type=4 的助手 → 拒绝</b>（embedded 协议仅适用于页面嵌入应用）。</li>
 * </ol>
 */
class EmbeddedTokenAuthenticatorTest {

    private static final String SECRET = "unit-test-secret-key";
    private static final String APP_SECRET = "app-secret-for-embedded";

    private AssistantRepository assistantRepository;
    private UserRepository userRepository;
    private EmbeddedTokenAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        assistantRepository = Mockito.mock(AssistantRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        authenticator = new EmbeddedTokenAuthenticator(
                new com.xiongx9527.askdata.common.cache.HotCacheService(userRepository,
                        Mockito.mock(com.xiongx9527.askdata.system.repository.ApiKeyRepository.class),
                        assistantRepository), SECRET,
                com.xiongx9527.askdata.common.i18n.TestMessageResolver.create());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
    }

    private void givenAssistant(long id, int type, String appSecret) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", "app-" + id);
        row.put("type", type);
        row.put("app_secret", appSecret);
        row.put("domain", "https://demo.example.com");
        row.put("oid", 1L);
        when(assistantRepository.findById(id)).thenReturn(Optional.of(row));
    }

    private void givenUser(long id, String account, int status, long oid) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("account", account);
        row.put("name", account);
        row.put("email", account + "@example.com");
        row.put("status", status);
        row.put("oid", oid);
        when(userRepository.findByAccount(account)).thenReturn(Optional.of(row));
        when(userRepository.findById(id)).thenReturn(Optional.of(row));
    }

    private String embeddedToken(String account, long embeddedId, String secret) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account", account);
        payload.put("embeddedId", embeddedId);
        payload.put("appId", "some-app-id");
        return "Embedded " + JwtCodec.encode(payload, secret, 3600L);
    }

    @Test
    @DisplayName("app_secret 正确 + 普通账号 + type=4 → 通过")
    void correctAppSecretAccepted() {
        givenAssistant(100L, 4, APP_SECRET);
        givenUser(20L, "alice", 1, 5L);

        EmbeddedTokenAuthenticator.AuthResult result =
                authenticator.authenticate(embeddedToken("alice", 100L, APP_SECRET));

        assertNotNull(result);
        assertEquals("alice", result.getCurrentUser().get("account"));
        assertEquals(100L, ((Number) result.getAssistant().get("id")).longValue());
    }

    @Test
    @DisplayName("管理员账号被拒绝（安全红线）")
    void adminAccountRejected() {
        givenAssistant(100L, 4, APP_SECRET);
        givenUser(1L, "admin", 1, 1L);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(embeddedToken("admin", 100L, APP_SECRET)));
        assertTrue(e.getMessage().contains("Admin account is not allowed"),
                "message: " + e.getMessage());
    }

    @Test
    @DisplayName("非 type=4 的助手被拒绝（安全红线）")
    void nonEmbeddedTypeRejected() {
        // type=0（普通小助手）/ type=1（高级应用）都不接受 embedded 协议
        givenAssistant(101L, 0, APP_SECRET);
        givenUser(20L, "alice", 1, 5L);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(embeddedToken("alice", 101L, APP_SECRET)));
        assertTrue(e.getMessage().contains("Invalid embedded application type"),
                "message: " + e.getMessage());
    }

    @Test
    @DisplayName("错误的 app_secret → 验签失败")
    void wrongAppSecretRejected() {
        givenAssistant(100L, 4, APP_SECRET);
        givenUser(20L, "alice", 1, 5L);

        assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(embeddedToken("alice", 100L, "wrong-secret")));
    }

    @Test
    @DisplayName("载荷缺 account → 拒绝")
    void missingAccountRejected() {
        givenAssistant(100L, 4, APP_SECRET);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("embeddedId", 100L);
        String token = "Embedded " + JwtCodec.encode(payload, APP_SECRET, 3600L);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(token));
        assertTrue(e.getMessage().contains("Missing account parameter"), "message: " + e.getMessage());
    }

    @Test
    @DisplayName("未知 scheme → 拒绝")
    void unknownSchemeRejected() {
        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate("Bearer some-token"));
        assertTrue(e.getMessage().contains("Token schema error"), "message: " + e.getMessage());
    }

    @Test
    @DisplayName("assistant 通道：载荷缺 assistant_id → 拒绝")
    void assistantSchemeRequiresAssistantId() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", 1);
        String token = "Assistant " + JwtCodec.encode(payload, SECRET, 3600L);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(token));
        assertTrue(e.getMessage().contains("Missing assistant_id parameter"), "message: " + e.getMessage());
    }

    @Test
    @DisplayName("assistant 通道：带 assistant_id → 通过")
    void assistantSchemeAccepted() {
        givenAssistant(102L, 0, APP_SECRET);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", 1);
        payload.put("assistant_id", 102L);
        String token = "Assistant " + JwtCodec.encode(payload, SECRET, 3600L);

        EmbeddedTokenAuthenticator.AuthResult result = authenticator.authenticate(token);
        assertNotNull(result);
        assertEquals("sqlbot-inner-assistant", result.getCurrentUser().get("account"));
    }

    @Test
    @DisplayName("停用用户（status != 1）→ 拒绝")
    void disabledUserRejected() {
        givenAssistant(100L, 4, APP_SECRET);
        givenUser(20L, "alice", 0, 5L);

        assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(embeddedToken("alice", 100L, APP_SECRET)));
    }

    @Test
    @DisplayName("未绑定工作空间的用户 → 拒绝")
    void userWithoutWorkspaceRejected() {
        givenAssistant(100L, 4, APP_SECRET);
        givenUser(20L, "alice", 1, 0L);

        assertThrows(AuthFailureException.class,
                () -> authenticator.authenticate(embeddedToken("alice", 100L, APP_SECRET)));
    }

    @Test
    @DisplayName("embeddedId 缺失时按 appId 做 XOR 解密回退")
    void embeddedIdFallsBackToXorDecryptedAppId() {
        // 构造一个经 xor_decrypt(appId) 能还原出 100 的 appId
        long encrypted = 100L ^ 0xABCD1234L;
        byte[] bytes = new byte[]{
                (byte) (encrypted >>> 24), (byte) (encrypted >>> 16), (byte) (encrypted >>> 8), (byte) encrypted};
        String appId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        givenAssistant(100L, 4, APP_SECRET);
        givenUser(20L, "alice", 1, 5L);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account", "alice");
        payload.put("appId", appId);
        String token = "Embedded " + JwtCodec.encode(payload, APP_SECRET, 3600L);

        EmbeddedTokenAuthenticator.AuthResult result = authenticator.authenticate(token);
        assertNotNull(result);
        assertEquals(100L, ((Number) result.getAssistant().get("id")).longValue());
    }
}
