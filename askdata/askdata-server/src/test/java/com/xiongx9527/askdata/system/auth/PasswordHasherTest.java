package com.xiongx9527.askdata.system.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地口令版本化（对齐 SQLBot common/core/passwords.py，提交 8b4097f2）。
 *
 * <p>覆盖：新写入 Argon2id 格式与往返、遗留 MD5 只读兼容、包装 MD5 校验、
 * needsUpgrade 三态、参数越界拒绝、篡改检测、PHC 编解码稳定性。
 * 算法由 BouncyCastle Argon2BytesGenerator（标准实现）提供，本测试聚焦格式与流程。</p>
 */
class PasswordHasherTest {

    // ------------------------------------------------------------------
    // 新写入：Argon2id
    // ------------------------------------------------------------------

    @Test
    void hashPasswordProducesArgon2idPhcFormat() {
        String hash = PasswordHasher.hashPassword("hello world");
        assertTrue(hash.startsWith("$argon2id$v=19$"), hash);
        assertTrue(hash.contains("m=19456,t=2,p=1$"), hash);
        // $ 分段：[0]="" [1]=argon2id [2]=v=19 [3]=参数 [4]=salt [5]=hash
        // salt(16字节→22字符) + hash(32字节→43字符)，无填充
        String[] parts = hash.split("\\$");
        assertEquals(6, parts.length, hash);
        assertEquals(22, parts[4].length(), "salt base64 无填充长度");
        assertEquals(43, parts[5].length(), "hash base64 无填充长度");
        assertFalse(parts[4].contains("=") || parts[5].contains("="), "salt/hash 应为无填充 Base64");
    }

    /**
     * 跨实现兼容黄金向量：由 Node.js crypto.argon2（独立 Argon2id 实现）生成，
     * 参数 m=19456,t=2,p=1。证明本类与标准 argon2 库（含 Python argon2-cffi）互通。
     */
    @Test
    void verifiesExternalArgon2Vector() {
        String vector = "$argon2id$v=19$m=19456,t=2,p=1$PpADkNipkm2qqdXI5jpj6Q$FilGdDz/HHPolfcJDmDO/0fUSwlZpZfe5xFaEGUIavU";
        assertTrue(PasswordHasher.verifyPassword("cross-check-123", vector));
        assertFalse(PasswordHasher.verifyPassword("wrong", vector));
        assertFalse(PasswordHasher.needsUpgrade(vector), "参数与当前默认一致，无需升级");
    }

    @Test
    void argon2idRoundTrip() {
        String hash = PasswordHasher.hashPassword("S3cret!口令");
        assertTrue(PasswordHasher.verifyPassword("S3cret!口令", hash));
        assertFalse(PasswordHasher.verifyPassword("wrong", hash));
        assertFalse(PasswordHasher.verifyPassword("", hash));
    }

    @Test
    void saltMakesHashNonDeterministic() {
        String a = PasswordHasher.hashPassword("same");
        String b = PasswordHasher.hashPassword("same");
        assertNotEquals(a, b, "每次应随机盐，产出不同密文");
        assertTrue(PasswordHasher.verifyPassword("same", a));
        assertTrue(PasswordHasher.verifyPassword("same", b));
    }

    // ------------------------------------------------------------------
    // 遗留 MD5 只读兼容
    // ------------------------------------------------------------------

    @Test
    void legacyMd5StillVerifies() {
        String md5 = md5("admin123");
        assertTrue(PasswordHasher.isLegacyPassword(md5));
        assertTrue(PasswordHasher.verifyPassword("admin123", md5));
        assertFalse(PasswordHasher.verifyPassword("wrong", md5));
        assertTrue(PasswordHasher.needsUpgrade(md5));
    }

    @Test
    void legacyMd5DetectionRejectsNonMd5() {
        assertFalse(PasswordHasher.isLegacyPassword("not-md5"));
        assertFalse(PasswordHasher.isLegacyPassword(md5("x").toUpperCase()));
        assertFalse(PasswordHasher.isLegacyPassword(null));
    }

    // ------------------------------------------------------------------
    // 包装 MD5：迁移产物
    // ------------------------------------------------------------------

    @Test
    void wrappedMd5RoundTrip() {
        String wrapped = PasswordHasher.wrapLegacyPassword(md5("legacy-pass"));
        assertTrue(wrapped.startsWith(PasswordHasher.WRAPPED_MD5));
        assertTrue(PasswordHasher.verifyPassword("legacy-pass", wrapped));
        assertFalse(PasswordHasher.verifyPassword("other", wrapped));
        assertTrue(PasswordHasher.needsUpgrade(wrapped));
    }

    @Test
    void wrapRejectsNonLegacyValue() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.wrapLegacyPassword("$argon2id$v=19$..."));
    }

    // ------------------------------------------------------------------
    // needsUpgrade 三态
    // ------------------------------------------------------------------

    @Test
    void freshArgon2DoesNotNeedUpgrade() {
        String hash = PasswordHasher.hashPassword("fresh");
        assertFalse(PasswordHasher.needsUpgrade(hash));
    }

    @Test
    void corruptAndUnknownFormsAreSafe() {
        assertFalse(PasswordHasher.needsUpgrade(null));
        assertFalse(PasswordHasher.needsUpgrade("garbage"));
        assertFalse(PasswordHasher.verifyPassword("x", "garbage"));
        assertFalse(PasswordHasher.verifyPassword("x", "$argon2id$v=19$broken"));
        assertFalse(PasswordHasher.verifyPassword(null, PasswordHasher.hashPassword("x")));
    }

    // ------------------------------------------------------------------
    // 参数越界 / 篡改检测（对齐上游 verify_password 的资源防护）
    // ------------------------------------------------------------------

    @Test
    void outOfRangeParamsAreRejected() {
        // m=1（低于下限8）—— 构造一个参数越界的 PHC 串
        String bad = "$argon2id$v=19$m=1,t=2,p=1$"
                + b64(new byte[16]) + "$" + b64(new byte[32]);
        assertFalse(PasswordHasher.verifyPassword("anything", bad));
        assertFalse(PasswordHasher.needsUpgrade(bad));
    }

    @Test
    void tamperedHashFailsVerification() {
        String hash = PasswordHasher.hashPassword("integrity");
        String[] parts = hash.split("\\$");
        byte[] h = Base64.getDecoder().decode(parts[4]);
        h[0] ^= 0x01; // 翻转一个 bit
        String tampered = parts[0] + "$" + parts[1] + "$" + parts[2] + "$" + parts[3]
                + "$" + b64(h);
        assertFalse(PasswordHasher.verifyPassword("integrity", tampered));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }
}
