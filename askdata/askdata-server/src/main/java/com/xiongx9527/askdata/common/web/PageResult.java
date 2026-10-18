package com.xiongx9527.askdata.common.web;

import java.util.List;

/**
 * 通用分页响应，与 SQLBot {@code common.core.schemas.PaginatedResponse} 逐字对齐：
 * {@code {"items": [...], "total": N, "page": p, "size": s, "total_pages": t}}。
 */
public class PageResult<T> {

    private List<T> items;

    private long total;

    private int page;

    private int size;

    private long total_pages;

    public PageResult() {
    }

    public PageResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
        this.total_pages = size <= 0 ? 0 : (total + size - 1) / size;
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
}
