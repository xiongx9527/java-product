package com.xiongx9527.askdata.chart.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.common.storage.FileStorageTestFactory;
import com.xiongx9527.askdata.support.MetadbMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图表图片 token 的跨实例解析（验收 A7）与过期清理（A8）。
 *
 * <p>两个 service 实例代表两个节点：各自独立的内存缓存与本地目录，仅共享元数据库。
 * A 生成的 token 必须能被 B 解析——这是多实例下图片不 404 的前提。</p>
 */
class ChartImageClusterTest {

    private JdbcTemplate jdbc;

    private ChartTokenRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chart_token_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        repository = new ChartTokenRepository(jdbc);
    }

    private ChartImageService node(Path dir, long ttlMs) {
        AskdataFileStorage storage = FileStorageTestFactory.askdata(dir);
        ChartImageService service = new ChartImageService(new ChatChartBuilder(new ObjectMapper()),
                new ChartImageRenderer(), new ObjectMapper(), storage, dir, ttlMs);
        service.setTokenRepository(repository);
        return service;
    }

    private static Map<String, Object> rows() {
        return Map.of("rows", List.of(Map.of("m", "m1", "s", 10), Map.of("m", "m2", "s", 20)));
    }

    @Test
    void tokenGeneratedOnOneInstanceResolvesOnAnother() throws Exception {
        Path shared = Files.createTempDirectory("chart-cluster");
        Path nodeADir = Files.createTempDirectory("chart-node-a");
        Path nodeBDir = Files.createTempDirectory("chart-node-b");

        // 两个节点共用同一份文件存储（模拟对象存储），但内存缓存与本地目录互不相通
        AskdataFileStorage sharedStorage = FileStorageTestFactory.askdata(shared);
        ChartImageService realA = new ChartImageService(new ChatChartBuilder(new ObjectMapper()),
                new ChartImageRenderer(), new ObjectMapper(), sharedStorage, nodeADir, 86_400_000L);
        realA.setTokenRepository(repository);
        ChartImageService realB = new ChartImageService(new ChatChartBuilder(new ObjectMapper()),
                new ChartImageRenderer(), new ObjectMapper(), sharedStorage, nodeBDir, 86_400_000L);
        realB.setTokenRepository(repository);

        String url = realA.renderFromResult(rows(), "t");
        assertNotNull(url);
        String token = url.substring(url.lastIndexOf('/') + 1);

        byte[] png = realB.get(token);
        assertNotNull(png, "实例 B 必须能凭 A 生成的 token 取回图片");
        assertEquals((byte) 0x89, png[0]);
    }

    @Test
    void expiredTokenIsRemovedFromSharedStore() throws Exception {
        Path dir = Files.createTempDirectory("chart-expire");
        ChartImageService node = node(dir, 1L);
        String url = node.renderFromResult(rows(), "t");
        String token = url.substring(url.lastIndexOf('/') + 1);

        // 等 TTL 过期后：读取返回 null（404），并清理共享映射
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertNull(node.get(token));
        assertTrue(repository.find(token).isEmpty(), "过期 token 的映射应从共享存储删除");
    }

    @Test
    void cleanupDeletesExpiredRowsAcrossInstances() throws Exception {
        Path dir = Files.createTempDirectory("chart-cleanup");
        ChartImageService node = node(dir, 1L);
        String url = node.renderFromResult(rows(), "t");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertTrue(repository.find(token).isPresent());

        Thread.sleep(5L);
        int removed = node.cleanupExpired();
        assertEquals(1, removed);
        assertTrue(repository.findExpired(System.currentTimeMillis(), 10).isEmpty());
    }
}
