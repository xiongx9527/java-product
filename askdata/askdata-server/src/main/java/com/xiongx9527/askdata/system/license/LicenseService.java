package com.xiongx9527.askdata.system.license;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 许可证管理（A28；对齐 SQLBot xpack {@code /system/license} 的对外契约）。
 *
 * <p>前端契约（{@code web/src/api/license.ts} 逐字一致）：</p>
 * <ul>
 *   <li>{@code GET /system/license}：{@code {status, license:{corporation, expired, count, version,
 *       edition, serialNo, remark, isv}}}；无许可证时 {@code {status:"community", license:null}}；</li>
 *   <li>{@code POST /system/license}：ws admin 上传 {@code {license_key}}，返回同上（失败带
 *       {@code message}）；</li>
 *   <li>{@code GET /system/license/version}：真实版本号（有许可证取许可证版本，否则取产品版本）。</li>
 * </ul>
 *
 * <p>许可证为自描述 JSON：{@code {product, edition, version, corporation, count, serialNo, remark,
 * isv, expires_at, signature}}，其中 {@code signature} 是对其余字段（按 key 排序的规范化 JSON）
 * 的 HMAC-SHA256。签名密钥来自配置 {@code askdata.license.sign-key}；未配置时首次上传时生成
 * 随机密钥并持久化（{@code sys_arg}），保证同一安装内可校验、重启不失效。</p>
 */
@Service
public class LicenseService {

    /** sys_license 固定行 id（仅保留最新一条）。 */
    private static final long ROW_ID = 1L;

    private static final String SIGN_KEY_ARG = "license.sign_key";

    private static final DateTimeFormatter EXPIRED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    private final CurrentUserService currentUserService;

    private final String appVersion;

    private final String configuredSignKey;

