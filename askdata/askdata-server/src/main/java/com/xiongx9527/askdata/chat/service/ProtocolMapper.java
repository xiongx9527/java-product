package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.fw.plus.ai.nativeimpl.llm.LlmProtocol;

import java.util.Optional;

/**
 * 协议枚举与历史数字编码的互转。
 *
 * <p>单一事实源是框架 {@link LlmProtocol}（OPENAI_CHAT_COMPLETIONS / AZURE_OPENAI_CHAT_COMPLETIONS /
 * OPENAI_RESPONSES / ANTHROPIC_MESSAGES / GEMINI）。历史 {@code ai_model.upstream_format} 数字编码
 * （0 自动识别 / 1 Chat / 2 Anthropic / 3 Responses / 4 Azure）仅用于兼容仍按数字分支的遗留
 * {@code ChatModelClient} 路径，由本类派生、不落库。</p>
 */
public final class ProtocolMapper {

    /** 数据库默认值（与前端的协议下拉首项、迁移脚本默认值一致）。 */
    public static final String DEFAULT = LlmProtocol.OPENAI_CHAT_COMPLETIONS.name();

    private ProtocolMapper() {
    }

    /** 字符串协议 → 框架枚举；无法识别（如向量/重排协议 OPENAI_COMPATIBLE/TEI/COHERE）返回空。 */
    public static Optional<LlmProtocol> toLlmProtocol(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LlmProtocol.valueOf(protocol.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 是否走框架 {@code LlmPortResolver} 闭环（目前仅 Gemini；其余暂走遗留 ChatModelClient）。 */
    public static boolean isFrameworkRoutable(String protocol) {
        return LlmProtocol.GEMINI.name().equals(protocol);
    }

    /** 历史数字 → 枚举字符串（与 V9 迁移回填规则一致）。 */
    public static String fromUpstreamFormat(int upstreamFormat) {
        switch (upstreamFormat) {
            case 2:
                return LlmProtocol.ANTHROPIC_MESSAGES.name();
            case 3:
                return LlmProtocol.OPENAI_RESPONSES.name();
            case 4:
                return LlmProtocol.AZURE_OPENAI_CHAT_COMPLETIONS.name();
            case 0:
            case 1:
            default:
                return LlmProtocol.OPENAI_CHAT_COMPLETIONS.name();
        }
    }

    /**
     * 枚举字符串 → 历史数字（派生给遗留 {@code ChatModelClient} 分支用，不落库）。
     * Gemini 在遗留路径里按 Chat 兼容通道过渡（=1）。
     */
    public static int toUpstreamFormat(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            return 1;
        }
        switch (protocol.trim()) {
            case "OPENAI_RESPONSES":
                return 3;
            case "ANTHROPIC_MESSAGES":
                return 2;
            case "AZURE_OPENAI_CHAT_COMPLETIONS":
                return 4;
            case "OPENAI_CHAT_COMPLETIONS":
            case "GEMINI":
            default:
                return 1;
        }
    }
}
