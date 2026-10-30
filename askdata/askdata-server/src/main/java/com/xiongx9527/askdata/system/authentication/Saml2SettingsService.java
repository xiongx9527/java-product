package com.xiongx9527.askdata.system.authentication;

import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SAML2 配置读写与校验（A25，对齐 SQLBot 前端 {@code SAML2Editor.vue} 调用的三个端点）。
 *
 * <p>端点（前端 {@code request.get/post} 逐字契约，后端映射在 {@link Saml2SettingsController}）：</p>
 * <ul>
 *   <li>{@code GET  /setting/authentication/info/saml}：返回当前 SAML2 配置；未配置时返回空值默认体
 *       （不再 404/无响应）；</li>
 *   <li>{@code POST /setting/authentication/save/saml}：校验必填项后持久化到
 *       {@code sys_authentication}（type=5，config 为 JSON）；</li>
 *   <li>{@code POST /setting/authentication/validate/saml2}：校验证书与 IdP 元数据，
 *       返回 {@code {data:"true"|"false", valid, message}}——前端以 {@code res.data === 'true'}
 *       判定成功，{@code message} 给出明确成功/失败原因。</li>
 * </ul>
 *
 * <p>字段名沿用前端 camelCase：{@code idpMetaUrl / privateKey / certificate /
 * wantAuthnRequestsSigned / wantAssertionsSigned / mapping / spEntityId / spAcs}。</p>
 *
 * <p>三个端点均仅管理员可调（对齐 SQLBot xpack 设置页权限）。</p>
 */
@Service
public class Saml2SettingsService {

    /** 认证源类型：SAML2。 */
    private static final int SAML2_TYPE = SsoAuthenticator.TYPE_SAML2;

    private static final String SAML2_NAME = "SAML2";

    private final AuthenticationRepository authenticationRepository;

    private final Saml2SsoClient saml2SsoClient;

    private final CurrentUserService currentUserService;

    private final MessageResolver messageResolver;

    private final ObjectMapper objectMapper;

    public Saml2SettingsService(AuthenticationRepository authenticationRepository,
                                Saml2SsoClient saml2SsoClient,
                                CurrentUserService currentUserService,
                                MessageResolver messageResolver,
                                ObjectMapper objectMapper) {
        this.authenticationRepository = authenticationRepository;
        this.saml2SsoClient = saml2SsoClient;
        this.currentUserService = currentUserService;
        this.messageResolver = messageResolver;
        this.objectMapper = objectMapper;
    }

    /** GET /setting/authentication/info/saml：当前 SAML2 配置（缺失时为空默认体）。 */
    public Map<String, Object> info() {
        currentUserService.requireAdmin();
        return viewOf(readConfig());
    }

    /**
     * POST /setting/authentication/save/saml：校验必填项后持久化。
     *
     * @param body 前端表单（camelCase；额外带 spEntityId / spAcs）
     * @return 保存后的配置视图
     */
    @Transactional
    public Map<String, Object> save(Map<String, Object> body) {
        currentUserService.requireAdmin();
        Map<String, Object> config = normalize(body);
        validateRequired(config);
        writeConfig(config);
        return viewOf(config);
    }

