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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 许可证管理（A3 License 校验与门控；对齐 SQLBot xpack /system/license 的对外契约）。
 *
 * 前端契约（web/src/api/license.ts 逐字一致）：
 *  - GET /system/license：{status, enforcement, enterprise, license:{corporation, expired,
 *    count, version, edition, serialNo, remark, isv}}；无许可证时 {status:"community", license:null}；
 *  - POST /system/license：ws admin 上传 {license_key}，返回同上（失败带 message）；
 *  - GET /system/license/version：真实版本号（有许可证取许可证版本，否则取产品版本）。
 *
 * 许可证为自描述 JSON：{product, edition, version, corporation, count, serialNo, remark,
 * isv, expires_at, signature}，signature 是对其余字段（按 key 排序的规范化 JSON）的 HMAC-SHA256，
 * 密钥来自配置 askdata.license.sign-key。
 *
 * 门控（A27）：企业能力是否可用由 askdata.enterprise-enabled 决定——
 *   true  → 企业能力始终可用（绕过 License 校验，便于开发/试用）；
 *   false → 由 License 决定企业版/社区版：持有有效许可证为企业版（enterprise=true），否则社区版（enterprise=false）。
 *   缺省 false（License 决定）。askdata.license.enforcement 仅保留用于契约兼容，实际门控以 enterprise-enabled 为准。
 * 签名密钥未配置时校验链 fail-closed：上传直接返回 invalid 并给出可读配置提示，不再生成随机密钥掩盖问题。
 */
@Service
public class LicenseService {

    private static final long ROW_ID = 1L;

    private static final DateTimeFormatter EXPIRED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentUserService currentUserService;
    private final String appVersion;
    private final String configuredSignKey;
    private final String enforcement;
    private final long maxCount;
    private final boolean enterpriseConfigEnabled;

    public LicenseService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                          CurrentUserService currentUserService,
                          @Value("${askdata.app.version:1.0.0}") String appVersion,
                          @Value("${askdata.license.sign-key:}") String configuredSignKey,
                          @Value("${askdata.license.enforcement:bypass}") String enforcement,
                          @Value("${askdata.license.max-count:1000000}") long maxCount,
                          @Value("${askdata.enterprise-enabled:true}") boolean enterpriseEnabled) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
        this.appVersion = appVersion;
        this.configuredSignKey = configuredSignKey;
        this.enforcement = enforcement == null ? "bypass" : enforcement.trim();
        this.maxCount = maxCount;
        this.enterpriseConfigEnabled = enterpriseEnabled;
    }

    public Map<String, Object> upload(String licenseKey) {
        currentUserService.requireWsAdmin();
        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            return invalid("license is empty");
        }
        Map<String, Object> license;
        try {
            license = objectMapper.readValue(licenseKey,
                    new TypeReference<TreeMap<String, Object>>() {});
        } catch (Exception e) {
            return invalid("license is not valid JSON");
        }
        Object signature = license.remove("signature");
        if (signature == null) {
            return invalid("license signature is missing");
        }
        String expected;
        try {
            expected = sign(canonical(license));
        } catch (IllegalStateException signKeyMissing) {
            return invalid(signKeyMissing.getMessage());
        }
        if (!String.valueOf(signature).equalsIgnoreCase(expected)) {
            return invalid("license signature does not match");
        }
        String licenseVersion = text(license, "version");
        if (licenseVersion == null || licenseVersion.trim().isEmpty()) {
            return invalid("license version is missing");
        }
        if (!licenseVersion.trim().equals(appVersion.trim())) {
            return invalid("license version " + licenseVersion + " does not match product version " + appVersion);
        }
        int count = intOf(license.get("count"), 0);
        if (count > maxCount) {
            return invalid("license authorized count " + count + " exceeds maximum allowed " + maxCount);
        }
        long expireAt = longOf(license.get("expires_at"), 0L);
        String status = expireAt > 0 && expireAt * 1000L < System.currentTimeMillis() ? "expired" : "valid";
        persist(licenseKey, status, license, expireAt, null);
        return view(status, license, null);
    }

    /**
     * 本地签发 License：按入参（corporation / expiresAt / version / serialNo / remark / count / edition / isv）
     * 组装自描述 JSON，并用配置的 {@code askdata.license.sign-key} 做 HMAC-SHA256 签名，返回可直接上传的
     * 完整 License 字符串（含 signature）。sign-key 未配置时 fail-closed（与上传校验一致）。
     */
    public String generate(Map<String, Object> req) {
        signKey();
        Map<String, Object> lic = new TreeMap<>();
        lic.put("product", "askdata");
        lic.put("edition", text(req, "edition", "enterprise"));
        lic.put("version", text(req, "version", appVersion));
        lic.put("corporation", text(req, "corporation", ""));
        lic.put("count", intOf(req.get("count"), 0));
        lic.put("serialNo", text(req, "serialNo", ""));
        lic.put("remark", text(req, "remark", ""));
        lic.put("isv", text(req, "isv", ""));
        Object expiresRaw = req.get("expiresAt") != null ? req.get("expiresAt") : req.get("expires_at");
        lic.put("expires_at", parseExpiresAt(expiresRaw));
        String canonical = canonical(lic);
        String signature = sign(canonical);
        lic.put("signature", signature);
        try {
            return objectMapper.writeValueAsString(lic);
        } catch (Exception e) {
            throw new IllegalStateException("unable to serialize generated license: " + e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> row = findRow();
        if (row == null) {
            Map<String, Object> community = new LinkedHashMap<>();
            community.put("status", "community");
            community.put("license", null);
            community.put("enforcement", enforcement);
            community.put("enterprise", enterpriseEnabled());
            return community;
        }
        String status = String.valueOf(row.get("status"));
        Map<String, Object> license = parseLicense(row.get("license"));
        String failReason = row.get("fail_reason") == null ? null : String.valueOf(row.get("fail_reason"));
        Map<String, Object> out = view(status, license, failReason);
        out.put("enforcement", enforcement);
        out.put("enterprise", enterpriseEnabled());
        return out;
    }

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

    public boolean enterpriseEnabled() {
        // 企业功能总开关（askdata.enterprise-enabled）：
        //  true  → 绕过 License，企业能力始终可用（开发/试用/演示 bypass）；
        //  false → 由 License 决定企业版/社区版：持有有效许可证为企业版，否则社区版。
        if (enterpriseConfigEnabled) {
            return true;
        }
        Map<String, Object> row = findRow();
        return row != null && "valid".equals(String.valueOf(row.get("status")));
    }

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
                    new TypeReference<TreeMap<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String canonical(Map<String, Object> license) {
        try {
            return objectMapper.writeValueAsString(license);
        } catch (Exception e) {
            throw new IllegalStateException("unable to canonicalize license");
        }
    }

    private String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("unable to sign license: " + e.getMessage());
        }
    }

    private String signKey() {
        if (configuredSignKey == null || configuredSignKey.trim().isEmpty()) {
            throw new IllegalStateException("askdata.license.sign-key is not configured; "
                    + "set it to issue and verify licenses (verification is disabled / fail-closed)");
        }
        return configuredSignKey.trim();
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

    private static String text(Map<String, Object> map, String key, String dflt) {
        String v = text(map, key);
        return v == null ? dflt : v;
    }

    /** 解析过期时间：支持 epoch 秒（数字）或 yyyy-MM-dd 日期字符串（取当天 23:59:59）。 */
    private static long parseExpiresAt(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignore) {
            // 尝试 yyyy-MM-dd
        }
        try {
            return LocalDate.parse(s).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (Exception ignore) {
            return 0L;
        }
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
