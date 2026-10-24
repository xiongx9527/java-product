package com.xiongx9527.askdata.system.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.Base64;

/**
 * 口令/敏感参数混淆加解密（Decisions D1）。
 *
 * <p>前端（含原始 SQLBot 前端惯例，askdata 拷贝中的 utils/xpackPatch.ts）：CryptoJS
 * AES-ECB/PKCS7，key = {@code SQLBot1234567890}，输出 Base64。本类与前端 key/模式逐字互通。</p>
 *
 * <p>AI 模型 api_key/api_domain 的往返形态对齐 SQLBot {@code apps/system/api/aimodel.py}：</p>
 * <ul>
 *   <li>写入：前端已加密的值原样落库；明文（curl/脚本）自动加密后落库，DB 中不出现明文；</li>
 *   <li>读取（GET /system/aimodel/{id}）：解密为明文返回，前端编辑表单展示后再次
 *       {@code sqlbotEncrypt} 提交，单层往返不叠加；</li>
 *   <li>使用（连通检查/对话）：解密为明文后访问模型端点。</li>
 * </ul>
 *
 * <p><b>应用密钥配置化</b>：落库加密密钥来自配置 {@code askdata.secret.key}
 * （可用环境变量 {@code ASKDATA_SECRET_KEY} 覆盖），缺省值与历史常量一致，
 * 保证老库数据仍可解密（对齐 SQLBot 走 {@code SECRET_KEY} 环境变量）。</p>
 */
@Component
public class CryptoUtil {

    private static final Logger log = LoggerFactory.getLogger(CryptoUtil.class);

    /** AES key：与前端 CryptoJS 惯例一致（16 字节）。 */
    public static final String SQLBOT_AES_KEY = "SQLBot1234567890";

    // 应用 SECRET_KEY 不再内置缺省值：通过 ASKDATA_SECRET_KEY / askdata.secret.key 配置；未配置时运行期回退（见 generateFallbackSecretKey）。

    /**
     * simple_aes 的固定 IV 文本（对齐 SQLBot {@code common/utils/aes_crypto.py} 的
     * {@code simple_aes_iv_text = 'sqlbot_em_aes_iv'}）。
     */
    public static final String SIMPLE_AES_IV_TEXT = "sqlbot_em_aes_iv";

    /**
     * 应用 SECRET_KEY（AES-128，16 字节）：服务端敏感字段的落库加密密钥。
     *
     * <p>数据源 configuration（spec §3）：前端 CryptoJS AES-ECB（key {@link #SQLBOT_AES_KEY}）传输，
     * 服务端解密后以本密钥 AES 再次加密落库，DB 中不出现传输层可解的明文。
     * 密钥由配置提供（{@code askdata.secret.key}，可用环境变量 {@code ASKDATA_SECRET_KEY} 覆盖）；未配置时运行期生成兜底密钥（见 {@code generateFallbackSecretKey}），运维应显式设定以便解密老库数据。</p>
     */
    private final String askDataSecretKey;

    @Autowired
    public CryptoUtil(@Value("${askdata.secret.key:}") String askDataSecretKey) {
        if (askDataSecretKey == null || askDataSecretKey.isEmpty()) {
            // 不内置缺省密钥：未配置时用运行期随机密钥保证可启动，但历史落库密文将不可解
            log.warn("未配置 askdata.secret.key / ASKDATA_SECRET_KEY，使用运行期随机密钥；"
                    + "重启后历史落库密文不可解密，请在部署环境显式配置密钥");
            this.askDataSecretKey = generateFallbackSecretKey();
        } else {
            this.askDataSecretKey = askDataSecretKey;
        }
    }

