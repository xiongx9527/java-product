package com.xiongx9527.askdata.system.sso;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** 极简 JSON 解析工具（A7；仅依赖 Jackson）。 */
public final class SsoJson {

    private static final ObjectMapper M = new ObjectMapper();

    private SsoJson() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        try {
            return M.readValue(s, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("parse idp json failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String s) {
        try {
            return M.readValue(s, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("parse idp json failed: " + e.getMessage(), e);
        }
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : v.toString();
    }
}
