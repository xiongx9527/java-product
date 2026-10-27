package com.xiongx9527.askdata.common.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A26：五语种键集合一致性自动检查。
 *
 * <p>{@code ResourceBundle#keySet()} 会把父包键并入子包（缺键时静默回退中文），
 * 因此这里直接读取各 {@code messages_*.properties} 文件本身，逐键比对，
 * 任何新增文案漏翻译都会在这里失败，而不是在运行时静默回退。</p>
 */
class I18nBundleKeyParityTest {

    /** 五语种（含缺省包）属性文件。 */
    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_zh_CN.properties",
            "messages_en.properties",
            "messages_zh_TW.properties",
            "messages_ko_KR.properties",
            "messages_th_TH.properties");

    @Test
    void allBundlesDeclareTheSameKeySet() throws IOException {
        Map<String, Set<String>> keysByBundle = new LinkedHashMap<>();
        for (String bundle : BUNDLES) {
            keysByBundle.put(bundle, keysOf(bundle));
        }
        Set<String> base = keysByBundle.get("messages.properties");
        assertTrue(base.size() >= 177, "should align with SQLBot 177 keys, got " + base.size());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : keysByBundle.entrySet()) {
            Set<String> missing = new TreeSet<>(base);
            missing.removeAll(entry.getValue());
            Set<String> extra = new TreeSet<>(entry.getValue());
            extra.removeAll(base);
            if (!missing.isEmpty()) {
                problems.add(entry.getKey() + " missing " + missing.size() + ": " + head(missing));
            }
            if (!extra.isEmpty()) {
                problems.add(entry.getKey() + " extra " + extra.size() + ": " + head(extra));
            }
        }
        assertTrue(problems.isEmpty(), "locale key sets diverge:\n" + String.join("\n", problems));
    }

    @Test
    void everyLocaleHasOwnTranslationForEmbeddedDecryptErrors() throws IOException {
        List<String> keys = List.of("i18n_embedded.credential_decrypt_failed",
                "i18n_embedded.password_decrypt_failed");
        for (String bundle : BUNDLES) {
            Properties props = load(bundle);
            for (String key : keys) {
                assertTrue(props.containsKey(key), bundle + " missing key " + key);
                assertFalse(props.getProperty(key).trim().isEmpty(), bundle + " has empty value for " + key);
            }
        }
    }

    @Test
    void englishDiffersFromChineseForNewlyAddedKeys() throws IOException {
        Properties zh = load("messages.properties");
        Properties en = load("messages_en.properties");
        for (String key : List.of("mcp.error.invalid.id", "storage.unsupported.type", "op.delete.success")) {
            assertTrue(zh.containsKey(key) && en.containsKey(key), "missing key " + key);
            assertFalse(zh.getProperty(key).equals(en.getProperty(key)),
                    "English bundle still falls back to Chinese for " + key);
        }
    }

    private static String head(Set<String> keys) {
        List<String> list = new ArrayList<>(keys);
        return String.join(", ", list.subList(0, Math.min(5, list.size())));
    }

    private static Set<String> keysOf(String bundle) throws IOException {
        return new TreeSet<>(load(bundle).stringPropertyNames());
    }

    private static Properties load(String bundle) throws IOException {
        Properties props = new Properties();
        Path file = resolve(bundle);
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static Path resolve(String bundle) {
        Path fromModule = Paths.get("src/main/resources").resolve(bundle);
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return Paths.get("askdata/askdata-server").resolve("src/main/resources").resolve(bundle);
    }
}
