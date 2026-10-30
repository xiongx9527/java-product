package com.xiongx9527.askdata.cluster;

import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.config.CacheProperties;
import com.xiongx9527.askdata.mcp.cluster.McpSseClusterBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 集群就绪自检：{@code askdata.cluster.enabled=true} 时在启动期逐项核对「任意实例处理任意请求」
 * 所依赖的共享化前提，输出可读诊断，并按 {@code askdata.cluster.fail-fast} 决定是否拒绝启动。
 *
 * <p>检查项与判据：</p>
 * <ul>
 *   <li>元数据库：H2 文件/内存库为 FAIL（各实例各一份数据）；</li>
 *   <li>应用密钥：{@code askdata.secret.key} 为空为 FAIL（每进程随机，跨实例无法解密落库密文）；</li>
 *   <li>会话与缓存：非 Redis 为 FAIL（登录态与缓存不跨实例）；</li>
 *   <li>单例锁：db 后端要求共享元数据库，redis 后端要求共享 Redis；</li>
 *   <li>文件存储：local 平台为 FAIL（文件写在生成它的节点上）；</li>
 *   <li>雪花 workerId：关闭框架分配且未显式配置时为 WARN（PID 派生在容器内易撞号）；</li>
 *   <li>MCP SSE：无 {@code StringRedisTemplate} 时为 WARN（退化为进程内会话，需负载均衡粘性会话）。</li>
 * </ul>
 */
