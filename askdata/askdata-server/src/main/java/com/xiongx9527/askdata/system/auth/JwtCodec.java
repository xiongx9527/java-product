package com.xiongx9527.askdata.system.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HS256 JWT 编解码（对齐 SQLBot {@code common/core/security.py}：{@code ALGORITHM = "HS256"}，
 * {@code create_access_token} 用 {@code settings.SECRET_KEY} 签名）。
 *
 * <p>SQLBot 用 PyJWT，载荷为 JSON 对象、签名覆盖 {@code header.payload}（base64url 无 padding）。
 * 本实现与之逐字互通，因此 askdata 签发的 token 可被 SQLBot 校验，反之亦然。</p>
 *
 * <p>刻意不引入额外 JWT 依赖：本机 {@code ~/.m2} 中 jjwt 的可用版本（0.12.3 等）与项目
 * Spring Boot 3.5 的 Jackson 栈需要额外坐标，而 hutool-all（项目已有传递依赖）自带 JWT；
 * 为保持零新增依赖风险，这里直接用 JDK 的 {@link Mac} 实现 HS256，语义与 PyJWT 一致。</p>
 */
public final class JwtCodec {

    /** 与 SQLBot {@code security.ALGORITHM} 逐字一致。 */
    public static final String ALGORITHM = "HS256";

    private static final String HMAC_SHA256 = "HmacSHA256";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtCodec() {
    }

    /** 签发 HS256 token（载荷 + exp 秒时间戳）。 */
    public static String encode(Map<String, Object> payload, String secret, Long expiresInSeconds) {
        Map<String, Object> claims = new LinkedHashMap<>();
        if (payload != null) {
            claims.putAll(payload);
        }
        if (expiresInSeconds != null) {
            claims.put("exp", System.currentTimeMillis() / 1000L + expiresInSeconds);
        }
        String header = ENCODER.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body;
        try {
            body = ENCODER.encodeToString(MAPPER.writeValueAsBytes(claims));
        } catch (Exception e) {
            throw new JwtException("Unable to encode token payload: " + e.getMessage());
        }
        String signingInput = header + "." + body;
        String signature = ENCODER.encodeToString(hmac(signingInput, secret));
        return signingInput + "." + signature;
    }

    /**
     * 校验签名并返回载荷；签名不匹配或格式非法时抛 {@link JwtException}。
     *
     * @param verifyExpiration 是否校验 exp（SQLBot 的 ask/embedded 通道用 {@code verify_exp: False}）
     */
    public static Map<String, Object> decode(String token, String secret, boolean verifyExpiration) {
        if (token == null || token.isEmpty()) {
            throw new JwtException("Miss token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtException("Invalid token format");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(signingInput, secret);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Invalid token signature encoding");
        }
        if (!MessageDigestHolder.constantTimeEquals(expected, actual)) {
            throw new JwtException("Invalid token signature");
        }
        Map<String, Object> claims;
        try {
            claims = MAPPER.readValue(DECODER.decode(parts[1]), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new JwtException("Invalid token payload");
        }
        if (verifyExpiration) {
            Object exp = claims.get("exp");
            if (exp == null) {
                throw new JwtException("Token expired");
            }
            long expireAt = exp instanceof Number
                    ? ((Number) exp).longValue()
                    : Long.parseLong(String.valueOf(exp));
            if (expireAt < System.currentTimeMillis() / 1000L) {
                throw new JwtException("Token expired");
            }
        }
        return claims;
    }

    /**
     * 只解析载荷、不校验签名（对齐 SQLBot {@code jwt.decode(param, options={"verify_signature": False})}）。
     * 仅用于"先取 access_key / embeddedId 再用真实密钥二次验签"的通道。
     */
    public static Map<String, Object> decodeWithoutSignature(String token) {
        if (token == null || token.isEmpty()) {
            throw new JwtException("Miss token");
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new JwtException("Invalid token format");
        }
        try {
            return MAPPER.readValue(DECODER.decode(parts[1]), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new JwtException("Invalid token payload");
        }
    }

    private static byte[] hmac(String signingInput, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new JwtException("Unable to sign token: " + e.getMessage());
        }
    }

    /** 定长比较（避免签名校验的时序侧信道）。 */
    static final class MessageDigestHolder {
        private MessageDigestHolder() {
        }

        static boolean constantTimeEquals(byte[] a, byte[] b) {
            if (a == null || b == null || a.length != b.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < a.length; i++) {
                result |= a[i] ^ b[i];
            }
            return result == 0;
        }
    }

    /** JWT 处理异常（对齐 SQLBot 侧的 jwt 异常分类：expired / invalid）。 */
    public static class JwtException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JwtException(String message) {
            super(message);
        }
    }
}
