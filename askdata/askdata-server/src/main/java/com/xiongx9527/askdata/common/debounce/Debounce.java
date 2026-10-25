package com.xiongx9527.askdata.common.debounce;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口防抖注解：标注在控制器方法上，使该方法在防抖窗口内对同一维度只放行一次，
 * 重复请求返回 429（Retry-After）。主要用于数据更新/写入接口防重复提交。
 *
 * <p>防抖键由「接口 + 可选当前用户 + 可选接口参数 + 可选 SpEL 表达式」组成：
 * <ul>
 *   <li>{@link #user()}：是否把当前登录用户纳入键（可选，取不到时回退 anon）；</li>
 *   <li>{@link #args()}：纳入键的方法参数（参数名或索引 0/#0/p0，'*' 表示全部）；</li>
 *   <li>{@link #spel()}：SpEL 表达式列表，对方法参数（#p0..#pn、#a0..#an、参数名）与 #user 求值，结果追加进键，提供更灵活的拼装。</li>
 *   <li>{@link #windowMs()}：本接口独立的防抖窗口（毫秒）；缺省 -1 表示沿用全局 askdata.debounce.window-ms。</li>
 * </ul>
 * 全局开关与默认窗口由 askdata.debounce.enabled / window-ms 控制。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Debounce {
    /** 是否把当前登录用户纳入键；取不到用户时回退 anon。缺省包含。 */
    boolean user() default true;

    /** 纳入键的方法参数选择：参数名或索引（0 / #0 / p0）；'*' 表示全部参数；为空表示不含参数。 */
    String[] args() default {};

    /** SpEL 表达式列表，对方法参数（#p0..#pn、#a0..#an、参数名）与 #user（当前用户）求值，结果追加进键。 */
    String[] spel() default {};

    /** 自定义拦截提示；为空时使用 i18n 默认文案（i18n_debounce.too_frequent）。 */
    String message() default "";

    /**
     * 本接口独立的防抖窗口（毫秒）。
     * 取值 &gt; 0 时覆盖全局 askdata.debounce.window-ms；&lt;= 0（含缺省 -1）时沿用全局窗口。
     * 上限受 {@code DebounceProperties.MAX_WINDOW_MS} 约束。
     */
    long windowMs() default -1L;
}
