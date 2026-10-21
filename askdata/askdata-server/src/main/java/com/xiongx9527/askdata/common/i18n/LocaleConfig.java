package com.xiongx9527.askdata.common.i18n;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * 多语言解析配置（A9：对齐 SQLBot 五语言 zh-CN/zh-TW/en/ko-KR/th-TH）。
 * <ul>
 *   <li>缺省语言中文（zh-CN），支持 {@code ?lang=zh-CN|zh-TW|en|ko-KR|th-TH} 参数切换；</li>
 *   <li>无 lang 参数时按 {@code Accept-Language} 自动切换；</li>
 *   <li>LocaleChangeInterceptor 将 lang 写入 Cookie，跨请求保持。</li>
 * </ul>
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public AskdataLocaleResolver localeResolver() {
        return new AskdataLocaleResolver();
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
