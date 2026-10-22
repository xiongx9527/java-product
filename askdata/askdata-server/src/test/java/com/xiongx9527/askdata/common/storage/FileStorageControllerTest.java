package com.xiongx9527.askdata.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通用文件下载端点：凭存储 url 流式取回；未知 url 404、缺参 400。
 */
class FileStorageControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsStoredFile() throws Exception {
        AskdataFileStorage storage = FileStorageTestFactory.askdata(tempDir);
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 9, 9};
        StoredFile saved = storage.save(png, "chart.png", "chart", "image/png");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FileStorageController(storage)).build();
        mvc.perform(get("/api/file/download").param("url", saved.url()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("chart.png")));
    }

    @Test
    void unknownUrlReturns404() throws Exception {
        AskdataFileStorage storage = FileStorageTestFactory.askdata(tempDir);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FileStorageController(storage)).build();
        mvc.perform(get("/api/file/download").param("url", "https://evil.example.com/secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void multipartSavedThenDownloaded() throws Exception {
        AskdataFileStorage storage = FileStorageTestFactory.askdata(tempDir);
        byte[] csv = "a,b\n1,2".getBytes(StandardCharsets.UTF_8);
        StoredFile saved = storage.save(new MockMultipartFile("file", "data.csv", "text/csv", csv), "export");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FileStorageController(storage)).build();
        mvc.perform(get("/api/file/download").param("url", saved.url()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"));
    }
}
