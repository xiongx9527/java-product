package com.xiongx9527.askdata.common.debounce;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.xiongx9527.askdata.common.cache.CacheNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 接口防抖契约（纯 JUnit，不需要外部服务）：
 * 1) 缺省关闭时一律放行；
 * 2) 开启后同一 key 在窗口内只放行一次，不同 key 互不影响；
 * 3) 缓存异常时放行（防抖是保护措施，不应中断业务）。
 */
class RequestDebouncerTest {

    private DebounceProperties properties;

    private CaffeineCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        properties = new DebounceProperties();
        properties.setWindowMs(1000L);
        cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(CacheNames.REQUEST_DEBOUNCE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(1000L))
                .build());
    }

    private RequestDebouncer debouncer() {
        return new RequestDebouncer(cacheManager, properties);
    }

    @Test
    @DisplayName("缺省关闭：一律放行")
    void disabledAlwaysAllows() {
        RequestDebouncer debouncer = debouncer();
        assertTrue(debouncer.allow("k"));
        assertTrue(debouncer.allow("k"));
    }

    @Test
    @DisplayName("开启后：同 key 窗口内只放行一次，不同 key 互不影响")
    void enabledDebouncesSameKey() {
        properties.setEnabled(true);
        RequestDebouncer debouncer = debouncer();
        assertTrue(debouncer.allow("POST|/api/v1/chat/question|u1"), "首次应放行");
        assertFalse(debouncer.allow("POST|/api/v1/chat/question|u1"), "窗口内重复请求应被拦截");
        assertTrue(debouncer.allow("POST|/api/v1/chat/question|u2"), "不同调用方应互不影响");
        assertTrue(debouncer.allow("GET|/api/v1/datasource/list|u1"), "不同接口应互不影响");
    }

    @Test
    @DisplayName("缓存异常时放行（不因防抖故障中断业务）")
    void cacheFailureStillAllows() {
        properties.setEnabled(true);
        RequestDebouncer debouncer = new RequestDebouncer(new org.springframework.cache.CacheManager() {
            @Override public org.springframework.cache.Cache getCache(String name) {
                throw new IllegalStateException("cache unavailable");
            }
            @Override public java.util.Collection<String> getCacheNames() { return java.util.List.of(); }
        }, properties);
        assertTrue(debouncer.allow("k"));
    }

    @Test
    @DisplayName("路径匹配：patterns 为空时全部命中；否则按 Ant 风格匹配")
    void pathMatching() {
        assertTrue(properties.matches("/api/v1/chat/question"), "未配置 patterns 时应全部命中");
        properties.setPaths(java.util.List.of("/api/v1/chat/*"));
        assertTrue(properties.matches("/api/v1/chat/question"));
        assertFalse(properties.matches("/api/v1/datasource/list"));
    }
}
