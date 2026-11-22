package com.xiongx9527.askdata.datasource.support;

import com.xiongx9527.askdata.system.auth.CryptoUtil;

/**
 * 数据源 configuration 加解密（spec §3：前端 CryptoJS AES-ECB key {@code SQLBot1234567890}
 * 传输 → 服务端解密后以应用 SECRET_KEY AES 落库）。
 *
 * <p>四种形态：</p>
 * <ul>
 *   <li>前端形态：AES-ECB({@link CryptoUtil#SQLBOT_AES_KEY})，前端 {@code encrypted()} 产生、
 *       {@code decrypted()} 消费；list/get 响应的 configuration 必须为该形态；</li>
 *   <li>落库形态（当前）：AES-256-GCM envelope（{@link DatasourceKeyring}，每部署独立密钥），
 *       {@code gcm1:keyId:...} 前缀；DB 中不出现传输层可解明文；</li>
 *   <li>历史落库形态：AES-ECB(应用 SECRET_KEY) —— 只读兼容，启动期迁移到 GCM；</li>
 *   <li>明文 JSON：引擎实际消费的 DatasourceConf JSON。</li>
 * </ul>
 *
 * <p><b>应用密钥配置化</b>：落库形态的密钥由配置提供（不再硬编码常量）。
 * 本类保留静态入口以便既有调用点零改动；Spring 启动时由
 * {@link com.xiongx9527.askdata.system.auth.CryptoUtil} 注入配置化密钥
 * （见 {@link #configure(CryptoUtil)}）。</p>
 */
public final class DatasourceCrypto {

    /** 配置化的加解密器（legacy 解密用；未注入时使用缺省密钥）。 */
    private static volatile CryptoUtil cryptoUtil = new CryptoUtil();

    /** 独立密钥环（落库 GCM 加解密；未初始化时调用会抛错提示）。 */
    private static volatile DatasourceKeyring keyring;

    private DatasourceCrypto() {
    }

    /**
     * 注入配置化的 {@link CryptoUtil}（应用启动时调用）。
     *
     * @param util 配置化实例
     */
    public static void configure(CryptoUtil util) {
        if (util != null) {
            cryptoUtil = util;
        }
    }

    /** 注入独立密钥环（应用启动时调用）。 */
    public static void configureKeyring(DatasourceKeyring ring) {
        if (ring != null) {
            keyring = ring;
        }
    }

    public static DatasourceKeyring keyring() {
        return keyring;
    }

    /** 当前生效的加解密器（legacy）。 */
    public static CryptoUtil crypto() {
        return cryptoUtil;
    }

    /** 入站 configuration（gcm1 envelope/前端密文/历史落库密文/明文 JSON）→ 明文 JSON；null 返回 "{}"。 */
    public static String decodeConfiguration(String value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        // 当前落库形态：AES-256-GCM envelope
        if (DatasourceKeyring.isEnvelope(value)) {
            DatasourceKeyring ring = keyring;
            if (ring == null) {
                throw new IllegalStateException("数据源密钥环未初始化，无法解密 gcm1 配置");
            }
            return ring.decrypt(value);
        }
        // 历史形态：应用 SECRET_KEY ECB / 前端 ECB / 明文
        String plain = cryptoUtil.decryptWithAppSecret(value);
        if (plain != null && looksLikeJson(plain)) {
            return plain;
        }
        plain = CryptoUtil.decryptSqlbot(value);
        if (plain != null) {
            return plain;
        }
        return value;
    }

    /**
     * 明文 JSON 粗判。
     *
     * <p>应用密钥可配置后，用错密钥解密仍有极小概率通过 PKCS#7 填充校验并产出乱码；
     * 若直接采信会把乱码当明文交给 Jackson，导致本可回退到前端密钥的密文解析失败。
     * 只有解密结果呈 JSON 对象/数组形态时才认定命中。</p>
     */
    private static boolean looksLikeJson(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /** 明文 JSON → 落库形态（AES-256-GCM envelope，每部署独立密钥）。 */
    public static String encodeForStore(String plainJson) {
        if (plainJson == null || plainJson.isEmpty()) {
            return plainJson;
        }
        DatasourceKeyring ring = keyring;
        if (ring == null) {
            throw new IllegalStateException("数据源密钥环未初始化，无法加密落库配置");
        }
        return ring.encrypt(plainJson);
    }

    /** 落库形态（或明文 JSON）→ 前端形态，供 list/get 响应（前端 decrypted 消费）。 */
    public static String encodeForFrontend(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        return CryptoUtil.encryptSqlbot(decodeConfiguration(stored));
    }
}
