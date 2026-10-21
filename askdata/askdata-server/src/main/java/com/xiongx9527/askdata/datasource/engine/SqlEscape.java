package com.xiongx9527.askdata.datasource.engine;

/**
 * SQL 值转义与逻辑运算符白名单（对齐 SQLBot {@code apps/datasource/crud/row_permission.py}
 * 的 {@code _escape_sql_value} 与 {@code _VALID_LOGIC_OPS}）。
 *
 * <p>转义顺序与 SQLBot 逐字一致：先对单引号做加倍（{@code '} → {@code ''}），
 * 再对反斜杠做加倍。该顺序保证注入载荷中的引号被锁定在字符串字面量内，
 * 是 CWE-89 修复的语义基线（见 SQLBot {@code test_cwe89_escape_fix.py}）。</p>
 *
 * <p>逻辑运算符白名单仅允许 {@code AND}/{@code OR}（大小写不敏感），用于 expression_tree 的
 * {@code logic} 字段校验，防止攻击者通过逻辑字段注入 {@code ' AND 1=1) UNION SELECT ...'} 之类片段。</p>
 */
public final class SqlEscape {

    /** 反斜杠字符（用码点 92 构造，避免源码中出现字面量反斜杠）。 */
    private static final String BACKSLASH = Character.toString((char) 92);

    /** 允许的逻辑运算符（与 SQLBot {@code _VALID_LOGIC_OPS} 一致）。 */
    private static final java.util.Set<String> VALID_LOGIC_OPS =
            java.util.Collections.unmodifiableSet(
                    new java.util.HashSet<>(java.util.Arrays.asList("AND", "OR")));

    private SqlEscape() {
    }

    /**
     * 转义 SQL 字符串字面量里的值（对齐 SQLBot {@code _escape_sql_value}）。
     *
     * @param value 待转义的值（非字符串会被 {@code String.valueOf} 强制为字符串）
     * @return 转义后的值；{@code null} 入参原样返回 {@code null}
     */
    public static String escapeValue(Object value) {
        if (value == null) {
            return null;
        }
        String escaped = String.valueOf(value);
        // 顺序必须与 SQLBot 一致：先引号加倍，再反斜杠加倍
        escaped = escaped.replace("'", "''");
        escaped = escaped.replace(BACKSLASH, BACKSLASH + BACKSLASH);
        return escaped;
    }

    /**
     * 逻辑运算符白名单校验（对齐 SQLBot {@code _VALID_LOGIC_OPS}）。
     *
     * @param op 待校验的运算符（可能为 null）
     * @return 仅 {@code AND}/{@code OR}（大小写不敏感）返回 true
     */
    public static boolean isValidLogicOp(String op) {
        if (op == null) {
            return false;
        }
        return VALID_LOGIC_OPS.contains(op.toUpperCase(java.util.Locale.ROOT));
    }
}
