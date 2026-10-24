package com.xiongx9527.askdata.common.debounce;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 接口防抖配置（{@code askdata.debounce.*}）。
 *
 * <ul>
 *   <li>{@code enabled}：是否启用（缺省关闭，避免改变既有行为）；</li>
 *   <li>{@code window-ms}：防抖窗口（毫秒，缺省 1000）——同一「调用方 + 接口」在该窗口内
 *       只放行一次，重复请求直接返回 429；</li>
 *   <li>{@code paths}：生效的接口路径（Ant 风格，如 {@code /api/v1/chat/question}）；
 *       为空表示所有路径生效。</li>
 * </ul>
 *
 * <p>后端复用缓存层（{@code askdata.cache.type}：memory / redis / none），
 * 故多实例部署时用 redis 后端即可全局防抖。</p>
 */
@Component
@ConfigurationProperties(prefix = "askdata.debounce")
public class DebounceProperties {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 是否启用接口防抖。 */
    private boolean enabled = false;

    /** 防抖窗口（毫秒）。 */
    private long windowMs = 1000L;

    /** 生效的接口路径（Ant 风格）；为空表示全部。 */
    private List<String> paths = new ArrayList<>();

    /** 判断路径是否命中防抖范围。 */
    public boolean matches(String path) {
        if (paths == null || paths.isEmpty()) {
            return true;
        }
        if (path == null) {
            return false;
        }
        for (String pattern : paths) {
            if (pattern != null && !pattern.trim().isEmpty() && MATCHER.match(pattern.trim(), path)) {
                return true;
            }
        }
        return false;
    }

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

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }
}
