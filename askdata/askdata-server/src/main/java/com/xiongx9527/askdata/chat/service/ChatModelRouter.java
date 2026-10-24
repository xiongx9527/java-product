package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型路由（复用 system-foundation 的 {@link AiModelRepository} 与 {@link CryptoUtil}）：
 * 解析默认对话模型与可选 embedding 模型的明文连接信息，供 {@link ChatModelClient} 调用。
 *
 * <p>api_key/api_domain 在库中是加密形态（与 {@code system/aimodel} 写入路径一致），
 * 此处经 {@link CryptoUtil#resolvePlain} 还原为明文后再交给模型端点。
 * 模型未配置时一律返回 {@code null}（由调用方降级），不抛异常。</p>
 *
 * <p>embedding 模型识别：{@code ai_model.model_type = 1}（0 为对话模型）。
 * 该约定与 D7「bge-m3/nomic-embed-text embedding」一致——未配置 embedding 模型时，
 * RAG 自动退化为词法召回（D5 的降级路径）。</p>
 */
@Service
public class ChatModelRouter {

    private static final int CHAT_MODEL_TYPE = 0;

    private static final int EMBEDDING_MODEL_TYPE = 1;

    private final AiModelRepository aiModelRepository;

    private final ObjectMapper objectMapper;

    public ChatModelRouter(AiModelRepository aiModelRepository, ObjectMapper objectMapper) {
        this.aiModelRepository = aiModelRepository;
        this.objectMapper = objectMapper;
    }

    /** 模型引用（明文连接信息）。 */
    public static class ModelRef {

        public final long id;

        public final String name;

        public final String baseModel;

        public final String apiDomain;

        public final String apiKey;

        /** §2.3: 协议/供应商（ai_model.protocol / supplier）——原先只落库不消费，现随模型解析出来供路由使用。 */
        public final int protocol;

        public final int supplier;

        /** §2.3: 模型推理参数（ai_model.config 的 config_list 解析为 key→val）。
         * 覆盖 temperature / top_p / max_tokens / enable_thinking 及任何厂商自定义 additional_params；
         * 主链路原先只读取 temperature 却从不下发，其余参数更是仅落库。现统一透传到请求体。 */
        public final Map<String, Object> params;

        /** 便捷：temperature（来自 params；缺省 null）。 */
        public final Double temperature;

        public ModelRef(long id, String name, String baseModel, String apiDomain, String apiKey,
                        int protocol, int supplier, Map<String, Object> params, Double temperature) {
            this.id = id;
            this.name = name;
            this.baseModel = baseModel;
            this.apiDomain = apiDomain;
            this.apiKey = apiKey;
            this.protocol = protocol;
            this.supplier = supplier;
            this.params = params == null ? Collections.<String, Object>emptyMap() : params;
            this.temperature = temperature;
        }

        public boolean usable() {
            return apiDomain != null && !apiDomain.trim().isEmpty()
                    && baseModel != null && !baseModel.trim().isEmpty();
        }
    }

    /** 默认对话模型（对齐 SQLBot {@code get_default_model}）；未配置返回 Optional.empty()。 */
    public Optional<ModelRef> defaultChatModel() {
        Optional<Map<String, Object>> row = aiModelRepository.findDefault();
        return row.map(this::toRef);
    }

    /**
     * 指定 id 的对话模型（对齐 SQLBot {@code custom_model}：MCP 可按次选择模型）；不存在返回 Optional.empty()。
     */
    public Optional<ModelRef> chatModelById(long id) {
        return aiModelRepository.findById(id).map(this::toRef);
    }

    /** 默认 embedding 模型（model_type=1）；未配置返回 Optional.empty()。 */
    public Optional<ModelRef> embeddingModel() {
        return aiModelRepository.list(null).stream()
                .filter(item -> Rows.intg(item, "model_type", CHAT_MODEL_TYPE) == EMBEDDING_MODEL_TYPE)
                .findFirst()
                .flatMap(item -> aiModelRepository.findById(Rows.lng(item, "id", 0L)))
                .map(this::toRef);
    }

    private ModelRef toRef(Map<String, Object> row) {
        Map<String, Object> params = paramsOf(Rows.str(row, "config"));
        return new ModelRef(
                Rows.lng(row, "id", 0L),
                Rows.str(row, "name"),
                Rows.str(row, "base_model"),
                CryptoUtil.resolvePlain(Rows.str(row, "api_domain")),
                CryptoUtil.resolvePlain(Rows.str(row, "api_key")),
                Rows.intg(row, "protocol", 1),
                Rows.intg(row, "supplier", 0),
                params,
                (Double) params.get("temperature"));
    }

    /** §2.3: 把 ai_model.config（config_list：[{key,val},...]）解析为 key→val 的推理参数 Map；缺省/非法返回空 Map。 */
    private Map<String, Object> paramsOf(String configJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (configJson == null || configJson.isEmpty()) {
            return result;
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(configJson,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> item : items == null ? Collections.<Map<String, Object>>emptyList() : items) {
                String key = Rows.str(item, "key");
                if (key != null && !key.isEmpty() && item.containsKey("val")) {
                    result.put(key, Rows.get(item, "val"));
                }
            }
        } catch (Exception e) {
            return result;
        }
        return result;
    }
}
