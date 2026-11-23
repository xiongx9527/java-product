package com.xiongx9527.askdata.common.web;

/**
 * 分页入参归一化（对齐 SQLBot {@code common/core/pagination.py#normalize_page_args}）。
 *
 * <p>分页参数来自未校验的路径变量：{@code page=0} 会算出负 OFFSET，{@code size=0} 会在计算
 * total_pages 时除零，超大 {@code size} 则让单个请求返回任意大的结果集。本类统一钳制：
 * page 小于 1 取 1；size 小于 1 取默认值 20，大于 500 取 500。</p>
 *
 * <p><b>线程安全</b>：全静态无状态。</p>
 */
public final class PageArgs {

    /** 默认每页条数（对齐 SQLBot DEFAULT_PAGE_SIZE）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页上限（对齐 SQLBot MAX_PAGE_SIZE）。 */
    public static final int MAX_PAGE_SIZE = 500;

    private PageArgs() {
    }

    /** page 小于 1 归一化为 1。 */
    public static int normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    /** size 小于 1 取默认 20，大于 500 取 500。 */
    public static int normalizeSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /** 计算 OFFSET；{@code offset >= total}（越界页）返回 -1，调用方可提前返回空列表避免绑定超大 OFFSET。 */
    public static int offset(int page, int size, long total) {
        long value = (long) (normalizePage(page) - 1) * (long) normalizeSize(size);
        return value >= total ? -1 : (int) value;
    }
}