@Component
public class ClusterReadinessChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClusterReadinessChecker.class);

    private final ClusterProperties properties;

    private final CacheProperties cacheProperties;

    private final Environment environment;

    private final ObjectProvider<AskdataFileStorage> fileStorageProvider;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    private final ObjectProvider<McpSseClusterBridge> mcpBridgeProvider;

    public ClusterReadinessChecker(ClusterProperties properties, CacheProperties cacheProperties,
                                   Environment environment,
                                   ObjectProvider<AskdataFileStorage> fileStorageProvider,
                                   ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                   ObjectProvider<McpSseClusterBridge> mcpBridgeProvider) {
        this.properties = properties;
        this.cacheProperties = cacheProperties;
        this.environment = environment;
        this.fileStorageProvider = fileStorageProvider;
        this.redisTemplateProvider = redisTemplateProvider;
        this.mcpBridgeProvider = mcpBridgeProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        ClusterReadinessReport report = check();
        for (ClusterReadinessReport.Item item : report.items()) {
            switch (item.level()) {
                case OK:
                    log.info("集群自检 [通过] {}：{}", item.name(), item.detail());
                    break;
                case WARN:
                    log.warn("集群自检 [告警] {}：{}；建议：{}", item.name(), item.detail(), item.fix());
                    break;
                case FAIL:
                    log.error("集群自检 [不满足] {}：{}；修复：{}", item.name(), item.detail(), item.fix());
                    break;
                default:
                    break;
            }
        }
        long ok = report.items().stream().filter(i -> i.level() == ClusterReadinessReport.Level.OK).count();
        long warn = report.items().stream().filter(i -> i.level() == ClusterReadinessReport.Level.WARN).count();
        long fail = report.items().stream().filter(i -> i.level() == ClusterReadinessReport.Level.FAIL).count();
        log.info("集群自检汇总：通过 {} 项 / 告警 {} 项 / 不满足 {} 项（fail-fast={}）",
                ok, warn, fail, properties.isFailFast());
        if (properties.isFailFast() && report.hasFailure()) {
            ClusterReadinessReport.Item first = report.firstFailure();
            throw new IllegalStateException("集群模式启动被拒绝（askdata.cluster.fail-fast=true）："
                    + (first == null ? "存在不满足项" : first.name() + " - " + first.detail()
                    + "；修复：" + first.fix()));
        }
    }

    /** 执行全部检查（供启动流程与测试复用）。 */
    public ClusterReadinessReport check() {
        List<ClusterReadinessReport.Item> items = new ArrayList<>();
        items.add(checkMetadataDatabase());
        items.add(checkSecretKey());
        items.add(checkSessionStore());
        items.add(checkCacheBackend());
        items.add(checkLockBackend());
        items.add(checkFileStorage());
        items.add(checkWorkerId());
        items.add(checkMcpSse());
        return new ClusterReadinessReport(items);
    }

    private ClusterReadinessReport.Item checkMetadataDatabase() {
        String url = environment.getProperty("spring.datasource.url", "");
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:h2:file") || lower.startsWith("jdbc:h2:mem")) {
            return new ClusterReadinessReport.Item("元数据库", ClusterReadinessReport.Level.FAIL,
                    "当前为 H2（" + lower.replaceAll("jdbc:h2:(file|mem):[^;?]*", "jdbc:h2:$1:…") + "），各实例各一份数据",
                    "改用共享 MySQL/PostgreSQL（spring.datasource.url + 对应 Flyway 方言目录）");
        }
        if (url.isEmpty()) {
            return new ClusterReadinessReport.Item("元数据库", ClusterReadinessReport.Level.FAIL,
                    "未配置 spring.datasource.url", "配置共享元数据库连接");
        }
        return new ClusterReadinessReport.Item("元数据库", ClusterReadinessReport.Level.OK, url, "");
    }

    private ClusterReadinessReport.Item checkSecretKey() {
        String key = environment.getProperty("askdata.secret.key", "");
        if (key == null || key.trim().isEmpty()) {
            return new ClusterReadinessReport.Item("应用密钥", ClusterReadinessReport.Level.FAIL,
                    "askdata.secret.key 为空，各实例启动期会各自随机生成",
                    "为所有实例配置同一个 ASKDATA_SECRET_KEY，否则跨实例无法解密数据源密码等密文");
        }
        return new ClusterReadinessReport.Item("应用密钥", ClusterReadinessReport.Level.OK,
                "已配置（长度 " + key.length() + "）", "");
    }

    private ClusterReadinessReport.Item checkSessionStore() {
        String store = cacheProperties.resolveSaTokenStore();
        if (CacheProperties.TYPE_REDIS.equals(store)) {
            return new ClusterReadinessReport.Item("会话存储", ClusterReadinessReport.Level.OK, "redis", "");
        }
        return new ClusterReadinessReport.Item("会话存储", ClusterReadinessReport.Level.FAIL,
                "当前为 " + store + "，登录态只存在于处理登录请求的实例",
                "设置 ASKDATA_SA_TOKEN_STORE=redis（或 ASKDATA_CACHE_TYPE=redis）");
    }

    private ClusterReadinessReport.Item checkCacheBackend() {
        String type = cacheProperties.resolveType();
        if (CacheProperties.TYPE_REDIS.equals(type)) {
            return new ClusterReadinessReport.Item("缓存后端", ClusterReadinessReport.Level.OK, "redis", "");
        }
        if (CacheProperties.TYPE_NONE.equals(type)) {
            return new ClusterReadinessReport.Item("缓存后端", ClusterReadinessReport.Level.WARN,
                    "none（不缓存）", "可用，但用户/API Key/助手每次请求都会查库；集群建议 redis");
        }
        return new ClusterReadinessReport.Item("缓存后端", ClusterReadinessReport.Level.FAIL,
                "当前为 " + type + "，写操作失效只影响本实例，其它实例最多 TTL 内仍是旧值",
                "设置 ASKDATA_CACHE_TYPE=redis");
    }

    private ClusterReadinessReport.Item checkLockBackend() {
        String type = environment.getProperty("askdata.lock.type", "db").trim().toLowerCase(Locale.ROOT);
        if ("redis".equals(type)) {
            boolean redisAvailable = redisTemplateProvider.getIfAvailable() != null;
            return new ClusterReadinessReport.Item("单例锁",
                    redisAvailable ? ClusterReadinessReport.Level.OK : ClusterReadinessReport.Level.FAIL,
                    "redis（" + (redisAvailable ? "已装配" : "未装配 StringRedisTemplate") + "）",
                    redisAvailable ? "" : "确认 Redis 连接可用，或改用 askdata.lock.type=db（需共享元数据库）");
        }
        String url = environment.getProperty("spring.datasource.url", "").toLowerCase(Locale.ROOT);
        boolean sharedDb = !url.startsWith("jdbc:h2:");
        return new ClusterReadinessReport.Item("单例锁",
                sharedDb ? ClusterReadinessReport.Level.OK : ClusterReadinessReport.Level.WARN,
                "db 行锁（作用域=元数据库）",
                sharedDb ? "" : "元数据库非共享时行锁不跨实例，建议 askdata.lock.type=redis");
    }

    private ClusterReadinessReport.Item checkFileStorage() {
        AskdataFileStorage storage = fileStorageProvider.getIfAvailable();
        String platform = storage == null ? "disabled" : storage.platform();
        if ("local".equalsIgnoreCase(platform)) {
            return new ClusterReadinessReport.Item("文件存储", ClusterReadinessReport.Level.FAIL,
                    "local（文件写在处理上传的实例本地目录）",
                    "设置 FILE_STORAGE_PLATFORM=minio|aliyun-oss|tencent-cos|amazon-s3 并补齐凭据，或挂载共享存储");
        }
        return new ClusterReadinessReport.Item("文件存储",
                "disabled".equals(platform) ? ClusterReadinessReport.Level.FAIL : ClusterReadinessReport.Level.OK,
                platform, "disabled".equals(platform) ? "文件存储未装配" : "");
    }

    private ClusterReadinessReport.Item checkWorkerId() {
        boolean uidEnabled = Boolean.TRUE.equals(
                environment.getProperty("fw.plus.uid.enabled", Boolean.class, Boolean.TRUE));
        long workerId = environment.getProperty("askdata.id.worker-id", Long.class, -1L);
        if (uidEnabled) {
            return new ClusterReadinessReport.Item("雪花 workerId", ClusterReadinessReport.Level.OK,
                    "由框架 WORKER_NODE 表分配（依赖共享元数据库）", "");
        }
        if (workerId >= 0) {
            return new ClusterReadinessReport.Item("雪花 workerId", ClusterReadinessReport.Level.OK,
                    "显式配置 worker-id=" + workerId, "");
        }
        return new ClusterReadinessReport.Item("雪花 workerId", ClusterReadinessReport.Level.WARN,
                "fw.plus.uid.enabled=false 且未配置 askdata.id.worker-id，回退为 PID%32 派生（容器内 PID 常为 1）",
                "为每个实例显式配置不同的 askdata.id.worker-id，或恢复 fw.plus.uid.enabled=true");
    }

    private ClusterReadinessReport.Item checkMcpSse() {
        boolean mcpEnabled = Boolean.TRUE.equals(
                environment.getProperty("fw-plus.ai.mcp.enabled", Boolean.class, Boolean.TRUE));
        if (!mcpEnabled) {
            return new ClusterReadinessReport.Item("MCP 会话", ClusterReadinessReport.Level.OK, "未启用", "");
        }
        String transport = environment.getProperty("fw-plus.ai.mcp.transport", "http-sse");
        if (!"http-sse".equalsIgnoreCase(transport)) {
            return new ClusterReadinessReport.Item("MCP 会话", ClusterReadinessReport.Level.OK,
                    "transport=" + transport, "");
        }
        boolean redisRegistry = redisTemplateProvider.getIfAvailable() != null;
        boolean bridgeActive = mcpBridgeProvider != null && mcpBridgeProvider.getIfAvailable() != null;
        if (redisRegistry && bridgeActive) {
            return new ClusterReadinessReport.Item("MCP 会话", ClusterReadinessReport.Level.OK,
                    "http-sse + Redis 会话归属与请求中继（会话在哪个实例建立，"
                            + "POST /mcp/messages 落到其它实例时由中继转发给持有实例）", "");
        }
        if (redisRegistry) {
            return new ClusterReadinessReport.Item("MCP 会话", ClusterReadinessReport.Level.WARN,
                    "http-sse 且 Redis 已装配，但 MCP SSE 集群中继未启用（未装配 McpSseClusterBridge）；"
                            + "POST /mcp/messages 落到非持有实例仍会 session not found",
                    "确认集群 profile 生效（askdata.cache.type=redis）以激活 McpSseClusterConfiguration，"
                            + "或为负载均衡开启粘性会话");
        }
        return new ClusterReadinessReport.Item("MCP 会话", ClusterReadinessReport.Level.WARN,
                "http-sse 且无 StringRedisTemplate，SSE 会话为进程内；POST /mcp/messages 落到其它实例会 session not found",
                "启用 Redis（askdata.cache.type=redis）以激活 RedisServerSseRegistry，或为负载均衡开启粘性会话");
    }
}
