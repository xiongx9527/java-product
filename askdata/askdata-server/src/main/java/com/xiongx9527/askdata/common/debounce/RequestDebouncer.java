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
 * 内存后端（Caffeine）与 Redis 后端都是原子的。占位值存放「本次窗口过期时间戳」，
 * 由此支持<b>按接口自定义窗口</b>（{@link Debounce#windowMs()}）：窗口到期后即使占位尚未被
 * 缓存 TTL 回收，也会重新占位并放行（re-occupy）。缓存异常时<b>放行</b>
 * （防抖是保护措施，不应因缓存故障而中断业务）。</p>
 *
 * <p>底层缓存的 TTL 由 {@code CacheConfig} 设为足以覆盖最大窗口的值，故占位在逻辑窗口内不会被提前回收。</p>
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
     * @param key       调用方 + 接口 维度的键
     * @param windowMs  本次生效的防抖窗口（毫秒，&gt; 0；会被收敛到上限）
     * @return true 放行；false 表示窗口内重复请求，应返回 429
     */
    public boolean allow(String key, long windowMs) {
        if (!properties.isEnabled() || key == null) {
            return true;
        }
        long window = Math.max(1L, Math.min(windowMs, DebounceProperties.MAX_WINDOW_MS));
        try {
            Cache cache = cacheManager.getCache(CacheNames.REQUEST_DEBOUNCE);
            if (cache == null) {
                return true;
            }
            long now = System.currentTimeMillis();
            long expiry = now + window;
            // putIfAbsent 原子占位：返回 null 表示此前不存在 → 本次放行
            Cache.ValueWrapper existing = cache.putIfAbsent(key, expiry);
            if (existing == null) {
                return true;
            }
            Long prevExpiry = asLong(existing.get());
            // 占位已过期（窗口已过）但尚未被 TTL 回收 → 重新占位后放行
            if (prevExpiry == null || now > prevExpiry) {
                cache.put(key, expiry);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.info("接口防抖判定失败（放行）: {}", e.getMessage());
            return true;
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
