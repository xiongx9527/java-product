package com.xiongx9527.askdata.cluster;

import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.config.CacheProperties;
import com.xiongx9527.askdata.mcp.cluster.McpSseClusterBridge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集群就绪自检：逐项判据与 fail-fast 行为（验收 A1/A2/A13/A14）。
 */
class ClusterReadinessCheckerTest {

    private static ClusterReadinessChecker checker(MockEnvironment env, String storagePlatform,
                                                   boolean redisTemplate, CacheProperties cache) {
        return checker(env, storagePlatform, redisTemplate, redisTemplate, cache);
    }

    private static ClusterReadinessChecker checker(MockEnvironment env, String storagePlatform,
                                                   boolean redisTemplate, boolean mcpBridge,
                                                   CacheProperties cache) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (storagePlatform != null) {
            AskdataFileStorage storage = mock(AskdataFileStorage.class);
            when(storage.platform()).thenReturn(storagePlatform);
            beans.addBean("askdataFileStorage", storage);
        }
        if (redisTemplate) {
            beans.addBean("stringRedisTemplate", mock(StringRedisTemplate.class));
        }
        ObjectProvider<AskdataFileStorage> storageProvider =
                beans.getBeanProvider(AskdataFileStorage.class);
        ObjectProvider<StringRedisTemplate> redisProvider =
                beans.getBeanProvider(StringRedisTemplate.class);
        if (mcpBridge) {
            beans.addBean("mcpSseClusterBridge", mock(McpSseClusterBridge.class));
        }
        ObjectProvider<McpSseClusterBridge> bridgeProvider =
                beans.getBeanProvider(McpSseClusterBridge.class);
        ClusterProperties props = new ClusterProperties();
        props.setEnabled(true);
        return new ClusterReadinessChecker(props, cache, env, storageProvider, redisProvider, bridgeProvider);
    }

    private static MockEnvironment env(String dbUrl, String secretKey, String lockType) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", dbUrl);
        env.setProperty("askdata.secret.key", secretKey);
        env.setProperty("askdata.lock.type", lockType);
        return env;
    }

    private static CacheProperties redisCache() {
        CacheProperties cache = new CacheProperties();
        cache.setType(CacheProperties.TYPE_REDIS);
        cache.setSaTokenStore(CacheProperties.TYPE_REDIS);
        return cache;
    }

    private static CacheProperties memoryCache() {
        return new CacheProperties();
    }

    @Test
    void h2MemoryAndLocalStorageAreFailures() {
        // A1：单实例默认配置（H2 + 空密钥 + memory + local）必须逐项报不满足
        MockEnvironment env = env("jdbc:h2:file:./data/askdata;MODE=MySQL", "", "db");
        ClusterReadinessReport report = checker(env, "local", false, memoryCache()).check();

        assertTrue(report.hasFailure());
        assertEquals(ClusterReadinessReport.Level.FAIL, levelOf(report, "元数据库"));
        assertEquals(ClusterReadinessReport.Level.FAIL, levelOf(report, "应用密钥"));
        assertEquals(ClusterReadinessReport.Level.FAIL, levelOf(report, "会话存储"));
        assertEquals(ClusterReadinessReport.Level.FAIL, levelOf(report, "缓存后端"));
        assertEquals(ClusterReadinessReport.Level.FAIL, levelOf(report, "文件存储"));
    }

    @Test
    void sharedInfrastructurePassesAllChecks() {
        // A2：共享 MySQL + 同一密钥 + Redis 会话/缓存 + 远端存储 → 全部通过
        MockEnvironment env = env("jdbc:mysql://db:3306/askdata", "k".repeat(32), "redis");
        ClusterReadinessReport report = checker(env, "minio", true, redisCache()).check();

        assertFalse(report.hasFailure());
        assertFalse(report.hasWarning());
        assertTrue(report.items().stream()
                .allMatch(i -> i.level() == ClusterReadinessReport.Level.OK));
    }

    @Test
    void mcpWithoutRedisWarnsAboutStickySessions() {
        // A13：http-sse 且无 StringRedisTemplate → 告警说明需要粘性会话或启用 Redis
        MockEnvironment env = env("jdbc:mysql://db:3306/askdata", "k".repeat(32), "redis");
        env.setProperty("fw-plus.ai.mcp.enabled", "true");
        env.setProperty("fw-plus.ai.mcp.transport", "http-sse");
        ClusterReadinessReport report = checker(env, "minio", false, redisCache()).check();

        assertEquals(ClusterReadinessReport.Level.WARN, levelOf(report, "MCP 会话"));
        assertTrue(itemOf(report, "MCP 会话").fix().contains("粘性会话"));
    }

    @Test
    void mcpWithRedisIsClusterReady() {
        // A13：Redis + 集群中继均就绪时才算集群可用，且说明不再依赖粘性会话
        MockEnvironment env = env("jdbc:mysql://db:3306/askdata", "k".repeat(32), "redis");
        ClusterReadinessReport report = checker(env, "minio", true, true, redisCache()).check();

        ClusterReadinessReport.Item item = itemOf(report, "MCP 会话");
        assertEquals(ClusterReadinessReport.Level.OK, item.level());
        assertTrue(item.detail().contains("中继"));
    }

    @Test
    void mcpWithRedisButNoBridgeWarnsInsteadOfFalseOk() {
        // A13：仅装 Redis 但中继配置未生效时不能报 OK（框架注册表本身不跨实例）
        MockEnvironment env = env("jdbc:mysql://db:3306/askdata", "k".repeat(32), "redis");
        env.setProperty("fw-plus.ai.mcp.enabled", "true");
        env.setProperty("fw-plus.ai.mcp.transport", "http-sse");
        ClusterReadinessReport report = checker(env, "minio", true, false, redisCache()).check();

        ClusterReadinessReport.Item item = itemOf(report, "MCP 会话");
        assertEquals(ClusterReadinessReport.Level.WARN, item.level());
        assertTrue(item.detail().contains("中继未启用"));
        assertTrue(item.fix().contains("粘性会话"));
    }

    @Test
    void workerIdFallsBackWithPidWarning() {
        // A12：关闭框架分配且未显式配置 worker-id → 撞号风险告警
        MockEnvironment env = env("jdbc:mysql://db:3306/askdata", "k".repeat(32), "redis");
        env.setProperty("fw.plus.uid.enabled", "false");
        ClusterReadinessReport report = checker(env, "minio", true, redisCache()).check();

        assertEquals(ClusterReadinessReport.Level.WARN, levelOf(report, "雪花 workerId"));
    }

    @Test
    void failFastRejectsStartup() {
        // A1：fail-fast=true 时拒绝启动，错误信息指向第一处不满足项
        MockEnvironment env = env("jdbc:h2:file:./data/askdata", "", "db");
        ClusterReadinessChecker checker = checker(env, "local", false, memoryCache());
        setFailFast(checker, true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checker.run(null));
        assertTrue(error.getMessage().contains("元数据库"));
    }

    @Test
    void disabledClusterSkipsChecks() {
        // A14：不开启集群模式时不产生任何告警/异常（单实例零回归）
        MockEnvironment env = env("jdbc:h2:file:./data/askdata", "", "db");
        ClusterReadinessChecker checker = checker(env, "local", false, memoryCache());
        setEnabled(checker, false);

        checker.run(null);
        assertFalse(checker.check().items().isEmpty());
    }

    private static void setEnabled(ClusterReadinessChecker checker, boolean enabled) {
        ClusterProperties props = propertiesOf(checker);
        props.setEnabled(enabled);
    }

    private static void setFailFast(ClusterReadinessChecker checker, boolean failFast) {
        propertiesOf(checker).setFailFast(failFast);
    }

    private static ClusterProperties propertiesOf(ClusterReadinessChecker checker) {
        try {
            java.lang.reflect.Field field =
                    ClusterReadinessChecker.class.getDeclaredField("properties");
            field.setAccessible(true);
            return (ClusterProperties) field.get(checker);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClusterReadinessReport.Level levelOf(ClusterReadinessReport report, String name) {
        return itemOf(report, name).level();
    }

    private static ClusterReadinessReport.Item itemOf(ClusterReadinessReport report, String name) {
        List<ClusterReadinessReport.Item> items = report.items().stream()
                .filter(i -> i.name().equals(name)).toList();
        assertFalse(items.isEmpty(), "缺少检查项: " + name);
        return items.get(0);
    }
}
