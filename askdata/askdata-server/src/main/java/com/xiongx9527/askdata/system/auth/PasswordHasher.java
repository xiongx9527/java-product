package com.xiongx9527.askdata.system.auth;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 版本化本地口令（对齐 SQLBot {@code common/core/passwords.py}，提交 8b4097f2）。
 *
 * <p>遗留 MD5 只读兼容；新写入一律加盐 Argon2id。{@code sys_user.password} 存在三种形态：</p>
 * <ul>
 *   <li>遗留 MD5：{@code [0-9a-f]{32}} —— 只读兼容，登录成功后按需升级；</li>
 *   <li>包装 MD5：{@code sqlbot-md5-argon2id-v1:} + Argon2id(MD5明文) —— 迁移产物；</li>
 *   <li>Argon2id：{@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>} —— 新写入标准。</li>
 * </ul>
 *
 * <p>PHC 字符串与 argon2-cffi 逐字兼容：salt/hash 用无填充 Base64，参数顺序 m,t,p。
 * 参数范围校验对齐上游（防损坏/恶意编码触发过度资源分配）。</p>
 */
public final class PasswordHasher {

    /** 包装前缀：被包装的是 MD5，校验时先算 MD5 再喂给 Argon2id。 */
    public static final String WRAPPED_MD5 = "sqlbot-md5-argon2id-v1:";

    private static final String PHC_PREFIX = "$argon2id$v=19$";
    private static final Pattern LEGACY_MD5 = Pattern.compile("[0-9a-f]{32}");

    // 与上游 HASHER 一致的参数
    private static final int TIME_COST = 2;
    private static final int MEMORY_COST_KB = 19456;
    private static final int PARALLELISM = 1;
    private static final int SALT_LEN = 16;
    private static final int HASH_LEN = 32;

    // verify 前对编码串的参数上限（对齐上游 verify_password 的范围校验）
    private static final int MAX_TIME_COST = 10;
    private static final int MAX_MEMORY_COST_KB = 262144;
    private static final int MAX_PARALLELISM = 8;
    private static final int MAX_SALT_LEN = 64;
    private static final int MAX_HASH_LEN = 64;

    private static final SecureRandom RANDOM = new SecureRandom();
    // argon2-cffi 的 PHC 输出用标准 Base64 字母表（+/）且去掉填充 =
    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    private PasswordHasher() {
    }

    /** 是否遗留 32 位 MD5。 */
    public static boolean isLegacyPassword(String value) {
        return value != null && LEGACY_MD5.matcher(value).matches();
    }

    /** 遗留 MD5 hex（小写），仅用于读兼容与包装；新写入不得调用。 */
    public static String legacyMd5(String password) {
        return CryptoUtil.md5Hex(password);
    }

    /** 新口令写入：加盐 Argon2id PHC 串。 */
    public static String hashPassword(String password) {
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        return encode(salt, argon2(password.getBytes(StandardCharsets.UTF_8), salt,
                TIME_COST, MEMORY_COST_KB, PARALLELISM, HASH_LEN));
    }

    /** 把一个遗留 MD5 包装成 {@code WRAPPED_MD5 + Argon2id(MD5)}。 */
    public static String wrapLegacyPassword(String md5) {
        if (!isLegacyPassword(md5)) {
            throw new IllegalArgumentException("Expected a legacy local credential");
        }
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        // 关键：被喂给 Argon2 的是 MD5 字符串本身（对齐上游 HASHER.hash(value)）
        return WRAPPED_MD5 + encode(salt, argon2(md5.getBytes(StandardCharsets.UTF_8), salt,
                TIME_COST, MEMORY_COST_KB, PARALLELISM, HASH_LEN));
    }

    /**
     * 校验口令，兼容三种形态。任何损坏/非法编码一律返回 false，不抛异常。
     */
    public static boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        // 形态1：遗留 MD5 —— 常量时间比对
        if (isLegacyPassword(stored)) {
            return MessageDigest.isEqual(legacyMd5(password).getBytes(StandardCharsets.UTF_8),
                    stored.getBytes(StandardCharsets.UTF_8));
        }
        boolean wrapped = stored.startsWith(WRAPPED_MD5);
        String encoded = wrapped ? stored.substring(WRAPPED_MD5.length()) : stored;
        if (!encoded.startsWith(PHC_PREFIX)) {
            return false;
        }
        try {
            Params p = parse(encoded);
            byte[] input = (wrapped ? legacyMd5(password) : password).getBytes(StandardCharsets.UTF_8);
            byte[] expected = argon2(input, p.salt, p.timeCost, p.memoryCostKb, p.parallelism, p.hash.length);
            return MessageDigest.isEqual(expected, p.hash);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 存储的口令是否需要升级到新形态（legacy / 包装 / 参数过时）。 */
    public static boolean needsUpgrade(String stored) {
        if (stored == null) {
            return false;
        }
        if (isLegacyPassword(stored) || stored.startsWith(WRAPPED_MD5)) {
            return true;
        }
        if (!stored.startsWith(PHC_PREFIX)) {
            return false;
        }
        try {
            Params p = parse(stored);
            return p.timeCost != TIME_COST || p.memoryCostKb != MEMORY_COST_KB
                    || p.parallelism != PARALLELISM || p.salt.length != SALT_LEN
                    || p.hash.length != HASH_LEN;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // PHC 字符串编解码（argon2-cffi 兼容：无填充 Base64）
    // ------------------------------------------------------------------

    private static String encode(byte[] salt, byte[] hash) {
        return PHC_PREFIX
                + "m=" + MEMORY_COST_KB + ",t=" + TIME_COST + ",p=" + PARALLELISM
                + "$" + B64_ENCODER.encodeToString(salt)
                + "$" + B64_ENCODER.encodeToString(hash);
    }

    private static Params parse(String phc) throws IllegalArgumentException {
        // $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>
        String rest = phc.substring(PHC_PREFIX.length());
        String[] parts = rest.split("\\$", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("bad PHC structure");
        }
        Params p = new Params();
        String[] kv = parts[0].split(",", -1);
        if (kv.length != 3) {
            throw new IllegalArgumentException("bad PHC params");
        }
        p.memoryCostKb = parseParam(kv[0], "m");
        p.timeCost = parseParam(kv[1], "t");
        p.parallelism = parseParam(kv[2], "p");
        p.salt = B64_DECODER.decode(parts[1]);
        p.hash = B64_DECODER.decode(parts[2]);

        if (!(1 <= p.timeCost && p.timeCost <= MAX_TIME_COST
                && 8 <= p.memoryCostKb && p.memoryCostKb <= MAX_MEMORY_COST_KB
                && 1 <= p.parallelism && p.parallelism <= MAX_PARALLELISM
                && 8 <= p.salt.length && p.salt.length <= MAX_SALT_LEN
                && 16 <= p.hash.length && p.hash.length <= MAX_HASH_LEN)) {
            throw new IllegalArgumentException("Argon2 params out of range");
        }
        return p;
    }

    private static int parseParam(String token, String expectedKey) {
        if (!token.startsWith(expectedKey + "=")) {
            throw new IllegalArgumentException("bad param: " + token);
        }
        return Integer.parseInt(token.substring(2));
    }

    private static byte[] argon2(byte[] password, byte[] salt, int timeCost, int memoryCostKb,
                                 int parallelism, int hashLen) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(timeCost)
                .withMemoryAsKB(memoryCostKb)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] out = new byte[hashLen];
        gen.generateBytes(password, out);
        return out;
    }

    private static final class Params {
        int memoryCostKb;
        int timeCost;
        int parallelism;
        byte[] salt;
        byte[] hash;
    }
}
