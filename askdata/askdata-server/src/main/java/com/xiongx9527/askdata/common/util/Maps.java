package com.xiongx9527.askdata.common.util;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java 9 {@code Map.of} 的 Java 8 等价实现（不可变、保留插入顺序）。
 *
 * <p>方法签名与 {@code Map.of} 对齐，便于在 Java 8 平台上平替调用点。</p>
 */
public final class Maps {

    private Maps() {
    }

    /** 空 Map。 */
    public static <K, V> Map<K, V> of() {
        return Collections.emptyMap();
    }

    /** 1 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        return Collections.unmodifiableMap(m);
    }

    /** 2 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        return Collections.unmodifiableMap(m);
    }

    /** 3 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return Collections.unmodifiableMap(m);
    }

    /** 4 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        return Collections.unmodifiableMap(m);
    }

    /** 5 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        m.put(k5, v5);
        return Collections.unmodifiableMap(m);
    }

    /** 6 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        m.put(k5, v5);
        m.put(k6, v6);
        return Collections.unmodifiableMap(m);
    }

    /** 7 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        m.put(k5, v5);
        m.put(k6, v6);
        m.put(k7, v7);
        return Collections.unmodifiableMap(m);
    }

    /** 8 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        m.put(k5, v5);
        m.put(k6, v6);
        m.put(k7, v7);
        m.put(k8, v8);
        return Collections.unmodifiableMap(m);
    }

    /** 9 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        m.put(k5, v5);
        m.put(k6, v6);
        m.put(k7, v7);
        m.put(k8, v8);
        m.put(k9, v9);
        return Collections.unmodifiableMap(m);
    }

    /** 10 组键值对的不可变 Map。 */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        m.put(k5, v5);
        m.put(k6, v6);
        m.put(k7, v7);
        m.put(k8, v8);
        m.put(k9, v9);
        m.put(k10, v10);
        return Collections.unmodifiableMap(m);
    }

    /** 超过 10 组键值对时的通用入口。 */
    public static <K, V> Map<K, V> ofEntries(Map.Entry<K, V>... entries) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        for (Map.Entry<K, V> e : entries) {
            m.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(m);
    }

    /** 单条 entry（配合 ofEntries 使用）。 */
    public static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new AbstractMap.SimpleImmutableEntry<K, V>(key, value);
    }
}
