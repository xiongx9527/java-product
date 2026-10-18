package com.xiongx9527.askdata.system.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 口令/敏感参数混淆加解密（Decisions D1）。
 *
 * 前端（含原始 SQLBot 前端惯例）：CryptoJS AES-ECB/PKCS7，key = {@code SQLBot1234567890}，
 * 输出 Base64。本类与前端 key/模式逐字互通。
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
