package com.xiongx9527.askdata.common.debounce;

/**
 * 防抖拦截异常：窗口内重复请求时由 {@link DebounceAspect} 抛出，
 * 由 {@code ApiExceptionHandler} 统一转为 HTTP 429（统一 Result 信封 + Retry-After）。
 * 可携带自定义提示（来自 {@link Debounce#message()}）与本次生效窗口（来自 {@link Debounce#windowMs()}），
 * 提示为空时回退 i18n 默认文案，窗口 &lt;=0 时由处理器回退全局窗口。
 */
public class DebounceBlockedException extends RuntimeException {

    private final String detailOverride;
    private final long windowMs;

    public DebounceBlockedException() {
        this(null, -1L);
    }

    public DebounceBlockedException(String detailOverride) {
        this(detailOverride, -1L);
    }

    /**
     * @param detailOverride 自定义拦截提示（可为空，由处理器回退 i18n 默认文案）
     * @param windowMs       本次生效的防抖窗口（毫秒，&lt;=0 表示由处理器回退全局窗口）
     */
    public DebounceBlockedException(String detailOverride, long windowMs) {
        super("debounce: too many requests");
        this.detailOverride = detailOverride;
        this.windowMs = windowMs;
    }

    /** 自定义拦截提示；为空表示使用默认文案。 */
    public String getDetailOverride() {
        return detailOverride;
    }

    /** 本次生效窗口（毫秒）；&lt;=0 表示未携带，由处理器回退全局窗口。 */
    public long getWindowMs() {
        return windowMs;
    }
}
