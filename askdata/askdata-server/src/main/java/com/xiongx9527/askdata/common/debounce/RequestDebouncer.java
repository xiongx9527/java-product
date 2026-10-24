package com.xiongx9527.askdata.common.debounce;

import com.xiongx9527.askdata.common.cache.CacheNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * 接口防抖器：同一「调用方 + 接口」在防抖窗口内只放行一次。
 *
 * <p>实现要点：使用 Spring Cache 的 {@code putIfAbsent} 做<b>原子占位</b>——
 * 内存后端（Caffeine）与 Redis 后端都是原子的，且沿用该缓存的 TTL（此处为防抖窗口）。
 * 缓存异常时<b>放行</b>（防抖是保护措施，不应因缓存故障而中断业务）。</p>
 *
 * <p>后端由 {@code askdata.cache.type} 决定：memory 为单实例防抖，redis 为多实例全局防抖，
 * none 则不防抖。</p>
 */
@Service
public class RequestDebouncer {

    private static final Logger log = LoggerFactory.getLogger(RequestDebouncer.class);

    private final CacheManager cacheManager;
    private final DebounceProperties properties;

    public RequestDebouncer(CacheManager cacheManager, DebounceProperties properties) {
        this.cacheManager = cacheManager;
        this.properties = properties;
    }

    /**
     * 判定是否放行。
     *
     * @param key 调用方 + 接口 维度的键
     * @return true 放行；false 表示窗口内重复请求，应返回 429
     */
    public boolean allow(String key) {
        if (!properties.isEnabled() || key == null) {
            return true;
        }
        try {
            Cache cache = cacheManager.getCache(CacheNames.REQUEST_DEBOUNCE);
            if (cache == null) {
                return true;
            }
            // putIfAbsent 原子占位：返回 null 表示此前不存在 → 本次放行
            return cache.putIfAbsent(key, "1") == null;
        } catch (Exception e) {
            log.info("接口防抖判定失败（放行）: {}", e.getMessage());
            return true;
        }
    }
}
