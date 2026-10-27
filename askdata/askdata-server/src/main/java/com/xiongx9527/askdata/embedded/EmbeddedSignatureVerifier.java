package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A11 嵌入签名协议（SQLBot AES 信封）。
 *
 * <p>嵌入 token 是 AES 加密的 JSON 信封，字段与 SQLBot 一致：
 * {@code assistant_id, target, sign_time, webdriver, user_agent, timezone}。校验要求：</p>
 * <ul>
 *   <li>请求 Origin 与信封 {@code target} 相等；</li>
 *   <li>{@code sign_time} 落在当前时间 ±10 秒窗口内；</li>
 *   <li>{@code user_agent} 与请求 UA 逐字一致；</li>
 *   <li>{@code timezone} 与请求时区一致；</li>
 *   <li>{@code webdriver} 不得为 true。</li>
 * </ul>
 *
 * <p>AES key 来自配置 {@code askdata.embedded.aes-key}，可轮换；未配置时给出可读错误。
 * 旧 {@code assistantId.timestamp.hmac} 格式仅在 {@code askdata.embedded.legacy-hmac-enabled=true}
 * 时接受，默认关闭（见 {@link #legacyHmacEnabled()}）。</p>
 */
@Component
public class EmbeddedSignatureVerifier {

    /** 嵌入信封请求头（宿主下发的 AES token）。 */
    public static final String TOKEN_HEADER = "SQLBOT-EMBEDDED-TOKEN";

    /** 时间窗口（秒）：±10 秒。 */
    public static final long WINDOW_SECONDS = 10L;

    private static final String CIPHER = "AES/GCM/NoPadding";

    private static final int IV_LENGTH = 12;

    private static final int TAG_BITS = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String aesKey;

    private final boolean legacyHmacEnabled;

    public EmbeddedSignatureVerifier(@Value("${askdata.embedded.aes-key:}") String aesKey,
                                     @Value("${askdata.embedded.legacy-hmac-enabled:false}") boolean legacyHmacEnabled) {
        this.aesKey = aesKey == null ? "" : aesKey.trim();
        this.legacyHmacEnabled = legacyHmacEnabled;
    }

    /** 旧 {@code assistantId.timestamp.hmac} 兼容开关，默认关闭。 */
    public boolean legacyHmacEnabled() {
        return legacyHmacEnabled;
    }

    /**
     * 宿主侧签发：构造信封并 AES 加密（供嵌入页下发 token，也供测试使用）。
     */
    public String issue(long assistantId, String target, long signTime, boolean webdriver,
                        String userAgent, String timezone) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("assistant_id", assistantId);
        envelope.put("target", target);
        envelope.put("sign_time", signTime);
        envelope.put("webdriver", webdriver);
        envelope.put("user_agent", userAgent);
        envelope.put("timezone", timezone);
        try {
            return encrypt(MAPPER.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException("unable to issue embedded token: " + e.getMessage(), e);
        }
    }

    /** 解密 token 为信封字段；密钥缺失或解密失败给出可读错误。 */
    public Map<String, Object> decrypt(String token) {
        requireKey();
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("embedded token is empty");
        }
        try {
            byte[] all = Base64.getUrlDecoder().decode(token.trim());
            if (all.length <= IV_LENGTH) {
                throw new IllegalArgumentException("embedded token is malformed");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(all, IV_LENGTH, all.length);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return MAPPER.readValue(plain, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("embedded token cannot be decrypted");
        }
    }

    /**
     * 校验嵌入 token。任一条件不满足抛 {@link IllegalArgumentException} 并说明原因。
     */
    public void verify(String token, String origin, String userAgent, String timezone, long nowSeconds) {
        Map<String, Object> envelope = decrypt(token);
        Object target = envelope.get("target");
        Object signTime = envelope.get("sign_time");
        Object ua = envelope.get("user_agent");
        Object tz = envelope.get("timezone");
        Object webdriver = envelope.get("webdriver");
        if (target == null || signTime == null || ua == null || tz == null) {
            throw new IllegalArgumentException("embedded token is missing required fields");
        }
        if (Boolean.TRUE.equals(webdriver)) {
            throw new IllegalArgumentException("embedded token marked as webdriver");
        }
        if (!String.valueOf(target).equals(origin)) {
            throw new IllegalArgumentException("embedded token origin does not match target");
        }
        long signedAt = toLong(signTime, Long.MIN_VALUE);
        if (signedAt == Long.MIN_VALUE || Math.abs(nowSeconds - signedAt) > WINDOW_SECONDS) {
            throw new IllegalArgumentException("embedded token sign_time is outside the allowed window");
        }
        if (!String.valueOf(ua).equals(userAgent)) {
            throw new IllegalArgumentException("embedded token user agent does not match");
        }
        if (!String.valueOf(tz).equals(timezone)) {
            throw new IllegalArgumentException("embedded token timezone does not match");
        }
    }

    private void requireKey() {
        if (aesKey.isEmpty()) {
            throw new IllegalStateException("askdata.embedded.aes-key is not configured; "
                    + "embedded token verification is unavailable");
        }
    }

    private SecretKeySpec secretKey() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(aesKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private String encrypt(String plain) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    private static long toLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
