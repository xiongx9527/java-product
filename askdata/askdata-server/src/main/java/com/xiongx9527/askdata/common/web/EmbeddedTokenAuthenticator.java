package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.common.util.Maps;

import com.xiongx9527.askdata.common.cache.HotCacheService;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.auth.JwtCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 嵌入助手鉴权通道（对齐 SQLBot {@code apps/system/middleware/auth.py} 的
 * {@code validateAssistant} / {@code validateEmbedded}）。
 *
 * <p>请求头：{@code X-SQLBOT-ASSISTANT-TOKEN: assistant <jwt>} 或
 * {@code X-SQLBOT-ASSISTANT-TOKEN: embedded <jwt>}。</p>
 *
 * <p>两条分支的差异（与 SQLBot 逐字一致）：</p>
 * <ul>
 *   <li><b>assistant</b>：用应用级 SECRET_KEY 验签，载荷须含 {@code assistant_id}；</li>
 *   <li><b>embedded</b>：用该助手自己的 {@code app_secret} 验签（页面嵌入协议），
 *       载荷含 {@code account}；{@code embeddedId} 缺失时用 {@code appId} 做 XOR 解密回退。</li>
 * </ul>
 *
 * <p>两条安全红线（SQLBot 修复 CNVD 嵌入鉴权绕过时加的，必须逐字复现）：</p>
 * <ol>
 *   <li><b>拒绝管理员账号</b>：{@code app_secret} 由集成方持有，攻击者拿到任意应用的
 *       {@code app_secret} 就能伪造 {@code account=admin} 的管理员身份；</li>
 *   <li><b>只接受 type=4</b>：embedded 协议（{@code app_secret} + account）仅适用于
 *       页面嵌入（type=4）应用。</li>
 * </ol>
 */
@Component
public class EmbeddedTokenAuthenticator {

    /** 请求头名（SQLBot {@code settings.ASSISTANT_TOKEN_KEY}）。 */
    public static final String HEADER = "X-SQLBOT-ASSISTANT-TOKEN";

    /** 页面嵌入应用的 type（SQLBot {@code assistant_info.type != 4} 判定）。 */
    public static final int EMBEDDED_TYPE = 4;

    /** SQLBot {@code xor_decrypt} 的默认密钥。 */
    private static final long XOR_KEY = 0xABCD1234L;

    /** 热点数据缓存（助手信息 / 用户信息，走 askdata.cache.type 指定的后端）。 */
    private final HotCacheService hotCacheService;

    private final String secretKey;

    /** 错误文案统一走消息包（A4 / F-15）。 */
    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    /** 管理员判定（A24：按角色，与 CurrentUserService 同一口径）。 */
    private final com.xiongx9527.askdata.system.service.CurrentUserService currentUserService;

    public EmbeddedTokenAuthenticator(HotCacheService hotCacheService,
                                      @Value("${askdata.secret.key:}") String secretKey,
                                      com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver,
                                      com.xiongx9527.askdata.system.service.CurrentUserService currentUserService) {
        this.hotCacheService = hotCacheService;
        this.messageResolver = messageResolver;
        this.currentUserService = currentUserService;
        // 验收 A5：优先使用 askdata.secret.key（与主密钥配置一致）；未配置时才回退到运行期随机密钥
        this.secretKey = (secretKey == null || secretKey.isEmpty())
                ? java.util.UUID.randomUUID().toString() : secretKey;
    }

    /**
     * 校验嵌入助手通道。
     *
     * @param rawHeader 请求头原值（形如 {@code assistant <jwt>} / {@code embedded <jwt>}）
     * @return 校验通过时返回 {@link AuthResult}（当前用户 + 助手信息）
     */
    public AuthResult authenticate(String rawHeader) {
        if (rawHeader == null || rawHeader.trim().isEmpty()) {
            throw new AuthFailureException(messageResolver.get("i18n_miss_args", Maps.of("key", HEADER)));
        }
        String[] parts = rawHeader.trim().split("\\s+", 2);
        if (parts.length < 2) {
            throw new AuthFailureException(messageResolver.get("i18n_apikey.token_schema_error"));
        }
        String scheme = parts[0];
        String token = parts[1].trim();
        if ("embedded".equalsIgnoreCase(scheme)) {
            return validateEmbedded(token);
        }
        if (!"assistant".equalsIgnoreCase(scheme)) {
            throw new AuthFailureException(messageResolver.get("i18n_apikey.token_schema_error"));
        }
        return validateAssistant(token);
    }

    /** assistant 方案：用应用 SECRET_KEY 验签，载荷须含 assistant_id。 */
    private AuthResult validateAssistant(String token) {
        Map<String, Object> claims;
        try {
            claims = JwtCodec.decode(token, secretKey, true);
        } catch (RuntimeException e) {
            throw new AuthFailureException(e.getMessage());
        }
        Object assistantIdValue = claims.get("assistant_id");
        if (assistantIdValue == null || String.valueOf(assistantIdValue).isEmpty()) {
            throw new AuthFailureException(messageResolver.get("i18n_miss_args", Maps.of("key", "assistant_id")));
        }
        long assistantId = toLong(assistantIdValue, -1L);
        Map<String, Object> assistant = hotCacheService.assistantById(assistantId);
        if (assistant == null) {
            throw new AuthFailureException(messageResolver.get("assistant.embedded.not.found.simple"));
        }
        // SQLBot 的 assistant 通道用内部助手身份（sqlbot-inner-assistant）作为当前用户
        // （对齐 SQLBot apps/system/crud/assistant.py 的 get_assistant_user）。
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", toLong(claims.get("id"), 0L));
        user.put("account", "sqlbot-inner-assistant");
        user.put("name", "sqlbot-inner-assistant");
        user.put("email", "sqlbot-inner-assistant@sqlbot.com");
        user.put("oid", Rows.lng(assistant, "oid", 1L));
        user.put("status", 1);
        user.put("origin", 0);
        user.put("language", "zh-CN");
        return new AuthResult(user, assistant);
    }

