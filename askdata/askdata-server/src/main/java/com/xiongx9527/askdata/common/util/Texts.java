package com.xiongx9527.askdata.common.util;

/**
 * 字符串辅助方法（补齐 Java 11 {@code String.repeat} 在 Java 8 上的缺失）。
 */
public final class Texts {

    private Texts() {
    }

    /** 重复字符串 count 次；count <= 0 返回空串。 */
    public static String repeat(String s, int count) {
        if (s == null || count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
