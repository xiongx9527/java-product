package com.xiongx9527.askdata.datasource.support;

import com.xiongx9527.askdata.system.auth.CryptoUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * configuration 三态加解密单测（spec §3：前端 AES 传输 → 应用 SECRET_KEY 落库）。
 */
class DatasourceCryptoTest {

    private static final String PLAIN =
            "{\"host\":\"192.168.1.100\",\"port\":3306,\"username\":\"root\",\"password\":\"Root57@123456\","
                    + "\"database\":\"askdata_demo\"}";

    /** 静态注入是全局状态，用例结束必须复位，避免污染同 JVM 的其他用例。 */
    @AfterEach
    void resetCrypto() {
        DatasourceCrypto.configure(new CryptoUtil());
    }

    @Test
    void storeRoundTrip() {
        String stored = DatasourceCrypto.encodeForStore(PLAIN);
        // 落库态：应用 SECRET_KEY 密文，且不等于前端密文
        assertTrue(!PLAIN.equals(stored));
        assertTrue(!DatasourceCrypto.encodeForStore(PLAIN).equals(CryptoUtil.encryptSqlbot(PLAIN)));
        // 服务端可解密回明文
        assertEquals(PLAIN, DatasourceCrypto.decodeConfiguration(stored));
        // 前端密文同样可解码（入站路径）
        assertEquals(PLAIN, DatasourceCrypto.decodeConfiguration(CryptoUtil.encryptSqlbot(PLAIN)));
        // 明文 JSON 原样返回（兼容直写）
        assertEquals(PLAIN, DatasourceCrypto.decodeConfiguration(PLAIN));
        // 空值兜底
        assertEquals("{}", DatasourceCrypto.decodeConfiguration(null));
    }

    @Test
    void frontendRoundTrip() {
        String stored = DatasourceCrypto.encodeForStore(PLAIN);
        // 读路径：落库态 → 前端态，前端可用同 key 的 decrypted() 还原
        String frontendForm = DatasourceCrypto.encodeForFrontend(stored);
        assertEquals(PLAIN, CryptoUtil.decryptSqlbot(frontendForm));
        // 前端再次加密提交后，服务端仍可解码（单层往返不叠加）
        assertEquals(PLAIN, DatasourceCrypto.decodeConfiguration(CryptoUtil.encryptSqlbot(PLAIN)));
    }

    @Test
    void frontendCipherStillDecodesWithDifferentAppSecret() {
        // 应用密钥与前端密钥不同时，入站前端密文必须仍能解码（不能被错密钥的乱码截胡）
        DatasourceCrypto.configure(new CryptoUtil("another-app-secret"));
        assertEquals(PLAIN, DatasourceCrypto.decodeConfiguration(CryptoUtil.encryptSqlbot(PLAIN)));
    }

    @Test
    void schemaSeedConfigurationDecrypts() {
        // schema.sql 演示数据源 configuration 种子（应用 SECRET_KEY AES），
        // 由 openssl enc -aes-128-ecb -K <hex(AskDataSecret001)> 生成。
        // A5 起应用密钥不再内置，纯单测需显式注入该演示密钥才能解密历史种子。
        DatasourceCrypto.configure(new CryptoUtil("AskDataSecret001"));
        String seed = "hIpB9A0WThYnmHrUb8w1TCVGAgpvpsLG2uiGEK5f+Jl8xg3alPddR/AbO+Y7LPTAZ8P5cI3TTYxMvQXy+x72XQHj0rv"
                + "/dao6K6Tvc3uZa5mamV/48NcQwWs1NapZGZSfLT18HJEAZtWeTdedRp9pzgGRXxSNscDxA26JKSMWcTelwa/od9mn4tKI"
                + "VEd+zyGVL9TWup0K5EveL2BgZ6sRqGErSUNcn6sjWKAa34imG7EgsnxrXCMI9Zna4Aax1zeHhwhqW7gbIffhnRQF8eJhl"
                + "fiNMrer8O7OAjFb8d6hh52xvwkZUPzyJAPWZNV8qU+AkpTzpgIeCxReBk5n/6iO1y+jBAPK7dmKVib0I98xN0Q=";
        String plain = DatasourceCrypto.decodeConfiguration(seed);
        assertTrue(plain.contains("\"host\":\"192.168.1.100\""), plain);
        assertTrue(plain.contains("\"port\":3306"));
        assertTrue(plain.contains("\"password\":\"Root57@123456\""));
        assertTrue(plain.contains("\"database\":\"askdata_demo\""));
        assertTrue(plain.contains("\"timeout\":30"));
        // 非密文/非 JSON 输入按原样返回（不抛错）
        assertNull(CryptoUtil.decryptSqlbot("not-a-cipher"));
    }
}
