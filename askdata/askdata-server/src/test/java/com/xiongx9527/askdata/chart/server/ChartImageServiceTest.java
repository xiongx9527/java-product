package com.xiongx9527.askdata.chart.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-side image (acceptance A4): ChartImageService renders a Q&A result into a PNG and
 * returns an accessible URL that can later be resolved by the same token.
 */
class ChartImageServiceTest {

    private ChartImageService service() {
        return new ChartImageService(new ChatChartBuilder(new ObjectMapper()),
                new ChartImageRenderer(), new ObjectMapper());
    }

    @Test
    void rendersResultAndReturnsUrl() {
        ChartImageService svc = service();
        Map<String, Object> result = Map.of(
                "rows", List.of(
                        Map.of("month", "m1", "sales", 120),
                        Map.of("month", "m2", "sales", 200))
        );
        String url = svc.renderFromResult(result, "monthly sales");
        assertNotNull(url);
        assertTrue(url.contains("/api/chart/image/"), "URL should point at /api/chart/image/: " + url);
        String token = url.substring(url.lastIndexOf('/') + 1);
        byte[] png = svc.get(token);
        assertNotNull(png);
        assertEquals((byte) 0x89, png[0]);
    }

    @Test
    void returnsNullWhenNoRows() {
        ChartImageService svc = service();
        assertNull(svc.renderFromResult(Map.of("rows", List.of()), "x"));
        assertNull(svc.renderFromResult(Map.of("foo", "bar"), "x"));
        assertNull(svc.renderFromResult(null, "x"));
    }

    @Test
    void unknownTokenYieldsNull() {
        assertEquals(null, service().get("does-not-exist"));
    }
}
