package com.xiongx9527.askdata.system.authentication;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.platform.EnterprisePlatformService;
import com.xiongx9527.askdata.system.platform.PlatformRepository;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A25：认证源列表/详情仅管理员可见（认证源 config 含 IdP 密钥等敏感信息）。
 *
 * <p>回归点：非管理员调 {@code GET /system/authentication} 若不加校验会泄露 CAS/OIDC/LDAP
 * 的服务端密钥与回跳地址。</p>
 */
class AuthenticationServiceAdminGuardTest {

    private AuthenticationRepository repository;
    private CurrentUserService currentUserService;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuthenticationRepository.class);
        currentUserService = mock(CurrentUserService.class);
        service = new AuthenticationService(repository, mock(UserRepository.class),
                mock(PlatformRepository.class), currentUserService, mock(SsoAuthenticator.class),
                mock(CasSsoClient.class), mock(LdapSsoClient.class), mock(Saml2SsoClient.class),
                mock(IdGenerator.class), mock(EnterprisePlatformService.class));
    }

    @Test
    void nonAdminCannotListOrReadAuthenticationSources() {
        doThrow(new BadRequestException("perm.admin.only")).when(currentUserService).requireAdmin();

        assertThrows(BadRequestException.class, () -> service.list());
        assertThrows(BadRequestException.class, () -> service.getById(1L));

        // 敏感数据绝不能在被拒绝之后仍然被读取
        verify(repository, never()).list();
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void adminCanListAuthenticationSources() {
        when(repository.list()).thenReturn(Collections.singletonList(
                java.util.Collections.singletonMap("id", 1L)));
        when(repository.findById(1L)).thenReturn(Optional.of(java.util.Collections.singletonMap("id", 1L)));

        assertEquals(1, service.list().size());
        assertEquals(1L, service.getById(1L).get("id"));
        verify(currentUserService, org.mockito.Mockito.times(2)).requireAdmin();
    }
}
