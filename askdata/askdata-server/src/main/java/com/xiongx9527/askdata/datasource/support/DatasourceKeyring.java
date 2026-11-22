package com.xiongx9527.askdata.datasource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.system.auth.CryptoUtil;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据源配置独立密钥环（对齐 SQLBot 6dcad176 / datasource/utils/utils.py）。
 *
 * <p>每部署一份密钥文件，落库密文用 AES-256-GCM（带认证标签）。存储格式：
 * {@code gcm1:<keyId>:base64(nonce[12] || tag[16] || ciphertext)}，header 作为 AAD 参与认证。</p>
 *
 * <p>密钥文件缺省 {@code ./data/.secrets/datasource-keys.json}，可用环境变量
 * {@code ASKDATA_DATASOURCE_KEYS_FILE} 覆盖。文件不存在且无存量加密配置时原子创建；
 * 有存量加密配置但文件缺失时抛错（绝不静默生成新密钥导致旧数据永久不可解）。</p>
 */
public final class DatasourceKeyring {

    private static final String VERSION = "gcm1";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64_ENC = Base64.getEncoder();
    private static final Base64.Decoder B64_DEC = Base64.getDecoder();

    /** 当前生效的密钥环（未初始化时按环境变量/缺省路径惰性加载）。 */
    private static volatile DatasourceKeyring instance;

    private final Path path;
    private final String activeId;
    private final Map<String, byte[]> keys;
    private final ObjectMapper mapper = new ObjectMapper();

    private DatasourceKeyring(Path path, String activeId, Map<String, byte[]> keys) {
        this.path = path;
        this.activeId = activeId;
        this.keys = keys;
    }

    /** 用显式密钥文件路径初始化（应用启动时调用）。 */
    public static synchronized void initialize(String keyFilePath, boolean hasEncryptedConfigs) {
        Path path = Paths.get(keyFilePath);
        if (!Files.exists(path) && !hasEncryptedConfigs) {
            createKeyFile(path);
        }
        instance = load(path);
    }

    /** 清空单例（测试用）。 */
    public static synchronized void reset() {
        instance = null;
    }

    public static DatasourceKeyring get() {
        DatasourceKeyring k = instance;
        if (k == null) {
            throw new IllegalStateException("DatasourceKeyring 未初始化");
        }
        return k;
    }

    /** 当前 active 密钥 id（迁移器判断是否需重加密）。 */
    public String activeId() {
        return activeId;
    }

    /** 缺省密钥文件路径：环境变量优先，否则 ./data/.secrets/datasource-keys.json。 */
    public static String defaultPath() {
        String env = System.getenv("ASKDATA_DATASOURCE_KEYS_FILE");
        return env != null && !env.trim().isEmpty()
                ? env.trim() : Paths.get("data", ".secrets", "datasource-keys.json").toString();
    }

    // ------------------------------------------------------------------
    // 密钥文件
    // ------------------------------------------------------------------

    private static void createKeyFile(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            setOwnerOnly(parent);
            byte[] key = new byte[KEY_BYTES];
            RANDOM.nextBytes(key);
            Map<String, Object> doc = new LinkedHashMap<String, Object>();
            doc.put("active", "primary");
            Map<String, String> keyMap = new LinkedHashMap<String, String>();
            keyMap.put("primary", B64_ENC.encodeToString(key));
            doc.put("keys", keyMap);
            byte[] content = new ObjectMapper().writeValueAsBytes(doc);
            Path tmp = Files.createTempFile(parent, "datasource-keys-", ".tmp");
            try {
                Files.write(tmp, content);
                setOwnerOnly(tmp);
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化数据源密钥文件: " + path, e);
        }
    }

