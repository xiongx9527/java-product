package com.xiongx9527.askdata.mcp.cluster;

import com.ajaxjs.mcp.server.McpServer;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.cluster.McpRedisBroadcaster;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.cluster.RedisServerSseRegistry;

import java.io.PrintWriter;

/**
 * 集群感知的 SSE 注册表（验收 A13）。
 *
 * <p>框架的 {@link RedisServerSseRegistry} 只在<b>本 JVM</b> 的 connections 里查会话：
 * {@code POST /mcp/messages} 一旦被负载均衡派到非持有实例，控制器会先
 * {@code belongsToRuntime()} 判 404，根本走不到 pub/sub 路由。本类补上缺失的一环：</p>
 *
 * <ul>
 *   <li>{@link #openSession} / {@link #removeConnection} 在 Redis 登记「会话 → 持有实例」；</li>
 *   <li>{@link #hasSession} 认远端会话（让控制器放行，鉴权仍由框架完成）；</li>
 *   <li>{@link #handle} 对远端会话不本地处理，而是把原始 JSON-RPC 转发给持有实例——
 *       MCP 的会话状态（协商版本、能力）保存在持有实例的内存里，必须由它处理。</li>
 * </ul>
 */
public class ClusterSseRegistry extends RedisServerSseRegistry {

    private final McpSseClusterBridge bridge;

    public ClusterSseRegistry(McpServer server, McpRedisBroadcaster broadcaster,
                              McpSseClusterBridge bridge) {
        this(server, broadcaster, "default", bridge);
    }

    public ClusterSseRegistry(McpServer server, McpRedisBroadcaster broadcaster, String routeKey,
                              McpSseClusterBridge bridge) {
        super(server, broadcaster, routeKey);
        this.bridge = bridge;
        bridge.bind(this);
    }

    @Override
    public void openSession(String sessionId, PrintWriter writer, String endpoint) {
        // 先登记再写 endpoint 事件：客户端拿到 endpoint 后会立刻 POST，其它实例必须已能看到归属
        bridge.registerSession(sessionId);
        super.openSession(sessionId, writer, endpoint);
    }

    @Override
    public void removeConnection(String sessionId) {
        try {
            super.removeConnection(sessionId);
        } finally {
            bridge.unregisterSession(sessionId);
        }
    }

    @Override
    public boolean isSessionOpen(String sessionId) {
        boolean open = super.isSessionOpen(sessionId);
        if (open) {
            // SSE 流每秒轮询一次：顺带续期归属，实例被强杀时归属最多在一个 TTL 后失效
            bridge.touchSession(sessionId);
        }
        return open;
    }

    @Override
    public boolean hasSession(String sessionId) {
        return super.hasSession(sessionId) || bridge.isOwnedElsewhere(sessionId);
    }

    @Override
    public void handle(String sessionId, String message) {
        if (!super.hasSession(sessionId) && bridge.isOwnedElsewhere(sessionId)) {
            // 非持有实例：转发给持有实例处理，响应由它写回自己的 SSE 流
            bridge.relayRequest(sessionId, message);
            return;
        }
        super.handle(sessionId, message);
    }

    /** 本实例是否真正持有该 SSE 连接（不经 Redis 归属判断）。 */
    boolean holdsLocalSession(String sessionId) {
        return super.hasSession(sessionId);
    }

    /** 由持有实例在收到转发请求后调用：本地处理并把响应写回本实例的 SSE 流。 */
    void handleRelayed(String sessionId, String message) {
        super.handle(sessionId, message);
    }
}
