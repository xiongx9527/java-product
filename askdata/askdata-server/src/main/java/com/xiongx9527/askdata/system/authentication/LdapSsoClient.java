package com.xiongx9527.askdata.system.authentication;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LDAP 认证源客户端。
 *
 * <p>两段：</p>
 * <ol>
 *   <li>用配置的服务账号 bind 到 LDAP（{@code server_address} / {@code bind_dn} / {@code bind_pwd}）；</li>
 *   <li>按 {@code ou} + {@code user_filter} 查用户，读取属性并映射为本地用户字段。</li>
 * </ol>
 *
 * <p>config 字段（对齐前端 {@code views/system/authentication/LdapEditor.vue}）：
 * {@code server_address} / {@code bind_dn} / {@code bind_pwd} / {@code ou} / {@code user_filter} / {@code mapping}。</p>
 *
 * <p>LDAP/JNDI 是 JDK 内置能力，无需额外依赖。</p>
 */
public class LdapSsoClient implements SsoClient {

    @Override
    public String authorizeUrl(Map<String, Object> config, SsoCallback callback) {
        // LDAP 是直连协议，没有浏览器跳转阶段；前端 LdapLoginForm 直接提交账号口令到
        // POST /system/authentication/sso/3。
        return null;
    }

    @Override
    public Map<String, Object> exchange(Map<String, Object> config, SsoCallback callback) {
        throw new UnsupportedOperationException(
                "LDAP login uses POST /system/authentication/sso/{type} with username/password");
    }

    /**
     * 账号口令登录：先以服务账号 bind，再以用户 DN bind 校验口令，最后取回用户属性。
     *
     * @param config   认证源配置
     * @param username 用户名
     * @param password 口令
     */
    public Map<String, Object> loginWithPassword(Map<String, Object> config, String username, String password) {
        String serverAddress = str(config, "server_address");
        String bindDn = str(config, "bind_dn");
        String bindPwd = str(config, "bind_pwd");
        String ou = str(config, "ou");
        String userFilter = str(config, "user_filter");
        if (serverAddress == null || bindDn == null) {
            throw new IllegalStateException("Missing server_address or bind_dn");
        }

        // 1) 服务账号 bind，定位用户 DN
        DirContext serviceContext = null;
        DirContext userContext = null;
        try {
            serviceContext = bind(serverAddress, bindDn, bindPwd);
            String userDn = findUserDn(serviceContext, ou, userFilter, username);
            if (userDn == null) {
                throw new IllegalStateException("LDAP user not found: " + username);
            }
            // 2) 以用户 DN bind 校验口令
            userContext = bind(serverAddress, userDn, password);
            // 3) 取回属性
            Map<String, Object> attributes = readAttributes(userContext, userDn);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("platform_uid", username);
            result.put("account", pick(attributes, config, "account", "uid", "cn", "sAMAccountName", username));
            result.put("name", pick(attributes, config, "name", "cn", "displayName", "realName", username));
            result.put("email", pick(attributes, config, "email", "mail", "emailAddress", null));
            return result;
        } finally {
            close(userContext);
            close(serviceContext);
        }
    }

    @Override
    public boolean validate(Map<String, Object> config) {
        String serverAddress = str(config, "server_address");
        String bindDn = str(config, "bind_dn");
        String bindPwd = str(config, "bind_pwd");
        if (serverAddress == null || bindDn == null) {
            throw new IllegalStateException("missing server_address / bind_dn in LDAP config");
        }
        DirContext context = null;
        try {
            context = bind(serverAddress, bindDn, bindPwd);
            return true;
        } finally {
            close(context);
        }
    }

    private DirContext bind(String serverAddress, String principal, String credentials) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, serverAddress);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, credentials);
        try {
            return new InitialDirContext(env);
        } catch (javax.naming.NamingException e) {
            throw new IllegalStateException("LDAP bind failed: " + e.getMessage(), e);
        }
    }

    /** 按 ou + user_filter 搜索用户 DN。 */
    private String findUserDn(DirContext context, String ou, String userFilter, String username) {
        String base = ou == null || ou.isEmpty() ? "" : ou;
        String filter = userFilter == null || userFilter.isEmpty()
                ? "(uid=" + escape(username) + ")"
                : userFilter.replace("?", escape(username));
        javax.naming.directory.SearchControls controls =
                new javax.naming.directory.SearchControls();
        controls.setSearchScope(javax.naming.directory.SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(0);
        controls.setReturningAttributes(null);
        NamingEnumeration<javax.naming.directory.SearchResult> results = null;
        try {
            results = context.search(base, filter, controls);
            if (results.hasMore()) {
                return results.next().getNameInNamespace();
            }
        } catch (Exception ignored) {
            // 搜索失败时按约定 DN 兜底
        } finally {
            close(results);
        }
        return null;
    }

    private Map<String, Object> readAttributes(DirContext context, String userDn) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        try {
            Attributes attrs = context.getAttributes(userDn);
            javax.naming.NamingEnumeration<? extends Attribute> names = attrs.getAll();
            while (names.hasMore()) {
                Attribute attribute = names.next();
                Object value = attribute.get();
                if (value != null) {
                    attributes.put(attribute.getID(), value instanceof byte[]
                            ? new String((byte[]) value, java.nio.charset.StandardCharsets.UTF_8)
                            : String.valueOf(value));
                }
            }
        } catch (Exception ignored) {
            // 属性读取失败时返回已取到的部分
        }
        return attributes;
    }

    private String pick(Map<String, Object> attributes, Map<String, Object> config, String localKey,
                        String... candidates) {
        Object mapping = config.get("mapping");
        if (mapping != null) {
            try {
                Map<?, ?> map = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(String.valueOf(mapping), Map.class);
                Object mapped = map.get(localKey);
                if (mapped != null) {
                    Object value = attributes.get(String.valueOf(mapped));
                    if (value != null) {
                        return String.valueOf(value);
                    }
                }
            } catch (Exception ignored) {
                // mapping 非合法 JSON 时按默认顺序取值
            }
        }
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Object value = attributes.get(candidate);
            if (value != null && !String.valueOf(value).isEmpty()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\5c").replace("*", "\\2a")
                .replace("(", "\\28").replace(")", "\\29");
    }

    private static void close(Object closeable) {
        if (closeable instanceof DirContext) {
            try {
                ((DirContext) closeable).close();
            } catch (Exception ignored) {
                // 关闭失败不影响主流程
            }
        } else if (closeable instanceof NamingEnumeration) {
            try {
                ((NamingEnumeration<?>) closeable).close();
            } catch (Exception ignored) {
                // 关闭失败不影响主流程
            }
        }
    }

    static String str(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
