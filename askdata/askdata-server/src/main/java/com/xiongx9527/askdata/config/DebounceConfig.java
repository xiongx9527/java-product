package com.xiongx9527.askdata.config;

import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.fw.plus.common.debounce.DebounceBackendResolver;
import com.xiongx9527.fw.plus.common.debounce.DebounceUserResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口防抖的产品侧接线（防抖内核在框架 {@code fw-plus-boot}）。
 *
 * <ul>
 *   <li>{@link DebounceUserResolver}：当前用户取 {@link CurrentUserService#uid()}，
 *       兼容 Sa-Token 主通道与 API Key / 嵌入助手支线通道；</li>
 *   <li>{@link DebounceBackendResolver}：防抖后端跟随缓存后端（{@code askdata.cache.type}）——
 *       memory 为单实例占位，redis 为多实例原子占位。</li>
 * </ul>
 */
@Configuration
public class DebounceConfig {

    @Bean
    public DebounceUserResolver debounceUserResolver(CurrentUserService currentUserService) {
        return () -> String.valueOf(currentUserService.uid());
    }

    @Bean
    public DebounceBackendResolver debounceBackendResolver(CacheProperties cacheProperties) {
        return () -> CacheProperties.TYPE_REDIS.equals(cacheProperties.resolveType());
    }
}
