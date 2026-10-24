package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 问数 AI 模型接入：用框架 langchain4j 的 OpenAI 兼容模型 Bean（替代原手写 HTTP 客户端作为 Assistant 的模型底座）。
 *
 * <p>连接信息复用 {@link ChatModelRouter}（数据库里的默认对话模型，明文还原后注入）。
 * 由 {@code askdata.assistant.enabled=true} 开启——缺省不装配，故无默认模型/未开启时不影响其它上下文加载测试。</p>
 *
 * <p>对话上下文日志监听器 {@code ChatContextLogger} 由框架 {@code LangChain4jSupportAutoConfiguration}
 * 提供并统一挂载，无需在此自定义或注入。</p>
 */
@Configuration
@ConditionalOnProperty(name = "askdata.assistant.enabled", havingValue = "true")
public class AiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfig.class);

    @Bean
    public ChatModel askdataChatModel(ChatModelRouter router) {
        ChatModelRouter.ModelRef ref = router.defaultChatModel()
                .filter(ChatModelRouter.ModelRef::usable)
                .orElseThrow(() -> new IllegalStateException(
                        "askdata.assistant.enabled=true 但未配置可用的默认对话模型（请到 系统-AI模型 设置默认模型）"));
        log.info("装配 langchain4j ChatModel: domain={}, model={}", ref.apiDomain, ref.baseModel);
        return buildChatModel(ref);
    }

    /** 由模型引用构建 OpenAI 兼容 ChatModel（MCP {@code custom_model} 按次选模型复用）。 */
    public static ChatModel buildChatModel(ChatModelRouter.ModelRef ref) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(openAiBase(ref.apiDomain))
                .modelName(ref.baseModel);
        if (ref.apiKey != null && !ref.apiKey.isEmpty()) {
            builder.apiKey(ref.apiKey);
        } else {
            builder.apiKey("sk-local"); // 本地 Ollama 等无需真实 key
        }
        if (ref.temperature != null) {
            builder.temperature(ref.temperature);
        }
        return builder.build();
    }

    @Bean
    public StreamingChatModel askdataStreamingChatModel(ChatModelRouter router) {
        ChatModelRouter.ModelRef ref = router.defaultChatModel()
                .filter(ChatModelRouter.ModelRef::usable)
                .orElseThrow(() -> new IllegalStateException(
                        "askdata.assistant.enabled=true 但未配置可用的默认对话模型"));
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(openAiBase(ref.apiDomain))
                .modelName(ref.baseModel);
        if (ref.apiKey != null && !ref.apiKey.isEmpty()) {
            builder.apiKey(ref.apiKey);
        } else {
            builder.apiKey("sk-local");
        }
        if (ref.temperature != null) {
            builder.temperature(ref.temperature);
        }
        return builder.build();
    }

    /** 端点规范化为 OpenAI 兼容 base（langchain4j 自行拼接 /chat/completions）：补 /v1。 */
    static String openAiBase(String apiDomain) {
        String base = apiDomain.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.matches(".*/v1(/.*)?")) {
            return base;
        }
        return base + "/v1";
    }
}