    public LicenseService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                          CurrentUserService currentUserService,
                          @Value("${askdata.app.version:1.0.0}") String appVersion,
                          @Value("${askdata.license.sign-key:}") String configuredSignKey) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
        this.appVersion = appVersion;
        this.configuredSignKey = configuredSignKey;
    }

    // ------------------------------------------------------------------
    // 上传 / 状态
    // ------------------------------------------------------------------

    /**
     * 上传许可证（ws admin）。解析 + 验签 + 判定期限后持久化，返回与 GET 一致的视图。
     *
     * @param licenseKey 许可证原文（JSON）
     */
    public Map<String, Object> upload(String licenseKey) {
        currentUserService.requireWsAdmin();
        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            return invalid("license is empty");
        }
        Map<String, Object> license;
        try {
            license = objectMapper.readValue(licenseKey,
                    new TypeReference<TreeMap<String, Object>>() {
                    });
        } catch (Exception e) {
            return invalid("license is not valid JSON");
        }
        Object signature = license.remove("signature");
        if (signature == null) {
            return invalid("license signature is missing");
        }
        if (!String.valueOf(signature).equalsIgnoreCase(sign(canonical(license)))) {
            return invalid("license signature does not match");
        }
        long expireAt = longOf(license.get("expires_at"), 0L);
        String status = expireAt > 0 && expireAt * 1000L < System.currentTimeMillis() ? "expired" : "valid";
        persist(licenseKey, status, license, expireAt, null);
        return view(status, license, null);
    }

    /** GET /system/license：当前许可证状态。 */
    public Map<String, Object> status() {
        Map<String, Object> row = findRow();
        if (row == null) {
            Map<String, Object> community = new LinkedHashMap<>();
            community.put("status", "community");
            community.put("license", null);
            return community;
        }
        String status = String.valueOf(row.get("status"));
        Map<String, Object> license = parseLicense(row.get("license"));
        String failReason = row.get("fail_reason") == null ? null : String.valueOf(row.get("fail_reason"));
        return view(status, license, failReason);
    }

    /** GET /system/license/version：真实版本（许可证版本优先，否则产品版本）。 */
    public String version() {
        Map<String, Object> row = findRow();
        if (row != null) {
            Object version = row.get("version");
            if (version != null && !String.valueOf(version).trim().isEmpty()) {
                return String.valueOf(version);
            }
        }
        return appVersion;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Map<String, Object> invalid(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "invalid");
        out.put("license", null);
        out.put("message", reason);
        return out;
    }

    private Map<String, Object> view(String status, Map<String, Object> license, String failReason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        Map<String, Object> view = null;
        if (license != null) {
            view = new LinkedHashMap<>();
            view.put("corporation", text(license, "corporation"));
            long expireAt = longOf(license.get("expires_at"), 0L);
            view.put("expired", expireAt > 0 ? EXPIRED_FORMAT.format(Instant.ofEpochSecond(expireAt)) : "-");
            view.put("count", intOf(license.get("count"), 0));
            view.put("version", text(license, "version"));
            view.put("edition", text(license, "edition"));
            view.put("serialNo", text(license, "serialNo"));
            view.put("remark", text(license, "remark"));
            view.put("isv", text(license, "isv"));
        }
        out.put("license", view);
        if (failReason != null && !failReason.isEmpty()) {
            out.put("message", failReason);
        }
        return out;
    }

    private void persist(String licenseKey, String status, Map<String, Object> license, long expireAt,
                         String failReason) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sys_license WHERE id = ?",
                Integer.class, ROW_ID);
        if (exists != null && exists > 0) {
            jdbc.update("UPDATE sys_license SET license = ?, status = ?, version = ?, edition = ?, "
                            + "corporation = ?, count = ?, serial_no = ?, remark = ?, isv = ?, expire_at = ?, "
                            + "fail_reason = ?, create_time = ? WHERE id = ?",
                    licenseKey, status, text(license, "version"), text(license, "edition"),
                    text(license, "corporation"), intOf(license.get("count"), 0),
                    text(license, "serialNo"), text(license, "remark"), text(license, "isv"),
                    expireAt, failReason, System.currentTimeMillis() / 1000L, ROW_ID);
            return;
        }
        jdbc.update("INSERT INTO sys_license (id, license, status, version, edition, corporation, count, "
                        + "serial_no, remark, isv, expire_at, fail_reason, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ROW_ID, licenseKey, status, text(license, "version"), text(license, "edition"),
                text(license, "corporation"), intOf(license.get("count"), 0),
                text(license, "serialNo"), text(license, "remark"), text(license, "isv"),
                expireAt, failReason, System.currentTimeMillis() / 1000L);
    }

    private Map<String, Object> findRow() {
        try {
            return jdbc.queryForList("SELECT * FROM sys_license WHERE id = ?", ROW_ID)
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseLicense(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(String.valueOf(raw),
                    new TypeReference<TreeMap<String, Object>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    /** 规范化 JSON（按 key 排序，无空白；签名字段已移除）。 */
    private String canonical(Map<String, Object> license) {
        try {
            return objectMapper.writeValueAsString(license);
        } catch (Exception e) {
            throw new IllegalStateException("unable to canonicalize license");
        }
    }

    /** HMAC-SHA256(key, canonical) 的 hex。 */
    private String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("unable to sign license: " + e.getMessage());
        }
    }

    /** 签名密钥：配置优先；否则取持久化的随机密钥（首次生成）。 */
    private String signKey() {
        if (configuredSignKey != null && !configuredSignKey.trim().isEmpty()) {
            return configuredSignKey.trim();
        }
        try {
            return jdbc.queryForObject("SELECT pval FROM sys_arg WHERE pkey = ?", String.class, SIGN_KEY_ARG);
        } catch (Exception ignored) {
            // 尚未生成
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String key = hex(random);
        jdbc.update("INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (?, ?, ?, 'str', 999)",
                System.currentTimeMillis(), SIGN_KEY_ARG, key);
        return key;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long longOf(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
