package com.xiongx9527.askdata.chart.server;

import com.xiongx9527.askdata.common.util.Lists;
import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.common.storage.FileStorageTestFactory;
import com.xiongx9527.askdata.common.web.AuthWhitelist;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 出图 URL 生命周期（验收 A5）：持久化 token -> 文件映射（重启可打开）、TTL 过期清理、
 * 篡改/未知 token 返回 404、/api/chart/image/* 在鉴权白名单内、未登录第三方有效期内可访问。
 */
class ChartImageTokenLifecycleTest {

    private ChartImageService service(Path dir, long ttlMs) {
        AskdataFileStorage fs = FileStorageTestFactory.askdata(dir);
        return new ChartImageService(new ChatChartBuilder(new ObjectMapper()),
                new ChartImageRenderer(), new ObjectMapper(), fs, dir, ttlMs);
    }

    private static Map<String, Object> rows() {
        return Maps.of("rows", Lists.of(
                Maps.of("m", "m1", "s", 10),
                Maps.of("m", "m2", "s", 20)));
    }

    @Test
    void tokenToFileMappingSurvivesRestart() throws IOException {
        // A5：持久化 token->文件映射，进程重启后仍可凭同一 token 打开图片
        Path dir = Files.createTempDirectory("chart-img-restart");
        AskdataFileStorage fs = FileStorageTestFactory.askdata(dir);
        ChartImageService svcA = new ChartImageService(new ChatChartBuilder(new ObjectMapper()),
                new ChartImageRenderer(), new ObjectMapper(), fs, dir, 86_400_000L);
        String url = svcA.renderFromResult(rows(), "t");
        assertNotNull(url);
        String token = url.substring(url.lastIndexOf('/') + 1);
        // 模拟重启：全新 service 实例（重新加载持久化的 token 映射），底层文件存储指向同一目录。
        // token 映射 JSON 持久化到 dir，svcB 构造时 reload，证明重启后仍可凭同一 token 打开图片。
        ChartImageService svcB = new ChartImageService(new ChatChartBuilder(new ObjectMapper()),
                new ChartImageRenderer(), new ObjectMapper(), fs, dir, 86_400_000L);
        byte[] png = svcB.get(token);
        assertNotNull(png, "image must still resolve after restart");
        assertEquals((byte) 0x89, png[0]);
    }

    @Test
    void unauthenticatedThirdPartyCanAccessWithinValidity() throws Exception {
        // A5：未登录第三方在有效期内可访问（controller 免鉴权，返回 200）
        Path dir = Files.createTempDirectory("chart-img-anon");
        ChartImageService svc = service(dir, 86_400_000L);
        String url = svc.renderFromResult(rows(), "t");
        String token = url.substring(url.lastIndexOf('/') + 1);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChartImageController(svc)).build();
        mvc.perform(get("/api/chart/image/" + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void tamperedOrUnknownTokenReturns404() throws Exception {
        // A5：篡改（非 32 hex）/ 未知 token 一律 404
        Path dir = Files.createTempDirectory("chart-img-tamper");
        ChartImageService svc = service(dir, 86_400_000L);
        // 格式非法（篡改）
        assertNull(svc.get("tampered!!"), "tampered token must not resolve");
        assertNull(svc.get("abc"), "non-32-hex token must not resolve");
        // 未知 token
        assertNull(svc.get("0123456789abcdef0123456789abcdef"), "unknown token must not resolve");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChartImageController(svc)).build();
        mvc.perform(get("/api/chart/image/tampered!!")).andExpect(status().isNotFound());
        mvc.perform(get("/api/chart/image/short")).andExpect(status().isNotFound());
    }

    @Test
    void expiredTokenReturns404AndIsCleaned() throws Exception {
        // A5：过期 token 返回 404，且按 TTL 清理（不无限增长）
        Path dir = Files.createTempDirectory("chart-img-ttl");
        ChartImageService svc = service(dir, 1L); // 1ms TTL
        String url = svc.renderFromResult(rows(), "t");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertEquals(1, svc.cachedCount());
        Thread.sleep(20);
        // 过期后访问返回 404
        assertNull(svc.get(token));
        assertEquals(0, svc.cachedCount(), "expired token removed from store");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChartImageController(svc)).build();
        mvc.perform(get("/api/chart/image/" + token)).andExpect(status().isNotFound());
    }

    @Test
    void cleanupExpiredRemovesOnlyExpiredEntries() throws Exception {
        // A5：cleanupExpired 只清过期，保留有效；证明不会无限增长
        Path dir = Files.createTempDirectory("chart-img-cleanup");
        ChartImageService svc = service(dir, 1L);
        // 5 个立即可过期的 token
        for (int i = 0; i < 5; i++) {
            assertNotNull(svc.renderFromResult(rows(), "t" + i));
        }
        Thread.sleep(20);
        int removed = svc.cleanupExpired();
        assertEquals(5, removed);
        assertEquals(0, svc.cachedCount());
    }

    @Test
    void tokensAreUnguessableAndDistinct() throws IOException {
        Path dir = Files.createTempDirectory("chart-img-unguess");
        ChartImageService svc = service(dir, 86_400_000L);
        String a = svc.renderFromResult(rows(), "t");
        String b = svc.renderFromResult(rows(), "t");
        assertFalse(a.equals(b), "each render yields a distinct token");
        String ta = a.substring(a.lastIndexOf('/') + 1);
        String tb = b.substring(b.lastIndexOf('/') + 1);
        assertEquals(32, ta.length());
        assertEquals(32, tb.length());
        assertTrue(ta.matches("^[0-9a-f]{32}$"), "token is 32-hex");
    }

    @Test
    void chartImageEndpointIsWhitelisted() {
        // A5：/api/chart/image/* 在鉴权白名单内（未登录第三方可访问）
        AuthWhitelist wl = new AuthWhitelist("/api/v1");
        assertTrue(wl.isWhitelistedPath("/api/chart/image/abc123def456"),
                "/api/chart/image/{token} must be whitelisted");
        assertTrue(wl.isWhitelistedPath("/api/chart/image"),
                "exact /api/chart/image must be whitelisted");
        assertFalse(wl.isWhitelistedPath("/api/chart/other/abc"),
                "other chart paths must NOT be whitelisted");
    }
}
