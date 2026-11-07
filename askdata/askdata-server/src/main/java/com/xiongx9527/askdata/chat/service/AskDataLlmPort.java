package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.fw.plus.ai.askdata.llm.ResilientLlmPort;
import com.xiongx9527.fw.plus.ai.base.model.LlmPort;
import com.xiongx9527.fw.plus.ai.base.model.LlmRequest;
import com.xiongx9527.fw.plus.ai.base.model.RerankPort;
import com.xiongx9527.fw.plus.ai.support.resilience.FailoverErrorClassifier;
import com.xiongx9527.fw.plus.ai.support.resilience.FallbackLlmPort;
import com.xiongx9527.fw.plus.ai.support.resilience.FallbackRerankPort;
import com.xiongx9527.fw.plus.ai.support.resilience.KeywordFailoverErrorClassifier;
import com.xiongx9527.fw.plus.ai.support.resilience.LlmModelCandidate;
import com.xiongx9527.fw.plus.ai.support.resilience.RerankModelCandidate;
import com.xiongx9527.askdata.config.FallbackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.function.Supplier;

/**
 * askdata 侧的多模型故障转移编排：给定工作空间 {@code oid}，动态解析该空间可见的对话 / 重排模型
 * 候选链，经框架 {@link FallbackLlmPort} / {@link FallbackRerankPort} 在主模型失败时顺次切换。
 *
 * <p>设计原则（对齐用户对框架的要求）：本类<b>只组装框架提供的故障转移机制</b>，具体「哪些错误该
 * 重试 / 切换 / 终止」的策略由 {@link FallbackProperties}（{@code askdata.ai.fallback.*}）注入，
 * 框架侧 {@link KeywordFailoverErrorClassifier} 仅提供匹配机制，不内置任何业务错误清单。</p>
 *
 * <p>候选链每次调用动态解析（{@link ChatModelRouter#chatModelCandidates}/{@link ChatModelRouter#rerankModelCandidates}），
 * 数据库模型配置的增删改无需重启即生效。每个候选端口外裹 {@link ResilientLlmPort}（同模型内重试 + 熔断），
 * 再外包 {@link FallbackLlmPort}（跨模型切换），分层互不干扰。</p>
 *
 * <p>流式切换语义由框架保证：候选<b>吐字前</b>失败透明切下一候选；<b>吐字后</b>失败如实上报（避免内容交错重复）。</p>
 */
@Service
public class AskDataLlmPort {

    private static final Logger log = LoggerFactory.getLogger(AskDataLlmPort.class);

    private final ChatModelRouter modelRouter;

    private final FrameworkModelBridge bridge;

    private final FallbackProperties fallbackProperties;

    public AskDataLlmPort(ChatModelRouter modelRouter, FrameworkModelBridge bridge,
                          FallbackProperties fallbackProperties) {
        this.modelRouter = modelRouter;
        this.bridge = bridge;
        this.fallbackProperties = fallbackProperties;
    }

    // ------------------------------------------------------------------
    // 对话：流式（经故障转移端口逐 token 回调）
    // ------------------------------------------------------------------

    /**
     * 按工作空间候选链流式补全。
     *
     * @param oid          工作空间 id（用于解析候选链）
     * @param messages     对话消息
     * @param extraParams  推理参数（temperature / max_tokens 等），透传进请求体
     * @param handler      逐 token 回调（askdata 侧契约）
     */
    public void stream(long oid, List<ChatModelClient.ModelMessage> messages,
                       Map<String, Object> extraParams, ChatModelClient.TokenHandler handler) {
        stream(oid, null, messages, extraParams, handler);
    }

