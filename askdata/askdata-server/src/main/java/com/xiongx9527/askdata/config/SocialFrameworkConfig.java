package com.xiongx9527.askdata.config;

import com.xiongx9527.fw.plus.auth.social.AbstractSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.SocialLoginProviderRegistry;
import com.xiongx9527.fw.plus.auth.social.SocialTokenStore;
import com.xiongx9527.fw.plus.auth.social.general.CasSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.general.OidcSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.general.Saml2SocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.DingTalkSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.FeishuSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.LarkSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.LdapSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.WechatWorkSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.personal.WechatScanSocialLoginProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * 社交/企业登录装配（product 侧）：注册框架 9 个 provider 到 SocialLoginProviderRegistry，
 * 并在启用 Redis 时把 product 实现的 RedisSocialTokenStore 注入框架（全局静态生效）。
 *
 * <p>框架 fw-plus-auth-social 为纯库，不自动注册任何 Spring bean，全部由本配置手工装配。
 * 未启用 Redis 时 AbstractSocialLoginProvider 自动使用自带 Guava 内存实现。</p>
 */
@Configuration
public class SocialFrameworkConfig {

    @Bean
    public SocialLoginProviderRegistry socialLoginProviderRegistry() {
        SocialLoginProviderRegistry registry = new SocialLoginProviderRegistry();
        registry.register(new OidcSocialLoginProvider());
        registry.register(new CasSocialLoginProvider());
        registry.register(new Saml2SocialLoginProvider());
        registry.register(new WechatWorkSocialLoginProvider());
        registry.register(new DingTalkSocialLoginProvider());
        registry.register(new FeishuSocialLoginProvider());
        registry.register(new LarkSocialLoginProvider());
        registry.register(new LdapSocialLoginProvider());
        registry.register(new WechatScanSocialLoginProvider());
        return registry;
    }

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private SocialTokenStore redisTokenStore;

    @PostConstruct
    void init() {
        if (redisTemplate != null && redisTokenStore != null) {
            AbstractSocialLoginProvider.setTokenStore(redisTokenStore);
        }
        // 否则框架默认使用 LocalSocialTokenStore（Guava 内存），功能等价
    }
}
