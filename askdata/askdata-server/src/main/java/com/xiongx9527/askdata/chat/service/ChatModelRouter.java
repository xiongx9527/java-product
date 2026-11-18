package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** 重排（打分）模型类型；ai_model.model_type = 2（与对话 0 / 向量 1 并列）。 */
    private static final int RERANK_MODEL_TYPE = 2;

    /**
     * 向量模型解析缓存 TTL。
     *
     * <p>向量模型是全局配置，回填/召回会高频读取（回填 N 张表原先要 2N 次查库）。
     * 失效以 {@code AiModelRepository#writeVersion()} 为准：任何经仓储的写操作都会自增版本，
     * 缓存键随之失配而重新解析；TTL 只用于兜住绕过仓储直接改库的极端场景。</p>
     */
    private static final long EMBEDDING_CACHE_TTL_MS = 60_000L;

    private final AiModelRepository aiModelRepository;

    private final ObjectMapper objectMapper;

    /** 已解析的默认向量模型（{@code null} 表示「已解析为无可用模型」）。 */
    private volatile CachedEmbedding cachedEmbedding;

    public ChatModelRouter(AiModelRepository aiModelRepository, ObjectMapper objectMapper) {
        this.aiModelRepository = aiModelRepository;
        this.objectMapper = objectMapper;
    }

    /** 工作空间模型策略读取；离线单测未注入时退化为「无策略」，保持既有解析行为。 */
    private WorkspaceRepository workspaceRepository;

    private MessageResolver messageResolver;

    @Autowired(required = false)
    public void setWorkspaceRepository(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Autowired(required = false)
    public void setMessageResolver(MessageResolver messageResolver) {
        this.messageResolver = messageResolver;
    }

    private String message(String key) {
        return messageResolver == null ? key : messageResolver.get(key);
    }

    /** 缓存项：解析结果 + 解析时刻 + 解析时的仓储写版本。 */
    private static final class CachedEmbedding {

        final ModelRef model;

        final long loadedAt;

        final long writeVersion;

        CachedEmbedding(ModelRef model, long loadedAt, long writeVersion) {
            this.model = model;
            this.loadedAt = loadedAt;
            this.writeVersion = writeVersion;
        }
    }

    /** 模型引用（明文连接信息）。 */
    public static class ModelRef {

        public final long id;

        public final String name;

        public final String baseModel;

        public final String apiDomain;

        public final String apiKey;

        /** §2.3: 协议（ai_model.protocol，框架 LlmProtocol 枚举名，如 OPENAI_CHAT_COMPLETIONS / GEMINI）——随模型解析出来供路由使用。 */
        public final String protocol;

        public final int supplier;

        /** §2.3: 模型推理参数（ai_model.config 的 config_list 解析为 key→val）。
         * 覆盖 temperature / top_p / max_tokens / enable_thinking 及任何厂商自定义 additional_params；
         * 主链路原先只读取 temperature 却从不下发，其余参数更是仅落库。现统一透传到请求体。 */
        public final Map<String, Object> params;

        /** 便捷：temperature（来自 params；缺省 null）。 */
        public final Double temperature;

        /** 向量维度（ai_model.dimension；仅向量模型有值，未探测为 null）。 */
        public final Integer dimension;

        /** 兼容旧调用方（测试夹具）：无维度。 */
        public ModelRef(long id, String name, String baseModel, String apiDomain, String apiKey,
                        String protocol, int supplier, Map<String, Object> params, Double temperature) {
            this(id, name, baseModel, apiDomain, apiKey, protocol, supplier, params, temperature, null);
        }

        public ModelRef(long id, String name, String baseModel, String apiDomain, String apiKey,
                        String protocol, int supplier, Map<String, Object> params, Double temperature,
                        Integer dimension) {
            this.id = id;
            this.name = name;
            this.baseModel = baseModel;
            this.apiDomain = apiDomain;
            this.apiKey = apiKey;
            this.protocol = protocol;
            this.supplier = supplier;
            this.params = params == null ? Collections.<String, Object>emptyMap() : params;
            this.temperature = temperature;
            this.dimension = dimension;
        }

        public boolean usable() {
            return apiDomain != null && !apiDomain.trim().isEmpty()
                    && baseModel != null && !baseModel.trim().isEmpty();
        }
    }

    /**
     * 默认对话模型（对齐 SQLBot {@code get_default_model}）；未配置返回 Optional.empty()。
     *
     * <p>该类型尚无默认模型时回退到首个「已启用 + 已校验」的模型，保证首次配置后即可使用。</p>
     */
    public Optional<ModelRef> defaultChatModel() {
        Optional<Map<String, Object>> row = aiModelRepository.findDefault(CHAT_MODEL_TYPE);
        if (!row.isPresent()) {
            row = Optional.ofNullable(firstAvailable(CHAT_MODEL_TYPE));
        }
        return row.map(this::toRef);
    }

    /**
     * 工作空间视角的默认对话模型：全局默认 → 该空间可用模型 → 全局首个可用。
     *
     * <p>一个工作空间可以绑定多个模型（对话时用 {@code custom_model} 指定），未指定时按本方法
     * 选一个：全局默认对所有空间生效（对齐 SQLBot），没有默认时才退到该空间自己的可用模型，
     * 避免「空间 A 的模型被空间 B 静默使用」。</p>
     */
    public Optional<ModelRef> defaultChatModel(long oid) {
        Optional<Map<String, Object>> row = aiModelRepository.findDefault(CHAT_MODEL_TYPE);
        if (row.isPresent()) {
            return row.map(this::toRef);
        }
        // 没有默认模型时只接受「本空间唯一可用模型」；多个候选属于歧义，不猜
        List<Map<String, Object>> candidates = aiModelRepository.listByWorkspace(oid, CHAT_MODEL_TYPE, false);
        if (candidates.size() == 1) {
            return Optional.of(toRef(candidates.get(0)));
        }
        return Optional.empty();
    }

    /** 指定工作空间内可用对话模型数（用于区分「未配置」与「多模型未设默认」两种阻断）。 */
    public int workspaceModelCount(long oid) {
        return aiModelRepository.countAvailableInWorkspace(oid, CHAT_MODEL_TYPE);
    }

	/**
	 * 对话模型故障转移候选链（按优先级排序）：
	 * <b>默认对话模型在前作为主模型，工作空间可见的其它启用对话模型在后作为辅助</b>。
	 *
	 * <p>供 {@code AskDataLlmPort} 动态解析为跨端点的 {@link com.xiongx9527.fw.plus.ai.support.resilience.FallbackLlmPort}
	 * 候选——主模型不可用（限流 / 余额不足 / 无权限等）时透明切到辅助模型。
	 * 候选在每次调用时动态解析（经返回的列表 + 调用方懒解析），库里模型增删改无需重启即生效。</p>
	 *
	 * @param oid 工作空间 id
	 */
	public List<ModelRef> chatModelCandidates(long oid) {
		List<ModelRef> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		Optional<ModelRef> def = defaultChatModel(oid);
		def.ifPresent(d -> {
			seen.add(d.id);
			out.add(d);
		});
		// 工作空间绑定的其它启用对话模型（不含默认项，避免重复），按名称稳定排序
		for (Map<String, Object> row : aiModelRepository.listByWorkspace(oid, CHAT_MODEL_TYPE, false)) {
			if (!Rows.bool(row, "enable", false)) {
				continue;
			}
			ModelRef r = toRef(row);
			if (seen.add(r.id)) {
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * 重排（打分）模型故障转移候选链：默认重排模型在前，工作空间可见的其它启用重排模型在后。
	 *
	 * <p>语义与 {@link #chatModelCandidates(long)} 一致，模型类型为 {@code model_type = 2}。
	 * 当前产品侧尚无真实 rerank 打分调用链，此处先把候选解析能力备好，随 RAG 接入即用。</p>
	 *
	 * @param oid 工作空间 id
	 */
	public List<ModelRef> rerankModelCandidates(long oid) {
		List<ModelRef> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		Optional<ModelRef> def = aiModelRepository.findDefault(RERANK_MODEL_TYPE).map(this::toRef);
		def.ifPresent(d -> {
			seen.add(d.id);
			out.add(d);
		});
		for (Map<String, Object> row : aiModelRepository.listByWorkspace(oid, RERANK_MODEL_TYPE, false)) {
			if (!Rows.bool(row, "enable", false)) {
				continue;
			}
			ModelRef r = toRef(row);
			if (seen.add(r.id)) {
				out.add(r);
			}
		}
		return out;
	}

    /**
     * 指定 id 的对话模型（对齐 SQLBot {@code custom_model}：可按次选择模型）。
     *
     * <p>除类型/启用两项外，还要求模型在当前工作空间可见（显式绑定或该类型默认模型）——
     * SQLBot 只在 {@code ws} 的模型列表内生效，否则任意 id 都能跨空间取用。</p>
     *
     * <p>「已校验」不再单独判定：{@code ai_model.valid} 列已合并进 {@code enable}
     * （见 V6__drop_ai_model_valid.sql），启用即代表通过过连通校验。</p>
     */
    public Optional<ModelRef> chatModelById(long id, long oid) {
        Optional<Map<String, Object>> row = aiModelRepository.findById(id);
        if (!row.isPresent()) {
            return Optional.empty();
        }
        Map<String, Object> model = row.get();
        if (Rows.intg(model, "model_type", CHAT_MODEL_TYPE) != CHAT_MODEL_TYPE
                || !Rows.bool(model, "enable", false)) {
            return Optional.empty();
        }
        if (!aiModelRepository.isAvailableInWorkspace(id, oid)) {
            return Optional.empty();
        }
        return Optional.of(toRef(model));
    }

    /**
     * 工作空间模型策略解析（对齐 SQLBot {@code resolve_model_id}）优先级：
     * <ol>
     *   <li>策略开启但工作空间默认模型为空 → 禁止该空间一切模型调用（含 assistant / MCP 自带模型）；</li>
     *   <li>按次 {@code custom_model}（须在映射授权内）；</li>
     *   <li>助手自带模型；</li>
     *   <li>工作空间默认模型；</li>
     *   <li>全局默认模型。</li>
     * </ol>
     *
     * <p>第 2/3 步不可用时沿用既有回退行为（不抛错），仅第 1 步策略禁用为硬阻断。</p>
     */
    public Optional<ModelRef> resolveChatModel(long oid, Long requestCustomModelId, Long assistantModelId) {
        boolean enabled = false;
        String workspaceModel = null;
        if (workspaceRepository != null) {
            Optional<Map<String, Object>> policy = workspaceRepository.findModelPolicy(oid);
            if (policy.isPresent()) {
                enabled = Rows.bool(policy.get(), "enable_custom_model", false);
                workspaceModel = Rows.str(policy.get(), "custom_model");
            }
            if (enabled && (workspaceModel == null || workspaceModel.trim().isEmpty())) {
                throw new BadRequestException(message("i18n_llm.workspace_disabled"));
            }
        }
        if (requestCustomModelId != null) {
            Optional<ModelRef> ref = chatModelById(requestCustomModelId, oid);
            if (ref.isPresent()) {
                return ref;
            }
        }
        if (assistantModelId != null) {
            Optional<ModelRef> ref = chatModelById(assistantModelId, oid);
            if (ref.isPresent()) {
                return ref;
            }
        }
        if (enabled && workspaceModel != null && !workspaceModel.trim().isEmpty()) {
            try {
                Optional<ModelRef> ref = chatModelById(Long.parseLong(workspaceModel.trim()), oid);
                if (ref.isPresent()) {
                    return ref;
                }
            } catch (NumberFormatException ignore) {
                // 历史非法值退到全局默认，不阻断问数
            }
        }
        return defaultChatModel(oid);
    }

    /**
     * 默认 embedding 模型（model_type=1）；未配置或不可用返回 Optional.empty()。
     *
     * <p>结果按 {@link #EMBEDDING_CACHE_TTL_MS} 缓存，避免回填时每行都查库；
     * 模型配置变更（增删改、设默认、连通校验回写）都会让仓储写版本号自增，缓存立即失配并重新解析，
     * 因此「保存配置 → 下一次调用」即生效，不依赖 TTL 到期。</p>
     */
    public Optional<ModelRef> embeddingModel() {
        long version = aiModelRepository.writeVersion();
        CachedEmbedding cached = cachedEmbedding;
        if (cached != null && cached.writeVersion == version
                && System.currentTimeMillis() - cached.loadedAt < EMBEDDING_CACHE_TTL_MS) {
            return Optional.ofNullable(cached.model);
        }
        Optional<Map<String, Object>> row = aiModelRepository.findDefault(EMBEDDING_MODEL_TYPE);
        if (!row.isPresent()) {
            row = Optional.ofNullable(firstAvailable(EMBEDDING_MODEL_TYPE));
        }
        ModelRef model = row.map(this::toRef).orElse(null);
        cachedEmbedding = new CachedEmbedding(model, System.currentTimeMillis(), version);
        return Optional.ofNullable(model);
    }

    /** 指定类型下首个已启用的模型；不存在返回 null。 */
    private Map<String, Object> firstAvailable(int modelType) {
        for (Map<String, Object> row : aiModelRepository.listByType(modelType)) {
            if (Rows.bool(row, "enable", false)) {
                return row;
            }
        }
        return null;
    }

    private ModelRef toRef(Map<String, Object> row) {
        Map<String, Object> params = paramsOf(Rows.str(row, "config"));
        return new ModelRef(
                Rows.lng(row, "id", 0L),
                Rows.str(row, "name"),
                Rows.str(row, "base_model"),
                CryptoUtil.resolvePlain(Rows.str(row, "api_domain")),
                CryptoUtil.resolvePlain(Rows.str(row, "api_key")),
                Rows.str(row, "protocol"),
                Rows.intg(row, "supplier", 0),
                params,
                (Double) params.get("temperature"),
                Rows.get(row, "dimension") == null ? null : Rows.intg(row, "dimension", 0));
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
