package com.xiongx9527.askdata.system.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSO 客户端 Bean 注册（brief D9）。
 *
 * <p>JustAuth 侧（OIDC / OAuth2）由 {@link SsoAuthenticator} 直接构造请求；
 * CAS / LDAP / SAML2 需要各自的协议客户端，这里注册为 Spring Bean 供
 * {@link AuthenticationService} 注入。</p>
 */
@Configuration
public class SsoClientConfig {

    @Bean
    public CasSsoClient casSsoClient(ObjectMapper objectMapper) {
        return new CasSsoClient(objectMapper);
    }

    @Bean
    public LdapSsoClient ldapSsoClient() {
        return new LdapSsoClient();
    }

    @Bean
    public Saml2SsoClient saml2SsoClient(ObjectMapper objectMapper) {
        return new Saml2SsoClient(objectMapper);
    }
}
