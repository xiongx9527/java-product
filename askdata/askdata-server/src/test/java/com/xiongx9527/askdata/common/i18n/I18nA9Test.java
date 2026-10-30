package com.xiongx9527.askdata.common.i18n;

import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A9 国际化验证：
 * <ul>
 *   <li>默认/中文/英文三包键集合与 SQLBot 177 键对齐，英文包无缺键；五语言包键集合一致；</li>
 *   <li>?lang= 与 Accept-Language 切换生效；</li>
 *   <li>具名占位符 {name} 解析生效。</li>
 * </ul>
 */
class I18nA9Test {

    private final MessageSource source = buildSource();
    private final MessageResolver resolver = new MessageResolver(source);

    private static MessageSource buildSource() {
        ResourceBundleMessageSource s = new ResourceBundleMessageSource();
        s.setBasename("messages");
        s.setDefaultEncoding("UTF-8");
        s.setFallbackToSystemLocale(false);
        s.setUseCodeAsDefaultMessage(false);
        return s;
    }

    private Set<String> keys(Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", locale,
                Thread.currentThread().getContextClassLoader(), new MessageResolver.UTF8Control());
        return bundle.keySet();
    }

    @Test
    void allBundlesHaveConsistentKeySets() {
        Set<String> base = keys(Locale.ROOT);
        assertTrue(base.size() >= 177, "should align with SQLBot 177 keys, got " + base.size());
        for (Locale loc : new Locale[]{Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH,
                new Locale("zh", "TW"), new Locale("ko", "KR"), new Locale("th", "TH")}) {
            Set<String> ks = keys(loc);
            assertEquals(base.size(), ks.size(), "key count mismatch for " + loc);
            assertEquals(base, ks, "key set mismatch for " + loc);
        }
    }

    @Test
    void englishBundleHasNoMissingKeys() {
        Set<String> base = keys(Locale.ROOT);
        Set<String> en = keys(Locale.ENGLISH);
        assertFalse(en.contains(null));
        for (String k : base) {
            assertTrue(en.contains(k), "English bundle missing key: " + k);
        }
    }

    @Test
    void sqlbotKeyResolvesPerLocale() {
        assertEquals("Incorrect account or password!",
                resolver.get("i18n_login.account_pwd_error", Locale.ENGLISH));
        assertEquals("账号或密码错误！",
                resolver.get("i18n_login.account_pwd_error", Locale.SIMPLIFIED_CHINESE));
        // 其余三语言可解析（不为 key 本身）
        for (Locale loc : new Locale[]{new Locale("zh", "TW"), new Locale("ko", "KR"), new Locale("th", "TH")}) {
            String v = resolver.get("i18n_login.account_pwd_error", loc);
            assertNotNull(v);
            assertFalse(v.equals("i18n_login.account_pwd_error"), "unresolved for " + loc);
        }
    }

    @Test
    void namedPlaceholderResolves() {
        Map<String, Object> args = new HashMap<>();
        args.put("msg", "X");
        assertEquals("X already exists!", resolver.get("i18n_exist", args, Locale.ENGLISH));
        assertEquals("X已存在！", resolver.get("i18n_exist", args, Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    void langParameterSwitchesLocale() {
        AskdataLocaleResolver r = new AskdataLocaleResolver();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("lang", "en");
        assertEquals(Locale.ENGLISH, r.resolveLocale(req));

        req.removeAllParameters();
        req.setParameter("lang", "zh-TW");
        assertEquals(new Locale("zh", "TW"), r.resolveLocale(req));

        req.removeAllParameters();
        req.setParameter("lang", "ko-KR");
        assertEquals(new Locale("ko", "KR"), r.resolveLocale(req));

        req.removeAllParameters();
        req.setParameter("lang", "th-TH");
        assertEquals(new Locale("th", "TH"), r.resolveLocale(req));
    }

    @Test
    void acceptLanguageFallback() {
        AskdataLocaleResolver r = new AskdataLocaleResolver();
        MockHttpServletRequest req = new MockHttpServletRequest();
        // 无 lang 参数时回退到 Accept-Language
        req.addHeader("Accept-Language", "th-TH,th;q=0.9,en;q=0.8");
        assertEquals(new Locale("th", "TH"), r.resolveLocale(req));

        req = new MockHttpServletRequest();
        req.addHeader("Accept-Language", "ko-KR,ko;q=0.9");
        assertEquals(new Locale("ko", "KR"), r.resolveLocale(req));
    }

    @Test
    void defaultsToChinese() {
        AskdataLocaleResolver r = new AskdataLocaleResolver();
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertEquals(Locale.SIMPLIFIED_CHINESE, r.resolveLocale(req));
    }
}
