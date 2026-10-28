package com.xiongx9527.askdata.common.debounce;

import com.xiongx9527.askdata.config.CacheProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防抖的跨实例语义（验收 A11）。
 *
 * <p>用<b>两个彼此独立的</b> {@link RequestDebouncer}（各自的 Redis 连接、各自的 CacheManager）
 * 模拟两个 askdata 实例，只共享同一个 Redis。两个实例同时对同一防抖键发起判定，
 * 断言恰好一次放行——这正是验收的「同一用户对同一接口在两个实例上并发重复提交，
 * 只有一次被执行」。Redis 后端走 Lua 原子占位，窗口越是卡在过期边界越能暴露竞态，
 * 因此这里同时覆盖「窗口内拦截」与「窗口过期后恢复」。</p>
 *
 * <p>默认跳过：需要显式提供 {@code -Daskdata.test.redis.port=<port>}（集群 e2e 的 Redis，
 * 默认编排发布在 13379），避免没有 Redis 的环境误报失败。</p>
 */
@EnabledIfSystemProperty(named = "askdata.test.redis.port", matches = "\\d+")
class DebounceRedisCrossInstanceTest {

    private static final int ATTEMPTS = 16;

    @Test
    void concurrentCallsOnTwoInstancesAllowExactlyOnce() throws Exception {
        LettuceConnectionFactory factoryA = connectionFactory();
        LettuceConnectionFactory factoryB = connectionFactory();
        try {
            DebounceProperties properties = new DebounceProperties();
            properties.setEnabled(true);
            properties.setWindowMs(60_000L);

            RequestDebouncer instanceA = redisBacked(factoryA, properties);
            RequestDebouncer instanceB = redisBacked(factoryB, properties);
            String key = "a11:" + UUID.randomUUID();

            CyclicBarrier start = new CyclicBarrier(ATTEMPTS);
            AtomicInteger allowed = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < ATTEMPTS; i++) {
                // 奇偶交替：一半请求落在实例 A，一半落在实例 B
                RequestDebouncer target = (i % 2 == 0) ? instanceA : instanceB;
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    if (target.allow(key, 60_000L)) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            pool.shutdownNow();

            assertEquals(1, allowed.get(),
                    "两个独立实例共享 Redis 时，同一防抖键在窗口内只能放行一次");
        } finally {
            factoryA.destroy();
            factoryB.destroy();
        }
    }

    @Test
    void occupiedSlotBlocksTheOtherInstanceWithinWindow() {
        LettuceConnectionFactory factoryA = connectionFactory();
        LettuceConnectionFactory factoryB = connectionFactory();
        try {
            DebounceProperties properties = new DebounceProperties();
            properties.setEnabled(true);

            RequestDebouncer instanceA = redisBacked(factoryA, properties);
            RequestDebouncer instanceB = redisBacked(factoryB, properties);
            String key = "a11-win:" + UUID.randomUUID();

            assertTrue(instanceA.allow(key, 60_000L), "首个请求放行");
            assertFalse(instanceB.allow(key, 60_000L), "窗口内跨实例重复请求被拦截");
        } finally {
            factoryA.destroy();
            factoryB.destroy();
        }
    }

    /**
     * 窗口刚好过期的边界：旧实现的「读旧值 → 各自重新占位」会让并发双方都放行，
     * Lua 原子脚本必须仍然只放行一次。
     */
    @Test
    void raceAtExpiryBoundaryStillAllowsOnlyOne() throws Exception {
        LettuceConnectionFactory factoryA = connectionFactory();
        LettuceConnectionFactory factoryB = connectionFactory();
        try {
            DebounceProperties properties = new DebounceProperties();
            properties.setEnabled(true);

            RequestDebouncer instanceA = redisBacked(factoryA, properties);
            RequestDebouncer instanceB = redisBacked(factoryB, properties);
            String key = "a11-edge:" + UUID.randomUUID();

            assertTrue(instanceA.allow(key, 120L), "首个请求放行");
            Thread.sleep(180L); // 占位已过窗口，但可能尚未被 TTL 回收

            AtomicInteger allowed = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier start = new CyclicBarrier(2);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                RequestDebouncer target = (i == 0) ? instanceA : instanceB;
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    if (target.allow(key, 60_000L)) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            pool.shutdownNow();

            assertEquals(1, allowed.get(), "窗口边界并发重占位也必须只成功一次");
        } finally {
            factoryA.destroy();
            factoryB.destroy();
        }
    }

    /** 每个实例各自一条连接（互不共享进程内状态），并预热连接避免把建连耗时算进防抖窗口。 */
    private static LettuceConnectionFactory connectionFactory() {
        int port = Integer.getInteger("askdata.test.redis.port");
        String host = System.getProperty("askdata.test.redis.host", "127.0.0.1");
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        factory.getConnection().close();
        return factory;
    }

    /** 集群形态：缓存声明为 redis，并注入同一个 Redis 的 StringRedisTemplate。 */
    private static RequestDebouncer redisBacked(LettuceConnectionFactory factory, DebounceProperties properties) {
        RedisCacheManager cacheManager = RedisCacheManager.builder(factory)
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10)))
                .build();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("stringRedisTemplate", template);
        ObjectProvider<StringRedisTemplate> provider = beans.getBeanProvider(StringRedisTemplate.class);
        CacheProperties cacheProperties = new CacheProperties();
        cacheProperties.setType(CacheProperties.TYPE_REDIS);
        return new RequestDebouncer(cacheManager, properties, cacheProperties, provider);
    }
}
