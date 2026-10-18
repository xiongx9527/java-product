package com.xiongx9527.askdata.system.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 口令/敏感参数混淆加解密（Decisions D1）。
 *
 * 前端（含原始 SQLBot 前端惯例，askdata 拷贝中的 utils/xpackPatch.ts）：CryptoJS
 * AES-ECB/PKCS7，key = {@code SQLBot1234567890}，输出 Base64。本类与前端 key/模式逐字互通。
 *
 * <p>AI 模型 api_key/api_domain 的往返形态对齐 SQLBot {@code apps/system/api/aimodel.py}：</p>
 * <ul>
 *   <li>写入：前端已加密的值原样落库；明文（curl/脚本）自动加密后落库，DB 中不出现明文；</li>
 *   <li>读取（GET /system/aimodel/{id}）：解密为明文返回，前端编辑表单展示后再次
 *       {@code sqlbotEncrypt} 提交，单层往返不叠加；</li>
 *   <li>使用（连通检查/对话）：解密为明文后访问模型端点。</li>
 * </ul>
 */
public final class CryptoUtil {

    /** AES key：与前端 CryptoJS 惯例一致（16 字节）。 */
    public static final String SQLBOT_AES_KEY = "SQLBot1234567890";

    private CryptoUtil() {
    }

    /** 解密前端 AES-ECB/PKCS7 + Base64 密文；非法输入返回 null。 */
    public static String decryptSqlbot(String cipherTextBase64) {
        if (cipherTextBase64 == null || cipherTextBase64.isEmpty()) {
            return null;
        }
        try {
            SecretKeySpec key = new SecretKeySpec(SQLBOT_AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(cipherTextBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 与前端 {@code sqlbotEncrypt} 同构：AES-ECB/PKCS7 + Base64。 */
    public static String encryptSqlbot(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            SecretKeySpec key = new SecretKeySpec(SQLBOT_AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("AES encrypt failed", e);
        }
    }

    /**
     * 落库规范化（写入路径）：已加密值原样返回；明文自动加密。
     * 保证 DB 中 api_key/api_domain 均为 AES 密文（SQLBot async_model_info 的迁移语义）。
     */
    public static String normalizeForStore(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (decryptSqlbot(value) != null) {
            return value;
        }
        return encryptSqlbot(value);
    }

    /**
     * 读取明文（读取/使用路径）：http(s) 开头视为历史明文，直接返回；
     * 否则尝试解密，失败则按原样返回（兼容手写明文/异常输入）。
     */
    public static String resolvePlain(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (stored.startsWith("http://") || stored.startsWith("https://")) {
            return stored;
        }
        String plain = decryptSqlbot(stored);
        return plain == null ? stored : plain;
    }

    /** MD5 hex（小写）——口令落库/校验格式（SQLBot md5pwd 同）。 */
    public static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
