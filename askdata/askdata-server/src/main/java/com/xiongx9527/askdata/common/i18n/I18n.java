package com.xiongx9527.askdata.common.i18n;

import com.xiongx9527.fw.plus.common.util.SpringUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * 静态消息解析：优先走 Spring {@link MessageResolver}（按当前请求 locale 解析，支持多语言）；
 * 当没有 Spring 上下文（纯单元测试、直接 {@code new} 出来的类）时，回落到 classpath 下的
 * 默认 {@code messages.properties}（中文）按原样返回，保证调用点始终拿到可读文案、不会 NPE。
 *
 * <p>与 {@link MessageResolver} 一致：位置占位符 {@code {0}}/{@code {1}} 由调用方按顺序传入。
 * 回落分支只做最朴素的 {@code {i}} 字面替换（不引入 MessageFormat 的引号/分组语义），
 * 足以覆盖错误文案里 {字段名} 这类占位。</p>
 */
public final class I18n {

    private I18n() {
    }

    public static String msg(String code, Object... args) {
        try {
            MessageResolver resolver = SpringUtils.getBean(MessageResolver.class);
            if (resolver != null) {
                return resolver.get(code, args);
            }
        } catch (Throwable ignored) {
            // 无 Spring 上下文或 bean 未注册：走回落
        }
        return fallback(code, args);
    }

    private static String fallback(String code, Object... args) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = I18n.class.getClassLoader();
        }
        try (InputStream stream = loader.getResourceAsStream("messages.properties")) {
            if (stream == null) {
                return code;
            }
            ResourceBundle bundle = new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!bundle.containsKey(code)) {
                return code;
            }
            String template = bundle.getString(code);
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    template = template.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
                }
            }
            return template;
        } catch (MissingResourceException | IllegalStateException | java.io.IOException e) {
            return code;
        }
    }
}
