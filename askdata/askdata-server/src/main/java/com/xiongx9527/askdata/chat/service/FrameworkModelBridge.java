package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.fw.plus.ai.base.model.ChatMessage;
import com.xiongx9527.fw.plus.ai.base.model.EmbeddingPort;
import com.xiongx9527.fw.plus.ai.base.model.LlmPort;
import com.xiongx9527.fw.plus.ai.base.model.LlmRequest;
import com.xiongx9527.fw.plus.ai.base.model.LlmStreamHandler;
import com.xiongx9527.fw.plus.ai.base.model.ModelValidation;
import com.xiongx9527.fw.plus.ai.base.model.RerankPort;
import com.xiongx9527.fw.plus.ai.nativeimpl.embedding.EmbeddingModelConfig;
import com.xiongx9527.fw.plus.ai.nativeimpl.embedding.EmbeddingPortResolver;
import com.xiongx9527.fw.plus.ai.nativeimpl.embedding.EmbeddingProtocol;
import com.xiongx9527.fw.plus.ai.nativeimpl.llm.LlmModelConfig;
import com.xiongx9527.fw.plus.ai.nativeimpl.llm.LlmPortResolver;
import com.xiongx9527.fw.plus.ai.nativeimpl.llm.LlmProtocol;
import com.xiongx9527.fw.plus.ai.nativeimpl.rerank.RerankModelConfig;
import com.xiongx9527.fw.plus.ai.nativeimpl.rerank.RerankPortResolver;
import com.xiongx9527.fw.plus.ai.nativeimpl.rerank.RerankProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * askdata 与框架 {@code fw-plus-ai-native} 的桥接层：所有对话 / 向量调用都统一经框架
 * {@link LlmPortResolver} / {@link EmbeddingPortResolver} 路由，askdata 不再自管 HTTP/SSE。
 *
 * <p>设计原则（用户要求「product 不要单独造车，使用框架基础能力」）：</p>
 * <ul>
 *   <li>协议是唯一路由键：{@code ai_model.protocol} 存框架 {@link LlmProtocol} 枚举名，
 *       桥接层按名解析后交给框架对应端口（OpenAI / Azure / Responses / Anthropic / Gemini）。</li>
 *   <li>{@link ChatModelClient} 保留 {@code ModelMessage} / {@code TokenHandler} / {@code FrameWriter}
 *       等 askdata 侧契约，仅把底层实现委托给本桥接层，调用方无需感知框架类型。</li>
 *   <li>超时沿用 askdata 主链路经验值：连接 8s、读取 600s（慢模型首 token 时延可达数分钟）。</li>
 * </ul>
 *
 * <p>已知框架侧缺口（由用户在框架侧修复，见交付说明）：Gemini {@code thought} 思考流未透传为
 * reasoning_content；{@code EmbeddingPortResolver} 仅支持 {@code OPENAI_COMPATIBLE}；
 * 向量端口读超时仅 30s；Azure 的 {@code deployment_name} 只能以 model 表达。</p>
 */
@Service
public class FrameworkModelBridge {

    private static final Logger log = LoggerFactory.getLogger(FrameworkModelBridge.class);

