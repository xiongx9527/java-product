package com.xiongx9527.askdata.datasource.support;

import com.xiongx9527.askdata.system.auth.CryptoUtil;

/**
 * 数据源 configuration 加解密（spec §3：前端 CryptoJS AES-ECB key {@code SQLBot1234567890}
 * 传输 → 服务端解密后以应用 SECRET_KEY AES 落库）。
 *
 * <p>三种形态：</p>
 * <ul>
 *   <li>前端形态：AES-ECB({@link CryptoUtil#SQLBOT_AES_KEY})，前端 {@code encrypted()} 产生、
 *       {@code decrypted()} 消费；list/get 响应的 configuration 必须为该形态；</li>
 *   <li>落库形态：AES-ECB(应用 SECRET_KEY，来自配置 {@code askdata.secret.key})，
 *       DB 中不出现传输层可解明文；</li>
 *   <li>明文 JSON：引擎实际消费的 DatasourceConf JSON。</li>
 * </ul>
 *
 * <p><b>应用密钥配置化</b>：落库形态的密钥由配置提供（不再硬编码常量）。
 * 本类保留静态入口以便既有调用点零改动；Spring 启动时由
 * {@link com.xiongx9527.askdata.system.auth.CryptoUtil} 注入配置化密钥
 * （见 {@link #configure(CryptoUtil)}）。</p>
 */
public final class DatasourceCrypto {

    /** 配置化的加解密器（未注入时使用缺省密钥）。 */
    private static volatile CryptoUtil cryptoUtil = new CryptoUtil();

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

    /** 当前生效的加解密器。 */
    public static CryptoUtil crypto() {
        return cryptoUtil;
    }

    /** 入站 configuration（前端密文/历史落库密文/明文 JSON）→ 明文 JSON；null 返回 "{}"。 */
    public static String decodeConfiguration(String value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        String plain = cryptoUtil.decryptWithAppSecret(value);
        if (plain != null) {
            return plain;
        }
        plain = CryptoUtil.decryptSqlbot(value);
        if (plain != null) {
            return plain;
        }
        return value;
    }

    /** 明文 JSON → 落库形态（应用 SECRET_KEY AES）。 */
    public static String encodeForStore(String plainJson) {
        if (plainJson == null || plainJson.isEmpty()) {
            return plainJson;
        }
        return cryptoUtil.encryptWithAppSecret(plainJson);
    }

    /** 落库形态（或明文 JSON）→ 前端形态，供 list/get 响应（前端 decrypted 消费）。 */
    public static String encodeForFrontend(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        return CryptoUtil.encryptSqlbot(decodeConfiguration(stored));
    }
}
