package com.xiongx9527.askdata.common.cache;

/**
 * 缓存名常量（对齐 SQLBot 的 CacheNamespace / CacheName 语义）。
 *
 * <p>命名带上 {@code askdata:} 前缀，与 Redis 缓存键前缀（{@code askdata:cache:}）区分，
 * 避免与其它业务的键空间冲突。</p>
 */
public final class CacheNames {

    /** 用户信息（SQLBot {@code user:info}，key = 用户 id）。 */
    public static final String USER_INFO = "askdata:user:info";

    /** 用户信息（按 account 查，key = 账号）。 */
    public static final String USER_INFO_BY_ACCOUNT = "askdata:user:info:by-account";

    /** API Key 信息（SQLBot {@code ask:info}，key = access_key）。 */
    public static final String API_KEY_INFO = "askdata:apikey:info";

    /** 助手信息（SQLBot {@code assistant:info}，key = 助手 id）。 */
    public static final String ASSISTANT_INFO = "askdata:assistant:info";

    private CacheNames() {
    }
}
