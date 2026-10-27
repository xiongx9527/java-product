package com.xiongx9527.askdata.parity;

import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.engine.SqlSafety;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A27：配置项补齐。
 *
 * <p>校验 {@code application.yaml} 中向量召回阈值、样例行开关、元数据查询开关、
 * token 有效期、图表图片 TTL/清理、许可证与嵌入签名开关均已声明且有默认值，
 * 并且这些键确实被代码读取（未配置时行为与默认值一致）。</p>
 */
class A27ConfigCompletenessTest {

    @AfterEach
    void resetSqlSafety() {
        SqlSafety.setAllowMetadataQueries(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void applicationYamlDeclaresA27KeysWithDefaults() throws IOException {
        Map<String, Object> root = loadYaml();
        Map<String, Object> askdata = (Map<String, Object>) root.get("askdata");
        assertNotNull(askdata, "askdata section missing");

        assertDefault(askdata, "vector.recall-threshold", "0.4");
        assertDefault(askdata, "prompt.sample-data-enabled", "true");
        assertDefault(askdata, "sql.allow-metadata-queries", "false");
        assertDefault(askdata, "chart.image-base-url", "/api/chart/image");
        assertDefault(askdata, "chart.image-ttl-ms", "86400000");
        assertDefault(askdata, "chart.cleanup-interval-ms", "3600000");
        assertDeclared(askdata, "chart.token-store-dir");
        assertDefault(askdata, "license.enforcement", "bypass");
        assertDeclared(askdata, "license.sign-key");
        assertDefault(askdata, "license.max-count", "1000000");
        assertDeclared(askdata, "embedded.aes-key");
        assertDefault(askdata, "embedded.legacy-hmac-enabled", "false");

        Map<String, Object> saToken = (Map<String, Object>) root.get("sa-token");
        assertNotNull(saToken, "sa-token section missing");
        assertDefault(saToken, "timeout", "691200");
    }

    @Test
    void configKeysAreActuallyReadByCode() throws IOException {
        assertSourceContains("chat/service/SchemaContextBuilder.java",
                "askdata.prompt.sample-data-enabled");
        assertSourceContains("terminology/TerminologyRecallService.java",
                "askdata.vector.recall-threshold");
        assertSourceContains("training/TrainingRecallService.java",
                "askdata.vector.recall-threshold");
        assertSourceContains("datasource/engine/SqlSafetySettings.java",
                "askdata.sql.allow-metadata-queries");
        assertSourceContains("chart/server/ChartImageService.java",
                "askdata.chart.cleanup-interval-ms");
    }

    @Test
    void metadataQueriesRejectedByDefaultAndAllowedWhenEnabled() {
        String sql = "SELECT table_name FROM information_schema.tables";
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead(sql, "mysql"));
        assertThrows(DatasourceException.class,
                () -> SqlSafety.checkRead("SELECT * FROM pg_catalog.pg_class", "postgresql"));

        SqlSafety.setAllowMetadataQueries(true);
        assertDoesNotThrow(() -> SqlSafety.checkRead(sql, "mysql"));
        assertTrue(SqlSafety.isAllowMetadataQueries());
    }

    @Test
    void normalSelectUnaffectedByMetadataGate() {
        assertDoesNotThrow(() -> SqlSafety.checkRead("SELECT id, name FROM t WHERE age > 18", "mysql"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml() throws IOException {
        try (InputStream in = A27ConfigCompletenessTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            assertNotNull(in, "application.yaml not on classpath");
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertDeclared(Map<String, Object> section, String dotted) {
        assertNotNull(walk(section, dotted), "missing config key: " + dotted);
    }

    @SuppressWarnings("unchecked")
    private static void assertDefault(Map<String, Object> section, String dotted, String expected) {
        Object value = walk(section, dotted);
        assertNotNull(value, "missing config key: " + dotted);
        String text = String.valueOf(value);
        assertTrue(text.contains(":" + expected + "}"),
                dotted + " default should be " + expected + ", got " + text);
    }

    @SuppressWarnings("unchecked")
    private static Object walk(Map<String, Object> section, String dotted) {
        Object current = section;
        for (String part : dotted.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static void assertSourceContains(String relative, String needle) throws IOException {
        Path path = Paths.get("src/main/java/com/xiongx9527/askdata").resolve(relative);
        if (!Files.exists(path)) {
            path = Paths.get("askdata/askdata-server").resolve("src/main/java/com/xiongx9527/askdata")
                    .resolve(relative);
        }
        assertTrue(Files.exists(path), "source not found: " + path);
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(text.contains(needle), relative + " should read config key " + needle);
    }
}