    /**
     * POST /setting/authentication/validate/saml2：校验证书与 IdP 元数据。
     *
     * <p>请求缺必填项或格式非法时抛 {@link BadRequestException}（前端直接展示文案）；
     * 校验完成但不可用（元数据不可读 / 无 SSO 端点）时返回 {@code valid=false} 与原因。</p>
     */
    public Map<String, Object> validate(Map<String, Object> body) {
        currentUserService.requireAdmin();
        Map<String, Object> config = normalize(body);
        validateRequired(config);
        String metadataUrl = Rows.str(config, "idpMetaUrl");
        try {
            String ssoEndpoint = Saml2SsoClient.parseEndpoint(fetchMetadata(metadataUrl), "SingleSignOnService");
            if (ssoEndpoint == null || ssoEndpoint.isEmpty()) {
                return result(false, messageResolver.get("saml.metadata.no.sso"));
            }
            return result(true, messageResolver.get("saml.validate.ok"));
        } catch (RuntimeException e) {
            return result(false, messageResolver.get("saml.metadata.failed",
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** 拉取 IdP 元数据（测试可覆盖为离线实现）。 */
    protected String fetchMetadata(String metadataUrl) {
        return saml2SsoClient.metadata(metadataUrl);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 读取已保存配置（无记录返回空 Map）。 */
    private Map<String, Object> readConfig() {
        Optional<Map<String, Object>> stored = authenticationRepository.findByType(SAML2_TYPE);
        if (!stored.isPresent()) {
            return new LinkedHashMap<>();
        }
        return parse(Rows.str(stored.get(), "config"));
    }

    /** 写入配置：保留既有 enable / valid / create_time（设置页不改变启停状态）。 */
    private void writeConfig(Map<String, Object> config) {
        Optional<Map<String, Object>> stored = authenticationRepository.findByType(SAML2_TYPE);
        boolean enable = stored.isPresent() && Rows.bool(stored.get(), "enable", false);
        boolean valid = stored.isPresent() && Rows.bool(stored.get(), "valid", false);
        long createTime = stored.isPresent()
                ? Rows.lng(stored.get(), "create_time", System.currentTimeMillis())
                : System.currentTimeMillis();
        String json;
        try {
            json = objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("i18n_format_invalid",
                    Maps.of("key", "config")));
        }
        authenticationRepository.upsert(SAML2_TYPE, SAML2_NAME, SAML2_TYPE, json, enable, valid, createTime);
    }

    /** 表单 → 配置 Map（只保留已知字段，布尔值归一）。 */
    private Map<String, Object> normalize(Map<String, Object> body) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("idpMetaUrl", text(body, "idpMetaUrl"));
        config.put("privateKey", text(body, "privateKey"));
        config.put("certificate", text(body, "certificate"));
        config.put("wantAuthnRequestsSigned", bool(body, "wantAuthnRequestsSigned"));
        config.put("wantAssertionsSigned", bool(body, "wantAssertionsSigned"));
        config.put("mapping", text(body, "mapping"));
        config.put("spEntityId", text(body, "spEntityId"));
        config.put("spAcs", text(body, "spAcs"));
        return config;
    }

    /**
     * 必填与格式校验：IdP 元数据地址必填且为 http(s)；开启签名时私钥与证书必填，
     * 且证书必须能按 X.509 解析、私钥必须能按 PKCS#8 解析。
     */
    private void validateRequired(Map<String, Object> config) {
        String idpMetaUrl = Rows.str(config, "idpMetaUrl");
        if (idpMetaUrl == null || idpMetaUrl.isEmpty()) {
            throw new BadRequestException(messageResolver.get("saml.idp.meta.required"));
        }
        if (!idpMetaUrl.startsWith("http://") && !idpMetaUrl.startsWith("https://")) {
            throw new BadRequestException(messageResolver.get("saml.idp.meta.invalid", idpMetaUrl));
        }
        boolean signing = Rows.bool(config, "wantAuthnRequestsSigned", false)
                || Rows.bool(config, "wantAssertionsSigned", false);
        String certificate = Rows.str(config, "certificate");
        String privateKey = Rows.str(config, "privateKey");
        if (signing && (certificate == null || certificate.isEmpty()
                || privateKey == null || privateKey.isEmpty())) {
            throw new BadRequestException(messageResolver.get("saml.signing.required"));
        }
        if (certificate != null && !certificate.isEmpty()) {
            parseCertificate(certificate);
        }
        if (privateKey != null && !privateKey.isEmpty()) {
            parsePrivateKey(privateKey);
        }
    }

    /** 校验证书可解析（PEM 或裸 Base64 的 X.509）。 */
    private void parseCertificate(String certificate) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate parsed = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(decodePem(certificate, "CERTIFICATE")));
            if (parsed == null) {
                throw new IllegalStateException("empty certificate");
            }
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("saml.certificate.invalid",
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** 校验私钥可解析（PEM 或裸 Base64 的 PKCS#8）。 */
    private void parsePrivateKey(String privateKey) {
        try {
            byte[] der = decodePem(privateKey, "PRIVATE KEY");
            java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
            keyFactory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("saml.privatekey.invalid",
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** 去掉 PEM 头尾与空白后 Base64 解码；裸 Base64 直接解码。 */
    private static byte[] decodePem(String text, String type) {
        String cleaned = text
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }

    /** 配置 → 前端视图（缺失字段给空串/false，避免前端拿到 undefined）。 */
    private Map<String, Object> viewOf(Map<String, Object> config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("idpMetaUrl", nullToEmpty(Rows.str(config, "idpMetaUrl")));
        view.put("privateKey", nullToEmpty(Rows.str(config, "privateKey")));
        view.put("certificate", nullToEmpty(Rows.str(config, "certificate")));
        view.put("wantAuthnRequestsSigned", Rows.bool(config, "wantAuthnRequestsSigned", false));
        view.put("wantAssertionsSigned", Rows.bool(config, "wantAssertionsSigned", false));
        view.put("mapping", nullToEmpty(Rows.str(config, "mapping")));
        view.put("spEntityId", nullToEmpty(Rows.str(config, "spEntityId")));
        view.put("spAcs", nullToEmpty(Rows.str(config, "spAcs")));
        return view;
    }

    /** 校验结果：{@code data} 为前端判定用的 "true"/"false" 字符串，{@code message} 为原因。 */
    private static Map<String, Object> result(boolean valid, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", valid ? "true" : "false");
        result.put("valid", valid);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return value != null && ("true".equalsIgnoreCase(String.valueOf(value).trim())
                || "1".equals(String.valueOf(value).trim()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
