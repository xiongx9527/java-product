package com.xiongx9527.askdata.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 版单 worker 守卫锁：{@code SET key token NX PX ttl} + 持有者校验释放。
 *
 * <p><b>作用域提示</b>：Redis 锁是<b>全局</b>的，与 DB 锁「按元数据库互斥」的语义不同。
 * 仅当部署形态为「多实例共享 Redis、但各实例使用各自的元数据库」时才有意义；
 * 若各实例共享同一元数据库，DB 锁已经足够，用 Redis 锁反而会把作用域放大到全局。</p>
 *
 * <p>释放时用 Lua 脚本比对持有者标识再删除，避免误删他人已续期的锁。</p>
 */
public class RedisWorkerLock implements WorkerLock {

    private static final Logger log = LoggerFactory.getLogger(RedisWorkerLock.class);

    /** 仅当锁值仍是自己写入的 token 时才删除。 */
    private static final DefaultRedisScript<Long> DEL_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    /** 锁键前缀。 */
    private final String prefix;

    /** 锁 TTL（秒）：进程异常退出时靠它自动过期。 */
    private final Duration ttl;

    /** 防抖窗口：最近在该窗口内已执行过的任务不再重复执行（0/负数表示不防抖）。 */
    private final Duration debounce;

    /** 本进程持有的 token（键 → token），用于释放时校验持有者。 */
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    public RedisWorkerLock(StringRedisTemplate redis, String prefix, Duration ttl, Duration debounce) {
        this.redis = redis;
        this.prefix = (prefix == null || prefix.trim().isEmpty()) ? "askdata:lock:" : prefix.trim();
        this.ttl = (ttl == null || ttl.isZero() || ttl.isNegative()) ? Duration.ofSeconds(1800L) : ttl;
        this.debounce = debounce;
    }

    @Override
    public boolean tryAcquire(String key) {
        if (isDebounced(key)) {
            log.info("单 worker 守卫 {} 在防抖窗口内已执行过，跳过（窗口 {}s）", key, debounce.getSeconds());
            return false;
        }
        String lockKey = prefix + key;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            tokens.put(key, token);
            if (debounce != null && !debounce.isZero() && !debounce.isNegative()) {
                // 记录「最近已执行」标记，窗口内不再重复执行（重启风暴防护）
                try {
                    redis.opsForValue().set(doneKey(key), "1", debounce);
                } catch (Exception e) {
                    log.info("写入 Redis 防抖标记失败 {}: {}", key, e.getMessage());
                }
            }
            return true;
        }
        return false;
    }

    /** 防抖判定：存在「最近已执行」标记则跳过。 */
    private boolean isDebounced(String key) {
        if (debounce == null || debounce.isZero() || debounce.isNegative()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(doneKey(key)));
        } catch (Exception e) {
            return false;
        }
    }

    /** 「最近已执行」标记键。 */
    private String doneKey(String key) {
        return prefix + "done:" + key;
    }

    @Override
    public void release(String key) {
        String token = tokens.remove(key);
        if (token == null) {
            return;
        }
        try {
            redis.execute(DEL_IF_OWNER, Collections.singletonList(prefix + key), token);
        } catch (Exception e) {
            log.info("释放 Redis 单 worker 守卫失败 {}: {}", key, e.getMessage());
        }
    }

    @Override
    public String name() {
        return "redis";
    }
}
