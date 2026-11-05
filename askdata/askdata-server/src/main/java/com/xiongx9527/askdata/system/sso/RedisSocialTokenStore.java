package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.fw.plus.auth.social.CachedUserToken;
import com.xiongx9527.fw.plus.auth.social.SocialTokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的社交登录 token 缓存实现（product 侧实现框架 SocialTokenStore 契约）。
 *
 * <p>仅在启用 Redis（askdata.cache.type=redis / sa-token-store=redis / lock.type=redis）时，
 * Spring 才会提供 StringRedisTemplate；未启用时 redis 为 null，本实现退化为安全空操作，
 * 框架自动回退到自带 Guava 内存实现（LocalSocialTokenStore），功能等价。</p>
 */
@Component
public class RedisSocialTokenStore implements SocialTokenStore {

    private static final String APP_PREFIX = "idp:app:";
    private static final String USER_PREFIX = "idp:user:";

    @Autowired(required = false)
    private StringRedisTemplate redis;

    @Override
    public String getAppToken(String key) {
        if (redis == null) {
            return null;
        }
        return redis.opsForValue().get(APP_PREFIX + key);
    }

    @Override
    public void putAppToken(String key, String token, long ttlSeconds) {
        if (redis == null || token == null) {
            return;
        }
        redis.opsForValue().set(APP_PREFIX + key, token, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void evictAppToken(String key) {
        if (redis == null) {
            return;
        }
        redis.delete(APP_PREFIX + key);
    }

    @Override
    public CachedUserToken getUserToken(String key) {
        if (redis == null) {
            return null;
        }
        String json = redis.opsForValue().get(USER_PREFIX + key);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            ObjectMapper om = new ObjectMapper();
            Map<?, ?> m = om.readValue(json, Map.class);
            return new CachedUserToken(
                    (String) m.get("accessToken"),
                    (String) m.get("refreshToken"),
                    (String) m.get("openid"),
                    (String) m.get("unionid"),
                    ((Number) m.get("expiresAt")).longValue());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void putUserToken(String key, CachedUserToken token) {
        if (redis == null || token == null) {
            return;
        }
        try {
            ObjectMapper om = new ObjectMapper();
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("accessToken", token.getAccessToken());
            m.put("refreshToken", token.getRefreshToken());
            m.put("openid", token.getOpenid());
            m.put("unionid", token.getUnionid());
            m.put("expiresAt", token.getExpiresAt());
            long ttl = (token.getExpiresAt() - System.currentTimeMillis()) / 1000L;
            ttl = ttl <= 0 ? 1L : ttl;
            redis.opsForValue().set(USER_PREFIX + key, om.writeValueAsString(m), ttl, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 序列化失败则放弃缓存，交由框架远程获取
        }
    }

    @Override
    public void evictUserToken(String key) {
        if (redis == null) {
            return;
        }
        redis.delete(USER_PREFIX + key);
    }
}
