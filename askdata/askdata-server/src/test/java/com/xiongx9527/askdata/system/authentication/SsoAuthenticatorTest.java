package com.xiongx9527.askdata.system.authentication;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Maps;
import me.zhyd.oauth.model.AuthUser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 第三方用户信息 → 本地用户字段的映射（账号取登录名，不能取显示名）。
 *
 * <p>回归点：MaxKey 的 OIDC userinfo 把 {@code name} 与 {@code preferred_username} 都填成
 * displayName（如“孙尚香”），真正的登录名在 {@code user} 声明里（如 {@code sunsx}）；
 * 之前账号、邮箱都被写成了显示名。</p>
 */
class SsoAuthenticatorTest {

    private final SsoAuthenticator authenticator = new SsoAuthenticator(new ObjectMapper());

    @Test
    void maxkeyOidcUsesLoginNameAsAccountNotDisplayName() {
        JSONObject raw = new JSONObject();
        raw.put("sub", "sunsx");
        raw.put("userId", "622227747934179401");
        raw.put("user", "sunsx");
        raw.put("name", "孙尚香");
        raw.put("preferred_username", "孙尚香");
        AuthUser authUser = AuthUser.builder()
                .uuid("sunsx")
                .username("孙尚香")
                .nickname("孙尚香")
                .rawUserInfo(raw)
                .build();

        Map<String, Object> user = authenticator.toLocalUser(authUser, Maps.of());
        assertEquals("sunsx", user.get("account"));
        assertEquals("孙尚香", user.get("name"));
        assertNull(user.get("email"), "IdP 没有邮箱时不能拿显示名/用户名冒充邮箱");
        assertEquals("sunsx", user.get("platform_uid"));
    }

    @Test
    void standardOidcPrefersPreferredUsername() {
        JSONObject raw = new JSONObject();
        raw.put("sub", "u-1");
        raw.put("preferred_username", "alice");
        raw.put("name", "Alice Zhang");
        raw.put("email", "alice@example.com");
        AuthUser authUser = AuthUser.builder()
                .uuid("u-1")
                .username("alice")
                .nickname("Alice Zhang")
                .rawUserInfo(raw)
                .build();

        Map<String, Object> user = authenticator.toLocalUser(authUser, Maps.of());
        assertEquals("alice", user.get("account"));
        assertEquals("Alice Zhang", user.get("name"));
        assertEquals("alice@example.com", user.get("email"));
        assertEquals("u-1", user.get("platform_uid"));
    }

    @Test
    void mappingOverridesDefaultAttributeResolution() {
        JSONObject raw = new JSONObject();
        raw.put("sub", "u-1");
        raw.put("preferred_username", "alice");
        raw.put("email", "alice@example.com");
        AuthUser authUser = AuthUser.builder().uuid("u-1").rawUserInfo(raw).build();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mapping", "{\"account\":\"email\"}");
        Map<String, Object> user = authenticator.toLocalUser(authUser, config);
        assertEquals("alice@example.com", user.get("account"));
    }
}
