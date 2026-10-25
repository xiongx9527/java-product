package com.xiongx9527.askdata.system.service;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型模块（对齐 SQLBot apps/system/api/aimodel.py）。
 *
 * <p>api_key/api_domain 往返（见 {@link CryptoUtil}）：写入规范化加密落库、按 id 查询解密为明文、
 * 连通检查解密后访问模型端点。</p>
 */
@Service
public class AiModelService {
    @Autowired
    private MessageResolver messageResolver;

    private final AiModelRepository aiModelRepository;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    private final ObjectMapper objectMapper;

    public AiModelService(AiModelRepository aiModelRepository, CurrentUserService currentUserService,
                          IdGenerator idGenerator, ObjectMapper objectMapper) {
        this.aiModelRepository = aiModelRepository;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    public List<Map<String, Object>> list(String keyword) {
        currentUserService.requireAdmin();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : aiModelRepository.list(keyword)) {
            result.add(Rows.of(
                    "id", Rows.lng(row, "id", 0L),
                    "name", Rows.str(row, "name"),
                    "model_type", Rows.intg(row, "model_type", 0),
                    "base_model", Rows.str(row, "base_model"),
                    "supplier", Rows.intg(row, "supplier", 0),
                    "protocol", Rows.intg(row, "protocol", 1),
                    "default_model", Rows.bool(row, "default_model", false),
                    "ws_mapping_count", Rows.intg(row, "ws_mapping_count", 0)));
        }
        return result;
    }

