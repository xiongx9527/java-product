package com.xiongx9527.askdata.config;

import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 客户端装配：<b>仅在启用 Redis 时</b>（{@code askdata.cache.type=redis}
 * 或 {@code askdata.cache.sa-token-store=redis}）。
 *
 * <p>为什么不用 Spring Boot 自带的 {@code RedisAutoConfiguration}：该自动配置在
 * spring-data-redis 位于 classpath 时<b>无条件</b>创建 {@code StringRedisTemplate}，
 * 而框架的 {@code McpAutoConfiguration.RedisClusterConfiguration} 以
 * {@code @ConditionalOnBean(StringRedisTemplate)} 为条件，会随即注册
 * {@code mcpRedisMessageListenerContainer}——该容器在启动期即连接 Redis，
 * 导致「未部署 Redis 时应用无法启动」。</p>
 *
 * <p>故本项目在 {@code application.yaml} 中排除 {@code RedisAutoConfiguration}，
 * 改由本类在<b>确实启用 Redis 时</b>按需提供连接工厂与模板；未启用 Redis 时
 * 容器内不存在任何 Redis bean，框架的 Redis 集群模式不会激活，应用可无 Redis 启动。</p>
 */
@Configuration
@ConditionalOnExpression("'${askdata.cache.type:memory}' == 'redis'"
        + " || '${askdata.cache.sa-token-store:}' == 'redis'")
@EnableConfigurationProperties(RedisProperties.class)
public class RedisClientConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedisProperties properties) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        standalone.setDatabase(properties.getDatabase());
        if (hasText(properties.getUsername())) {
            standalone.setUsername(properties.getUsername());
        }
        if (hasText(properties.getPassword())) {
            standalone.setPassword(properties.getPassword());
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone);
        factory.setClientName("askdata");
        Duration timeout = properties.getTimeout();
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            factory.setTimeout(timeout.toMillis());
        }
        if (properties.getSsl() != null && properties.getSsl().isEnabled()) {
            factory.setUseSsl(true);
        }
        return factory;
    }

    @Bean
    public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Sa-Token 的 Redis 会话存储实现。
     *
     * <p>框架自带的自动配置已在本项目排除（见 application.yaml 的 spring.autoconfigure.exclude），
     * 因为它只要 spring-data-redis 在 classpath 就会装配并要求 RedisConnectionFactory bean；
     * 这里改为在确实启用 Redis 时创建，由 {@link SaTokenStoreInitializer} 注册给 {@code SaManager}。</p>
     */
    @Bean
    public SaTokenDaoForRedisTemplate saTokenDaoForRedisTemplate(RedisConnectionFactory factory) {
        SaTokenDaoForRedisTemplate dao = new SaTokenDaoForRedisTemplate();
        dao.init(factory);
        return dao;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
