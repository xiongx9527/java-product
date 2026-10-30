package com.xiongx9527.askdata.chart.server;

import com.xiongx9527.askdata.common.util.Lists;
import com.xiongx9527.askdata.common.util.Maps;

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
        return Lists.of(
                Maps.of("month", "m1", "sales", 120.0, "profit", 30.0),
                Maps.of("month", "m2", "sales", 200.0, "profit", 55.0),
                Maps.of("month", "m3", "sales", 150.0, "profit", 40.0)
        );
    }

    private static Map<String, Object> cartesian(String type) {
        return Maps.of(
                "type", type, "title", "trend",
                "columns", Lists.of(Maps.of("value", "month"), Maps.of("value", "sales")),
                "axis", Maps.of(
                        "x", Maps.of("value", "month"),
                        "y", Maps.of("value", "sales"))
        );
    }

    private static Map<String, Object> pie() {
        return Maps.of(
                "type", "pie", "title", "share",
                "columns", Lists.of(Maps.of("value", "month"), Maps.of("value", "sales")),
                "axis", Maps.of(
                        "x", Maps.of("value", "month"),
                        "y", Maps.of("value", "sales"))
        );
    }

    private static Map<String, Object> table() {
        return Maps.of(
                "type", "table", "title", "detail",
                "columns", Lists.of(Maps.of("value", "month"), Maps.of("value", "sales")),
                "axis", Maps.of()
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
        assertValidPng(new ChartImageRenderer().renderPng(cartesian("column"), Lists.of()));
    }

    @Test
    void rendersNullChartAsTablePlaceholder() {
        assertValidPng(new ChartImageRenderer().renderPng(null, sampleData()));
    }

    @Test
    void multiSeriesCartesianUsesAllSeries() {
        Map<String, Object> dual = Maps.of(
                "type", "column", "title", "sales and profit",
                "columns", Lists.of(Maps.of("value", "month"), Maps.of("value", "sales"), Maps.of("value", "profit")),
                "axis", Maps.of(
                        "x", Maps.of("value", "month"),
                        "y", Lists.of(Maps.of("value", "sales"), Maps.of("value", "profit")))
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
