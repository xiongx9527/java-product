package com.xiongx9527.askdata.common.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 排序参数校验工具（对齐 SQLBot {@code common/core/pagination.py} 的 {@code order_by}/{@code desc}）。
 *
 * <p>{@code order_by} 会被拼进 ORDER BY 子句，故必须做<b>白名单校验</b>，
 * 否则存在 SQL 注入风险。</p>
 */
public final class OrderBySupport {

    /** 默认允许的排序列（各模块按需扩展）。 */
    private static final Set<String> DEFAULT_ALLOWED = new HashSet<>(Arrays.asList(
            "id", "create_time", "update_time", "name", "word", "question", "description",
            "sort", "sort_no", "latest_record_time", "start_time", "finish_time", "status"));

    private OrderBySupport() {
    }

    /**
     * 校验排序列是否在白名单内。
     *
     * @param orderBy         排序列（可空/空 → 不排序）
     * @param allowedColumns  允许的列名集合（传 null 时用默认白名单）
     * @return 合法时返回规范化后的列名（小写）；不合法时返回 null（调用方忽略该排序）
     */
    public static String validate(String orderBy, Set<String> allowedColumns) {
        if (orderBy == null || orderBy.trim().isEmpty()) {
            return null;
        }
        String column = orderBy.trim().toLowerCase(Locale.ROOT);
        // 只允许简单列名（不含点、空白与 SQL 符号）
        if (!column.matches("[a-z_][a-z0-9_]*")) {
            return null;
        }
        Set<String> allowed = allowedColumns == null || allowedColumns.isEmpty() ? DEFAULT_ALLOWED : allowedColumns;
        return allowed.contains(column) ? column : null;
    }

    /**
     * 追加 ORDER BY 子句。
     *
     * @param sql         原 SQL
     * @param orderBy     排序列（未通过校验则原样返回）
     * @param desc        是否倒序
     * @param allowedColumns 允许的列名集合
     * @return 追加 ORDER BY 后的 SQL
     */
    public static String appendOrderBy(String sql, String orderBy, boolean desc, Set<String> allowedColumns) {
        String column = validate(orderBy, allowedColumns);
        if (column == null) {
            return sql;
        }
        return sql + " ORDER BY " + column + (desc ? " DESC" : " ASC");
    }
}
