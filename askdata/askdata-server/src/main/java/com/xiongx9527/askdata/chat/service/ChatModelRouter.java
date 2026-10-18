package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
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

        public final Double temperature;

        public ModelRef(long id, String name, String baseModel, String apiDomain, String apiKey,
                        Double temperature) {
            this.id = id;
            this.name = name;
            this.baseModel = baseModel;
            this.apiDomain = apiDomain;
            this.apiKey = apiKey;
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

    /** 默认 embedding 模型（model_type=1）；未配置返回 Optional.empty()。 */
    public Optional<ModelRef> embeddingModel() {
        return aiModelRepository.list(null).stream()
                .filter(item -> Rows.intg(item, "model_type", CHAT_MODEL_TYPE) == EMBEDDING_MODEL_TYPE)
                .findFirst()
                .flatMap(item -> aiModelRepository.findById(Rows.lng(item, "id", 0L)))
                .map(this::toRef);
    }

    private ModelRef toRef(Map<String, Object> row) {
        return new ModelRef(
                Rows.lng(row, "id", 0L),
                Rows.str(row, "name"),
                Rows.str(row, "base_model"),
                CryptoUtil.resolvePlain(Rows.str(row, "api_domain")),
                CryptoUtil.resolvePlain(Rows.str(row, "api_key")),
                temperature(Rows.str(row, "config")));
    }

    private Double temperature(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            return null;
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(configJson,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> item : items == null ? Collections.<Map<String, Object>>emptyList() : items) {
                if ("temperature".equals(Rows.str(item, "key"))) {
                    Object value = Rows.get(item, "val");
                    if (value instanceof Number) {
                        return ((Number) value).doubleValue();
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
