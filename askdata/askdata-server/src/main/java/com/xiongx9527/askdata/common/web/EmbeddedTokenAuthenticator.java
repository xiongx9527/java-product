package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.embedded.AssistantRepository;
import com.xiongx9527.askdata.system.auth.JwtCodec;
import com.xiongx9527.askdata.system.repository.UserRepository;
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

    private final AssistantRepository assistantRepository;

    private final UserRepository userRepository;

    private final String secretKey;

    public EmbeddedTokenAuthenticator(AssistantRepository assistantRepository,
                                      UserRepository userRepository,
                                      @Value("${askdata.secret-key:}") String secretKey) {
        this.assistantRepository = assistantRepository;
        this.userRepository = userRepository;
        // 验收 A5：签名密钥不再内置缺省值；未配置时用运行期随机密钥（重启后旧嵌入 token 失效，属预期）
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
            throw new AuthFailureException("Miss Token[" + HEADER + "]!");
        }
        String[] parts = rawHeader.trim().split("\\s+", 2);
        if (parts.length < 2) {
            throw new AuthFailureException("Token schema error!");
        }
        String scheme = parts[0];
        String token = parts[1].trim();
        if ("embedded".equalsIgnoreCase(scheme)) {
            return validateEmbedded(token);
        }
        if (!"assistant".equalsIgnoreCase(scheme)) {
            throw new AuthFailureException("Token schema error!");
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
            throw new AuthFailureException("Miss assistant payload error!");
        }
        long assistantId = toLong(assistantIdValue, -1L);
        Map<String, Object> assistant = assistantRepository.findById(assistantId)
                .orElseThrow(() -> new AuthFailureException("Assistant application not exist"));
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
            throw new AuthFailureException("Miss account payload error!");
        }
        String account = String.valueOf(accountValue);

        long embeddedId = resolveEmbeddedId(claims);
        if (embeddedId <= 0) {
            throw new AuthFailureException("Assistant application not exist");
        }
        Map<String, Object> assistant = assistantRepository.findById(embeddedId)
                .orElseThrow(() -> new AuthFailureException("Assistant application not exist"));

        // embedded 协议（app_secret + account）仅适用于页面嵌入（type=4）应用。
        if (Rows.intg(assistant, "type", 0) != EMBEDDED_TYPE) {
            throw new AuthFailureException("Invalid embedded app type!");
        }
        // 第二步：用该助手自己的 app_secret 验签。
        String appSecret = Rows.str(assistant, "app_secret");
        try {
            JwtCodec.decode(token, appSecret, true);
        } catch (RuntimeException e) {
            throw new AuthFailureException(e.getMessage());
        }

        Optional<Map<String, Object>> userRow = userRepository.findByAccount(account);
        if (!userRow.isPresent()) {
            throw new AuthFailureException("Account does not exist");
        }
        Map<String, Object> user = userRepository.findById(Rows.lng(userRow.get(), "id", 0L))
                .orElseThrow(() -> new AuthFailureException("Account does not exist"));
        if (Rows.intg(user, "status", 0) != 1) {
            throw new AuthFailureException("User is disabled");
        }
        if (Rows.lng(user, "oid", 0L) == 0L) {
            throw new AuthFailureException("No associated workspace");
        }
        // 安全红线：管理员账号不允许通过 embedded token 使用。
        if (isAdmin(user)) {
            throw new AuthFailureException("Admin account is not allowed for embedded token!");
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

    /** 管理员判定（与 CurrentUserService.isAdmin 逐字一致：id==1 且 account=='admin'）。 */
    private static boolean isAdmin(Map<String, Object> user) {
        return Rows.lng(user, "id", 0L) == 1L && "admin".equals(Rows.str(user, "account"));
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
