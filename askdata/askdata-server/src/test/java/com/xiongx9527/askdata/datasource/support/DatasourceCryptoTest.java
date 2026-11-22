package com.xiongx9527.askdata.datasource.support;

import com.xiongx9527.askdata.system.auth.CryptoUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * configuration 多态加解密单测：前端 AES-ECB 传输 → 每部署独立密钥 AES-256-GCM 落库，
 * 并兼容历史 AES-ECB 落库形态。
 */
class DatasourceCryptoTest {

    private static final String PLAIN =
            "{\"host\":\"192.168.1.100\",\"port\":3306,\"username\":\"root\",\"password\":\"Root57@123456\","
                    + "\"database\":\"askdata_demo\"}";

    private Path tmpDir;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("ds-keyring-test-");
        DatasourceKeyring.initialize(tmpDir.resolve("keys.json").toString(), false);
        DatasourceCrypto.configureKeyring(DatasourceKeyring.get());
    }

    /** 静态注入是全局状态，用例结束必须复位，避免污染同 JVM 的其他用例。 */
    @AfterEach
    void resetCrypto() throws Exception {
        DatasourceCrypto.configure(new CryptoUtil());
        DatasourceKeyring.reset();
        if (tmpDir != null) {
            Files.deleteIfExists(tmpDir.resolve("keys.json"));
            Files.deleteIfExists(tmpDir);
        }
    }

    @Test
    void storeRoundTrip() {
        String stored = DatasourceCrypto.encodeForStore(PLAIN);
        // 落库态：gcm1 envelope，且不等于明文/前端密文
        assertTrue(DatasourceKeyring.isEnvelope(stored));
        assertTrue(!PLAIN.equals(stored));
        assertTrue(!stored.equals(CryptoUtil.encryptSqlbot(PLAIN)));
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
        String frontendForm = DatasourceCrypto.encodeForFrontend(stored);
        assertEquals(PLAIN, CryptoUtil.decryptSqlbot(frontendForm));
        assertEquals(PLAIN, DatasourceCrypto.decodeConfiguration(CryptoUtil.encryptSqlbot(PLAIN)));
    }

    @Test
    void gcmDetectsTampering() {
        String stored = DatasourceCrypto.encodeForStore(PLAIN);
        // 篡改 payload 一个字符，GCM 认证必须失败（而非解出乱码）
        String[] parts = stored.split(":", 3);
        byte[] raw = java.util.Base64.getDecoder().decode(parts[2]);
        raw[raw.length - 1] ^= 0x01;
        String tampered = parts[0] + ":" + parts[1] + ":"
                + java.util.Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalStateException.class, () -> DatasourceCrypto.decodeConfiguration(tampered));
    }

    @Test
    void frontendCipherStillDecodesWithDifferentAppSecret() {
        DatasourceCrypto.configure(new CryptoUtil("another-app-secret"));
        assertEquals(PLAIN, DatasourceCrypto.decodeConfiguration(CryptoUtil.encryptSqlbot(PLAIN)));
    }

    @Test
    void schemaSeedConfigurationDecrypts() {
        // V2 种子演示数据源 configuration（历史 AES-ECB 形态），仍可被 decodeConfiguration 读取
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
        assertNull(CryptoUtil.decryptSqlbot("not-a-cipher"));
    }
}
