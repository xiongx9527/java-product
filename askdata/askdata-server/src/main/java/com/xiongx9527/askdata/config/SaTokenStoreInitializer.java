package com.xiongx9527.askdata.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 会话存储选择：内存（缺省）/ Redis，由 {@code askdata.cache.sa-token-store} 决定
 * （留空则跟随 {@code askdata.cache.type}）。
 *
 * <p>Sa-Token 1.46 的 {@code SaManager} 在未显式设置时惰性使用进程内的
 * {@code SaTokenDaoDefaultImpl}；Redis 实现 {@link SaTokenDaoForRedisTemplate} 由
 * {@code sa-token-redis-template} 的自动配置装配（其 {@code @Autowired init(RedisConnectionFactory)}
 * 会注入连接工厂），但它<b>不会</b>自动注册给 {@link SaManager}，故此处显式注册。</p>
 *
 * <p>注册为 Redis 后，登录态（token / session）存于 Redis，多实例共享；
 * 内存模式下各实例互不可见（与 SQLBot 的 memory 缓存后端语义一致）。</p>
 */
@Component
public class SaTokenStoreInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SaTokenStoreInitializer.class);

    private final CacheProperties properties;

    private final ObjectProvider<SaTokenDaoForRedisTemplate> redisDaoProvider;

    public SaTokenStoreInitializer(CacheProperties properties,
                                   ObjectProvider<SaTokenDaoForRedisTemplate> redisDaoProvider) {
        this.properties = properties;
        this.redisDaoProvider = redisDaoProvider;
    }

    @Override
    public void afterPropertiesSet() {
        String store = properties.resolveSaTokenStore();
        if (!CacheProperties.TYPE_REDIS.equals(store)) {
            // 内存：沿用 Sa-Token 默认的 SaTokenDaoDefaultImpl（进程内）。
            log.info("Sa-Token 会话存储：内存（进程内）");
            return;
        }
        SaTokenDaoForRedisTemplate dao = redisDaoProvider.getIfAvailable();
        if (dao == null) {
            throw new IllegalStateException("askdata.cache.sa-token-store=redis 需要 sa-token-redis-template 依赖"
                    + "与可用的 Redis 连接（spring.data.redis.*）");
        }
        SaManager.setSaTokenDao(dao);
        log.info("Sa-Token 会话存储：Redis");
    }
}
