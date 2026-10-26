package com.xiongx9527.askdata.chart.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端图表语义（验收 A4）：多 series 不同颜色、横向 bar 条长按数值比例、area 填充、
 * pie 标签（名称 数值 占比）、ChatChartBuilder 保留 multi-quota、未知 chart type 报错而非静默改 table。
 *
 * <p>通过真实渲染 PNG 并扫描像素断言语义，而非 mock。</p>
 */
class ChartSemanticsParityTest {

    private final ChartImageRenderer renderer = new ChartImageRenderer();
    private final ChatChartBuilder builder = new ChatChartBuilder(new ObjectMapper());

    @Test
    void seriesColorsAreDistinct() {
        // A4：多 series 必须用不同颜色
        assertFalse(ChartImageRenderer.seriesColor(0).equals(ChartImageRenderer.seriesColor(1)));
        assertFalse(ChartImageRenderer.seriesColor(1).equals(ChartImageRenderer.seriesColor(2)));
        // 多 series 出图确实用到两种颜色
        Map<String, Object> dual = Map.of(
                "type", "column", "title", "sales and profit",
                "columns", List.of(Map.of("value", "m"), Map.of("value", "s"), Map.of("value", "p")),
                "axis", Map.of(
                        "x", Map.of("value", "m"),
                        "y", List.of(Map.of("value", "s"), Map.of("value", "p"))));
        BufferedImage img = read(renderer.renderPng(dual, List.of(
                Map.of("m", "a", "s", 1, "p", 2),
                Map.of("m", "b", "s", 3, "p", 4))));
        assertTrue(colorPresent(img, ChartImageRenderer.seriesColor(0), 40), "series0 color present");
        assertTrue(colorPresent(img, ChartImageRenderer.seriesColor(1), 40), "series1 color present");
    }

    @Test
    void horizontalBarLengthIsProportionalToValue() {
        // A4：横向 bar 条长按数值比例（而非按类别顺序）
        Map<String, Object> bar = Map.of(
                "type", "bar", "title", "compare",
                "columns", List.of(Map.of("value", "cat"), Map.of("value", "val")),
                "axis", Map.of(
                        "x", Map.of("value", "cat"),
                        "y", Map.of("value", "val")));
        List<Map<String, Object>> data = List.of(
                Map.of("cat", "A", "val", 100.0),
                Map.of("cat", "B", "val", 200.0));
        BufferedImage img = read(renderer.renderPng(bar, data));
        // 类别 0（值 100）中心约在 y=343，类别 1（值 200）约在 y=165（见 drawCartesian 布局）
        int rightSmall = maxXOfColor(img, 313, 373, ChartImageRenderer.seriesColor(0), 40);
        int rightLarge = maxXOfColor(img, 135, 195, ChartImageRenderer.seriesColor(0), 40);
        assertTrue(rightSmall > 0, "small-value bar detected");
        assertTrue(rightLarge > 0, "large-value bar detected");
        double ratio = (double) rightLarge / rightSmall;
        // 值 200 vs 100，条长比应约等于 2（允许取整/抗锯齿容差）
        assertTrue(ratio > 1.7 && ratio < 2.3, "bar length ratio ~2 but was " + ratio);
    }

    @Test
    void areaIsFilledWithSeriesTint() {
        // A4：area 用系列色浅投影填充（不同于系列色本身）
        Color fill = ChartImageRenderer.areaFillColor(0);
        assertFalse(fill.equals(ChartImageRenderer.seriesColor(0)), "area fill differs from series color");
        Map<String, Object> area = Map.of(
                "type", "area", "title", "trend",
                "columns", List.of(Map.of("value", "m"), Map.of("value", "s")),
                "axis", Map.of(
                        "x", Map.of("value", "m"),
                        "y", Map.of("value", "s")));
        BufferedImage img = read(renderer.renderPng(area, List.of(
                Map.of("m", "a", "s", 50.0),
                Map.of("m", "b", "s", 150.0),
                Map.of("m", "c", "s", 100.0))));
        // 填充色应出现在图中（面积区内部为实色，抗锯齿边界外）
        assertTrue(colorPresent(img, fill, 10), "area fill color present in rendered PNG");
    }

