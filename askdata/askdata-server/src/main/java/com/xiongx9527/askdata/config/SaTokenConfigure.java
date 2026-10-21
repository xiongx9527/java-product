package com.xiongx9527.askdata.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.web.AuthChannelFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 鉴权配置（brief D10）：统一 Sa-Token（sa-token 1.46.0，经 fw-plus-auth-satoken 复用），
 * token 从 {@code X-SQLBOT-TOKEN: Bearer <token>} 读取（application.yaml 中
 * sa-token.token-name / token-prefix 配置）。
 *
 * <p>拦截范围：所有 {@code /api/v1/**} 业务接口。</p>
 *
 * <p>与 {@link AuthChannelFilter} 的分工（对齐 SQLBot TokenMiddleware 的三通道顺序）：</p>
 * <ul>
 *   <li>{@link AuthChannelFilter}（Servlet Filter，更早执行）负责：公共路径白名单放行、
 *       API Key（sk）通道、嵌入助手（assistant/embedded）通道；</li>
 *   <li>本拦截器负责主通道：请求若已被 {@link AuthChannelFilter} 放行（白名单或支线通道），
 *       则跳过登录校验；否则要求 Sa-Token 登录态。</li>
 * </ul>
 *
 * <p>白名单语义以 SQLBot {@code common/utils/whitelist.py} 的 33 条为准，
 * 在 {@link AuthChannelFilter} 内按 requestURI 原始路径判定（不再依赖本类的排除列表）。</p>
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    // 已被 AuthChannelFilter 放行（白名单 / API Key 通道 / 嵌入助手通道）：无需 Sa-Token 登录态。
                    if (isAllowedByChannelFilter()) {
                        return;
                    }
                    StpUtil.checkLogin();
                }))
                .addPathPatterns("/api/v1/**");
    }

    /** 从 Sa-Token 上下文取回当前 HttpServletRequest，判断是否已被 AuthChannelFilter 放行。 */
    private boolean isAllowedByChannelFilter() {
        try {
            Object source = cn.dev33.satoken.context.SaHolder.getRequest().getSource();
            if (!(source instanceof HttpServletRequest)) {
                return false;
            }
            return Boolean.TRUE.equals(
                    ((HttpServletRequest) source).getAttribute(AuthChannelFilter.ALLOWED_ATTRIBUTE));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