    /**
     * 按工作空间候选链流式补全（支持按次指定 {@code customModelId}）。
     *
     * @param customModelId 可选：本次调用指定的对话模型 id（对齐 SQLBot {@code custom_model}）；
     *                      非 null 且在工作空间内可用时置顶为主模型，其余候选作为故障转移后备；
     *                      不可用（不存在 / 未授权）时静默回退工作空间候选链。
     */
    public void stream(long oid, Long customModelId, List<ChatModelClient.ModelMessage> messages,
                       Map<String, Object> extraParams, ChatModelClient.TokenHandler handler) {
        if (messages == null || messages.isEmpty()) {
            handler.onError("模型请求缺少消息内容");
            return;
        }
        List<ChatModelRouter.ModelRef> refs = selectRefs(resolveRefs(oid, customModelId));
        if (refs.isEmpty()) {
            handler.onError("该工作空间无可用对话模型（请到系统-AI 模型配置可用模型并绑定到本空间）");
            return;
        }
        String primaryModel = refs.get(0).baseModel;
        LlmRequest request = bridge.toRequest(primaryModel, messages, extraParams);
        FallbackLlmPort fallback = buildChatFallback(refs);
        try {
            fallback.stream(request)
                    .handler(new FrameworkModelBridge.AskdataStreamHandler(handler))
                    .start();
        } catch (RuntimeException e) {
            log.warn("工作空间 {} 对话模型故障转移调用异常: {}", oid, e.getMessage());
            handler.onError("模型调用失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * 非流式补全：复用同一流式管线聚合正文（与 {@code ChatModelClient.generate} 行为一致）。
     *
     * @return 失败或空响应返回 {@code null}
     */
    public String complete(long oid, List<ChatModelClient.ModelMessage> messages,
                           Map<String, Object> extraParams, ChatModelClient.Usage usage) {
        return complete(oid, null, messages, extraParams, usage);
    }

    /** 非流式补全（支持按次指定 {@code customModelId}，语义同 {@link #stream(long, Long, List, Map, ChatModelClient.TokenHandler)}）。 */
    public String complete(long oid, Long customModelId, List<ChatModelClient.ModelMessage> messages,
                           Map<String, Object> extraParams, ChatModelClient.Usage usage) {
        final StringBuilder sb = new StringBuilder();
        final boolean[] failed = {false};
        stream(oid, customModelId, messages, extraParams, new ChatModelClient.TokenHandler() {
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

    // ------------------------------------------------------------------
    // 重排：故障转移打分（无可用重排端口时返回 null，调用方降级为不重排）
    // ------------------------------------------------------------------

    /**
     * 按工作空间候选链对文档重排打分。
     *
     * @return 分数列表（与 documents 下对齐）；无可用重排模型时返回 {@code null}
     */
    public List<Double> rerankScores(long oid, String query, List<String> documents) {
        List<ChatModelRouter.ModelRef> refs = selectRefs(modelRouter.rerankModelCandidates(oid));
        if (refs.isEmpty()) {
            return null;
        }
        List<RerankModelCandidate> ports = new ArrayList<>();
        for (ChatModelRouter.ModelRef ref : refs) {
            RerankPort port = bridge.buildRerankPort(ref);
            if (port != null) {
                ports.add(new RerankModelCandidate(port, ref.baseModel));
            }
        }
        if (ports.isEmpty()) {
            return null;
        }
        final List<RerankModelCandidate> finalPorts = ports;
        FailoverErrorClassifier classifier = buildClassifier();
        FallbackRerankPort fallback = new FallbackRerankPort(
                (Supplier<List<RerankModelCandidate>>) () -> finalPorts, null, classifier,
                fallbackProperties.getMaxRetries(), fallbackProperties.getBackoffMs());
        try {
            return fallback.score(query, documents);
        } catch (RuntimeException e) {
            log.warn("工作空间 {} 重排模型故障转移失败: {}", oid, e.getMessage());
            return null;
        }
    }

    /**
     * 按工作空间 {@code oid} 取框架 {@link LlmPort}（含候选链故障转移 + 熔断）。
     * 每问动态解析，不做成全局单例（否则丢失 workspace 级故障转移）。无可用模型时返回 {@code null}。
     *
     * <p>供 {@link AskDataFrameworkAdapter} 装配框架 SQL/图表能力时复用，避免在适配层重复编排。</p>
     */
    public LlmPort buildLlmPort(long oid) {
        List<ChatModelRouter.ModelRef> refs = selectRefs(modelRouter.chatModelCandidates(oid));
        if (refs.isEmpty()) {
            return null;
        }
        return buildChatFallback(refs);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 故障转移开关：关闭时仅保留主模型（不切换）。 */
    private List<ChatModelRouter.ModelRef> selectRefs(List<ChatModelRouter.ModelRef> all) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        return fallbackProperties.isEnabled() ? all : all.subList(0, 1);
    }

    /**
     * 解析候选链：{@code customModelId} 非 null 且在当前工作空间可用时置顶为主模型，其余候选作为故障转移后备；
     * 不可用（不存在 / 未授权）时静默回退工作空间默认候选链（对齐 SQLBot「指定模型不可用静默回退默认」）。
     */
    private List<ChatModelRouter.ModelRef> resolveRefs(long oid, Long customModelId) {
        List<ChatModelRouter.ModelRef> base = modelRouter.chatModelCandidates(oid);
        if (customModelId == null) {
            return base;
        }
        Optional<ChatModelRouter.ModelRef> custom = modelRouter.chatModelById(customModelId, oid);
        if (!custom.isPresent()) {
            log.warn("自定义模型 {} 在当前工作空间不可用，回退默认候选链", customModelId);
            return base;
        }
        List<ChatModelRouter.ModelRef> ordered = new ArrayList<>();
        ordered.add(custom.get());
        for (ChatModelRouter.ModelRef r : base) {
            if (r.id != customModelId.longValue()) {
                ordered.add(r);
            }
        }
        return ordered;
    }

    /** 由候选链构建故障转移端口；每个候选外裹 ResilientLlmPort（同模型内重试 + 熔断）。 */
    private FallbackLlmPort buildChatFallback(List<ChatModelRouter.ModelRef> refs) {
        FailoverErrorClassifier classifier = buildClassifier();
        final List<ChatModelRouter.ModelRef> finalRefs = refs;
        Supplier<List<LlmModelCandidate>> supplier = () -> {
            List<LlmModelCandidate> list = new ArrayList<>();
            for (ChatModelRouter.ModelRef ref : finalRefs) {
                LlmPort port = bridge.buildLlmPort(ref, ref.params);
                if (port != null) {
                    list.add(new LlmModelCandidate(new ResilientLlmPort(port), ref.baseModel));
                }
            }
            return list;
        };
        return new FallbackLlmPort(supplier, null, classifier,
                fallbackProperties.getMaxRetries(), fallbackProperties.getBackoffMs());
    }

    /** 由配置的错误策略构造分类器（空策略退化为「任何错误都切换」的中性默认）。 */
    private FailoverErrorClassifier buildClassifier() {
        return KeywordFailoverErrorClassifier.of(fallbackProperties.getErrorPolicies());
    }
}