    private static DatasourceKeyring load(Path path) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = new ObjectMapper().readValue(Files.readAllBytes(path), Map.class);
            String active = String.valueOf(doc.get("active"));
            @SuppressWarnings("unchecked")
            Map<String, Object> rawKeys = (Map<String, Object>) doc.get("keys");
            if (active == null || rawKeys == null) {
                throw new IllegalArgumentException("密钥文件缺少 active/keys");
            }
            Map<String, byte[]> keys = new LinkedHashMap<String, byte[]>();
            for (Map.Entry<String, Object> e : rawKeys.entrySet()) {
                String id = e.getKey();
                byte[] key = B64_DEC.decode(String.valueOf(e.getValue()));
                if (!KEY_ID.matcher(id).matches() || key.length != KEY_BYTES) {
                    throw new IllegalArgumentException("非法密钥 id 或长度: " + id);
                }
                keys.put(id, key);
            }
            if (!keys.containsKey(active)) {
                throw new IllegalArgumentException("active 密钥不存在: " + active);
            }
            return new DatasourceKeyring(path, active, Collections.unmodifiableMap(keys));
        } catch (IOException e) {
            throw new IllegalStateException("数据源密钥文件缺失或不可读: " + path
                    + "（请恢复原密钥文件，否则已加密配置无法解密）", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("数据源密钥文件格式非法: " + path, e);
        }
    }

    private static void setOwnerOnly(Path p) {
        try {
            Files.setPosixFilePermissions(p,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (Exception ignored) {
            // 非 POSIX 文件系统忽略（仍可读，但尽量限制）
        }
    }

    // ------------------------------------------------------------------
    // 加解密
    // ------------------------------------------------------------------

    /** 明文 → GCM envelope 字符串。 */
    public String encrypt(String plain) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] header = (VERSION + ":" + activeId + ":").getBytes(StandardCharsets.US_ASCII);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.get(activeId), "AES"),
                    new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(header);
            // JCA GCM 输出 ciphertext||tag；上游格式为 nonce||tag||ciphertext，重排 tag 到密文前
            byte[] ctAndTag = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            int ctLen = ctAndTag.length - TAG_BYTES;
            byte[] tag = new byte[TAG_BYTES];
            byte[] ct = new byte[ctLen];
            System.arraycopy(ctAndTag, ctLen, tag, 0, TAG_BYTES);
            System.arraycopy(ctAndTag, 0, ct, 0, ctLen);
            StringBuilder sb = new StringBuilder();
            sb.append(VERSION).append(':').append(activeId).append(':');
            sb.append(B64_ENC.encodeToString(join(nonce, join(tag, ct))));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("数据源配置加密失败", e);
        }
    }

    /** GCM envelope → 明文；非 gcm1 形态抛错（由调用方回退 legacy）。 */
    public String decrypt(String envelope) {
        try {
            String[] parts = envelope.split(":", 3);
            if (parts.length != 3 || !VERSION.equals(parts[0]) || !KEY_ID.matcher(parts[1]).matches()) {
                throw new IllegalArgumentException("非 gcm1 envelope");
            }
            byte[] key = keys.get(parts[1]);
            if (key == null) {
                throw new IllegalStateException("数据源配置密钥不可用: " + parts[1]);
            }
            byte[] raw = B64_DEC.decode(parts[2]);
            if (raw.length < NONCE_BYTES + TAG_BYTES) {
                throw new IllegalArgumentException("envelope 过短");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(raw, 0, nonce, 0, NONCE_BYTES);
            // raw = nonce(12) || tag(16) || ct；重排为 JCA 期望的 ct||tag
            int ctLen = raw.length - NONCE_BYTES - TAG_BYTES;
            byte[] tag = new byte[TAG_BYTES];
            byte[] ct = new byte[ctLen];
            System.arraycopy(raw, NONCE_BYTES, tag, 0, TAG_BYTES);
            System.arraycopy(raw, NONCE_BYTES + TAG_BYTES, ct, 0, ctLen);
            byte[] ctAndTag = join(ct, tag);
            byte[] header = (VERSION + ":" + parts[1] + ":").getBytes(StandardCharsets.US_ASCII);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(header);
            byte[] plain = cipher.doFinal(ctAndTag);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("数据源配置认证失败（密钥错误或数据被篡改）", e);
        }
    }

    /** 是否 gcm1 envelope。 */
    public static boolean isEnvelope(String value) {
        return value != null && value.startsWith(VERSION + ":");
    }

    /** 存量 legacy 密文 → 明文（兼容旧 app-secret ECB 与前端 ECB 两种形态）。 */
    public static String decodeLegacy(String stored, CryptoUtil cryptoUtil) {
        String plain = cryptoUtil.decryptWithAppSecret(stored);
        if (looksLikeJson(plain)) {
            return plain;
        }
        plain = CryptoUtil.decryptSqlbot(stored);
        if (plain != null) {
            return plain;
        }
        return stored; // 明文 JSON
    }

    private static boolean looksLikeJson(String v) {
        if (v == null) {
            return false;
        }
        String t = v.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
