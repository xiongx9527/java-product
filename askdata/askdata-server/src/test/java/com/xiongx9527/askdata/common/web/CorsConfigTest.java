package com.xiongx9527.askdata.common.web;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 配置单测（对齐 SQLBot {@code main.py} 的 CORSMiddleware + {@code init_dynamic_cors}）。
 */
class CorsConfigTest {

    @Test
    void parsesCommaAndSemicolonSeparatedOrigins() {
        List<String> origins = CorsConfig.parseOrigins("http://a.com, http://b.com;https://c.com/");
        assertEquals(Arrays.asList("http://a.com", "http://b.com", "https://c.com"), origins);
    }

    @Test
    void emptyOriginsFallBackToWildcard() {
        CorsConfig config = new CorsConfig("", null);
        assertEquals(Arrays.asList("*"), config.allOrigins());
    }

    @Test
    void nullOriginsFallBackToWildcard() {
        CorsConfig config = new CorsConfig(null, null);
        assertEquals(Arrays.asList("*"), config.allOrigins());
    }

    @Test
    void dynamicOriginsAreAddedAtRuntime() {
        CorsConfig config = new CorsConfig("http://a.com", null);
        config.addDynamicOrigins(Arrays.asList("https://assistant.example.com"));
        List<String> origins = config.allOrigins();
        assertTrue(origins.contains("http://a.com"));
        assertTrue(origins.contains("https://assistant.example.com"));
    }

    @Test
    void assistantDomainSupportsMultipleSeparators() {
        List<String> domains = CorsConfig.splitDomains("https://a.com, https://b.com;https://c.com/");
        assertEquals(Arrays.asList("https://a.com", "https://b.com", "https://c.com"), domains);
    }

    @Test
    void assistantDomainEmptyYieldsNoDomains() {
        assertTrue(CorsConfig.splitDomains(null).isEmpty());
        assertTrue(CorsConfig.splitDomains("").isEmpty());
        assertTrue(CorsConfig.splitDomains(" , ; ").isEmpty());
    }

    @Test
    void dynamicOriginsAreDeduplicated() {
        CorsConfig config = new CorsConfig("", null);
        config.addDynamicOrigins(Arrays.asList("https://a.com", "https://a.com/"));
        assertEquals(1, config.allOrigins().size());
    }
}
