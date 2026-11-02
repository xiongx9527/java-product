package com.xiongx9527.askdata.chat.service;

import java.net.URL;
import java.util.Locale;

/**
 * 上游报文格式判定与 URL 规范化（单一事实源）。
 *
 * <p>模型列表获取、连通校验与运行期请求三处必须共用本解析器：任一处置规则不同步，
 * 就会出现「校验通过、问数失败」且难以定位。</p>
 *
 * <p>格式取值与 {@code ai_model.upstream_format} 一致：
 * {@code 0} 自动识别、{@code 1} Chat Completions、{@code 2} Anthropic Messages、
 * {@code 3} Responses、{@code 4} Azure OpenAI。</p>
 */
public final class UpstreamFormatResolver {

    /** 自动识别（按 Base URL 推断）。 */
    public static final int AUTO = 0;

    /** OpenAI 兼容 Chat Completions（含 vLLM）。 */
    public static final int CHAT_COMPLETIONS = 1;

    /** Anthropic Messages。 */
    public static final int ANTHROPIC = 2;

    /** OpenAI Responses。 */
    public static final int RESPONSES = 3;

    /** Azure OpenAI。 */
    public static final int AZURE = 4;

    private UpstreamFormatResolver() {
    }

    /**
     * 判定实际生效的上游格式。
     *
     * <p>自动识别规则：{@code *.openai.azure.com|cn} → Azure；路径含 {@code /anthropic}
     * 或域名 {@code api.anthropic.com} → Anthropic Messages；其余 → Chat Completions。
     * Responses 只能手选（域名与 Chat Completions 无法区分）。</p>
     */
    public static int resolve(String baseUrl, int configured) {
        if (configured >= CHAT_COMPLETIONS && configured <= AZURE) {
            return configured;
        }
        return detect(baseUrl);
    }

    /** 按 Base URL 自动识别格式（{@link #AUTO} 的判定实现）。 */
    public static int detect(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return CHAT_COMPLETIONS;
        }
        if (ChatModelClient.isAzureEndpoint(baseUrl)) {
            return AZURE;
        }
        String lower = baseUrl.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("api.anthropic.com") || pathOf(lower).contains("/anthropic")) {
            return ANTHROPIC;
        }
        return CHAT_COMPLETIONS;
    }

    /**
     * 规范化 Base URL：去尾部 {@code /}、剥离尾部 {@code /chat/completions}、路径为空时补 {@code /v1}。
     *
     * <p>{@code fullUrl} 为 true 表示用户填写的是完整端点地址，原样返回，不追加路径。</p>
     */
    public static String normalizeBase(String baseUrl, boolean fullUrl) {
        if (baseUrl == null) {
            return "";
        }
        String base = baseUrl.trim();
        if (fullUrl) {
            return base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        base = stripSuffix(base, "/chat/completions");
        base = stripSuffix(base, "/responses");
        base = stripSuffix(base, "/messages");
        String path = pathOf(base);
        if (path.isEmpty() || "/".equals(path)) {
            return base + "/v1";
        }
        return base;
    }

    /** 对话端点：按格式返回最终请求 URL。 */
    public static String chatUrl(String baseUrl, String baseModel, int format, boolean fullUrl) {
        String base = normalizeBase(baseUrl, fullUrl);
        switch (format) {
            case ANTHROPIC:
                return join(base, "/messages");
            case RESPONSES:
                return join(base, "/responses");
            case AZURE:
                return ChatModelClient.chatCompletionsUrl(base, baseModel, null);
            case CHAT_COMPLETIONS:
            default:
                return join(base, "/chat/completions");
        }
    }

    /**
     * 模型列表端点：统一走 {@code /models}。
     *
     * <p>{@code fullUrl} 为 true 时必须能从完整端点剥离出服务根地址；无法识别时返回空串，
     * 调用方据此返回「不支持自动获取」且不发起请求。</p>
     */
    public static String modelsUrl(String baseUrl, boolean fullUrl) {
        String base = fullUrl ? stripEndpoint(baseUrl) : normalizeBase(baseUrl, false);
        if (base == null || base.isEmpty()) {
            return "";
        }
        if (base.endsWith("/models")) {
            return base;
        }
        return join(base, "/models");
    }

    /** 剥离完整 URL 的对话端点；无法识别时返回 {@code null}。 */
    private static String stripEndpoint(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return null;
        }
        for (String suffix : new String[]{"/chat/completions", "/responses", "/messages"}) {
            if (base.endsWith(suffix)) {
                return base.substring(0, base.length() - suffix.length());
            }
        }
        if (base.endsWith("/models")) {
            return base;
        }
        return null;
    }

    /** 向量端点：统一走 {@code /embeddings}。 */
    public static String embeddingsUrl(String baseUrl, boolean fullUrl) {
        String base = normalizeBase(baseUrl, fullUrl);
        if (base.isEmpty()) {
            return "";
        }
        if (base.endsWith("/embeddings")) {
            return base;
        }
        return join(base, "/embeddings");
    }

    /** 该格式是否支持向量模型（Anthropic Messages 与 Responses 仅支持对话）。 */
    public static boolean supportsEmbedding(int format) {
        return format == CHAT_COMPLETIONS || format == AZURE;
    }

    private static String join(String base, String suffix) {
        if (base.endsWith(suffix)) {
            return base;
        }
        return base + suffix;
    }

    private static String stripSuffix(String value, String suffix) {
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private static String pathOf(String url) {
        try {
            String path = new URL(url).getPath();
            return path == null ? "" : path;
        } catch (Exception e) {
            return "";
        }
    }
}
