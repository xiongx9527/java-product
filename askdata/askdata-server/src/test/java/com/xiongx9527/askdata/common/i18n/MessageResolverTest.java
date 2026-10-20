package com.xiongx9527.askdata.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * i18n 消息解析（A4 扩展）：验证 {@link MessageResolver} 按 locale 解析中文/英文，
 * 并对缺失 code 回落到 code 本身。
 */
class MessageResolverTest {

    private MessageResolver buildResolver() {
        ReloadableResourceBundleMessageSource src = new ReloadableResourceBundleMessageSource();
        src.setBasenames("classpath:messages");
        src.setDefaultEncoding("UTF-8");
        src.setUseCodeAsDefaultMessage(false);
        return new MessageResolver(src);
    }

    @Test
    void resolvesChinese() {
        MessageResolver resolver = buildResolver();
        assertEquals("数据源名称已存在", resolver.get("ds.name.exists", Locale.SIMPLIFIED_CHINESE));
        assertEquals("表不存在", resolver.get("ds.table.not.exists", Locale.SIMPLIFIED_CHINESE));
        assertEquals("解析 Excel 失败", resolver.get("ds.parse.excel.failed", Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    void resolvesEnglish() {
        MessageResolver resolver = buildResolver();
        assertEquals("Datasource name already exists", resolver.get("ds.name.exists", Locale.ENGLISH));
        assertEquals("Table does not exist", resolver.get("ds.table.not.exists", Locale.ENGLISH));
        assertEquals("Failed to parse Excel", resolver.get("ds.parse.excel.failed", Locale.ENGLISH));
        assertEquals("SQL can only contain read operations", resolver.get("sql.read.only", Locale.ENGLISH));
    }

    @Test
    void fallsBackToCodeWhenMissing() {
        MessageResolver resolver = buildResolver();
        assertEquals("ds.unknown.code", resolver.get("ds.unknown.code", Locale.ENGLISH));
    }

    @Test
    void resolvesWithArguments() {
        MessageResolver resolver = buildResolver();
        assertEquals("数据源不存在: 42", resolver.get("ds.not.found", Locale.SIMPLIFIED_CHINESE, 42));
        assertEquals("Datasource not found: 42", resolver.get("ds.not.found", Locale.ENGLISH, 42));
    }

    @Test
    void defaultsToContextLocale() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            MessageResolver resolver = buildResolver();
            assertEquals("Datasource name already exists", resolver.get("ds.name.exists"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
    @Test
    void resolvesNewModuleKeysZhAndEn() {
        MessageResolver resolver = buildResolver();
        // zh default (Chinese)
        assertEquals("不能删除默认工作空间", resolver.get("workspace.delete.default.denied", Locale.SIMPLIFIED_CHINESE));
        assertEquals("操作类型必填", resolver.get("dashboard.opt.required", Locale.SIMPLIFIED_CHINESE));
        assertEquals("仅支持 .jpg/.jpeg/.png/.svg 格式", resolver.get("assistant.file.format", Locale.SIMPLIFIED_CHINESE));
        assertEquals("操作类型", resolver.get("audit.export.col.type", Locale.SIMPLIFIED_CHINESE));
        assertEquals("用户不存在: 42", resolver.get("user.not.found", Locale.SIMPLIFIED_CHINESE, 42));
        assertEquals("助手不存在: 7", resolver.get("assistant.not.found", Locale.SIMPLIFIED_CHINESE, 7));
        // en
        assertEquals("Can not delete default workspace", resolver.get("workspace.delete.default.denied", Locale.ENGLISH));
        assertEquals("opt is required", resolver.get("dashboard.opt.required", Locale.ENGLISH));
        assertEquals("Only support .jpg/.jpeg/.png/.svg", resolver.get("assistant.file.format", Locale.ENGLISH));
        assertEquals("Operation Type", resolver.get("audit.export.col.type", Locale.ENGLISH));
        assertEquals("User with id [42] not found!", resolver.get("user.not.found", Locale.ENGLISH, 42));
        assertEquals("Assistant with id 7 not found", resolver.get("assistant.not.found", Locale.ENGLISH, 7));
    }

    @Test
    void fallsBackToCodeForNewMissingKey() {
        MessageResolver resolver = buildResolver();
        assertEquals("user.unknown.new.key", resolver.get("user.unknown.new.key", Locale.ENGLISH));
    }

}
