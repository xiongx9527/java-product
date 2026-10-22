package com.xiongx9527.askdata.system.sso;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权请求 state 存储（A7）：服务端签发、一次性消费、限时有效。
 *
 * <p>state 不再由调用方自由传入——{@code /sso/{type}/authorize} 自行签发并下发，回调必须带回
 * 同一个未过期、未被使用过、且协议匹配的 state，否则拒绝。缺少该校验时任何人都可以伪造
 * 回调请求（登录 CSRF），把受害者会话绑定到攻击者的外部身份上。</p>
 */
public class IdpStateStore {

    private static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> states = new ConcurrentHashMap<String, Entry>();

    private final long ttlMillis;

    public IdpStateStore() {
        this(DEFAULT_TTL_MILLIS);
    }

    public IdpStateStore(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** 为指定协议签发一次性 state。 */
    public String issue(IdpType type) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        String state = sb.toString();
        states.put(state, new Entry(type, System.currentTimeMillis() + ttlMillis));
        purge();
        return state;
    }

    /**
     * 校验并消费 state（一次性）。
     *
     * @throws IllegalStateException state 缺失、未知、已使用、已过期或协议不匹配
     */
    public void consume(IdpType type, String state) {
        if (state == null || state.trim().isEmpty()) {
            throw new IllegalStateException("sso callback missing state");
        }
        Entry entry = states.remove(state.trim());
        if (entry == null) {
            throw new IllegalStateException("sso state is unknown or already used");
        }
        if (System.currentTimeMillis() > entry.expiresAt) {
            throw new IllegalStateException("sso state expired");
        }
        if (entry.type != type) {
            throw new IllegalStateException("sso state does not match idp type");
        }
    }

    private void purge() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> now > e.getValue().expiresAt);
    }

    private static final class Entry {

        private final IdpType type;

        private final long expiresAt;

        private Entry(IdpType type, long expiresAt) {
            this.type = type;
            this.expiresAt = expiresAt;
        }
    }
}

