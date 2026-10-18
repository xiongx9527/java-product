package com.xiongx9527.askdata.common.web;

import java.util.List;

/**
 * SQLBot 风格分页信封（数据训练/术语/提示词/审计页）：
 * {@code {"current_page": p, "page_size": s, "total_count": n, "total_pages": t, "data": [...]}}，
 * 与 SQLBot 对应路由返回结构逐字一致（前端消费 {@code res.data} 与 {@code res.total_count}）。
 */
public class PagerResult<T> {

    private int current_page;

    private int page_size;

    private long total_count;

    private long total_pages;

    private List<T> data;

    public PagerResult() {
    }

    public PagerResult(List<T> data, long totalCount, int currentPage, int pageSize) {
        this.data = data;
        this.total_count = totalCount;
        this.current_page = currentPage;
        this.page_size = pageSize;
        this.total_pages = pageSize <= 0 ? 0 : (totalCount + pageSize - 1) / pageSize;
    }

    public int getCurrent_page() {
        return current_page;
    }

    public void setCurrent_page(int current_page) {
        this.current_page = current_page;
    }

    public int getPage_size() {
        return page_size;
    }

    public void setPage_size(int page_size) {
        this.page_size = page_size;
    }

    public long getTotal_count() {
        return total_count;
    }

    public void setTotal_count(long total_count) {
        this.total_count = total_count;
    }

    public long getTotal_pages() {
        return total_pages;
    }

    public void setTotal_pages(long total_pages) {
        this.total_pages = total_pages;
    }

    public List<T> getData() {
        return data;
    }

    public void setData(List<T> data) {
        this.data = data;
    }
}
