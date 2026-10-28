package com.xiongx9527.askdata.mcp.cluster;

import com.ajaxjs.mcp.server.McpServer;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.cluster.McpRedisBroadcaster;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集群 SSE 注册表的放行与转发判定（验收 A13）。
 *
 * <p>这正是框架缺口所在：{@code McpSseController.handleMessage} 先调
 * {@code runtime.hasSession} → {@code registry.hasSession}，框架实现只查本 JVM 的
 * connections，远端会话直接 404。{@link ClusterSseRegistry} 在<b>不改变鉴权顺序</b>的前提下
 * 让远端会话通过，并把请求交给持有实例。</p>
 */
class ClusterSseRegistryTest {

    @Test
    void remoteOwnedSessionPassesHasSessionAndIsRelayed() {
        McpSseClusterBridge bridge = mock(McpSseClusterBridge.class);
        when(bridge.isOwnedElsewhere("s1")).thenReturn(true);
        ClusterSseRegistry registry = registry(bridge);

        assertTrue(registry.hasSession("s1"), "非持有实例也必须认这个会话，否则控制器直接 404");
        assertFalse(registry.holdsLocalSession("s1"), "本实例并未持有该 SSE 连接");

        registry.handle("s1", "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":2}");

        verify(bridge).relayRequest("s1", "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":2}");
    }

    @Test
    void unknownSessionIsNotAccepted() {
        McpSseClusterBridge bridge = mock(McpSseClusterBridge.class);
        ClusterSseRegistry registry = registry(bridge);

        assertFalse(registry.hasSession("nope"), "既不在本机也不在集群归属中 → 保持 404");
    }

    @Test
    void openingStreamRegistersOwnershipBeforeClientCanPost() {
        McpSseClusterBridge bridge = mock(McpSseClusterBridge.class);
        ClusterSseRegistry registry = registry(bridge);
        StringWriter out = new StringWriter();

        registry.openSession("s2", new PrintWriter(out), "/mcp/messages");

        verify(bridge).registerSession("s2");
        assertTrue(registry.holdsLocalSession("s2"));
        assertTrue(out.toString().contains("endpoint"), "SSE 首个事件应告知客户端 messages 端点");
        registry.removeConnection("s2");
        verify(bridge).unregisterSession("s2");
    }

    @Test
    void localSessionDoesNotGetRelayed() {
        McpSseClusterBridge bridge = mock(McpSseClusterBridge.class);
        ClusterSseRegistry registry = registry(bridge);
        registry.openSession("s3", new PrintWriter(new StringWriter()), "/mcp/messages");

        // 本机持有：handle() 必须走本地处理分支（不投递转发通道）
        try {
            registry.handle("s3", "not-a-valid-json-rpc");
        } catch (RuntimeException ignored) {
            // 无效报文由框架本地处理时抛错，这里只关心没有走转发
        }
        verify(bridge, never()).relayRequest("s3", "not-a-valid-json-rpc");
    }

    private static ClusterSseRegistry registry(McpSseClusterBridge bridge) {
        return new ClusterSseRegistry(mock(McpServer.class), mock(McpRedisBroadcaster.class), bridge);
    }
}
