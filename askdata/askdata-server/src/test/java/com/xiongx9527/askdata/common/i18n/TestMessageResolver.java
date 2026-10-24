package com.xiongx9527.askdata.common.i18n;

import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * 单测用消息解析器：绑定真实 {@code messages_*.properties}（UTF-8），
 * 使断言覆盖实际文案而不是消息键。
 */
public final class TestMessageResolver {

    private TestMessageResolver() {
    }

    public static MessageResolver create() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new MessageResolver(source);
    }
}
