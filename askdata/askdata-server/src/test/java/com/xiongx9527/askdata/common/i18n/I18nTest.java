package com.xiongx9527.askdata.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * i18n 消息包验证（A4 离线证据）：验证资源包按 locale 解析中文/英文，且缺省回落到
 * messages.properties（中文）。无需 Spring 上下文，直接驱动 ResourceBundleMessageSource。
 */
class I18nTest {

    private ResourceBundleMessageSource source() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @Test
    void resolvesChineseForZhLocale() {
        assertEquals("账号或密码错误",
                source().getMessage("login.account.pwd.error", null, Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    void resolvesEnglishForEnLocale() {
        assertEquals("Account or password error",
                source().getMessage("login.account.pwd.error", null, Locale.ENGLISH));
    }

    @Test
    void fallsBackToDefaultBundleForUnsupportedLocale() {
        // 法语无专属包，回落到默认 messages.properties（中文）
        assertEquals("操作成功", source().getMessage("api.success", null, Locale.FRENCH));
    }

    @Test
    void resolvesParameterizedMessage() {
        ResourceBundleMessageSource src = source();
        // 无占位符的键，验证多键可用
        assertEquals("密码格式不正确", src.getMessage("login.pwd.format", null, Locale.CHINESE));
        assertEquals("The password format is incorrect",
                src.getMessage("login.pwd.format", null, Locale.ENGLISH));
    }
}
