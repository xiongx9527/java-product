package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.embedded.AssistantRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A25：CORS 动态 origin 必须在启动时被实际加载（对齐 SQLBot {@code init_dynamic_cors}）。
 *
 * <p>回归点：{@code initDynamicOrigins} 若从未被调用，助手域名就不会进入允许来源，
 * 嵌入页在助手创建后、服务重启前的跨域请求会全部失败。</p>
 */
class CorsDynamicOriginTest {

    @Test
    void initDynamicOriginsLoadsAssistantDomains() {
        AssistantRepository repository = mock(AssistantRepository.class);
        Map<String, Object> assistant = new LinkedHashMap<>();
        // SQLBot get_domain_list：domain 可为逗号分隔的多个域名，尾部斜杠需去掉
        assistant.put("domain", "https://a.example.com/,https://b.example.com");
        when(repository.listAllDomains()).thenReturn(Collections.singletonList(assistant));

        CorsConfig cors = new CorsConfig("https://configured.example.com", repository);
        cors.initDynamicOrigins();

        List<String> origins = cors.allOrigins();
        assertTrue(origins.contains("https://configured.example.com"), origins.toString());
        assertTrue(origins.contains("https://a.example.com"), origins.toString());
        assertTrue(origins.contains("https://b.example.com"), origins.toString());
    }

    @Test
    void splitDomainsHandlesSeparatorsAndSlashes() {
        List<String> expected = new ArrayList<>();
        expected.add("https://a.example.com");
        expected.add("https://b.example.com");
        expected.add("https://c.example.com");
        assertEquals(expected, CorsConfig.splitDomains("https://a.example.com/; https://b.example.com/,https://c.example.com"));
        assertEquals(Collections.emptyList(), CorsConfig.splitDomains(null));
        assertEquals(Collections.emptyList(), CorsConfig.splitDomains("  "));
    }

    @Test
    void addDynamicOriginsDeduplicatesAndIgnoresBlank() {
        AssistantRepository repository = mock(AssistantRepository.class);
        CorsConfig cors = new CorsConfig("", repository);
        cors.addDynamicOrigins(null);
        cors.addDynamicOrigins(Collections.emptyList());
        cors.addDynamicOrigins(java.util.Arrays.asList("https://a.example.com", " ", "https://a.example.com/"));

        List<String> origins = cors.allOrigins();
        assertEquals(1, origins.stream().filter("https://a.example.com"::equals).count(), origins.toString());
        // 未配置任何来源时保留兜底 "*"
        assertFalse(origins.contains("https://a.example.com/"), origins.toString());
    }
}
