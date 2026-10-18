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
 *   <li>落库形态：AES-ECB({@link CryptoUtil#ASK_DATA_SECRET_KEY})，DB 中不出现传输层可解明文；</li>
 *   <li>明文 JSON：引擎实际消费的 DatasourceConf JSON。</li>
 * </ul>
 */
public final class DatasourceCrypto {

    private DatasourceCrypto() {
    }

    /** 入站 configuration（前端密文/历史落库密文/明文 JSON）→ 明文 JSON；null 返回 "{}"。 */
    public static String decodeConfiguration(String value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        String plain = CryptoUtil.decryptWithAppSecret(value);
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
        return CryptoUtil.encryptWithAppSecret(plainJson);
    }

    /** 落库形态（或明文 JSON）→ 前端形态，供 list/get 响应（前端 decrypted 消费）。 */
    public static String encodeForFrontend(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        return CryptoUtil.encryptSqlbot(decodeConfiguration(stored));
    }
}
