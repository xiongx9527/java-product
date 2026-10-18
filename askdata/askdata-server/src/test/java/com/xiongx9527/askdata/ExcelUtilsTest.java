package com.xiongx9527.askdata;

import com.xiongx9527.askdata.common.util.ExcelUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 工具（POI）读写自检：训练/术语/提示词/审计导入导出的基础。
 */
class ExcelUtilsTest {

    @Test
    void writeAndReadRoundTrip() throws Exception {
        List<ExcelUtils.Column> columns = Arrays.asList(
                new ExcelUtils.Column("问题描述", "question"),
                new ExcelUtils.Column("示例 SQL", "description"));
        List<ExcelUtils.MapStringArray> rows = new ArrayList<>();
        rows.add(key -> "question".equals(key) ? "查询所有ID" : "SELECT id FROM t");
        byte[] bytes = ExcelUtils.writeXlsx(columns, rows);
        assertTrue(bytes.length > 0);

        List<String[]> read = ExcelUtils.readRows(new ByteArrayInputStream(bytes));
        assertEquals(2, read.size());
        assertEquals("问题描述", read.get(0)[0]);
        assertEquals("查询所有ID", read.get(1)[0]);
        assertEquals("SELECT id FROM t", read.get(1)[1]);
    }
}
