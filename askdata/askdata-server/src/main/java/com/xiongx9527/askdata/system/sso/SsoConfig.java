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
}
