package com.xiongx9527.askdata.common.debounce;

import com.xiongx9527.fw.plus.boot.autoconfigure.debounce.RedisDebounceSlot;
import com.xiongx9527.fw.plus.boot.autoconfigure.debounce.RequestDebouncer;
import com.xiongx9527.fw.plus.common.debounce.DebounceBackendResolver;
import com.xiongx9527.fw.plus.common.debounce.DebounceProperties;
import com.xiongx9527.fw.plus.common.debounce.DebounceSlot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * <p>连接参数：{@code -Daskdata.test.redis.port=<port>}（或 {@code ASKDATA_TEST_REDIS_PORT}，
 * 缺省 6379）、{@code askdata.test.redis.host}（缺省 127.0.0.1）、
 * {@code askdata.test.redis.password}（或 {@code ASKDATA_TEST_REDIS_PASSWORD}）。
 * Redis 不可达时整类跳过并给出配置方式——这是真实服务依赖，不是实现缺陷。</p>
 */
class DebounceRedisCrossInstanceTest {

    private static final int ATTEMPTS = 16;

    @BeforeAll
    static void requireRedis() {
        LettuceConnectionFactory probe = connectionFactory();
        try {
            probe.getConnection().ping();
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Redis 不可达（" + host() + ":" + port() + "）：" + e.getMessage());
        } finally {
            probe.destroy();
        }
    }

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
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host(), port());
        String password = config("askdata.test.redis.password", "ASKDATA_TEST_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) {
            factory.setPassword(password);
        }
        factory.afterPropertiesSet();
        factory.getConnection().close();
        return factory;
    }

    private static String host() {
        String host = config("askdata.test.redis.host", "ASKDATA_TEST_REDIS_HOST");
        return host == null || host.isEmpty() ? "127.0.0.1" : host;
    }

    private static int port() {
        String port = config("askdata.test.redis.port", "ASKDATA_TEST_REDIS_PORT");
        if (port == null || port.isEmpty()) {
            return 6379;
        }
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            return 6379;
        }
    }

    /** 系统属性优先，其次环境变量（都不设返回 null）。 */
    private static String config(String prop, String env) {
        String value = System.getProperty(prop);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(env);
        }
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** 集群形态：缓存声明为 redis，并注入同一个 Redis 的 StringRedisTemplate。 */
    private static RequestDebouncer redisBacked(LettuceConnectionFactory factory, DebounceProperties properties) {
        RedisCacheManager cacheManager = RedisCacheManager.builder(factory)
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10)))
                .build();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("redisDebounceSlot", new RedisDebounceSlot(template));
        beans.addBean("debounceBackendResolver", (DebounceBackendResolver) () -> true);
        ObjectProvider<DebounceSlot> slotProvider = beans.getBeanProvider(DebounceSlot.class);
        ObjectProvider<DebounceBackendResolver> resolverProvider =
                beans.getBeanProvider(DebounceBackendResolver.class);
        return new RequestDebouncer(cacheManager, properties, slotProvider, resolverProvider);
    }
}
