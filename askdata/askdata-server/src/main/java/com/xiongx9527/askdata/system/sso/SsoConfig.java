package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.askdata.system.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** SSO 相关 Bean 装配（A7）。 */
@Configuration
public class SsoConfig {

    @Bean
    public ExternalIdpService externalIdpService() {
        return new ExternalIdpService();
    }

    @Bean
    public LocalAccountLinker localAccountLinker(UserService userService) {
        return new LocalAccountLinker(userService, 1L);
    }

    /** 授权请求 state 存储（A7：一次性、限时，防回调 CSRF）。 */
    @Bean
    public IdpStateStore idpStateStore() {
        return new IdpStateStore();
    }
}
