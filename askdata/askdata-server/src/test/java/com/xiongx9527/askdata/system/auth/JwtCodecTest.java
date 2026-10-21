package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.system.auth.JwtCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 编解码单测（对齐 SQLBot {@code common/core/security.py} 的 HS256 语义）。
 *
 * <p>关键点：askdata 签发的 token 必须与 SQLBot（PyJWT）互通——同样的载荷与密钥，
 * 产出同样的 header/payload base64url（签名因实现差异不逐字比较，但可互相验签）。</p>
 */
class JwtCodecTest {

    private static final String SECRET = "unit-test-secret-key";

    @Test
    @DisplayName("签发的 token 为三段式 base64url，载荷可解码回来")
    void encodeProducesThreePartToken() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account", "admin");
        payload.put("id", 1);
        payload.put("oid", 1);

        String token = JwtCodec.encode(payload, SECRET, 3600L);

        assertNotNull(token);
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "JWT 应为 header.payload.signature 三段");
        assertTrue(token.chars().noneMatch(c -> c == '+' || c == '/' || c == '='),
                "base64url 不应含 + / = 填充");

        Map<String, Object> decoded = JwtCodec.decode(token, SECRET, true);
        assertEquals("admin", decoded.get("account"));
        assertEquals(1, ((Number) decoded.get("id")).intValue());
        assertEquals(1, ((Number) decoded.get("oid")).intValue());
        assertNotNull(decoded.get("exp"));
    }

    @Test
    @DisplayName("错误的密钥无法验签")
    void wrongSecretRejected() {
        String token = JwtCodec.encode(java.util.Collections.singletonMap("id", 1), SECRET, 3600L);
        JwtCodec.JwtException e = assertThrows(JwtCodec.JwtException.class,
                () -> JwtCodec.decode(token, "another-secret", true));
        assertTrue(e.getMessage().contains("signature"), e.getMessage());
    }

    @Test
    @DisplayName("过期 token 校验失败")
    void expiredTokenRejected() {
        String token = JwtCodec.encode(java.util.Collections.singletonMap("id", 1), SECRET, -10L);
        JwtCodec.JwtException e = assertThrows(JwtCodec.JwtException.class,
                () -> JwtCodec.decode(token, SECRET, true));
        assertTrue(e.getMessage().contains("expired"), e.getMessage());
    }

    @Test
    @DisplayName("verifyExpiration=false 时不校验 exp（对齐 SQLBot 的 ask/embedded 通道）")
    void expirationNotVerifiedWhenDisabled() {
        String token = JwtCodec.encode(java.util.Collections.singletonMap("id", 1), SECRET, -10L);
        Map<String, Object> decoded = JwtCodec.decode(token, SECRET, false);
        assertEquals(1, ((Number) decoded.get("id")).intValue());
    }

    @Test
    @DisplayName("不验签解析可取出载荷（access_key / embeddedId 回退用）")
    void decodeWithoutSignatureReadsPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_key", "ak-123");
        payload.put("embeddedId", 42);
        String token = JwtCodec.encode(payload, SECRET, 3600L);

        Map<String, Object> decoded = JwtCodec.decodeWithoutSignature(token);
        assertEquals("ak-123", decoded.get("access_key"));
        assertEquals(42, ((Number) decoded.get("embeddedId")).intValue());
    }

    @Test
    @DisplayName("格式非法的 token 抛异常")
    void malformedTokenRejected() {
        assertThrows(JwtCodec.JwtException.class, () -> JwtCodec.decode("not-a-jwt", SECRET, true));
        assertThrows(JwtCodec.JwtException.class, () -> JwtCodec.decode(null, SECRET, true));
    }

    @Test
    @DisplayName("载荷被篡改则验签失败")
    void tamperedPayloadRejected() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_key", "ak-original");
        String token = JwtCodec.encode(payload, SECRET, 3600L);
        String[] parts = token.split("\\.");
        // 替换 payload 段（重新 base64url 编码），签名不再匹配
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"access_key\":\"ak-forged\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forged + "." + parts[2];

        assertThrows(JwtCodec.JwtException.class, () -> JwtCodec.decode(tampered, SECRET, true));
    }
}
