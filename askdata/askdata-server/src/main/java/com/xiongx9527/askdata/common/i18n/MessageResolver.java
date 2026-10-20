package com.xiongx9527.askdata.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 消息解析（A4 i18n 骨架）：统一从 Spring {@link MessageSource} 按当前 locale 解析文案，
 * 缺省回落到 {@code messages.properties}（中文）。替代散落的硬编码中/英文字符串。
 */
@Component
public class MessageResolver {

    private final MessageSource messageSource;

    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 按当前请求 locale 解析（对齐 SQLBot i18n 的 lazy gettext）。 */
    public String get(String code, Object... args) {
        return get(code, LocaleContextHolder.getLocale(), args);
    }

    /** 按指定 locale 解析（MCP / 外部调用可显式传 locale）。 */
    public String get(String code, Locale locale, Object... args) {
        try {
            return messageSource.getMessage(code, args, locale);
        } catch (NoSuchMessageException e) {
            return code;
        }
    }
}