    @Test
    void pieLabelUsesNameValueAndPercent() {
        // A4：饼图标签 = 名称 + 数值 + 占比
        assertEquals("PAID 1,234 (35.0%)", ChartImageRenderer.pieLabel("PAID", 1234.0, 3524.0));
        assertEquals("X 0 (0.0%)", ChartImageRenderer.pieLabel("X", 0.0, 0.0));
        // 渲染饼图应产出合法 PNG（且标签绘制使用 pieLabel 格式）
        Map<String, Object> pie = Map.of(
                "type", "pie", "title", "share",
                "columns", List.of(Map.of("value", "cat"), Map.of("value", "val")),
                "axis", Map.of(
                        "x", Map.of("value", "cat"),
                        "y", Map.of("value", "val")));
        byte[] png = renderer.renderPng(pie, List.of(
                Map.of("cat", "PAID", "val", 1234.0),
                Map.of("cat", "FREE", "val", 2290.0)));
        assertNotNull(png);
        assertTrue(png.length > 100);
    }

    @Test
    void chatChartBuilderPreservesMultiQuota() {
        // A4：归一化保留 axis.multi-quota（多指标轴标记）
        Map<String, Object> chart = builder.normalize(
                "{\"type\":\"column\",\"title\":\"t\","
                        + "\"columns\":[{\"value\":\"m\"},{\"value\":\"s\"}],"
                        + "\"axis\":{\"x\":{\"value\":\"m\"},\"y\":{\"value\":\"s\"},"
                        + "\"multi-quota\":[\"s\",\"p\"]}}");
        assertNotNull(chart);
        @SuppressWarnings("unchecked")
        Map<String, Object> axis = (Map<String, Object>) chart.get("axis");
        assertTrue(axis.containsKey("multi-quota"), "multi-quota preserved by normalize");
        assertEquals(List.of("s", "p"), axis.get("multi-quota"));
    }

    @Test
    void chatChartBuilderAddsMultiQuotaForMultipleNumeric() {
        // A4：多数值列启发式构造时同步产出 multi-quota
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fields", List.of("m", "s", "p"));
        result.put("fields_info", List.of(
                Map.of("name", "m", "is_numeric", false),
                Map.of("name", "s", "is_numeric", true),
                Map.of("name", "p", "is_numeric", true)));
        result.put("data", List.of(Map.of("m", "a", "s", 1, "p", 2)));
        Map<String, Object> chart = builder.build(result, "compare");
        @SuppressWarnings("unchecked")
        Map<String, Object> axis = (Map<String, Object>) chart.get("axis");
        assertTrue(axis.containsKey("multi-quota"), "multi-quota produced for >1 numeric");
        assertEquals(List.of("s", "p"), axis.get("multi-quota"));
    }

    @Test
    void unknownChartTypeThrowsInsteadOfSilentTable() {
        // A4：未知 chart type 应当报错（由调用方回退启发式），而非静默降级为 table
        assertThrows(IllegalArgumentException.class,
                () -> builder.normalize("{\"type\":\"radar\",\"title\":\"x\"}"));
        // 缺 type 也应报错
        assertThrows(IllegalArgumentException.class,
                () -> builder.normalize("{\"title\":\"no type\"}"));
        // 合法 table 不应报错，且保持 table
        Map<String, Object> table = builder.normalize("{\"type\":\"table\"}");
        assertEquals("table", table.get("type"));
    }

    // ---- pixel helpers ----

    private static BufferedImage read(byte[] png) {
        try {
            return javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean colorPresent(BufferedImage img, Color target, int tol) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color c = new Color(img.getRGB(x, y));
                if (Math.abs(c.getRed() - target.getRed()) <= tol
                        && Math.abs(c.getGreen() - target.getGreen()) <= tol
                        && Math.abs(c.getBlue() - target.getBlue()) <= tol) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int maxXOfColor(BufferedImage img, int y0, int y1, Color target, int tol) {
        int maxX = -1;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color c = new Color(img.getRGB(x, y));
                if (Math.abs(c.getRed() - target.getRed()) <= tol
                        && Math.abs(c.getGreen() - target.getGreen()) <= tol
                        && Math.abs(c.getBlue() - target.getBlue()) <= tol) {
                    if (x > maxX) maxX = x;
                }
            }
        }
        return maxX;
    }
}
