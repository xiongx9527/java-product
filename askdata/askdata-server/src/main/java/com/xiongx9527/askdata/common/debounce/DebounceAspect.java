package com.xiongx9527.askdata.common.debounce;

import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 接口防抖切面：拦截标注 {@link Debounce} 的方法，按注解维度构造键并交给
 * {@link RequestDebouncer} 原子判定；窗口内重复请求抛 {@link DebounceBlockedException}。
 *
 * <p>相比原路径过滤器，AOP 能直接拿到方法参数（用户要求按接口参数防抖），且能在
 * DispatcherServlet 内获得 Sa-Token / 支线 AuthContext 用户上下文。</p>
 */
@Aspect
@Component
@Order(50)
public class DebounceAspect {

    private static final Logger log = LoggerFactory.getLogger(DebounceAspect.class);

    private final RequestDebouncer debouncer;
    private final DebounceProperties properties;
    private final DebounceKeyBuilder keyBuilder;
    private final CurrentUserService currentUserService;

    public DebounceAspect(RequestDebouncer debouncer, DebounceProperties properties,
                         DebounceKeyBuilder keyBuilder, CurrentUserService currentUserService) {
        this.debouncer = debouncer;
        this.properties = properties;
        this.keyBuilder = keyBuilder;
        this.currentUserService = currentUserService;
    }

    @Around("@annotation(debounce)")
    public Object around(ProceedingJoinPoint pjp, Debounce debounce) throws Throwable {
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method method = ms.getMethod();
        Long uid = resolveUid();
        String key = keyBuilder.buildKey(method, pjp.getArgs(), debounce, uid);
        long windowMs = effectiveWindow(debounce, properties.getWindowMs());
        if (!debouncer.allow(key, windowMs)) {
            throw new DebounceBlockedException(debounce.message(), windowMs);
        }
        return pjp.proceed();
    }

    private Long resolveUid() {
        try {
            return currentUserService.uid();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算本次生效的防抖窗口（毫秒）。
     * 注解 windowMs &gt; 0 时使用注解值（收敛到上限），否则沿用全局窗口。
     */
    static long effectiveWindow(Debounce debounce, long globalWindowMs) {
        long w = debounce.windowMs();
        if (w <= 0L) {
            return globalWindowMs;
        }
        return Math.min(w, DebounceProperties.MAX_WINDOW_MS);
    }
}
