package com.xiongx9527.askdata.system.sso;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.xiongx9527.askdata.system.authentication.LdapSsoClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LDAP 真 bind 端到端测试（验收 A2）。
 *
 * <p>用 UnboundID {@link InMemoryDirectoryServer} 起一个真实 LDAP 服务（真实 TCP 监听、
 * 随机端口、真实 LDAP 协议），驱动 {@link LdapSsoClient#loginWithPassword} 走完整链路：</p>
 * <ol>
 *   <li>服务账号 {@code cn=admin,dc=example,dc=com} bind 成功；</li>
 *   <li>按 {@code ou} + {@code user_filter} 在目录树中搜到用户 DN；</li>
 *   <li>以用户 DN + 口令做第二次真实 bind，错误口令必须被服务端拒绝；</li>
 *   <li>读取用户属性并按 mapping / 默认属性名映射为本地用户字段。</li>
 * </ol>
 *
 * <p>不依赖外部 LDAP 服务、不依赖环境变量，因此始终执行而不是 skip；
 * 服务端口为 0（由系统分配空闲端口），可与其他测试并行。</p>
 */
class LdapSsoRealBindE2ETest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final String ADMIN_DN = "cn=admin,dc=example,dc=com";
    private static final String ADMIN_PASSWORD = "admin-secret";
    private static final String PEOPLE_OU = "ou=people,dc=example,dc=com";
    private static final String ALICE_PASSWORD = "Passw0rd!";

    private static InMemoryDirectoryServer server;

    private final LdapSsoClient client = new LdapSsoClient();

    @BeforeAll
    static void startDirectoryServer() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        // 服务账号不落目录条目，作为额外 bind 凭据
        config.addAdditionalBindCredentials(ADMIN_DN, ADMIN_PASSWORD);
        // 测试用最小属性集，关闭 schema 校验避免 inetOrgPerson 必填属性干扰
        config.setSchema(null);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));

        server = new InMemoryDirectoryServer(config);
        server.startListening();

        server.add("dn: " + BASE_DN,
                "objectClass: top",
                "objectClass: domain",
                "dc: example");
        server.add("dn: " + PEOPLE_OU,
                "objectClass: top",
                "objectClass: organizationalUnit",
                "ou: people");
        server.add("dn: uid=alice," + PEOPLE_OU,
                "objectClass: top",
                "objectClass: person",
                "objectClass: organizationalPerson",
                "objectClass: inetOrgPerson",
                "uid: alice",
                "cn: Alice Zhang",
                "sn: Zhang",
                "mail: alice@example.com",
                "userPassword: " + ALICE_PASSWORD);
    }

    @AfterAll
    static void stopDirectoryServer() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    @Test
    void correctPasswordBindsAndMapsAttributes() {
        Map<String, Object> result = client.loginWithPassword(config(), "alice", ALICE_PASSWORD);

        assertNotNull(result, "登录结果不应为空");
        assertEquals("alice", result.get("platform_uid"), "platform_uid 应取登录名");
        assertEquals("alice", result.get("account"), "account 默认取 uid");
        assertEquals("Alice Zhang", result.get("name"), "name 默认取 cn");
        assertEquals("alice@example.com", result.get("email"), "email 默认取 mail");
    }

    @Test
    void wrongPasswordIsRejectedByRealBind() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.loginWithPassword(config(), "alice", "wrong-password"),
                "错误口令必须在真实 LDAP bind 阶段被拒绝");

        assertTrue(ex.getMessage().contains("LDAP bind failed"),
                "失败应来自用户 DN bind，而不是搜索阶段：" + ex.getMessage());
    }

    @Test
    void unknownUserIsRejectedBeforeBind() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.loginWithPassword(config(), "bob", ALICE_PASSWORD),
                "目录中不存在的用户应直接报未找到");

        assertTrue(ex.getMessage().contains("LDAP user not found"),
                "应报用户未找到：" + ex.getMessage());
    }

    @Test
    void validateSucceedsWithServiceAccount() {
        assertTrue(client.validate(config()), "服务账号 bind 应通过 validate");
    }

    private Map<String, Object> config() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("server_address", "ldap://127.0.0.1:" + server.getListenPort());
        config.put("bind_dn", ADMIN_DN);
        config.put("bind_pwd", ADMIN_PASSWORD);
        config.put("ou", PEOPLE_OU);
        config.put("user_filter", "(uid=?)");
        return config;
    }
}
