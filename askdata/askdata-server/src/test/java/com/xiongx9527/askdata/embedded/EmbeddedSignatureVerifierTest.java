package com.xiongx9527.askdata.embedded;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A11 嵌入签名协议：AES 信封字段、Origin/时间窗口/UA/时区严格校验，旧 HMAC 兼容开关默认关闭。
 */
class EmbeddedSignatureVerifierTest {

    private static final String KEY = "unit-test-embedded-aes-key";
    private static final String ORIGIN = "https://host.example.com";
    private static final String UA = "Mozilla/5.0 (Macintosh) AppleWebKit/537.36";
    private static final String TZ = "Asia/Shanghai";

    private final EmbeddedSignatureVerifier verifier = new EmbeddedSignatureVerifier(KEY, false);

    private String token(long signTime) {
        return verifier.issue(7L, ORIGIN, signTime, false, UA, TZ);
    }

    @Test
    void validEnvelopeAccepted() {
        long now = 1_700_000_000L;
        String token = token(now);
        assertDoesNotThrow(() -> verifier.verify(token, ORIGIN, UA, TZ, now));
    }

    @Test
    void wrongOriginRejected() {
        long now = 1_700_000_000L;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(token(now), "https://evil.example.com", UA, TZ, now));
        assertTrue(e.getMessage().contains("origin"));
    }

    @Test
    void outsideWindowRejected() {
        long now = 1_700_000_000L;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(token(now - 11), ORIGIN, UA, TZ, now));
        assertTrue(e.getMessage().contains("window"));
    }

    @Test
    void userAgentMismatchRejected() {
        long now = 1_700_000_000L;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(token(now), ORIGIN, UA + " Chrome/1", TZ, now));
        assertTrue(e.getMessage().contains("user agent"));
    }

    @Test
    void timezoneMismatchRejected() {
        long now = 1_700_000_000L;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(token(now), ORIGIN, UA, "UTC", now));
        assertTrue(e.getMessage().contains("timezone"));
    }

    @Test
    void webdriverEnvelopeRejected() {
        long now = 1_700_000_000L;
        String token = verifier.issue(7L, ORIGIN, now, true, UA, TZ);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(token, ORIGIN, UA, TZ, now));
        assertTrue(e.getMessage().contains("webdriver"));
    }

    @Test
    void tamperedTokenRejected() {
        long now = 1_700_000_000L;
        String token = token(now);
        byte[] raw = java.util.Base64.getUrlDecoder().decode(token);
        raw[raw.length - 1] ^= 0x01;
        String tampered = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(tampered, ORIGIN, UA, TZ, now));
    }

    @Test
    void missingKeyGivesReadableError() {
        EmbeddedSignatureVerifier noKey = new EmbeddedSignatureVerifier("", false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> noKey.decrypt("anything"));
        assertTrue(e.getMessage().contains("aes-key"));
    }

    @Test
    void legacyHmacDisabledByDefault() {
        assertFalse(new EmbeddedSignatureVerifier(KEY, false).legacyHmacEnabled());
    }

    @Test
    void legacyHmacEnabledWhenConfigured() {
        assertTrue(new EmbeddedSignatureVerifier(KEY, true).legacyHmacEnabled());
    }
}
