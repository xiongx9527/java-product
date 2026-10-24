package com.xiongx9527.askdata.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JdbcTemplate 结果行 / 请求 JSON Map 的取值工具。
 *
 * <p>取列时对大小写不敏感：H2 在不同兼容模式下可能返回小写或大写列名，
 * 本工具按原样、大写两种顺序查找，避免依赖具体数据库的标识符折叠策略。</p>
 */
public final class Rows {

    private Rows() {
    }

    /**
     * 复制一份行数据并把 key 统一小写（缓存边界归一化）。
     *
     * <p>JDBC 的 {@code queryForList} 返回 {@code LinkedCaseInsensitiveMap}（key 大写、忽略大小写），
     * 直查时取值不受影响；但经缓存（尤其 Redis 的 JSON 往返）后会退化为普通
     * {@code LinkedHashMap}，此时 {@code get("password")} 之类的小写直取会静默返回 null。
     * 故缓存读写统一归一为小写 key，使缓存命中与直查两条路径的取值语义一致。</p>
     */
    public static Map<String, Object> lowerCaseKeys(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return row;
        }
        Map<String, Object> out = new LinkedHashMap<>(row.size() * 2);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return out;
    }

    public static Object get(Map<?, ?> row, String key) {
        if (row == null || key == null) {
            return null;
        }
        if (row.containsKey(key)) {
            return row.get(key);
        }
        String upper = key.toUpperCase();
        if (row.containsKey(upper)) {
            return row.get(upper);
        }
        String lower = key.toLowerCase();
        if (row.containsKey(lower)) {
            return row.get(lower);
        }
        for (Map.Entry<?, ?> entry : row.entrySet()) {
            if (entry.getKey() != null && key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static String str(Map<?, ?> row, String key) {
        Object value = get(row, key);
        return value == null ? null : String.valueOf(value);
    }

    public static long lng(Map<?, ?> row, String key, long defaultValue) {
        Object value = get(row, key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : Long.parseLong(text);
    }

    public static Long lngOrNull(Map<?, ?> row, String key) {
        Object value = get(row, key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.parseLong(text);
    }

    public static Integer intg(Map<?, ?> row, String key) {
        Long value = lngOrNull(row, key);
        return value == null ? null : value.intValue();
    }

    public static int intg(Map<?, ?> row, String key, int defaultValue) {
        Integer value = intg(row, key);
        return value == null ? defaultValue : value;
    }

    public static boolean bool(Map<?, ?> row, String key, boolean defaultValue) {
        Object value = get(row, key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    /** JSON 数组 → List<Long>；元素可为数字或数字字符串（SQLBot Path/Body 混合场景）。 */
    public static List<Long> longList(Object value) {
        List<Long> result = new ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) value) {
            if (item == null) {
                continue;
            }
            if (item instanceof Number) {
                result.add(((Number) item).longValue());
            } else {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    result.add(Long.parseLong(text));
                }
            }
        }
        return result;
    }

    /** JSON 数组 → List<String>（ws_mapping 接口收发 string 形式的 id）。 */
    public static List<String> strList(Object value) {
        List<String> result = new ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) value) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /** JSON 数组 → List<Integer>（Query 参数 origins/oidlist）。 */
    public static List<Integer> intList(Object value) {
        List<Integer> result = new ArrayList<>();
        for (Long item : longList(value)) {
            result.add(item.intValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapList(Object value) {
        if (value instanceof List) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    /** 构造保持插入顺序的响应对象（字段名与 SQLBot schema 逐字一致）。 */
    public static Map<String, Object> of(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
