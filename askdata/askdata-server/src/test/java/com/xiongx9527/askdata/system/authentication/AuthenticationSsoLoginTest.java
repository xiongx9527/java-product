package com.xiongx9527.askdata.system.authentication;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.platform.EnterprisePlatformService;
import com.xiongx9527.askdata.system.platform.PlatformRepository;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A34：SSO 协议登录链路。
 *
 * <ul>
 *   <li>{@code GET /system/authentication/login/{type}}：OIDC/OAuth2/CAS/SAML2 返回可用授权跳转 URL，
 *       未配置/未启用时返回可读错误；</li>
 *   <li>{@code POST /system/authentication/sso/{type}}：回调换用户信息 → 本地建档/匹配 → 返回用户。</li>
 * </ul>
 */
class AuthenticationSsoLoginTest {

    /** insert 落库的本地用户（id → row），供 findById 回读。 */
    private final Map<Long, Map<String, Object>> users = new java.util.HashMap<>();

    private AuthenticationRepository repository;
    private UserRepository userRepository;
    private PlatformRepository platformRepository;
    private CasSsoClient casSsoClient;
    private LdapSsoClient ldapSsoClient;
    private Saml2SsoClient saml2SsoClient;
    private SsoAuthenticator ssoAuthenticator;
    private EnterprisePlatformService enterprisePlatformService;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuthenticationRepository.class);
        userRepository = mock(UserRepository.class);
        platformRepository = mock(PlatformRepository.class);
        casSsoClient = mock(CasSsoClient.class);
        ldapSsoClient = mock(LdapSsoClient.class);
        saml2SsoClient = mock(Saml2SsoClient.class);
        ssoAuthenticator = mock(SsoAuthenticator.class);
        enterprisePlatformService = mock(EnterprisePlatformService.class);
        service = new AuthenticationService(repository, userRepository, platformRepository,
                mock(CurrentUserService.class), ssoAuthenticator, casSsoClient, ldapSsoClient,
                saml2SsoClient, mock(IdGenerator.class), enterprisePlatformService);
    }

    /** 认证源 config JSON（client_id / redirect_uri 与前端 Editor 表单一致）。 */
    private static String configJson() {
        char q = (char) 34;
        return new StringBuilder()
                .append('{').append(q).append("client_id").append(q).append(':')
                .append(q).append("cid").append(q).append(',')
                .append(q).append("redirect_uri").append(q).append(':')
                .append(q).append("https://app/cb").append(q)
                .append('}').toString();
    }

    private Map<String, Object> source(int type, boolean enable) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", (long) type);
        row.put("name", "type-" + type);
        row.put("type", type);
        row.put("config", configJson());
        row.put("enable", enable);
        row.put("valid", true);
        return row;
    }

    @Test
    void toLoginReturnsCasAuthorizeUrl() {
        when(repository.findByType(SsoAuthenticator.TYPE_CAS)).thenReturn(Optional.of(source(4, true)));
        when(casSsoClient.authorizeUrl(any(), any())).thenReturn("https://cas.example/login?service=x");

        assertEquals("https://cas.example/login?service=x", service.toLogin(SsoAuthenticator.TYPE_CAS));
    }

    @Test
    void toLoginReturnsSaml2AuthorizeUrl() {
        when(repository.findByType(SsoAuthenticator.TYPE_SAML2)).thenReturn(Optional.of(source(5, true)));
        when(saml2SsoClient.authorizeUrl(any(), any())).thenReturn("https://idp.example/saml?sso=1");

        assertEquals("https://idp.example/saml?sso=1", service.toLogin(SsoAuthenticator.TYPE_SAML2));
    }

    @Test
    void toLoginDelegatesOidcToJustAuth() {
        when(repository.findByType(SsoAuthenticator.TYPE_OIDC)).thenReturn(Optional.of(source(1, true)));
        when(ssoAuthenticator.authorizeUrl(eq(SsoAuthenticator.TYPE_OIDC), any(), any()))
                .thenReturn("https://idp.example/authorize?client_id=cid");

        assertEquals("https://idp.example/authorize?client_id=cid",
                service.toLogin(SsoAuthenticator.TYPE_OIDC));
    }

    @Test
    void toLoginRejectsDisabledSourceReadably() {
        when(repository.findByType(SsoAuthenticator.TYPE_CAS)).thenReturn(Optional.of(source(4, false)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.toLogin(SsoAuthenticator.TYPE_CAS));
        assertTrue(e.getMessage().contains("disabled"), e.getMessage());
        verify(casSsoClient, org.mockito.Mockito.never()).authorizeUrl(any(), any());
    }

    @Test
    void toLoginRejectsMissingSourceReadably() {
        when(repository.findByType(SsoAuthenticator.TYPE_OIDC)).thenReturn(Optional.empty());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.toLogin(SsoAuthenticator.TYPE_OIDC));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }

    @Test
    void toLoginRejectsLdapRedirectReadibly() {
        when(repository.findByType(SsoAuthenticator.TYPE_LDAP)).thenReturn(Optional.of(source(3, true)));
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> service.toLogin(SsoAuthenticator.TYPE_LDAP));
        assertTrue(e.getMessage().contains("LDAP"), e.getMessage());
    }

    @Test
    void ssoLoginBindsPlatformUserAndCreatesLocalUser() {
        when(repository.findByType(SsoAuthenticator.TYPE_LDAP)).thenReturn(Optional.of(source(3, true)));
        when(ldapSsoClient.loginWithPassword(any(), eq("alice"), eq("Passw0rd!")))
                .thenReturn(Rows.of("platform_uid", "alice", "account", "alice", "name", "Alice",
                        "email", "alice@a.com"));
        when(platformRepository.findBinding(anyInt(), anyString())).thenReturn(Optional.empty());
        when(userRepository.findByAccount("alice")).thenReturn(Optional.empty());
        when(userRepository.oidListOf(1L)).thenReturn(Collections.singletonList(9L));
        org.mockito.Mockito.doAnswer(invocation -> {
            long id = invocation.getArgument(0, Long.class);
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("id", id);
            created.put("account", "alice");
            created.put("oid", 9L);
            created.put("status", 1);
            users.put(id, created);
            return null;
        }).when(userRepository).insert(anyLong(), eq("alice"), anyString(), anyString(), anyString(), eq(9L),
                anyInt(), anyLong(), anyString(), anyInt(), any());
        org.mockito.Mockito.lenient().when(userRepository.findById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0, Long.class);
            return java.util.Optional.ofNullable(users.get(id));
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "alice");
        body.put("password", "Passw0rd!");
        Map<String, Object> user = service.ssoLogin(SsoAuthenticator.TYPE_LDAP, body);

        assertEquals("alice", user.get("account"));
        verify(platformRepository).upsertBinding(anyLong(), anyLong(), eq(SsoAuthenticator.TYPE_LDAP),
                eq("alice"));
    }

    @Test
    void ssoLoginRejectsDisabledSourceReadibly() {
        when(repository.findByType(SsoAuthenticator.TYPE_LDAP)).thenReturn(Optional.of(source(3, false)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "alice");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.ssoLogin(SsoAuthenticator.TYPE_LDAP, body));
        assertTrue(e.getMessage().contains("disabled"), e.getMessage());
    }

    @Test
    void ssoLoginRejectsMissingAccountReadibly() {
        when(repository.findByType(SsoAuthenticator.TYPE_LDAP)).thenReturn(Optional.of(source(3, true)));
        when(ldapSsoClient.loginWithPassword(any(), eq("bob"), eq("x")))
                .thenReturn(Rows.of("platform_uid", "bob"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "bob");
        body.put("password", "x");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.ssoLogin(SsoAuthenticator.TYPE_LDAP, body));
        assertTrue(e.getMessage().contains("account"), e.getMessage());
    }

    @Test
    void ssoLoginRejectsDisabledLocalUser() {
        when(repository.findByType(SsoAuthenticator.TYPE_LDAP)).thenReturn(Optional.of(source(3, true)));
        when(ldapSsoClient.loginWithPassword(any(), eq("carol"), eq("x")))
                .thenReturn(Rows.of("platform_uid", "carol", "account", "carol"));
        when(platformRepository.findBinding(anyInt(), anyString())).thenReturn(Optional.of(
                Collections.singletonMap("uid", 42L)));
        when(userRepository.findById(42L)).thenReturn(Optional.of(Rows.of("id", 42L, "account", "carol",
                "status", 0, "oid", 9L)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "carol");
        body.put("password", "x");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.ssoLogin(SsoAuthenticator.TYPE_LDAP, body));
        assertTrue(e.getMessage().contains("disabled"), e.getMessage());
    }

    @Test
    void ssoLoginDelegatesEnterpriseImToPlatformUserByCode() {
        when(repository.findByType(EnterprisePlatformService.ORIGIN_WECOM))
                .thenReturn(Optional.of(source(6, true)));
        when(enterprisePlatformService.platformUserByCode(eq(EnterprisePlatformService.ORIGIN_WECOM), any()))
                .thenReturn(Rows.of("platform_uid", "wx-1", "account", "wxuser", "name", "Wx User"));
        when(platformRepository.findBinding(anyInt(), anyString())).thenReturn(Optional.of(
                Collections.singletonMap("uid", 42L)));
        when(userRepository.findById(42L)).thenReturn(Optional.of(Rows.of("id", 42L, "account", "wxuser",
                "status", 1, "oid", 9L)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "auth-code");
        Map<String, Object> user = service.ssoLogin(EnterprisePlatformService.ORIGIN_WECOM, body);
        assertEquals("wxuser", user.get("account"));
        verify(enterprisePlatformService).platformUserByCode(eq(EnterprisePlatformService.ORIGIN_WECOM),
                eq(body));
    }
}
