package com.xiongx9527.askdata.chart.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.common.storage.FileStorageTestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side image (acceptance A4): ChartImageController returns the PNG by token and
 * 404 for unknown tokens.
 */
class ChartImageControllerTest {

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
    void servesPngByToken() throws Exception {
        ChartImageService svc = service();
        String url = svc.renderFromResult(Map.of("rows", List.of(
                Map.of("m", "m1", "s", 10),
                Map.of("m", "m2", "s", 20))), "t");
        String token = url.substring(url.lastIndexOf('/') + 1);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChartImageController(svc)).build();
        mvc.perform(get("/api/chart/image/" + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void returns404ForUnknownToken() throws Exception {
        ChartImageService svc = service();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChartImageController(svc)).build();
        mvc.perform(get("/api/chart/image/missing"))
                .andExpect(status().isNotFound());
    }
}