    /**
     * 应用密钥（供落库加密使用；16 字节 AES-128 密钥）。
     *
     * <p>配置值长度非 16 时按 SHA-256 前 16 字节派生，保证 AES 密钥长度合法；
     * 这样运维可用任意长度的口令配置，而不必凑够 16 字节。</p>
     */
    public String askDataSecretKey() {
        byte[] bytes = askDataSecretKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 16) {
            return askDataSecretKey;
        }
        return sha256HexPrefix(askDataSecretKey, 16);
    }

    /** 无参构造（密钥未配置时回退到运行期兜底密钥；供纯静态调用场景与单测使用）。 */
    public CryptoUtil() {
        this("");
    }

    // ------------------------------------------------------------------
    // 前端 AES 互通（CryptoJS AES-ECB/PKCS7 + Base64）
    // ------------------------------------------------------------------

    /** 解密前端 AES-ECB/PKCS7 + Base64 密文；非法输入返回 null。 */
    public static String decryptSqlbot(String cipherTextBase64) {
        return decrypt(SQLBOT_AES_KEY, cipherTextBase64);
    }

    /** 与前端 {@code sqlbotEncrypt} 同构：AES-ECB/PKCS7 + Base64。 */
    public static String encryptSqlbot(String plainText) {
        return encrypt(SQLBOT_AES_KEY, plainText);
    }

    // ------------------------------------------------------------------
    // 应用密钥加解密（落库形态）
    // ------------------------------------------------------------------

    /** 应用 SECRET_KEY 解密（数据源 configuration 落库形态）；非法输入返回 null。 */
    public String decryptWithAppSecret(String cipherTextBase64) {
        return decrypt(askDataSecretKey(), cipherTextBase64);
    }

    /** 应用 SECRET_KEY 加密（数据源 configuration 落库形态）。 */
    public String encryptWithAppSecret(String plainText) {
        return encrypt(askDataSecretKey(), plainText);
    }

    // ------------------------------------------------------------------
    // simple_aes 兼容（对齐 SQLBot common/utils/aes_crypto.py）
    // ------------------------------------------------------------------

    /**
     * simple_aes 加密（对齐 SQLBot {@code simple_aes_encrypt}）。
     *
     * <p>语义：<b>AES-128-CBC</b>，密钥取 {@code key} 的前 16 字节，IV 取
     * {@code ivText} 的前 16 字节（缺省 {@link #SIMPLE_AES_IV_TEXT}），PKCS7 填充，
     * 输出 Base64。已实测与 SQLBot 运行实例的 {@code SecureEncryption.simple_aes_encrypt}
     * 逐字一致（{@code "hello world"} → {@code "7TAQYNnpdT3QsUQDYoebwQ=="}）。</p>
     */
    public static String simpleAesEncrypt(String plainText, String key, String ivText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = ivBytes(ivText);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(key), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("simple_aes encrypt failed", e);
        }
    }

    /** simple_aes 解密（对齐 SQLBot {@code simple_aes_decrypt}）；非法输入返回 null。 */
    public static String simpleAesDecrypt(String cipherTextBase64, String key, String ivText) {
        if (cipherTextBase64 == null || cipherTextBase64.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = ivBytes(ivText);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(key), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(cipherTextBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** simple_aes 加密（缺省 IV 文本）。 */
    public static String simpleAesEncrypt(String plainText, String key) {
        return simpleAesEncrypt(plainText, key, SIMPLE_AES_IV_TEXT);
    }

    /** simple_aes 解密（缺省 IV 文本）。 */
    public static String simpleAesDecrypt(String cipherTextBase64, String key) {
        return simpleAesDecrypt(cipherTextBase64, key, SIMPLE_AES_IV_TEXT);
    }

    /** simple_aes 的 IV 字节（取 ivText 前 16 字节；不足则右侧补 0）。 */
    private static byte[] ivBytes(String ivText) {
        String text = ivText == null || ivText.isEmpty() ? SIMPLE_AES_IV_TEXT : ivText;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[16];
        System.arraycopy(bytes, 0, iv, 0, Math.min(bytes.length, 16));
        return iv;
    }

    /** simple_aes 的密钥字节（取 key 前 16 字节；不足则右侧补 0）。 */
    private static SecretKeySpec keySpec(String key) {
        String text = key == null ? "" : key;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] material = new byte[16];
        System.arraycopy(bytes, 0, material, 0, Math.min(bytes.length, 16));
        return new SecretKeySpec(material, "AES");
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    private static String decrypt(String key, String cipherTextBase64) {
        if (cipherTextBase64 == null || cipherTextBase64.isEmpty()) {
            return null;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(cipherTextBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String encrypt(String key, String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
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

    /** SHA-256 十六进制文本的前 n 个字节（用于派生固定长度 AES 密钥）。 */
    /**
     * 密钥未配置时的运行期兜底密钥：不写入版本库，保证应用可启动。
     * 运维应通过 ASKDATA_SECRET_KEY / askdata.secret.key 显式设定，以便解密历史落库数据。
     */
    private static String generateFallbackSecretKey() {
        return java.util.UUID.randomUUID().toString();
    }
    private static String sha256HexPrefix(String text, int bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes * 2);
            for (int i = 0; i < bytes && i < hash.length; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("SHA-256 unavailable，使用缺省密钥: {}", e.getMessage());
            throw new IllegalStateException("SHA-256 unavailable, cannot derive AES key", e);
        }
    }
}
