package com.xiongx9527.askdata.mcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A15 regression: MCP non-interactive answer produces a self-contained markdown
 * string (record id + result table + image line), decoupled from Web SSE frames;
 * the image line is omitted when there is no chart.
 */
class McpMarkdownAnswerTest {

    @Test
    void includesRecordIdTableAndImageLine() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("record_id", 42L);
        result.put("sql", "SELECT 1");
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("fields", Arrays.asList("a", "b"));
        data.put("data", Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3, 4)));
        result.put("data", data);
        result.put("image_url", "http://img/x.png");

        String md = AskdataMcpTools.toMarkdownAnswer(result, "ask something");
        assertTrue(md.contains("记录 ID**：42"), md);
        assertTrue(md.contains("SQL**：`SELECT 1`"), md);
        assertTrue(md.contains("| a | b |"), md);
        assertTrue(md.contains("| 1 | 2 |"), md);
        assertTrue(md.contains("![]("), md);
        assertTrue(md.contains("http://img/x.png"), md);
    }

    @Test
    void omitsImageLineWhenNoChart() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("record_id", 7L);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("fields", Arrays.asList("a"));
        data.put("data", Arrays.asList(Arrays.asList(9)));
        result.put("data", data);

        String md = AskdataMcpTools.toMarkdownAnswer(result, null);
        assertTrue(md.contains("记录 ID**：7"), md);
        assertTrue(md.contains("| a |"), md);
        assertFalse(md.contains("![]("), "no chart -> no image line: " + md);
    }

    @Test
    void handlesRowsShapeFromNl2Sql() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("recordId", 5L);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("x", 10);
        rows.add(row);
        result.put("rows", rows);

        String md = AskdataMcpTools.toMarkdownAnswer(result, null);
        assertTrue(md.contains("记录 ID**：5"), md);
        assertTrue(md.contains("| x |"), md);
        assertTrue(md.contains("| 10 |"), md);
    }
}
