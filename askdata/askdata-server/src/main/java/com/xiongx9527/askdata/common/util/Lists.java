package com.xiongx9527.askdata.common.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Java 9 {@code List.of} 的 Java 8 等价实现（不可变）。
 */
public final class Lists {

    private Lists() {
    }

    /** 空 List。 */
    public static <T> List<T> of() {
        return Collections.emptyList();
    }

    /** 由给定元素构造不可变 List。 */
    @SafeVarargs
    public static <T> List<T> of(T... items) {
        List<T> copy = new ArrayList<T>(Arrays.asList(items));
        return Collections.unmodifiableList(copy);
    }
}
