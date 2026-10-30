package com.xiongx9527.askdata.common.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Java 9 {@code Set.of} 的 Java 8 等价实现（不可变、保留插入顺序）。
 */
public final class Sets {

    private Sets() {
    }

    /** 空 Set。 */
    public static <T> Set<T> of() {
        return Collections.emptySet();
    }

    /** 由给定元素构造不可变 Set。 */
    @SafeVarargs
    public static <T> Set<T> of(T... items) {
        return Collections.unmodifiableSet(new LinkedHashSet<T>(Arrays.asList(items)));
    }
}
