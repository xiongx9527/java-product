package com.xiongx9527.askdata.chart.server;

import com.xiongx9527.askdata.common.util.Lists;
import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.common.storage.FileStorageTestFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        Path dir;
        try {
            dir = Files.createTempDirectory("chart-img");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new ChartImageService(new ChatChartBuilder(new ObjectMapper()),
                new ChartImageRenderer(), new ObjectMapper(),
                FileStorageTestFactory.askdata(dir));
    }

    @Test
    void rendersResultAndReturnsUrl() {
        ChartImageService svc = service();
        Map<String, Object> result = Maps.of(
                "rows", Lists.of(
                        Maps.of("month", "m1", "sales", 120),
                        Maps.of("month", "m2", "sales", 200))
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
    void rendersChatPipelineResultShape() {
        // 主链路 result 形状为 {fields, fields_info, data}（无 rows）——SSR 必须同样出图
        ChartImageService svc = service();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fields", Lists.of("region", "cnt"));
        result.put("fields_info", Lists.of(
                Maps.of("name", "region", "is_numeric", false),
                Maps.of("name", "cnt", "is_numeric", true)));
        result.put("data", Lists.of(
                Maps.of("region", "east", "cnt", 3),
                Maps.of("region", "west", "cnt", 5)));
        String url = svc.renderFromResult(result, "各地区订单数");
        assertNotNull(url);
        String token = url.substring(url.lastIndexOf('/') + 1);
        byte[] png = svc.get(token);
        assertNotNull(png);
        assertEquals((byte) 0x89, png[0]);
    }

    @Test
    void returnsNullWhenNoRows() {
        ChartImageService svc = service();
        assertNull(svc.renderFromResult(Maps.of("rows", Lists.of()), "x"));
        assertNull(svc.renderFromResult(Maps.of("foo", "bar"), "x"));
        assertNull(svc.renderFromResult(null, "x"));
    }

    @Test
    void unknownTokenYieldsNull() {
        assertEquals(null, service().get("does-not-exist"));
    }
}