    /** embedded 方案：app_secret 验签 + 拒绝管理员 + 仅 type=4。 */
    private AuthResult validateEmbedded(String token) {
        Map<String, Object> claims;
        try {
            // 第一步：不验签取 appId / embeddedId / account（与 SQLBot 一致）。
            claims = JwtCodec.decodeWithoutSignature(token);
        } catch (RuntimeException e) {
            throw new AuthFailureException(e.getMessage());
        }
        Object accountValue = claims.get("account");
        if (accountValue == null || String.valueOf(accountValue).isEmpty()) {
            throw new AuthFailureException(messageResolver.get("i18n_miss_args", Maps.of("key", "account")));
        }
        String account = String.valueOf(accountValue);

        long embeddedId = resolveEmbeddedId(claims);
        if (embeddedId <= 0) {
            throw new AuthFailureException(messageResolver.get("assistant.embedded.not.found.simple"));
        }
        Map<String, Object> assistant = hotCacheService.assistantById(embeddedId);
        if (assistant == null) {
            throw new AuthFailureException(messageResolver.get("assistant.embedded.not.found.simple"));
        }

        // embedded 协议（app_secret + account）仅适用于页面嵌入（type=4）应用。
        if (Rows.intg(assistant, "type", 0) != EMBEDDED_TYPE) {
            throw new AuthFailureException(messageResolver.get("i18n_embedded.invalid_app_type"));
        }
        // 第二步：用该助手自己的 app_secret 验签。
        String appSecret = Rows.str(assistant, "app_secret");
        try {
            JwtCodec.decode(token, appSecret, true);
        } catch (RuntimeException e) {
            throw new AuthFailureException(e.getMessage());
        }

        Map<String, Object> userByAccount = hotCacheService.userByAccount(account);
        if (userByAccount == null) {
            throw new AuthFailureException(messageResolver.get("i18n_login.no_platform_user"));
        }
        Map<String, Object> user = hotCacheService.userById(Rows.lng(userByAccount, "id", 0L));
        if (user == null) {
            throw new AuthFailureException(messageResolver.get("i18n_login.no_platform_user"));
        }
        if (Rows.intg(user, "status", 0) != 1) {
            throw new AuthFailureException(messageResolver.get("i18n_login.user_disable",
                    Maps.of("msg", account)));
        }
        if (Rows.lng(user, "oid", 0L) == 0L) {
            throw new AuthFailureException(messageResolver.get("i18n_login.no_associated_ws",
                    Maps.of("msg", account)));
        }
        // 安全红线：管理员账号不允许通过 embedded token 使用。
        if (currentUserService.isAdmin(user)) {
            throw new AuthFailureException(messageResolver.get("i18n_embedded.admin_not_allowed"));
        }
        // F-08：SQLBot 语义——assistant.oid = user.oid 回写（对齐 get_assistant_user 分支，
        // 让下流的问数/审计拿到与助手一致的归属工作空间）。
        if (Rows.lng(assistant, "oid", 0L) != Rows.lng(user, "oid", 0L)) {
            Map<String, Object> updated = new java.util.LinkedHashMap<>(assistant);
            updated.put("oid", Rows.lng(user, "oid", 0L));
            assistant = updated;
        }
        return new AuthResult(ApiKeyTokenAuthenticator.userView(user), assistant);
    }

    /**
     * embeddedId 解析：优先取载荷的 {@code embeddedId}；缺失时用 {@code appId} 做 XOR 解密回退
     * （对齐 SQLBot {@code xor_decrypt(app_key)}）。
     */
    private long resolveEmbeddedId(Map<String, Object> claims) {
        Object embeddedId = claims.get("embeddedId");
        if (embeddedId != null && !String.valueOf(embeddedId).isEmpty()) {
            return toLong(embeddedId, -1L);
        }
        Object appId = claims.get("appId");
        if (appId == null || String.valueOf(appId).isEmpty()) {
            return -1L;
        }
        return xorDecrypt(String.valueOf(appId));
    }

    /**
     * SQLBot {@code xor_decrypt}：base64url 解码 → hex → 整数 → 与固定密钥异或。
     * 失败返回 -1。
     */
    static long xorDecrypt(String encryptedStr) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encryptedStr);
            StringBuilder hex = new StringBuilder(decoded.length * 2);
            for (byte b : decoded) {
                hex.append(String.format("%02x", b));
            }
            long encrypted = Long.parseLong(hex.toString(), 16);
            return encrypted ^ XOR_KEY;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private static long toLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 嵌入通道的校验结果（当前用户 + 助手信息）。 */
    public static class AuthResult {
        private final Map<String, Object> currentUser;

        private final Map<String, Object> assistant;

        public AuthResult(Map<String, Object> currentUser, Map<String, Object> assistant) {
            this.currentUser = currentUser;
            this.assistant = assistant;
        }

        public Map<String, Object> getCurrentUser() {
            return currentUser;
        }

        public Map<String, Object> getAssistant() {
            return assistant;
        }
    }
}
