package com.xiongx9527.askdata.common.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 输入流辅助方法（补齐 Java 9 {@code InputStream.readAllBytes} 在 Java 8 上的缺失）。
 */
public final class Io {

    private Io() {
    }

    /** 读尽输入流并关闭；null 返回空数组。 */
    public static byte[] readAll(InputStream in) throws java.io.IOException {
        if (in == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } finally {
            in.close();
        }
    }

    /** 按 UTF-8 读尽输入流。 */
    public static String readAllUtf8(InputStream in) throws java.io.IOException {
        return new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
    }
}
