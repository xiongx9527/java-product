package com.xiongx9527.askdata.common.i18n;

import cn.dev33.satoken.exception.NotLoginException;
import com.xiongx9527.askdata.common.web.ApiExceptionHandler;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.embedded.DynamicDatasourceResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4（F-15）验收回归：审计 §3 清单文件集上不再有硬编码英文错误文案，
 * 且同一错误随 {@code Accept-Language} 切换语言。
 *
 * <p>清单与来源：{@code docs/audit/2026-10-23-askdata-sqlbot-remaining-parity-audit.md} §3。</p>
 */
class I18nMessageConvergenceTest {

    /** §3 清单文件集（相对模块根目录）。 */
    private static final List<String> AUDITED_FILES = List.of(
            "src/main/java/com/xiongx9527/askdata/datasource/service/DatasourceService.java",
            "src/main/java/com/xiongx9527/askdata/terminology/TerminologyService.java",
            "src/main/java/com/xiongx9527/askdata/training/TrainingService.java",
            "src/main/java/com/xiongx9527/askdata/prompt/CustomPromptService.java",
            "src/main/java/com/xiongx9527/askdata/system/service/UserService.java",
            "src/main/java/com/xiongx9527/askdata/system/service/VariableService.java",
            "src/main/java/com/xiongx9527/askdata/system/service/WorkspaceService.java",
            "src/main/java/com/xiongx9527/askdata/system/service/AiModelService.java",
            "src/main/java/com/xiongx9527/askdata/system/controller/UserController.java",
            "src/main/java/com/xiongx9527/askdata/system/service/UserImportService.java",
            "src/main/java/com/xiongx9527/askdata/common/web/ApiKeyTokenAuthenticator.java",
            "src/main/java/com/xiongx9527/askdata/common/web/EmbeddedTokenAuthenticator.java",
            "src/main/java/com/xiongx9527/askdata/common/web/AuthChannelFilter.java",
            "src/main/java/com/xiongx9527/askdata/common/web/ApiExceptionHandler.java",
            "src/main/java/com/xiongx9527/askdata/embedded/DynamicDatasourceResolver.java");

    /**
     * 直接以字符串字面量构造面向用户的异常（而不是走 {@code MessageResolver}）。
     *
     * <p>用结构性规则而非固定文案清单：清单式断言会漏掉同一文件里新写的硬编码文案（假绿），
     * 结构规则能覆盖 §3 清单的同类问题。</p>
     */
    private static final java.util.regex.Pattern LITERAL_EXCEPTION =
            java.util.regex.Pattern.compile(
                    "new (?:BadRequestException|AuthFailureException|DatasourceException)\\(\\s*\"");

    /**
     * 非异常出口的硬编码文案：SSE 错误帧 {@code emitError(writer, "…")}、
     * 批量导入错误列表 {@code errors.add("…")}。这些同样会直接回显给用户。
     */
    private static final java.util.regex.Pattern LITERAL_ERROR_CHANNEL =
            java.util.regex.Pattern.compile("(?:emitError|errors\\.add)\\(\\s*(?:\\w+\\s*,\\s*)?\"");

    /** 多行拼接：构造器换行后，下一行以字面量开头（如 {@code "Workspace [...] is not..."}）。 */
    private static final java.util.regex.Pattern LITERAL_CONTINUATION =
            java.util.regex.Pattern.compile("^\"[A-Z][^\"]{6,}\"");

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void auditedFilesContainNoHardcodedUserFacingMessages() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String relative : AUDITED_FILES) {
            Path file = resolve(relative);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                    continue;
                }
                if (LITERAL_EXCEPTION.matcher(line).find()) {
                    violations.add(file.getFileName() + ":" + (i + 1) + " → " + line);
                    continue;
                }
                if (LITERAL_ERROR_CHANNEL.matcher(line).find()) {
                    violations.add(file.getFileName() + ":" + (i + 1) + " → " + line);
                    continue;
                }
                if (LITERAL_CONTINUATION.matcher(line).find() && line.contains("\"")) {
                    violations.add(file.getFileName() + ":" + (i + 1) + " → " + line);
                }
            }
        }
        assertTrue(violations.isEmpty(), "仍有硬编码错误文案:\n" + String.join("\n", violations));
    }

    @Test
    void errorMessageFollowsAcceptLanguage() {
        MessageResolver messages = new MessageResolver(new StaticMessageSource());
        DynamicDatasourceResolver resolver = new DynamicDatasourceResolver(new ObjectMapper(), messages);
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("type", "mysql");

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        String zh = assertThrows(BadRequestException.class, () -> resolver.resolve(0, hostConfig)).getMessage();

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        String en = assertThrows(BadRequestException.class, () -> resolver.resolve(0, hostConfig)).getMessage();

        assertTrue(zh.contains("高级应用"), "zh: " + zh);
        assertTrue(en.contains("advanced applications"), "en: " + en);
        assertNotEquals(zh, en);
    }

    /** 401 未登录正文曾硬编码英文前缀，不随 Accept-Language 切换；此处固定回归。 */
    @Test
    void notLoginMessageFollowsAcceptLanguage() {
        ApiExceptionHandler handler = new ApiExceptionHandler(TestMessageResolver.create());
        NotLoginException e = new NotLoginException("token invalid", "login", "-1");

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        String zh = handler.notLogin(e).getBody();

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        String en = handler.notLogin(e).getBody();

        assertTrue(zh != null && zh.contains("认证无效"), "zh: " + zh);
        assertTrue(en != null && en.contains("Authentication invalid"), "en: " + en);
        assertNotEquals(zh, en);
    }

    private static Path resolve(String relative) {
        Path fromModule = Paths.get(relative);
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return Paths.get("askdata/askdata-server").resolve(relative);
    }
}
