package com.xiongx9527.askdata.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息解析（A9 国际化）：统一从 Spring {@link MessageSource} 按当前 locale 解析文案，
 * 缺省回落到 {@code messages.properties}（中文）。
 *
 * <p>支持两种占位符：
 * <ul>
 *   <li>位置占位符 {@code {0}}：由 Spring MessageFormat 处理（对齐 askdata 既有键）；</li>
 *   <li>具名占位符 {@code {name}}：对齐 SQLBot i18n 的 {@code {msg}}/{@code {key}} 风格，
 *       通过 {@link #get(String, Map, Locale)} 传入具名参数做字符串替换。</li>
 * </ul>
 */
@Component
public class MessageResolver {

    private final MessageSource messageSource;

    private final UTF8Control utf8Control = new UTF8Control();

    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 按当前请求 locale 解析（位置占位符）。 */
    public String get(String code, Object... args) {
        return get(code, LocaleContextHolder.getLocale(), args);
    }

    /** 按指定 locale 解析（位置占位符）。 */
    public String get(String code, Locale locale, Object... args) {
        try {
            return messageSource.getMessage(code, args, locale);
        } catch (NoSuchMessageException e) {
            return code;
        }
    }

    /** 按当前请求 locale 解析具名占位符。 */
    public String get(String code, Map<String, Object> named) {
        return get(code, named, LocaleContextHolder.getLocale());
    }

    /** 按指定 locale 解析具名占位符（对齐 SQLBot 的 {@code {msg}}/{@code {key}} 风格）。 */
    public String get(String code, Map<String, Object> named, Locale locale) {
        try {
            String template = raw(code, locale);
            if (template == null) {
                return code;
            }
            if (named != null && !named.isEmpty()) {
                return formatNamed(template, named);
            }
            return template;
        } catch (MissingResourceException e) {
            return code;
        }
    }

    /** 直接读取原始文案（不做 MessageFormat 格式化，避免具名占位符被误解析）。 */
    private String raw(String code, Locale locale) {
        ClassLoader loader = getLoader();
        try {
            ResourceBundle bundle = ResourceBundle.getBundle("messages", locale, loader, utf8Control);
            if (bundle.containsKey(code)) {
                return bundle.getString(code);
            }
        } catch (MissingResourceException ignored) {
            // 回退到默认包
        }
        try {
            ResourceBundle def = ResourceBundle.getBundle("messages", Locale.ROOT, loader, utf8Control);
            if (def.containsKey(code)) {
                return def.getString(code);
            }
        } catch (MissingResourceException ignored) {
            // 无对应键
        }
        return null;
    }

    private String formatNamed(String template, Map<String, Object> args) {
        Matcher m = Pattern.compile("\\{([^}]+)\\}").matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object value = args.get(key);
            String replacement = value == null ? m.group(0) : String.valueOf(value);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private ClassLoader getLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : MessageResolver.class.getClassLoader();
    }

    /** 以 UTF-8 读取 classpath 下的 .properties，避免中文被按 ISO-8859-1 解析乱码。 */
    static class UTF8Control extends ResourceBundle.Control {
        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format,
                                        ClassLoader loader, boolean reload) throws IllegalAccessException, InstantiationException, IOException {
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (InputStream stream = loader.getResourceAsStream(resourceName)) {
                if (stream == null) {
                    return null;
                }
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    return new PropertyResourceBundle(reader);
                }
            }
        }
    }
}