    /** 与 ChatModelClient 一致：慢模型首 token 时延可达数分钟，放宽读超时保障完整返回。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 600000;

    // ------------------------------------------------------------------
    // 对话：流式
    // ------------------------------------------------------------------

    /**
     * 流式补全：经框架 {@link LlmPort} 逐 token 回调。
     *
     * @param protocol {@code ai_model.protocol}（框架 {@link LlmProtocol} 枚举名）；空或非法回落 OPENAI_CHAT_COMPLETIONS
     */
    public void stream(String apiDomain, String apiKey, String baseModel, String protocol,
                       List<ChatModelClient.ModelMessage> messages, Map<String, Object> extraParams,
                       ChatModelClient.TokenHandler handler) {
        if (apiDomain == null || apiDomain.trim().isEmpty() || baseModel == null || baseModel.trim().isEmpty()) {
            handler.onError("模型未配置：缺少 api_domain 或 base_model（请先到系统-AI 模型配置并设置默认模型）");
            return;
        }
        if (messages == null || messages.isEmpty()) {
            handler.onError("模型请求缺少消息内容");
            return;
        }
        try {
            LlmPort port = resolveLlmPort(apiDomain, apiKey, baseModel, protocol, extraParams);
            LlmRequest request = toRequest(baseModel, messages, extraParams);
            port.stream(request).handler(new AskdataStreamHandler(handler)).start();
        } catch (RuntimeException e) {
            log.warn("框架 LLM 流式调用异常（已降级）: {}", e.getMessage());
            handler.onError("模型调用失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * 流式补全（端口已解析）：直接复用调用方传入的 {@link LlmPort}（通常是 {@code AskDataLlmPort}
     * 产出的跨端点故障转移端口），不再按端点字段重新解析。
     *
     * @param model 主模型名（仅用于请求体占位；故障转移端口会按候选重写）
     */
    public void stream(LlmPort port, String model, List<ChatModelClient.ModelMessage> messages,
                       Map<String, Object> extraParams, ChatModelClient.TokenHandler handler) {
        if (port == null) {
            handler.onError("模型未配置：无可用的对话模型候选（请先到系统-AI 模型配置并设置默认模型）");
            return;
        }
        if (messages == null || messages.isEmpty()) {
            handler.onError("模型请求缺少消息内容");
            return;
        }
        try {
            LlmRequest request = toRequest(model, messages, extraParams);
            port.stream(request).handler(new AskdataStreamHandler(handler)).start();
        } catch (RuntimeException e) {
            log.warn("框架 LLM 流式调用异常（已降级）: {}", e.getMessage());
            handler.onError("模型调用失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * 非流式补全：复用同一流式管线聚合正文（与旧 {@code generate} 行为一致）。
     *
     * @return 失败或空响应返回 {@code null}
     */
    public String complete(String apiDomain, String apiKey, String baseModel, String protocol,
                           List<ChatModelClient.ModelMessage> messages, Map<String, Object> extraParams,
                           final ChatModelClient.Usage usage) {
        final StringBuilder sb = new StringBuilder();
        final boolean[] failed = {false};
        stream(apiDomain, apiKey, baseModel, protocol, messages, extraParams,
                new ChatModelClient.TokenHandler() {
                    @Override
                    public void onToken(String content, String reasoningContent) {
                        if (content != null) {
                            sb.append(content);
                        }
                    }

                    @Override
                    public void onComplete() {
                        // no-op
                    }

                    @Override
                    public void onError(String message) {
                        failed[0] = true;
                    }

                    @Override
                    public void onUsageDetail(long inputTokens, long outputTokens, long total) {
                        if (usage != null) {
                            usage.input += inputTokens;
                            usage.output += outputTokens;
                            usage.total += total;
                        }
                    }
                });
        return failed[0] || sb.length() == 0 ? null : sb.toString();
    }

	/**
	 * 非流式补全（端口已解析）：复用同一流式管线聚合正文（与旧 {@code generate} 行为一致）。
	 *
	 * @return 失败或空响应返回 {@code null}
	 */
	public String complete(LlmPort port, String model, List<ChatModelClient.ModelMessage> messages,
			Map<String, Object> extraParams, final ChatModelClient.Usage usage) {
		final StringBuilder sb = new StringBuilder();
		final boolean[] failed = {false};
		stream(port, model, messages, extraParams, new ChatModelClient.TokenHandler() {
			@Override
			public void onToken(String content, String reasoningContent) {
				if (content != null) {
					sb.append(content);
				}
			}

			@Override
			public void onComplete() {
				// no-op
			}

			@Override
			public void onError(String message) {
				failed[0] = true;
			}

			@Override
			public void onUsageDetail(long inputTokens, long outputTokens, long total) {
				if (usage != null) {
					usage.input += inputTokens;
					usage.output += outputTokens;
					usage.total += total;
				}
			}
		});
		return failed[0] || sb.length() == 0 ? null : sb.toString();
	}

	/** 对话模型校验：经框架 {@code validateModel}（一次 max_tokens=1 真实探测）。 */
	public ModelValidation validateChat(String apiDomain, String apiKey, String baseModel, String protocol) {
        try {
            LlmPort port = resolveLlmPort(apiDomain, apiKey, baseModel, protocol, Collections.<String, Object>emptyMap());
            return port.validateModel(baseModel);
        } catch (RuntimeException e) {
            log.warn("对话模型校验异常: {}", e.getMessage());
            return ModelValidation.invalid(baseModel, e.getMessage() == null ? "invalid" : e.getMessage(), 0L);
        }
    }

    // ------------------------------------------------------------------
    // 向量
    // ------------------------------------------------------------------

    /**
     * 批量向量化：经框架 {@link EmbeddingPort}。
     *
     * <p>框架 {@code EmbeddingPortResolver} 当前仅支持 {@code OPENAI_COMPATIBLE}；RAG 用的
     * OpenAI 兼容 {@code /v1/embeddings} 直接命中，无需协议分支。</p>
     *
     * @return 失败返回 {@code null}（调用方据此降级为词法召回）
     */
    public List<double[]> embed(String apiDomain, String apiKey, String baseModel,
                               List<String> inputs, Integer dimension) {
        if (apiDomain == null || apiDomain.trim().isEmpty() || baseModel == null || baseModel.trim().isEmpty()
                || inputs == null || inputs.isEmpty()) {
            return null;
        }
        try {
            EmbeddingPort port = EmbeddingPortResolver.create(EmbeddingModelConfig.builder()
                    .protocol(EmbeddingProtocol.OPENAI_COMPATIBLE)
                    .baseUrl(apiDomain)
                    .apiKey(apiKey)
                    .defaultModel(baseModel)
                    .dimension(dimension)
                    .connectTimeoutMillis(CONNECT_TIMEOUT_MS)
                    .readTimeoutMillis(READ_TIMEOUT_MS)
                    .build());
            return port.embedAll(inputs);
        } catch (RuntimeException e) {
            log.info("embedding 调用异常（降级为词法召回）: {}", e.getMessage());
            return null;
        }
    }

    /** 向量模型校验：经框架 {@code validateModel}（真实编码一条探针文本）。 */
    public ModelValidation validateEmbedding(String apiDomain, String apiKey, String baseModel, Integer dimension) {
        try {
            EmbeddingPort port = EmbeddingPortResolver.create(EmbeddingModelConfig.builder()
                    .protocol(EmbeddingProtocol.OPENAI_COMPATIBLE)
                    .baseUrl(apiDomain)
                    .apiKey(apiKey)
                    .defaultModel(baseModel)
                    .dimension(dimension)
                    .connectTimeoutMillis(CONNECT_TIMEOUT_MS)
                    .readTimeoutMillis(READ_TIMEOUT_MS)
                    .build());
            return port.validateModel(baseModel);
        } catch (RuntimeException e) {
            log.warn("向量模型校验异常: {}", e.getMessage());
            return ModelValidation.invalid(baseModel, e.getMessage() == null ? "invalid" : e.getMessage(), 0L);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private LlmPort resolveLlmPort(String apiDomain, String apiKey, String baseModel, String protocol,
                                   Map<String, Object> extraParams) {
        LlmProtocol resolved = ProtocolMapper.toLlmProtocol(protocol)
                .orElse(LlmProtocol.OPENAI_CHAT_COMPLETIONS);
        LlmModelConfig.Builder cb = LlmModelConfig.builder()
                .protocol(resolved)
                .baseUrl(apiDomain)
                .apiKey(apiKey)
                .defaultModel(baseModel)
                .connectTimeoutMillis(CONNECT_TIMEOUT_MS)
                .readTimeoutMillis(READ_TIMEOUT_MS);
        // Azure：api_version / deployment_name 是 askdata 历史 extraParams，框架 Azure 端口只认
        // config.apiVersion 并以 model 作 deployment，桥接层在此翻译（其余协议忽略这两个键）。
        if (resolved == LlmProtocol.AZURE_OPENAI_CHAT_COMPLETIONS && extraParams != null) {
            Object av = extraParams.get("api_version");
            if (av != null && !String.valueOf(av).trim().isEmpty()) {
                cb.apiVersion(String.valueOf(av).trim());
            }
            Object dn = extraParams.get("deployment_name");
            if (dn != null && !String.valueOf(dn).trim().isEmpty()) {
                cb.defaultModel(String.valueOf(dn).trim());
            }
        }
        return LlmPortResolver.create(cb.build());
    }

    /** 由 askdata 消息 + 推理参数构造框架 {@link LlmRequest}（model 由调用方指定，故障转移时按候选重写）。 */
    public LlmRequest toRequest(String baseModel, List<ChatModelClient.ModelMessage> messages,
                               Map<String, Object> extraParams) {
        LlmRequest.Builder b = LlmRequest.builder().model(baseModel);
        if (messages != null) {
            for (ChatModelClient.ModelMessage m : messages) {
                b.addMessage(toChatMessage(m));
            }
        }
        if (extraParams != null) {
            b.extraParams(extraParams);
        }
        return b.build();
    }

    /**
     * 由单个 {@link ChatModelRouter.ModelRef} 解析出框架 {@link LlmPort}（不含故障转移包装）。
     *
     * <p>供 {@code AskDataLlmPort} 为每个候选模型构建端口；{@code extraParams} 主要承载 Azure 的
     * {@code api_version}/{@code deployment_name} 等端点专属配置（来自 {@code ModelRef.params}）。</p>
     */
    public LlmPort buildLlmPort(ChatModelRouter.ModelRef ref, Map<String, Object> extraParams) {
        if (ref == null || !ref.usable()) {
            return null;
        }
        return resolveLlmPort(ref.apiDomain, ref.apiKey, ref.baseModel, ref.protocol, extraParams);
    }

    /**
     * 由单个 {@link ChatModelRouter.ModelRef} 解析出框架 {@link RerankPort}（不含故障转移包装）。
     *
     * <p>重排协议与对话协议是不同命名空间：仅识别框架 {@code RerankProtocol}（TEI / COHERE），
     * 其余（产品尚未接入重排评分路径）返回 {@code null}，由调用方降级为不重排。</p>
     */
    public RerankPort buildRerankPort(ChatModelRouter.ModelRef ref) {
        if (ref == null || !ref.usable() || ref.protocol == null) {
            return null;
        }
        RerankProtocol protocol;
        try {
            protocol = RerankProtocol.valueOf(ref.protocol.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            return RerankPortResolver.create(RerankModelConfig.builder()
                    .endpoint(ref.apiDomain)
                    .apiKey(ref.apiKey)
                    .defaultModel(ref.baseModel)
                    .protocol(protocol)
                    .connectTimeoutMillis(CONNECT_TIMEOUT_MS)
                    .readTimeoutMillis(READ_TIMEOUT_MS)
                    .build());
        } catch (RuntimeException e) {
            log.warn("重排模型端口构建失败（跳过）: {}", e.getMessage());
            return null;
        }
    }

    private ChatMessage toChatMessage(ChatModelClient.ModelMessage m) {
        if (m == null || m.content == null) {
            return ChatMessage.user("");
        }
        if ("system".equals(m.role)) {
            return ChatMessage.system(m.content);
        }
        if ("assistant".equals(m.role)) {
            return ChatMessage.assistant(m.content);
        }
        return ChatMessage.user(m.content);
    }

    /** askdata {@link ChatModelClient.TokenHandler} → 框架 {@link LlmStreamHandler} 适配器。 */
    public static final class AskdataStreamHandler implements LlmStreamHandler {

        private final ChatModelClient.TokenHandler delegate;

        public AskdataStreamHandler(ChatModelClient.TokenHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onToken(String content, String reasoningContent) {
            delegate.onToken(content, reasoningContent);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }

        @Override
        public void onError(String message) {
            delegate.onError(message);
        }

        @Override
        public void onUsage(long totalTokens) {
            delegate.onUsage(totalTokens);
        }

        @Override
        public void onUsageDetail(long inputTokens, long outputTokens, long totalTokens) {
            delegate.onUsageDetail(inputTokens, outputTokens, totalTokens);
        }
    }
}
