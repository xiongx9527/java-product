package com.xiongx9527.askdata.mcp.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.cluster.McpRedisBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP SSE 会话归属与请求转发的 Redis 通道（验收 A13）。
 *
 * <p>SSE 传输下，客户端与服务端之间有一条长连接；集群里这条连接只存在于某个实例。
 * 本类用 Redis 记录「会话 → 持有实例」，并提供一条请求转发通道：非持有实例收到
 * {@code POST /mcp/messages} 时把原始 JSON-RPC 转发给持有实例，由持有实例处理并写回 SSE 流。
 * 这样负载均衡无需粘性会话。</p>
 *
 * <p>所有 Redis 操作失败都只降级为「按本地处理」（记 debug 日志），不阻断 MCP 调用。</p>
 */
public class McpSseClusterBridge {

    private static final Logger log = LoggerFactory.getLogger(McpSseClusterBridge.class);

    /** 会话归属键前缀：值为持有实例 id。 */
    static final String PRESENCE_PREFIX = "askdata:mcp:sse:session:";

    /** 请求转发通道。 */
    static final String REQUEST_CHANNEL = "askdata:mcp:sse:requests";

    /** 归属 TTL：SSE 流每秒续期；实例崩溃后最多这么久内不再转发到它。 */
    static final Duration PRESENCE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    private final String instanceId;

    private final List<ClusterSseRegistry> registries = new CopyOnWriteArrayList<>();

    /** 慢调用（如 tools/call）不阻塞 Redis 订阅线程。 */
    private final ExecutorService executor;

    public McpSseClusterBridge(StringRedisTemplate redis, ObjectMapper objectMapper,
                               McpRedisBroadcaster broadcaster) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.instanceId = broadcaster != null && broadcaster.getServerId() != null
                ? broadcaster.getServerId() : UUID.randomUUID().toString();
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "mcp-sse-relay-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        log.info("MCP SSE 集群会话归属已启用（instanceId={}）", instanceId);
    }

    /** 本实例标识（与框架 publisher 的 serverId 一致，便于日志对齐）。 */
    public String instanceId() {
        return instanceId;
    }

    void bind(ClusterSseRegistry registry) {
        registries.add(registry);
    }

    /** SSE 流建立：登记会话归属。 */
    void registerSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        try {
            redis.opsForValue().set(presenceKey(sessionId), instanceId, PRESENCE_TTL);
        } catch (RuntimeException e) {
            log.debug("登记 MCP SSE 会话归属失败（降级为进程内）: {}", e.getMessage());
        }
    }

    /** SSE 流存活期间续期。 */
    void touchSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        try {
            redis.expire(presenceKey(sessionId), PRESENCE_TTL);
        } catch (RuntimeException e) {
            log.debug("续期 MCP SSE 会话归属失败: {}", e.getMessage());
        }
    }

    /** SSE 流关闭：清理归属。 */
    void unregisterSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        try {
            String key = presenceKey(sessionId);
            String owner = redis.opsForValue().get(key);
            // 只删自己的归属，避免误删同 id 的新会话（重连后由新实例持有）
            if (owner == null || owner.equals(instanceId)) {
                redis.delete(key);
            }
        } catch (RuntimeException e) {
            log.debug("清理 MCP SSE 会话归属失败: {}", e.getMessage());
        }
    }

    /** 会话是否由集群中<b>其它</b>实例持有。 */
    boolean isOwnedElsewhere(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        try {
            String owner = redis.opsForValue().get(presenceKey(sessionId));
            return owner != null && !owner.isEmpty() && !owner.equals(instanceId);
        } catch (RuntimeException e) {
            log.debug("查询 MCP SSE 会话归属失败（按本地处理）: {}", e.getMessage());
            return false;
        }
    }

    /** 非持有实例：把原始 JSON-RPC 转发给持有实例。 */
    void relayRequest(String sessionId, String message) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("type", "request");
            payload.put("sessionId", sessionId);
            payload.put("message", message);
            payload.put("serverId", instanceId);
            redis.convertAndSend(REQUEST_CHANNEL, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("转发 MCP SSE 请求失败，客户端需重试: {}", e.getMessage());
        }
    }

    /** Redis 订阅回调：只处理其它实例转发来的请求。 */
    void onMessage(String payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String from = node.path("serverId").asText(null);
            if (from != null && from.equals(instanceId)) {
                return;
            }
            String sessionId = node.path("sessionId").asText(null);
            String message = node.path("message").asText(null);
            if (sessionId == null || message == null) {
                return;
            }
            executor.execute(() -> deliver(sessionId, message));
        } catch (Exception e) {
            log.warn("解析 MCP SSE 转发请求失败: {}", e.getMessage());
        }
    }

    private void deliver(String sessionId, String message) {
        for (ClusterSseRegistry registry : registries) {
            if (registry.holdsLocalSession(sessionId)) {
                try {
                    registry.handleRelayed(sessionId, message);
                } catch (RuntimeException e) {
                    log.warn("处理转发的 MCP SSE 请求失败: {}", e.getMessage());
                }
                return;
            }
        }
        log.debug("收到 MCP SSE 转发请求但本实例不持有会话 {}，忽略", sessionId);
    }

    private static String presenceKey(String sessionId) {
        return PRESENCE_PREFIX + sessionId;
    }
}
