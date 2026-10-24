package com.xiongx9527.askdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 缓存配置（{@code askdata.cache.*}）。
 *
 * <p>后端可配置（对齐 SQLBot {@code init_sqlbot_cache} 的 memory / redis 两种后端）：</p>
 * <ul>
 *   <li>{@code type}：{@code memory}（Caffeine 进程内，缺省）/ {@code redis}（Redis）/ {@code none}（不缓存）；</li>
 *   <li>{@code max-size} / {@code ttl-seconds}：内存后端的条目上限与存活时间；</li>
 *   <li>{@code sa-token-store}：Sa-Token 会话存储，{@code memory}（缺省）/ {@code redis}；
 *       留空则跟随 {@code type}。</li>
 * </ul>
 *
 * <p>Redis 连接参数走 Spring Boot 标准位置 {@code spring.data.redis.*}。</p>
 */
@Component
@ConfigurationProperties(prefix = "askdata.cache")
public class CacheProperties {

    /** 进程内缓存（Caffeine）。 */
    public static final String TYPE_MEMORY = "memory";

    /** Redis 缓存。 */
    public static final String TYPE_REDIS = "redis";

    /** 不缓存。 */
    public static final String TYPE_NONE = "none";

    /** 缓存后端类型。 */
    private String type = TYPE_MEMORY;

    /** 内存后端：单缓存条目上限。 */
    private long maxSize = 10000L;

    /** 存活时间（秒）。 */
    private long ttlSeconds = 300L;

    /** Sa-Token 会话存储；留空则跟随 {@link #type}。 */
    private String saTokenStore = "";

    /** 归一化后的缓存后端类型。 */
    public String resolveType() {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (TYPE_REDIS.equals(t)) {
            return TYPE_REDIS;
        }
        if (TYPE_NONE.equals(t)) {
            return TYPE_NONE;
        }
        return TYPE_MEMORY;
    }

    /** 归一化后的 Sa-Token 会话存储类型（memory / redis）。 */
    public String resolveSaTokenStore() {
        String s = saTokenStore == null ? "" : saTokenStore.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            s = resolveType();
        }
        return TYPE_REDIS.equals(s) ? TYPE_REDIS : TYPE_MEMORY;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String getSaTokenStore() {
        return saTokenStore;
    }

    public void setSaTokenStore(String saTokenStore) {
        this.saTokenStore = saTokenStore;
    }
}
