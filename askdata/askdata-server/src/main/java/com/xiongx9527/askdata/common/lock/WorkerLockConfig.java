package com.xiongx9527.askdata.common.lock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * 单 worker 守卫的锁后端选择（{@code askdata.lock.type}）：
 * {@code db}（缺省，元数据库表行锁）/ {@code redis}（Redis SET NX PX）。
 *
 * <p>两个后端用互斥的 {@code @ConditionalOnProperty} 装配（各自带
 * {@code matchIfMissing}/{@code havingValue}），不依赖 {@code @ConditionalOnMissingBean} 的注册顺序。</p>
 */
@Configuration
public class WorkerLockConfig {

    /** Redis 锁键前缀。 */
    private static final String REDIS_LOCK_PREFIX = "askdata:lock:";

    /** Redis 锁 TTL（秒）：进程异常退出时靠它自动过期。 */
    private static final long REDIS_LOCK_TTL_SECONDS = 1800L;

    /**
     * 防抖窗口（秒，缺省 300）：一次成功执行后，该窗口内的后续启动不再重复执行同一任务
     * （防「应用反复重启 → 一次性启动任务被反复触发」）。
     */
    private static final long DEBOUNCE_SECONDS = 300L;

    /** DB 版锁（缺省）。 */
    @Bean
    @ConditionalOnProperty(prefix = "askdata.lock", name = "type", havingValue = "db", matchIfMissing = true)
    public WorkerLock dbWorkerLock(JdbcTemplate jdbc,
                                   @Value("${askdata.lock.debounce-seconds:300}") long debounceSeconds) {
        return new DbWorkerLock(jdbc, Duration.ofSeconds(Math.max(0L, debounceSeconds)));
    }

    /** Redis 版锁。 */
    @Bean
    @ConditionalOnProperty(prefix = "askdata.lock", name = "type", havingValue = "redis")
    public WorkerLock redisWorkerLock(StringRedisTemplate redisTemplate,
                                       @Value("${askdata.lock.debounce-seconds:300}") long debounceSeconds) {
        return new RedisWorkerLock(redisTemplate, REDIS_LOCK_PREFIX,
                Duration.ofSeconds(REDIS_LOCK_TTL_SECONDS),
                Duration.ofSeconds(Math.max(0L, debounceSeconds)));
    }
}
