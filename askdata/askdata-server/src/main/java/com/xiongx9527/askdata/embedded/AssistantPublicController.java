package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.OriginSupport;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.auth.JwtCodec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 嵌入助手公共端点（对齐 SQLBot {@code apps/system/api/assistant.py} 的
 * {@code /info/{id}}、{@code /app/{appId}}、{@code /validate/{id}}）。
 *
 * <p>这三个端点在 SQLBot 的 {@code common/utils/whitelist.py} 里是公共路径
 * （{@code /system/assistant/info/*}、{@code /system/assistant/app/*}、
 * {@code /system/assistant/validate/*}），嵌入页/外部站点在未登录状态下也要能取到助手信息。</p>
 *
 * <p>安全边界：请求的 origin（{@code Origin} 头，或 {@code X-SQLBOT-HOST-ORIGIN}，
 * 或从 Referer 推导）必须落在助手配置的 {@code domain} 列表内，否则拒绝；
 * 匹配时动态回写 {@code Access-Control-Allow-Origin}（对齐 SQLBot 的
 * {@code response.headers["Access-Control-Allow-Origin"] = origin}）。</p>
 */
@RestController
@RequestMapping({"api/v1"})
public class AssistantPublicController {

    /** SQLBot {@code settings.ACCESS_TOKEN_EXPIRE_MINUTES}（默认 8 天）。 */
    private static final long ACCESS_TOKEN_EXPIRE_MINUTES = 60L * 24L * 8L;

    private final AssistantRepository assistantRepository;

    private final String secretKey;

    public AssistantPublicController(AssistantRepository assistantRepository,
                                     @Value("${askdata.secret.key:}") String secretKey) {
        this.assistantRepository = assistantRepository;
        // 验收 A5：优先使用 askdata.secret.key（与主密钥配置一致）；未配置时才回退到运行期随机密钥
        this.secretKey = (secretKey == null || secretKey.isEmpty())
                ? java.util.UUID.randomUUID().toString() : secretKey;
    }

    /**
     * GET /system/assistant/info/{id}：助手信息 + 访问 token。
     *
     * <p><b>A7 安全边界</b>（对齐 SQLBot xpack {@code SQLBOT-EMBEDDED-SIGN} 校验）：
     * 除 origin 与助手 domain 匹配外，还必须携带合法签名头——签名头含 assistantId、
     * 时间戳与 HMAC-SHA256(app_secret, ...)，校验 assistantId 一致性、时间窗与
     * webdriver UA。仅伪造 Origin 头不足以换取 token。</p>
     */
    @GetMapping({"/system/assistant/info/{id}"})
    public ResponseEntity<Result<Map<String, Object>>> info(@PathVariable long id,
                                                            @RequestParam(value = "virtual", required = false) Long virtual,
                                                            HttpServletRequest request) {
        Map<String, Object> assistant = assistantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("assistant application not exist"));
        String origin = requireMatchedOrigin(request, assistant);
        requireEmbeddedSign(request, assistant, id);

        long assistantOid = 1L;
        if (Rows.intg(assistant, "type", 0) == 0) {
            assistantOid = oidOfConfiguration(Rows.str(assistant, "configuration"), 1L);
        }
        Map<String, Object> tokenPayload = new LinkedHashMap<>();
        tokenPayload.put("id", virtual);
        tokenPayload.put("account", "sqlbot-inner-assistant");
        tokenPayload.put("oid", assistantOid);
        tokenPayload.put("assistant_id", id);
        String accessToken = JwtCodec.encode(tokenPayload, secretKey, ACCESS_TOKEN_EXPIRE_MINUTES * 60L);

        Map<String, Object> result = view(assistant);
        result.put("token", accessToken);
        return ResponseEntity.ok()
                .header("Access-Control-Allow-Origin", origin)
                .body(Result.ok(result));
    }

    /**
     * GET /system/assistant/app/{appId}：嵌入应用信息（按 app_id 查）。
     * origin 必须落在该应用的 domain 内。
     */
    @GetMapping({"/system/assistant/app/{appId}"})
    public ResponseEntity<Result<Map<String, Object>>> app(@PathVariable("appId") String appId,
                                                            HttpServletRequest request) {
        if (appId == null || appId.trim().isEmpty()) {
            throw new IllegalArgumentException("miss assistant appId");
        }
        Map<String, Object> assistant = assistantRepository.findByAppId(appId)
                .orElseThrow(() -> new IllegalArgumentException("assistant application not exist"));
        String origin = requireMatchedOrigin(request, assistant);
        return ResponseEntity.ok()
                .header("Access-Control-Allow-Origin", origin)
                .body(Result.ok(view(assistant)));
    }

    /**
     * GET /system/assistant/validate/{id}：校验助手访问（返回 valid 与命中的 origin）。
     * origin 必须落在该助手的 domain 内。
     */
    @GetMapping({"/system/assistant/validate/{id}"})
    public ResponseEntity<Result<Map<String, Object>>> validate(@PathVariable long id,
                                                                 HttpServletRequest request) {
        Map<String, Object> assistant = assistantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("assistant application not exist"));
        String origin = requireMatchedOrigin(request, assistant);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", Boolean.TRUE);
        result.put("origin", origin);
        return ResponseEntity.ok()
                .header("Access-Control-Allow-Origin", origin)
                .body(Result.ok(result));
    }

    /**
     * A7：校验嵌入签名头（{@code SQLBOT-EMBEDDED-SIGN}）。
     *
     * <p>签名密钥为助手的 {@code app_secret}；未配置 app_secret 的助手视为未开放嵌入，
     * 直接拒绝。</p>
     */
    private void requireEmbeddedSign(HttpServletRequest request, Map<String, Object> assistant, long assistantId) {
        String appSecret = Rows.str(assistant, "app_secret");
        if (appSecret == null || appSecret.isEmpty()) {
            throw new IllegalArgumentException("embedded sign is not available for this assistant");
        }
        String header = request.getHeader(EmbeddedSigner.HEADER);
        boolean ok = EmbeddedSigner.valid(header, assistantId, appSecret, request.getHeader("User-Agent"),
                System.currentTimeMillis() / 1000L);
        if (!ok) {
            throw new IllegalArgumentException("invalid embedded sign");
        }
    }

    /**
     * 取请求 origin 并校验其落在助手的 domain 列表内；不匹配则拒绝。
     *
     * @return 归一化（去尾斜杠）后的 origin
     */
    private String requireMatchedOrigin(HttpServletRequest request, Map<String, Object> assistant) {
        String origin = OriginSupport.requestOrigin(request);
        if (origin == null || origin.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid origin");
        }
        String normalized = origin.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String domain = Rows.str(assistant, "domain");
        if (!OriginSupport.originMatchDomain(normalized, domain)) {
            throw new IllegalArgumentException("invalid origin");
        }
        return normalized;
    }

    /** 助手视图（字段与 SQLBot AssistantModel 一致）。 */
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", Rows.lng(row, "id", 0L));
        view.put("name", Rows.str(row, "name"));
        view.put("type", Rows.intg(row, "type", 0));
        view.put("domain", Rows.str(row, "domain"));
        view.put("description", Rows.str(row, "description"));
        view.put("configuration", Rows.str(row, "configuration"));
        view.put("create_time", Rows.lng(row, "create_time", 0L));
        view.put("app_id", Rows.str(row, "app_id"));
        view.put("app_secret", Rows.str(row, "app_secret"));
        view.put("oid", Rows.lng(row, "oid", 0L));
        view.put("enable_custom_model", Rows.bool(row, "enable_custom_model", false));
        view.put("custom_model", Rows.str(row, "custom_model"));
        return view;
    }

    /** 从助手 configuration JSON 中取 oid（缺失时用默认值）。 */
    private long oidOfConfiguration(String configuration, long fallback) {
        if (configuration == null || configuration.isEmpty()) {
            return fallback;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Map<?, ?> config = mapper.readValue(configuration, Map.class);
            Object oid = config.get("oid");
            if (oid instanceof Number) {
                return ((Number) oid).longValue();
            }
            if (oid != null) {
                return Long.parseLong(String.valueOf(oid));
            }
        } catch (Exception ignored) {
            // configuration 非合法 JSON 时用默认值
        }
        return fallback;
    }
}
