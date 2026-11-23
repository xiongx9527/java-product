package com.xiongx9527.askdata.common.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 分页入参归一化单测（对齐 SQLBot normalize_page_args）。 */
class PageArgsTest {

    @Test
    void normalizesPageAndSize() {
        assertEquals(1, PageArgs.normalizePage(0));
        assertEquals(1, PageArgs.normalizePage(-5));
        assertEquals(3, PageArgs.normalizePage(3));
        assertEquals(PageArgs.DEFAULT_PAGE_SIZE, PageArgs.normalizeSize(0));
        assertEquals(PageArgs.DEFAULT_PAGE_SIZE, PageArgs.normalizeSize(-1));
        assertEquals(20, PageArgs.normalizeSize(20));
        assertEquals(PageArgs.MAX_PAGE_SIZE, PageArgs.normalizeSize(9999));
        assertEquals(500, PageArgs.normalizeSize(500));
    }

    @Test
    void offsetAndOutOfRange() {
        assertEquals(0, PageArgs.offset(1, 20, 100));
        assertEquals(20, PageArgs.offset(2, 20, 100));
        // 越界页：offset >= total 返回 -1，调用方提前返回空列表，不绑定超大 OFFSET
        assertEquals(-1, PageArgs.offset(6, 20, 100));
        assertEquals(-1, PageArgs.offset(1, 20, 0));
    }
}