    public Map<String, Object> editor(long id) {
        currentUserService.requireAdmin();
        Map<String, Object> row = aiModelRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("aimodel.not.found", id)));
        return toEditor(row);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        currentUserService.requireAdmin();
        String name = required(body, "name");
        String baseModel = required(body, "base_model");
        String apiDomain = required(body, "api_domain");
        long id = idGenerator.nextId();
        boolean defaultModel = Rows.bool(body, "default_model", false) || aiModelRepository.count() == 0;
        aiModelRepository.insert(
                id,
                CryptoUtil.normalizeForStore(Rows.str(body, "api_key")),
                CryptoUtil.normalizeForStore(apiDomain),
                Rows.intg(body, "protocol", 1),
                Rows.intg(body, "supplier", 0),
                name,
                Rows.intg(body, "model_type", 0),
                baseModel,
                defaultModel,
                configJson(body),
                1,
                System.currentTimeMillis());
        return editor(id);
    }

    public void update(Map<String, Object> body) {
        currentUserService.requireAdmin();
        long id = Rows.lng(body, "id", 0L);
        Map<String, Object> existing = aiModelRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("aimodel.not.found", id)));
        String apiKey = body.containsKey("api_key")
                ? CryptoUtil.normalizeForStore(Rows.str(body, "api_key"))
                : Rows.str(existing, "api_key");
        String apiDomain = body.containsKey("api_domain")
                ? CryptoUtil.normalizeForStore(Rows.str(body, "api_domain"))
                : Rows.str(existing, "api_domain");
        String config = body.containsKey("config_list") ? configJson(body) : Rows.str(existing, "config");
        aiModelRepository.update(
                id,
                apiKey,
                apiDomain,
                Rows.get(body, "protocol") == null ? Rows.intg(existing, "protocol", 1)
                        : Rows.intg(body, "protocol", 1),
                Rows.get(body, "supplier") == null ? Rows.intg(existing, "supplier", 0)
                        : Rows.intg(body, "supplier", 0),
                Rows.get(body, "name") == null ? Rows.str(existing, "name") : Rows.str(body, "name"),
                Rows.get(body, "model_type") == null ? Rows.intg(existing, "model_type", 0)
                        : Rows.intg(body, "model_type", 0),
                Rows.get(body, "base_model") == null ? Rows.str(existing, "base_model")
                        : Rows.str(body, "base_model"),
                Rows.get(body, "default_model") == null ? Rows.bool(existing, "default_model", false)
                        : Rows.bool(body, "default_model", false),
                config);
    }

    @Transactional
    public void delete(long id) {
        currentUserService.requireAdmin();
        Map<String, Object> model = aiModelRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("aimodel.not.found", id)));
        if (Rows.bool(model, "default_model", false)) {
            throw new BadRequestException(messageResolver.get("i18n_llm.delete_default_error",
                    java.util.Map.of("key", String.valueOf(Rows.str(model, "name")))));
        }
        aiModelRepository.deleteAllMappings(id);
        aiModelRepository.delete(id);
    }

    public void setDefault(long id) {
        currentUserService.requireAdmin();
        Map<String, Object> model = aiModelRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("aimodel.not.found", id)));
        if (Rows.bool(model, "default_model", false)) {
            return;
        }
        aiModelRepository.clearDefaults();
        aiModelRepository.setDefault(id);
    }

    /** GET /system/aimodel/default：无默认模型时抛错（对齐 SQLBot check_default）。 */
    public void requireDefaultModel() {
        if (!aiModelRepository.findDefault().isPresent()) {
            throw new BadRequestException(messageResolver.get("aimodel.default.not.configured"));
        }
    }

    // ------------------------------------------------------------------
    // 模型 × 工作空间映射
    // ------------------------------------------------------------------

    public List<String> wsMapping(long id) {
        currentUserService.requireAdmin();
        requireModel(id);
        return toStringIds(aiModelRepository.mappingIds(id));
    }

    public List<String> replaceWsMapping(long id, List<String> wsIds) {
        currentUserService.requireAdmin();
        requireModel(id);
        List<Long> ids = dedup(wsIds);
        aiModelRepository.replaceMappings(id, ids);
        return toStringIds(ids);
    }

    public List<String> addWsMapping(long id, List<String> wsIds) {
        currentUserService.requireAdmin();
        requireModel(id);
        List<Long> ids = dedup(wsIds);
        List<Long> existing = aiModelRepository.existingMappingIds(id, ids);
        for (Long wsId : ids) {
            if (!existing.contains(wsId)) {
                aiModelRepository.addMapping(id, wsId);
            }
        }
        return toStringIds(aiModelRepository.mappingIds(id));
    }

    public List<String> deleteWsMapping(long id, List<String> wsIds) {
        currentUserService.requireAdmin();
        requireModel(id);
        aiModelRepository.deleteMappings(id, dedup(wsIds));
        return toStringIds(aiModelRepository.mappingIds(id));
    }

    /** MCP mcp_model_list：按指定工作空间列模型（调用方负责归属校验）。 */
    public List<Map<String, Object>> listByWs(long oid) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : aiModelRepository.listByWorkspace(oid, false)) {
            result.add(Rows.of(
                    "id", String.valueOf(Rows.lng(row, "id", 0L)),
                    "name", Rows.str(row, "name"),
                    "default_model", Rows.bool(row, "default_model", false),
                    "supplier", Rows.intg(row, "supplier", 0)));
        }
        return result;
    }

    /** /system/aimodel/list/by_ws：当前用户工作空间可用模型（id 为字符串，对齐 SQLBot AiModelBrief）。 */
    public List<Map<String, Object>> listByWs() {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : aiModelRepository.listByWorkspace(oid, false)) {
            result.add(Rows.of(
                    "id", String.valueOf(Rows.lng(row, "id", 0L)),
                    "name", Rows.str(row, "name"),
                    "default_model", Rows.bool(row, "default_model", false),
                    "supplier", Rows.intg(row, "supplier", 0)));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 连通检查（POST /system/aimodel/status，NDJSON 流式帧）
    // ------------------------------------------------------------------

    /**
     * 对齐 SQLBot check_llm：对模型端点发起一次真实对话请求（"1+1=?"），
     * 以 NDJSON 逐行输出 {@code {"content": "..."}}；失败输出 {@code {"error": "Validation failed [..]"}}。
     */
    public void streamStatus(Map<String, Object> body, HttpServletResponse response) throws IOException {
        currentUserService.requireAdmin();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        try {
            String apiDomain = CryptoUtil.resolvePlain(Rows.str(body, "api_domain"));
            String apiKey = CryptoUtil.resolvePlain(Rows.str(body, "api_key"));
            String baseModel = Rows.str(body, "base_model");
            if (apiDomain == null || apiDomain.isEmpty() || baseModel == null || baseModel.isEmpty()) {
                emitError(writer, messageResolver.get("aimodel.api_domain.required"));
                return;
            }
            checkModel(chatCompletionsUrl(apiDomain), apiKey, baseModel, temperatureOf(body), writer);
        } catch (Exception e) {
            emitError(writer, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            writer.flush();
        }
    }

    private void checkModel(String url, String apiKey, String baseModel, Double temperature, PrintWriter writer)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", baseModel);
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Rows.of("role", "user", "content", "1+1=?"));
            payload.put("messages", messages);
            payload.put("stream", true);
            if (temperature != null) {
                payload.put("temperature", temperature);
            }
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));
            connection.getOutputStream().flush();

            int code = connection.getResponseCode();
            if (code >= 400) {
                String detail = readAll(connection.getErrorStream());
                emitError(writer, messageResolver.get("aimodel.http_error", java.util.Map.of("msg",
                        "HTTP " + code + (detail.isEmpty() ? "" : ": " + detail))));
                return;
            }
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
                readSseStream(connection.getInputStream(), writer);
            } else {
                String text = readAll(connection.getInputStream());
                emitContentFromJson(text, writer);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** 解析 SSE `data:{...}` 帧并逐段输出 content。 */
    private void readSseStream(InputStream inputStream, PrintWriter writer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                String content = contentOf(data);
                if (content != null && !content.isEmpty()) {
                    writeLine(writer, Rows.of("content", content));
                }
            }
        }
    }

    /** 非流式响应：{choices:[{message:{content}}]} 或 {choices:[{text}]}。 */
    private void emitContentFromJson(String text, PrintWriter writer) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String content = contentOf(text);
        if (content != null && !content.isEmpty()) {
            writeLine(writer, Rows.of("content", content));
        }
    }

    private String contentOf(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.get("delta");
                if (delta != null && delta.get("content") != null && !delta.get("content").isNull()) {
                    return delta.get("content").asText();
                }
                JsonNode message = choice.get("message");
                if (message != null && message.get("content") != null && !message.get("content").isNull()) {
                    return message.get("content").asText();
                }
                if (choice.get("text") != null && !choice.get("text").isNull()) {
                    return choice.get("text").asText();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeLine(PrintWriter writer, Map<String, Object> frame) throws IOException {
        writer.println(objectMapper.writeValueAsString(frame));
        writer.flush();
    }

    private void emitError(PrintWriter writer, String message) throws IOException {
        writeLine(writer, Rows.of("error",
                messageResolver.get("i18n_llm.validate_error", java.util.Map.of("msg", message))));
    }

    private static String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, length);
                if (sb.length() > 4000) {
                    break;
                }
            }
        }
        String text = sb.toString();
        return text.length() > 800 ? text.substring(0, 800) : text;
    }

    /** 端点规范化：{@code http://host:11434} → {@code .../v1/chat/completions}，{@code .../v1} → {@code .../v1/chat/completions}。 */
    static String chatCompletionsUrl(String apiDomain) {
        String base = apiDomain.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        String path;
        try {
            path = new URL(base).getPath();
        } catch (Exception e) {
            return base + "/chat/completions";
        }
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return base + "/v1/chat/completions";
        }
        return base + "/chat/completions";
    }

    private Double temperatureOf(Map<String, Object> body) {
        for (Map<String, Object> item : Rows.mapList(Rows.get(body, "config_list"))) {
            if ("temperature".equals(Rows.str(item, "key"))) {
                Object val = Rows.get(item, "val");
                if (val instanceof Number) {
                    return ((Number) val).doubleValue();
                }
                try {
                    return val == null ? null : Double.parseDouble(String.valueOf(val));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private void requireModel(long id) {
        if (!aiModelRepository.findById(id).isPresent()) {
            throw new BadRequestException(messageResolver.get("aimodel.not.found", id));
        }
    }

    private Map<String, Object> toEditor(Map<String, Object> row) {
        Map<String, Object> editor = new LinkedHashMap<>();
        editor.put("id", Rows.lng(row, "id", 0L));
        editor.put("name", Rows.str(row, "name"));
        editor.put("model_type", Rows.intg(row, "model_type", 0));
        editor.put("base_model", Rows.str(row, "base_model"));
        editor.put("supplier", Rows.intg(row, "supplier", 0));
        editor.put("protocol", Rows.intg(row, "protocol", 1));
        editor.put("default_model", Rows.bool(row, "default_model", false));
        // 对齐 SQLBot get_model_by_id：解密后返回明文，前端编辑保存时再 sqlbotEncrypt
        editor.put("api_domain", CryptoUtil.resolvePlain(Rows.str(row, "api_domain")));
        editor.put("api_key", CryptoUtil.resolvePlain(Rows.str(row, "api_key")));
        editor.put("config_list", parseConfigList(Rows.str(row, "config")));
        return editor;
    }

    private Object parseConfigList(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String configJson(Map<String, Object> body) {
        Object configList = Rows.get(body, "config_list");
        if (configList == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(configList);
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("i18n_format_invalid", java.util.Map.of("key", "config_list")));
        }
    }

    private String required(Map<String, Object> body, String key) {
        String value = Rows.str(body, key);
        if (value == null || value.isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", java.util.Map.of("key", key)));
        }
        return value;
    }

    private static List<Long> dedup(List<String> wsIds) {
        List<Long> result = new ArrayList<>();
        for (Long id : Rows.longList(wsIds)) {
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private static List<String> toStringIds(List<Long> ids) {
        List<String> result = new ArrayList<>();
        for (Long id : ids) {
            result.add(String.valueOf(id));
        }
        return result;
    }
}
