package com.xiongx9527.askdata.common.debounce;

import com.xiongx9527.askdata.common.cache.CacheNames;
import com.xiongx9527.askdata.config.CacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 接口防抖器：同一「调用方 + 接口」在防抖窗口内只放行一次。
 *
 * <p>两种后端，语义一致但原子性实现不同：</p>
 * <ul>
 *   <li><b>Redis（多实例）</b>：用一段 Lua 脚本「读占位 + 比较过期时间 + PSETEX 占位」一次原子完成。
 *       不能只用缓存的 {@code putIfAbsent}：占位是通过值里的过期时间复用的，窗口刚过期时两个实例
 *       会同时读到旧值并各自重新占位，导致并发重复提交都被放行。Lua 在 Redis 内串行执行，
 *       跨实例严格只放行一次，且占位 TTL 恰为本次窗口，过期即自然消失。</li>
 *   <li><b>内存 / 其它</b>：沿用 Spring Cache 的 {@code putIfAbsent} 原子占位（单实例足够）；
 *       占位值存放「本次窗口过期时间戳」，支持按接口自定义窗口（{@link Debounce#windowMs()}）。</li>
 * </ul>
 *
 * <p>缓存/Redis 异常时<b>放行</b>（防抖是保护措施，不应因存储故障而中断业务）。</p>
 */
@Service
public class RequestDebouncer {

    private static final Logger log = LoggerFactory.getLogger(RequestDebouncer.class);

    /** Redis 占位键前缀（与 Spring Cache 的 {@code askdata:debounce::} 区分，避免值格式冲突）。 */
    static final String REDIS_KEY_PREFIX = "askdata:debounce:slot:";

    /**
     * 原子占位脚本：占位不存在或已过期（值 ≤ 当前时间）则写入新过期时间并返回 1，否则返回 0。
     *
     * <p>ARGV[1]=新过期时间戳，ARGV[2]=当前时间戳，ARGV[3]=窗口毫秒数（同时作为 TTL）。</p>
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) "
                    + "if (not cur) or tonumber(cur) <= tonumber(ARGV[2]) then "
                    + "  redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[1]) "
                    + "  return 1 "
                    + "end "
                    + "return 0",
            Long.class);

    private final CacheManager cacheManager;

    private final DebounceProperties properties;

    private final CacheProperties cacheProperties;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Autowired
    public RequestDebouncer(CacheManager cacheManager, DebounceProperties properties,
                            CacheProperties cacheProperties,
                            ObjectProvider<StringRedisTemplate> redisProvider) {
        this.cacheManager = cacheManager;
        this.properties = properties;
        this.cacheProperties = cacheProperties;
        this.redisProvider = redisProvider;
    }

    /** 兼容单测/内存后端的简化构造（不使用 Redis 原子脚本）。 */
    public RequestDebouncer(CacheManager cacheManager, DebounceProperties properties) {
        this(cacheManager, properties, null, null);
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
        if (useRedisBackend()) {
            return allowWithRedis(key, window);
        }
        return allowWithCache(key, window);
    }

    private boolean useRedisBackend() {
        return redisProvider != null && cacheProperties != null
                && CacheProperties.TYPE_REDIS.equals(cacheProperties.resolveType())
                && redisProvider.getIfAvailable() != null;
    }

    /** 多实例：Lua 脚本在 Redis 内串行执行，读-比较-写不可能被别的实例插入。 */
    private boolean allowWithRedis(String key, long window) {
        long now = System.currentTimeMillis();
        long expiry = now + window;
        try {
            Long acquired = redisProvider.getIfAvailable().execute(ACQUIRE_SCRIPT,
                    List.of(REDIS_KEY_PREFIX + key),
                    String.valueOf(expiry), String.valueOf(now), String.valueOf(window));
            return acquired != null && acquired == 1L;
        } catch (Exception e) {
            log.info("接口防抖判定失败（放行）: {}", e.getMessage());
            return true;
        }
    }

    /** 单实例：Spring Cache 的 putIfAbsent 原子占位 + 窗口过期后重新占位。 */
    private boolean allowWithCache(String key, long window) {
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
