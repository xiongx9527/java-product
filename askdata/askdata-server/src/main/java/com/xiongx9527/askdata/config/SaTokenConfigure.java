package com.xiongx9527.askdata.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 鉴权配置（brief D10）：统一 Sa-Token（sa-token 1.46.0，经 fw-plus-auth-satoken 复用），
 * token 从 {@code X-SQLBOT-TOKEN: Bearer <token>} 读取（application.yaml 中
 * sa-token.token-name / token-prefix 配置）。
 *
 * 拦截范围：所有 {@code /api/v1/**} 业务接口；白名单与 SQLBot 后端 whitelist.py 语义对齐：
 * {@code /login/*} 全免鉴权（含 /login/access-token、/change-pwd、/logout），静态资源不在 /api/v1 下。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/login/**");
    }
}
