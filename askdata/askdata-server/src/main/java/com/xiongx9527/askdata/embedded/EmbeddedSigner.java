package com.xiongx9527.askdata.embedded;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 嵌入式助手换取 token 的签名校验（A7；对齐 SQLBot xpack {@code SQLBOT-EMBEDDED-SIGN} 校验语义）。
 *
 * <p>签名头格式：{@code SQLBOT-EMBEDDED-SIGN: <assistantId>.<timestampSeconds>.<hexHmac>}，
 * 其中 {@code hexHmac = HMAC-SHA256(appSecret, assistantId + "." + timestampSeconds)}。</p>
 *
 * <p>校验项（任一不满足即拒绝）：</p>
 * <ul>
 *   <li>签名头存在且可解析；</li>
 *   <li>签名头中的 assistantId 与路径/查询中的助手 id 一致；</li>
 *   <li>时间戳落在允许窗口内（默认 ±5 分钟，防重放）；</li>
 *   <li>HMAC 与期望值常量时间相等；</li>
 *   <li>User-Agent 不含 webdriver 特征（防自动化重放）。</li>
 * </ul>
 *
 * <p>origin 与助手 domain 匹配仍由调用方额外校验（A7 保留既有边界，签名是新增的充分必要条件）。</p>
 */
public final class EmbeddedSigner {

    /** 签名头名（与 SQLBot 一致）。 */
    public static final String HEADER = "SQLBOT-EMBEDDED-SIGN";

    /** 允许的时间窗口（秒）：防重放。 */
    public static final long WINDOW_SECONDS = 300L;

    private static final String ALGORITHM = "HmacSHA256";

    private EmbeddedSigner() {
    }

    /** 计算签名（供嵌入页生成请求头与测试使用）。 */
    public static String sign(long assistantId, long timestampSeconds, String appSecret) {
        String data = assistantId + "." + timestampSeconds;
        return assistantId + "." + timestampSeconds + "." + hmacHex(appSecret, data);
    }

    /**
     * 校验签名头。
     *
     * @param headerValue  {@code SQLBOT-EMBEDDED-SIGN} 头值
     * @param assistantId  请求目标助手 id
     * @param appSecret    助手 app_secret
     * @param userAgent    请求 User-Agent（可为 null）
     * @param nowSeconds   当前时间（秒）
     * @return 校验通过返回 true
     */
    public static boolean valid(String headerValue, long assistantId, String appSecret, String userAgent,
                                long nowSeconds) {
        if (headerValue == null || headerValue.trim().isEmpty()) {
            return false;
        }
        if (appSecret == null || appSecret.isEmpty()) {
            return false;
        }
        if (isAutomatedUserAgent(userAgent)) {
            return false;
        }
        String[] parts = headerValue.trim().split("\\.");
        if (parts.length != 3) {
            return false;
        }
        long headerId;
        long timestamp;
        try {
            headerId = Long.parseLong(parts[0]);
            timestamp = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (headerId != assistantId) {
            return false;
        }
        long delta = Math.abs(nowSeconds - timestamp);
        if (delta > WINDOW_SECONDS) {
            return false;
        }
        String expected = hmacHex(appSecret, headerId + "." + timestamp);
        return constantTimeEquals(expected, parts[2]);
    }

    /** webdriver / 无头浏览器特征（对齐 SQLBot 对 UA 的判定）。 */
    static boolean isAutomatedUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return false;
        }
        String ua = userAgent.toLowerCase(java.util.Locale.ROOT);
        return ua.contains("webdriver") || ua.contains("headlesschrome") || ua.contains("phantomjs");
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute embedded sign", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(x, y);
    }
}
