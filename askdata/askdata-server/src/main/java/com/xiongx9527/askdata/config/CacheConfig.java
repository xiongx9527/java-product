package com.xiongx9527.askdata.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 缓存装配：后端由 {@code askdata.cache.type} 决定（memory / redis / none）。
 *
 * <ul>
 *   <li>{@code memory}（缺省）：Caffeine 进程内缓存，单机有效，零外部依赖；</li>
 *   <li>{@code redis}：Redis 缓存（{@link RedisCacheManager}），多实例共享，
 *       连接参数走 Spring Boot 标准位置 {@code spring.data.redis.*}；</li>
 *   <li>{@code none}：不缓存（{@link NoOpCacheManager}），等价于关闭缓存，便于排障与对比。</li>
 * </ul>
 *
 * <p>注意：Redis 后端会把值序列化成 JSON 再存，故读取后的数值类型可能与内存后端略有差异
 * （如 {@code Integer} / {@code Long}），业务侧读取请统一走 {@code Rows} 工具方法。</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Redis 缓存键前缀（与业务表命名空间隔离）。 */
    private static final String REDIS_KEY_PREFIX = "askdata:cache:";

    private final CacheProperties properties;

    public CacheConfig(CacheProperties properties) {
        this.properties = properties;
    }

    @Bean
    public CacheManager cacheManager(ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
        String type = properties.resolveType();
        if (CacheProperties.TYPE_REDIS.equals(type)) {
            RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
            if (factory == null) {
                throw new IllegalStateException(
                        "askdata.cache.type=redis 需要可用的 Redis 连接（spring.data.redis.*）");
            }
            log.info("缓存后端：Redis（前缀 {}，TTL {}s）", REDIS_KEY_PREFIX, properties.getTtlSeconds());
            return redisCacheManager(factory);
        }
        if (CacheProperties.TYPE_NONE.equals(type)) {
            log.info("缓存后端：不缓存（askdata.cache.type=none）");
            return new NoOpCacheManager();
        }
        log.info("缓存后端：进程内 Caffeine（上限 {} 条，TTL {}s）",
                properties.getMaxSize(), properties.getTtlSeconds());
        return caffeineCacheManager();
    }

    private CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(1L, properties.getTtlSeconds())))
                .maximumSize(Math.max(1L, properties.getMaxSize())));
        return manager;
    }

    private CacheManager redisCacheManager(RedisConnectionFactory factory) {
        Jackson2JsonRedisSerializer<Object> valueSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(Math.max(1L, properties.getTtlSeconds())))
                .prefixCacheNameWith(REDIS_KEY_PREFIX)
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));
        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .transactionAware()
                .build();
    }
}
