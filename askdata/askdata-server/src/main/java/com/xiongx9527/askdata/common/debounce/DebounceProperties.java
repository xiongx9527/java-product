package com.xiongx9527.askdata.common.debounce;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 接口防抖配置（{@code askdata.debounce.*}）。
 *
 * <ul>
 *   <li>{@code enabled}：是否启用（缺省关闭，避免改变既有行为）；</li>
 *   <li>{@code window-ms}：防抖窗口（毫秒，缺省 1000）——同一维度在该窗口内只放行一次，
 *       重复请求直接返回 429；同时作为底层缓存 TTL。</li>
 * </ul>
 *
 * <p>后端复用缓存层（{@code askdata.cache.type}：memory / redis / none），
 * 故多实例部署时用 redis 后端即可全局防抖。</p>
 *
 * <p>防抖的生效范围不再由路径配置，而是由控制器方法上的 {@link Debounce} 注解决定。</p>
 */
@Component
@ConfigurationProperties(prefix = "askdata.debounce")
public class DebounceProperties {

    /**
     * 单接口防抖窗口上限（毫秒）。注解 {@code @Debounce(windowMs=...)} 的取值会被收敛到此上限，
     * 以保证缓存占位（底层 TTL）足以覆盖逻辑窗口，避免提前过期导致误放行。
     */
    public static final long MAX_WINDOW_MS = 600_000L;

    /** 是否启用接口防抖。 */
    private boolean enabled = false;

    /** 防抖窗口（毫秒）。 */
    private long windowMs = 1000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getWindowMs() {
        return windowMs;
    }

    public void setWindowMs(long windowMs) {
        this.windowMs = windowMs;
    }
}
