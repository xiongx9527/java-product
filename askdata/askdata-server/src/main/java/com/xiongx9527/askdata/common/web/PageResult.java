package com.xiongx9527.askdata.common.web;

import java.util.List;

/**
 * 通用分页响应，与 SQLBot {@code common.core.schemas.PaginatedResponse} 逐字对齐：
 * {@code {"items": [...], "total": N, "page": p, "size": s, "total_pages": t}}。
 *
 * <p><b>排序支持</b>（对齐 SQLBot {@code common/core/pagination.py} 的 Paginator）：
 * 分页参数支持 {@code order_by}（排序列）与 {@code desc}（是否倒序），
 * 由 {@link #orderBy} / {@link #desc} 承载，供各 Repository 拼 ORDER BY 子句。</p>
 */
public class PageResult<T> {

    private List<T> items;

    private long total;

    private int page;

    private int size;

    private long total_pages;

    /** 排序列（可空；为空时沿用各查询的默认排序）。 */
    private String order_by;

    /** 是否倒序。 */
    private boolean desc;

    public PageResult() {
    }

    public PageResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
        this.total_pages = size <= 0 ? 0 : (total + size - 1) / size;
    }

    /** 带排序参数的分页响应。 */
    public PageResult(List<T> items, long total, int page, int size, String orderBy, boolean desc) {
        this(items, total, page, size);
        this.order_by = orderBy;
        this.desc = desc;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public long getTotal_pages() {
        return total_pages;
    }

    public void setTotal_pages(long total_pages) {
        this.total_pages = total_pages;
    }

    public String getOrder_by() {
        return order_by;
    }

    public void setOrder_by(String order_by) {
        this.order_by = order_by;
    }

    public boolean isDesc() {
        return desc;
    }

    public void setDesc(boolean desc) {
        this.desc = desc;
    }
}
