package com.xiongx9527.askdata.common.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公共配置键端点（对齐 SQLBot xpack 的 {@code GET /system/config/key}，
 * 事实源为运行实例 {@code 192.168.1.100} 的实际响应）。
 *
 * <p>SQLBot 的 {@code common/utils/whitelist.py} 把 {@code /system/config/key} 列为公共路径，
 * 用于在登录前下发 RSA 公钥与 salt（前端据此对敏感字段做非对称加密）。</p>
 *
 * <p>实测响应体（运行实例）：</p>
 * <pre>
 * {"code":0,"data":{"public_key":"-----BEGIN PUBLIC KEY-----\nMIIB...","salt":"jhPbfnKe7biRA72d",
 *  "create_time":1785464342102,"id":1,"update_time":1792498202642},"msg":null}
 * </pre>
 *
 * <p>askdata 侧无 xpack 的 rsa 表，改为启动时生成一次 RSA-2048 密钥对并常驻内存
 * （{@code public_key} 为 SPKI PEM，{@code salt} 为随机串）；语义等价：对外只暴露公钥。</p>
 */
@RestController
@RequestMapping({"api/v1"})
public class ConfigKeyController {

    private static final int KEY_SIZE = 2048;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Object> payload;

    public ConfigKeyController() {
        this.payload = buildPayload();
    }

    private Map<String, Object> buildPayload() {
        long now = System.currentTimeMillis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", 1L);
        data.put("public_key", generatePublicKeyPem());
        data.put("salt", generateSalt());
        data.put("create_time", now);
        data.put("update_time", now);
        return data;
    }

    /** GET /system/config/key：公共配置键（免鉴权）。 */
    @GetMapping({"/system/config/key"})
    public Result<Map<String, Object>> key() {
        return Result.ok(payload);
    }

    private static String generatePublicKeyPem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE, RANDOM);
            KeyPair keyPair = generator.generateKeyPair();
            String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
                    .encodeToString(keyPair.getPublic().getEncoded());
            return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate RSA public key", e);
        }
    }

    private static String generateSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
