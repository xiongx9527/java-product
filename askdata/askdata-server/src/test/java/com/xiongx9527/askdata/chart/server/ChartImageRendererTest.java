package com.xiongx9527.askdata.chart.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-side image (acceptance A4): given a G2-style chart config + data, the renderer
 * must produce a valid PNG.
 */
class ChartImageRendererTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private static List<Map<String, Object>> sampleData() {
        return List.of(
                Map.of("month", "m1", "sales", 120.0, "profit", 30.0),
                Map.of("month", "m2", "sales", 200.0, "profit", 55.0),
                Map.of("month", "m3", "sales", 150.0, "profit", 40.0)
        );
    }

    private static Map<String, Object> cartesian(String type) {
        return Map.of(
                "type", type, "title", "trend",
                "columns", List.of(Map.of("value", "month"), Map.of("value", "sales")),
                "axis", Map.of(
                        "x", Map.of("value", "month"),
                        "y", Map.of("value", "sales"))
        );
    }

    private static Map<String, Object> pie() {
        return Map.of(
                "type", "pie", "title", "share",
                "columns", List.of(Map.of("value", "month"), Map.of("value", "sales")),
                "axis", Map.of(
                        "x", Map.of("value", "month"),
                        "y", Map.of("value", "sales"))
        );
    }

    private static Map<String, Object> table() {
        return Map.of(
                "type", "table", "title", "detail",
                "columns", List.of(Map.of("value", "month"), Map.of("value", "sales")),
                "axis", Map.of()
        );
    }

    private void assertValidPng(byte[] png) {
        assertNotNull(png);
        assertTrue(png.length > 100, "PNG too small: " + png.length);
        byte[] head = new byte[8];
        System.arraycopy(png, 0, head, 0, 8);
        assertArrayEquals(PNG_MAGIC, head, "not a valid PNG");
    }

    @Test
    void rendersColumnToPng() {
        assertValidPng(new ChartImageRenderer().renderPng(cartesian("column"), sampleData()));
    }

    @Test
    void rendersBarToPng() {
        assertValidPng(new ChartImageRenderer().renderPng(cartesian("bar"), sampleData()));
    }

    @Test
    void rendersLineToPng() {
        assertValidPng(new ChartImageRenderer().renderPng(cartesian("line"), sampleData()));
    }

    @Test
    void rendersAreaToPng() {
        assertValidPng(new ChartImageRenderer().renderPng(cartesian("area"), sampleData()));
    }

    @Test
    void rendersPieToPng() {
        assertValidPng(new ChartImageRenderer().renderPng(pie(), sampleData()));
    }

    @Test
    void rendersTableToPng() {
        assertValidPng(new ChartImageRenderer().renderPng(table(), sampleData()));
    }

    @Test
    void rendersEmptyDataWithoutThrowing() {
        assertValidPng(new ChartImageRenderer().renderPng(cartesian("column"), List.of()));
    }

    @Test
    void rendersNullChartAsTablePlaceholder() {
        assertValidPng(new ChartImageRenderer().renderPng(null, sampleData()));
    }

    @Test
    void multiSeriesCartesianUsesAllSeries() {
        Map<String, Object> dual = Map.of(
                "type", "column", "title", "sales and profit",
                "columns", List.of(Map.of("value", "month"), Map.of("value", "sales"), Map.of("value", "profit")),
                "axis", Map.of(
                        "x", Map.of("value", "month"),
                        "y", List.of(Map.of("value", "sales"), Map.of("value", "profit")))
        );
        assertValidPng(new ChartImageRenderer().renderPng(dual, sampleData()));
    }

    @Test
    void honorsCustomDimensions() {
        byte[] png = new ChartImageRenderer().renderPng(cartesian("line"), sampleData(), 640, 360);
        assertValidPng(png);
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(640, img.getWidth());
            assertEquals(360, img.getHeight());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
