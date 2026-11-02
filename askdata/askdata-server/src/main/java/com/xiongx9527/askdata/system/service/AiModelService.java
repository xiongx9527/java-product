package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.common.util.Maps;
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

import javax.servlet.http.HttpServletResponse;
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

    /** 对话模型（ai_model.model_type=0）；向量模型为 1，Rerank 为 2（本期不开放）。 */
    public static final int CHAT_MODEL_TYPE = 0;

    /** 向量模型。 */
    public static final int EMBEDDING_MODEL_TYPE = 1;

    /** 自定义提供商（供应商表 id=15）：无静态模型预设，拉取失败只能手输。 */
    public static final int CUSTOM_SUPPLIER = 15;

    /** 上游模型列表返回条数上限，避免异常端点返回超大数组。 */
    private static final int MAX_MODELS = 200;

    @Autowired
    private MessageResolver messageResolver;

    private final AiModelRepository aiModelRepository;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    private final ObjectMapper objectMapper;

    private final com.xiongx9527.askdata.chat.service.ChatModelClient chatModelClient;

    public AiModelService(AiModelRepository aiModelRepository, CurrentUserService currentUserService,
                          IdGenerator idGenerator, ObjectMapper objectMapper,
                          com.xiongx9527.askdata.chat.service.ChatModelClient chatModelClient) {
        this.aiModelRepository = aiModelRepository;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.chatModelClient = chatModelClient;
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
                    "enable", Rows.bool(row, "enable", true),
                    "valid", Rows.bool(row, "valid", true),
                    "upstream_format", Rows.intg(row, "upstream_format", 1),
                    "dimension", Rows.get(row, "dimension"),
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
        // 新建不自动成为默认：默认模型必须可用且已启用，而新模型尚未通过连通校验
        boolean defaultModel = Rows.bool(body, "default_model", false);
        boolean valid = Rows.bool(body, "valid", false);
        boolean enable = Rows.bool(body, "enable", false) && valid;
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
                System.currentTimeMillis(),
                enable,
                valid,
                Rows.intg(body, "upstream_format", 0),
                dimensionOf(body));
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
        // valid 只由连通校验回写：保存配置沿用库中值，不因保存动作改写
        boolean valid = Rows.bool(existing, "valid", false);
        boolean existingEnable = Rows.bool(existing, "enable", false);
        boolean defaultModel = Rows.get(body, "default_model") == null
                ? Rows.bool(existing, "default_model", false)
                : Rows.bool(body, "default_model", false);
        boolean requestedEnable = Rows.get(body, "enable") == null
                ? existingEnable
                : Rows.bool(body, "enable", false);
        int modelType = Rows.get(body, "model_type") == null ? Rows.intg(existing, "model_type", 0)
                : Rows.intg(body, "model_type", 0);
        Integer dimension = Rows.get(body, "dimension") == null
                ? integerOf(Rows.get(existing, "dimension")) : dimensionOf(body);
        boolean isDefault = Rows.bool(existing, "default_model", false);
        if (isDefault && !requestedEnable) {
            // 默认模型不可停用：停用会让该类型链路无模型可用
            throw new BadRequestException(messageResolver.get("aimodel.disable_default_error"));
        }
        if (requestedEnable && !valid) {
            throw new BadRequestException(messageResolver.get("aimodel.enable_requires_valid"));
        }
        // 决策 15：向量模型的维度是启用前提——维度未探测时无法校验存储对齐
        if (requestedEnable && modelType == EMBEDDING_MODEL_TYPE && dimension == null) {
            throw new BadRequestException(messageResolver.get("aimodel.enable_requires_dimension"));
        }
        aiModelRepository.update(
                id,
                apiKey,
                apiDomain,
                Rows.get(body, "protocol") == null ? Rows.intg(existing, "protocol", 1)
                        : Rows.intg(body, "protocol", 1),
                Rows.get(body, "supplier") == null ? Rows.intg(existing, "supplier", 0)
                        : Rows.intg(body, "supplier", 0),
                Rows.get(body, "name") == null ? Rows.str(existing, "name") : Rows.str(body, "name"),
                modelType,
                Rows.get(body, "base_model") == null ? Rows.str(existing, "base_model")
                        : Rows.str(body, "base_model"),
                defaultModel,
                config,
                requestedEnable,
                valid,
                Rows.get(body, "upstream_format") == null ? Rows.intg(existing, "upstream_format", 0)
                        : Rows.intg(body, "upstream_format", 0),
                dimension);
    }

    @Transactional
    public void delete(long id) {
        currentUserService.requireAdmin();
        Map<String, Object> model = aiModelRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("aimodel.not.found", id)));
        if (Rows.bool(model, "default_model", false)) {
            throw new BadRequestException(messageResolver.get("i18n_llm.delete_default_error",
                    Maps.of("key", String.valueOf(Rows.str(model, "name")))));
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
        if (!Rows.bool(model, "valid", false) || !Rows.bool(model, "enable", false)) {
            throw new BadRequestException(messageResolver.get("aimodel.default.requires_available"));
        }
        // 只清除同类型默认标记，LLM 与向量模型互不影响
        aiModelRepository.clearDefaults(Rows.intg(model, "model_type", 0));
        aiModelRepository.setDefault(id);
    }

    /** GET /system/aimodel/default：无可用对话模型时抛错（对齐 SQLBot check_default）。 */
    public void requireDefaultModel() {
        if (!aiModelRepository.findDefault(CHAT_MODEL_TYPE).isPresent()
                && firstAvailable(CHAT_MODEL_TYPE) == null) {
            throw new BadRequestException(messageResolver.get("aimodel.default.not.configured"));
        }
    }

    /**
     * GET /system/aimodel/default/status：区分「未配置」与「已配置但不可用」。
     * configured = 是否存在任意 LLM 模型；available = 是否存在「已启用 + 已校验」的默认/首个 LLM。
     */
    public Map<String, Object> defaultStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> all = aiModelRepository.listByType(CHAT_MODEL_TYPE);
        boolean configured = !all.isEmpty();
        Map<String, Object> def =
                aiModelRepository.findDefault(CHAT_MODEL_TYPE).orElse(firstAvailable(CHAT_MODEL_TYPE));
        boolean available = def != null;
        result.put("configured", configured);
        result.put("available", available);
        result.put("model_id", def == null ? null : def.get("id"));
        result.put("model_name", def == null ? null : def.get("name"));
        return result;
    }

    /** 指定类型下首个「已启用 + 已校验」的模型；不存在返回 null。 */
    private Map<String, Object> firstAvailable(int modelType) {
        for (Map<String, Object> row : aiModelRepository.listByType(modelType)) {
            if (Rows.bool(row, "enable", false) && Rows.bool(row, "valid", false)) {
                return row;
            }
        }
        return null;
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

    /**
     * 按工作空间列可用对话模型（对齐 SQLBot {@code with_default=True}：绑定模型 ∪ 该类型默认模型）。
     *
     * <p>默认模型始终并入，否则「默认模型未显式绑定」时列表为空，MCP 客户端取不到可用模型。</p>
     */
    private List<Map<String, Object>> workspaceModels(long oid) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : aiModelRepository.listByWorkspace(oid, CHAT_MODEL_TYPE, true)) {
            result.add(Rows.of(
                    "id", String.valueOf(Rows.lng(row, "id", 0L)),
                    "name", Rows.str(row, "name"),
                    "default_model", Rows.bool(row, "default_model", false),
                    "supplier", Rows.intg(row, "supplier", 0)));
        }
        return result;
    }

    /** MCP mcp_model_list：按指定工作空间列模型（调用方负责归属校验）。 */
    public List<Map<String, Object>> listByWs(long oid) {
        return workspaceModels(oid);
    }

    /** MCP mcp_model_list 兼容层：与 {@link #listByWs(long)} 同口径（保留旧入口）。 */
    public List<Map<String, Object>> listByWsWithDefault(long oid) {
        return workspaceModels(oid);
    }

    /** /system/aimodel/list/by_ws：当前用户工作空间可用模型（id 为字符串，对齐 SQLBot AiModelBrief）。 */
    public List<Map<String, Object>> listByWs() {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        return workspaceModels(oid);
    }

    // ------------------------------------------------------------------
    // 连通检查（POST /system/aimodel/status，NDJSON 流式帧）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 上游模型列表（POST /system/aimodel/models）
    // ------------------------------------------------------------------

    /**
     * 代理请求上游 {@code /models}，供前端在选择基础模型时拉取。
     *
     * <p>返回 {@code {source, models[], error?}}：{@code source=remote} 表示来自上游；
     * {@code preset} 表示上游不可用、前端回退供应商静态清单；{@code none} 表示自定义提供商无兜底。</p>
     */
    public Map<String, Object> listModels(Map<String, Object> body) {
        currentUserService.requireAdmin();
        String apiDomain = CryptoUtil.resolvePlain(Rows.str(body, "api_domain"));
        String apiKey = CryptoUtil.resolvePlain(Rows.str(body, "api_key"));
        boolean fullUrl = Rows.bool(body, "full_url", false);
        int format = com.xiongx9527.askdata.chat.service.UpstreamFormatResolver.resolve(
                apiDomain, Rows.intg(body, "upstream_format", 0));
        int supplier = Rows.intg(body, "supplier", 0);
        Map<String, Object> result = new LinkedHashMap<>();
        if (apiDomain == null || apiDomain.trim().isEmpty()) {
            result.put("source", "none");
            result.put("models", new ArrayList<>());
            result.put("error", messageResolver.get("aimodel.api_domain.required"));
            return result;
        }
        if (format == com.xiongx9527.askdata.chat.service.UpstreamFormatResolver.ANTHROPIC) {
            // Anthropic Messages 不提供 /models
            return fallbackModels(supplier, result, null);
        }
        String url = com.xiongx9527.askdata.chat.service.UpstreamFormatResolver.modelsUrl(apiDomain, fullUrl);
        if (url.isEmpty()) {
            // 完整 URL 无法剥离对话端点：不发请求，直接给出「不支持自动获取」
            return fallbackModels(supplier, result, messageResolver.get("aimodel.models.unsupported"));
        }
        try {
            List<Map<String, Object>> models = fetchModels(url, apiKey, apiDomain);
            if (models.isEmpty()) {
                return fallbackModels(supplier, result, messageResolver.get("aimodel.models.empty"));
            }
            result.put("source", "remote");
            result.put("models", models);
            return result;
        } catch (Exception e) {
            return fallbackModels(supplier, result, messageResolver.get("aimodel.models.unavailable",
                    Maps.of("msg", e.getMessage() == null ? e.toString() : e.getMessage())));
        }
    }

    /** 自定义提供商（supplier=15）没有静态兜底，其余回退前端预设清单。 */
    private Map<String, Object> fallbackModels(int supplier, Map<String, Object> result, String error) {
        result.put("source", supplier == CUSTOM_SUPPLIER ? "none" : "preset");
        result.put("models", new ArrayList<>());
        if (error != null) {
            result.put("error", error);
        }
        return result;
    }

    private List<Map<String, Object>> fetchModels(String url, String apiKey, String apiDomain) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                if (com.xiongx9527.askdata.chat.service.ChatModelClient.isAzureEndpoint(apiDomain)) {
                    connection.setRequestProperty("api-key", apiKey);
                } else {
                    connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
            }
            int code = connection.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            String text = readAllFully(connection.getInputStream());
            JsonNode root = objectMapper.readTree(text);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                data = root.get("models");
            }
            List<Map<String, Object>> models = new ArrayList<>();
            if (data != null && data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.has("id") ? item.get("id").asText()
                            : item.has("name") ? item.get("name").asText() : null;
                    if (id == null || id.isEmpty()) {
                        continue;
                    }
                    models.add(Rows.of("id", id, "name", id, "model_type", guessModelType(id)));
                    if (models.size() >= MAX_MODELS) {
                        break;
                    }
                }
            }
            return models;
        } finally {
            connection.disconnect();
        }
    }

    /** 上游不返回类型，按名称推断向量模型（embedding / bge / gte 等）。 */
    private static int guessModelType(String id) {
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("embed") || lower.contains("bge") || lower.contains("gte")
                || lower.contains("m3e") || lower.contains("text-embedding")) {
            return EMBEDDING_MODEL_TYPE;
        }
        return CHAT_MODEL_TYPE;
    }

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
            // A23/A24：config_list 的推理参数（temperature / top_p / max_tokens / 厂商自定义）与
            // protocol 参与连通检查；Azure 专有域名走 deployment URL + api-key 头（与 ChatModelClient 同一套规则）
            Map<String, Object> params = modelParams(body);
            int protocol = Rows.intg(body, "protocol", 1);
            int modelType = Rows.intg(body, "model_type", CHAT_MODEL_TYPE);
            int format = com.xiongx9527.askdata.chat.service.UpstreamFormatResolver.resolve(
                    apiDomain, Rows.intg(body, "upstream_format", 0));
            Long modelId = Rows.get(body, "id") == null ? null : Rows.lng(body, "id", 0L);
            // 网络异常同样属于「校验未通过」：必须回写 valid=0，否则界面会显示一个连不上的可用模型
            boolean passed = false;
            try {
                if (modelType == EMBEDDING_MODEL_TYPE) {
                    passed = checkEmbedding(apiDomain, apiKey, baseModel, modelId, writer);
                } else if (format == com.xiongx9527.askdata.chat.service.UpstreamFormatResolver.ANTHROPIC
                        || format == com.xiongx9527.askdata.chat.service.UpstreamFormatResolver.RESPONSES) {
                    passed = checkChatWithFormat(apiDomain, apiKey, baseModel, params, format, writer);
                } else {
                    boolean azure = com.xiongx9527.askdata.chat.service.ChatModelClient.isAzureEndpoint(apiDomain);
                    String url = connectivityUrl(apiDomain, baseModel, params, protocol);
                    passed = checkModel(url, apiKey, baseModel, params, azure, writer);
                }
            } catch (Exception e) {
                emitError(writer, e.getMessage() == null ? e.toString() : e.getMessage());
                passed = false;
            }
            if (modelId != null) {
                // valid 只由连通校验回写；enable 需用户手动开启
                aiModelRepository.updateValid(modelId, passed);
                if (!passed) {
                    aiModelRepository.updateEnable(modelId, false);
                }
            }
        } catch (Exception e) {
            emitError(writer, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            writer.flush();
        }
    }

    /**
     * A24：连通检查的目标端点（对齐 SQLBot {@code check_llm} 的 {@code protocol} 分支）。
     *
     * <ul>
     *   <li>Azure 专有域名：{@code {base}/openai/deployments/{deployment}/chat/completions?api-version={v}}，
     *       deployment/api-version 取 config_list 的 {@code deployment_name} / {@code api_version}；</li>
     *   <li>{@code protocol == 1}（OpenAI）：按 api_domain 原样规范化（可带 {@code /v1} 或自定义前缀）；</li>
     *   <li>{@code protocol != 1}（vLLM）：api_domain 视为服务根地址，端点固定在 {@code /v1} 下
     *       （vLLM 的 OpenAI 兼容层始终暴露 {@code /v1/chat/completions}）。</li>
     * </ul>
     */
    public static String connectivityUrl(String apiDomain, String baseModel, Map<String, Object> params,
                                         int protocol) {
        if (com.xiongx9527.askdata.chat.service.ChatModelClient.isAzureEndpoint(apiDomain)) {
            return com.xiongx9527.askdata.chat.service.ChatModelClient.chatCompletionsUrl(
                    apiDomain, baseModel, params);
        }
        if (protocol == 1) {
            return com.xiongx9527.askdata.chat.service.ChatModelClient.chatCompletionsUrl(
                    apiDomain, baseModel, params);
        }
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
            return base + "/v1/chat/completions";
        }
        if (path != null && path.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    /**
     * A23：把 config_list（{@code [{key, val}]}）归并为请求参数 Map。
     *
     * <p>与 ChatModelRouter 组装 extraParams 的口径一致：只有同时含 key 与 val 的项生效。</p>
     */
    public static Map<String, Object> modelParams(Map<String, Object> body) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map<String, Object> item : Rows.mapList(Rows.get(body, "config_list"))) {
            String key = Rows.str(item, "key");
            Object val = Rows.get(item, "val");
            if (key == null || key.isEmpty() || val == null) {
                continue;
            }
            params.put(key, val);
        }
        return params;
    }

    private boolean checkModel(String url, String apiKey, String baseModel, Map<String, Object> params,
                              boolean azure, PrintWriter writer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.isEmpty()) {
                if (azure) {
                    // Azure 不用 Bearer，改用 api-key 头（对齐 SQLBot AzureOpenAI 分支）
                    connection.setRequestProperty("api-key", apiKey);
                } else {
                    connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
            }
            List<com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage> messages =
                    new ArrayList<>();
            messages.add(new com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage(
                    "user", "1+1=?"));
            // A23：复用主链路的请求体组装，保证连通检查与真实问数走同一套参数口径
            Map<String, Object> payload = com.xiongx9527.askdata.chat.service.ChatModelClient.buildPayload(
                    baseModel, messages, params, azure);
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));
            connection.getOutputStream().flush();

            int code = connection.getResponseCode();
            if (code >= 400) {
                String detail = readAll(connection.getErrorStream());
                emitError(writer, messageResolver.get("aimodel.http_error", Maps.of("msg",
                        "HTTP " + code + (detail.isEmpty() ? "" : ": " + detail))));
                return false;
            }
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
                readSseStream(connection.getInputStream(), writer);
            } else {
                String text = readAll(connection.getInputStream());
                emitContentFromJson(text, writer);
            }
            return true;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 向量模型连通校验：请求 {@code /embeddings}，成功时回写探测到的维度。
     *
     * <p>维度取响应中 {@code data[0].embedding} 的长度，是「启用」「设为默认」「问答入口」
     * 三处硬校验的共同来源。</p>
     */
    private boolean checkEmbedding(String apiDomain, String apiKey, String baseModel, Long modelId,
                                   PrintWriter writer) throws IOException {
        List<double[]> vectors = chatModelClient.embed(apiDomain, apiKey, baseModel,
                java.util.Collections.singletonList("1+1=?"));
        if (vectors == null || vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) {
            emitError(writer, messageResolver.get("aimodel.embedding.unavailable"));
            return false;
        }
        int dimension = vectors.get(0).length;
        writeLine(writer, Rows.of("content", "embedding dimension=" + dimension));
        writeLine(writer, Rows.of("dimension", dimension));
        if (modelId != null) {
            aiModelRepository.updateDimension(modelId, dimension);
        }
        return true;
    }

    /** Anthropic Messages / Responses 连通校验：与运行期共用同一格式分支，保证「校验通过即可用」。 */
    private boolean checkChatWithFormat(String apiDomain, String apiKey, String baseModel,
                                        Map<String, Object> params, int format, PrintWriter writer) {
        final boolean[] failed = {false};
        List<com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage> messages = new ArrayList<>();
        messages.add(new com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage("user", "1+1=?"));
        chatModelClient.streamCompletion(apiDomain, apiKey, baseModel, messages, params, 1, format,
                new com.xiongx9527.askdata.chat.service.ChatModelClient.TokenHandler() {
                    @Override
                    public void onToken(String content, String reasoningContent) {
                        if (content != null && !content.isEmpty()) {
                            try {
                                writeLine(writer, Rows.of("content", content));
                            } catch (IOException e) {
                                failed[0] = true;
                            }
                        }
                    }

                    @Override
                    public void onComplete() {
                        // no-op
                    }

                    @Override
                    public void onError(String message) {
                        failed[0] = true;
                        try {
                            emitError(writer, message);
                        } catch (IOException ignored) {
                            // 响应已中断，错误已通过 failed 标记回传
                        }
                    }

                    @Override
                    public void onUsageDetail(long inputTokens, long outputTokens, long total) {
                        // 连通校验不消费 usage
                    }
                });
        return !failed[0];
    }

    /** 完整读取响应体（不截断）：模型列表等业务响应必须全量读取。 */
    private static String readAllFully(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
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
                messageResolver.get("i18n_llm.validate_error", Maps.of("msg", message))));
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


    /** body.dimension → Integer；缺失或非正数返回 null（表示尚未探测）。 */
    private Integer dimensionOf(Map<String, Object> body) {
        Object value = Rows.get(body, "dimension");
        return integerOf(value);
    }

    private Integer integerOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            int v = ((Number) value).intValue();
            return v > 0 ? v : null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(text);
            if (v <= 0) {
                throw new BadRequestException(messageResolver.get("aimodel.dimension.invalid"));
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BadRequestException(messageResolver.get("aimodel.dimension.invalid"));
        }
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
        editor.put("enable", Rows.bool(row, "enable", true));
        editor.put("valid", Rows.bool(row, "valid", true));
        editor.put("upstream_format", Rows.intg(row, "upstream_format", 1));
        editor.put("dimension", Rows.get(row, "dimension"));
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
            throw new BadRequestException(messageResolver.get("i18n_format_invalid", Maps.of("key", "config_list")));
        }
    }

    private String required(Map<String, Object> body, String key) {
        String value = Rows.str(body, key);
        if (value == null || value.isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", key)));
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
