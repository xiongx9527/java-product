package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.common.util.Maps;

import com.xiongx9527.askdata.common.cache.HotCacheService;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.auth.JwtCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API Key（sk）鉴权通道（对齐 SQLBot {@code apps/system/middleware/auth.py} 的 {@code validateAskToken}）。
 *
 * <p>请求头形态：{@code X-SQLBOT-ASK-TOKEN: sk <jwt>}。JWT 载荷含 {@code access_key}：</p>
 * <ol>
 *   <li>先不验签解析载荷，取出 {@code access_key}（SQLBot 同样先 {@code verify_signature: False}）；</li>
 *   <li>用 {@code access_key} 查 {@code sys_apikey}，查不到 → 拒绝；</li>
 *   <li>{@code status=false}（停用）→ 拒绝；</li>
 *   <li>用该 key 的 {@code secret_key} 二次验签（这一步才真正防伪）→ 失败则拒绝；</li>
 *   <li>取 key 所属用户：不存在 / 已禁用 / 未绑定工作空间 → 拒绝。</li>
 * </ol>
 */
@Component
public class ApiKeyTokenAuthenticator {

    /** 请求头名（SQLBot 硬编码，与 settings.TOKEN_KEY 不同）。 */
    public static final String HEADER = "X-SQLBOT-ASK-TOKEN";

    /** 授权方案名（SQLBot 要求 scheme.lower() == "sk"）。 */
    public static final String SCHEME = "sk";

    /** 热点数据缓存（用户信息 / API Key 信息，走 askdata.cache.type 指定的后端）。 */
    private final HotCacheService hotCacheService;

    /** 错误文案统一走消息包（A4 / F-15）。 */
    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    public ApiKeyTokenAuthenticator(HotCacheService hotCacheService,
                                    com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver) {
        this.hotCacheService = hotCacheService;
        this.messageResolver = messageResolver;
    }

    /**
     * 校验 API Key 通道。
     *
     * @param rawHeader 请求头原值（形如 {@code sk <jwt>}）
     * @return 校验通过时返回当前用户视图；失败时抛 {@link AuthFailureException}
     */
    public Map<String, Object> authenticate(String rawHeader) {
        if (rawHeader == null || rawHeader.trim().isEmpty()) {
            throw new AuthFailureException(messageResolver.get("i18n_miss_args", Maps.of("key", HEADER)));
        }
        String[] parts = rawHeader.trim().split("\\s+", 2);
        if (parts.length < 2 || !SCHEME.equalsIgnoreCase(parts[0])) {
            throw new AuthFailureException(messageResolver.get("i18n_apikey.token_schema_error"));
        }
        String token = parts[1].trim();

        Map<String, Object> claims;
        try {
            // 第一步：不验签取 access_key（与 SQLBot 一致）。
            claims = JwtCodec.decodeWithoutSignature(token);
        } catch (RuntimeException e) {
            throw new AuthFailureException(e.getMessage());
        }
        String accessKey = claims.get("access_key") == null ? null : String.valueOf(claims.get("access_key"));
        if (accessKey == null || accessKey.isEmpty()) {
            throw new AuthFailureException(messageResolver.get("i18n_apikey.miss_access_key"));
        }

        Map<String, Object> key = hotCacheService.apiKeyByAccessKey(accessKey);
        if (key == null) {
            throw new AuthFailureException(messageResolver.get("i18n_apikey.invalid_access_key"));
        }
        if (!Rows.bool(key, "status", true)) {
            throw new AuthFailureException(messageResolver.get("i18n_apikey.disabled_access_key"));
        }
        String secretKey = Rows.str(key, "secret_key");
        try {
            // 第二步：用 secret_key 验签（真正防伪的一步）。
            JwtCodec.decode(token, secretKey, true);
        } catch (RuntimeException e) {
            throw new AuthFailureException(e.getMessage());
        }

        long uid = Rows.lng(key, "uid", 0L);
        Map<String, Object> user = hotCacheService.userById(uid);
        if (user == null) {
            throw new AuthFailureException(messageResolver.get("i18n_login.no_platform_user"));
        }
        if (Rows.intg(user, "status", 0) != 1) {
            throw new AuthFailureException(messageResolver.get("i18n_login.user_disable",
                    Maps.of("msg", Rows.str(user, "account") == null ? "" : Rows.str(user, "account"))));
        }
        if (Rows.lng(user, "oid", 0L) == 0L) {
            throw new AuthFailureException(messageResolver.get("i18n_login.no_associated_ws",
                    Maps.of("msg", Rows.str(user, "account") == null ? "" : Rows.str(user, "account"))));
        }
        return userView(user);
    }

    /**
     * 当前用户视图（对齐 SQLBot {@code UserInfoDTO}）。
     *
     * <p>SQLBot 的 {@code get_user_info} 是从 {@code sys_user} 整行构造 DTO 的，
     * 因此这里直接返回整行（含 {@code system_variables}）——该列参与行权限强制
     * （{@code DsPermissionQueryService}），缺失会让行权限对 API Key 通道失效。</p>
     */
    static Map<String, Object> userView(Map<String, Object> user) {
        return new LinkedHashMap<>(user);
    }
}
