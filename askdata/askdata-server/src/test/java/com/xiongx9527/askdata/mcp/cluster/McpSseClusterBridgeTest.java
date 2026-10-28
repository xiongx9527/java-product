package com.xiongx9527.askdata.mcp.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.cluster.McpRedisBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP SSE 跨实例中继的单元行为（验收 A13）。
 *
 * <p>框架的 {@code RedisServerSseRegistry.hasSession} 只查本 JVM，非持有实例会直接 404；
 * {@link McpSseClusterBridge} 用 Redis 记录会话归属，并把落到非持有实例的
 * {@code POST /mcp/messages} 转发给持有实例。这里用假 Redis 覆盖三段核心逻辑：
 * 归属登记/续期、归属判定、转发投递。</p>
 */
class McpSseClusterBridgeTest {

    private StringRedisTemplate redis;

    private ValueOperations<String, String> values;

    private McpSseClusterBridge bridge;

    private static final String MY_INSTANCE = "instance-a";

    private static final String SESSION = "session-1";

    private static final String PRESENCE_KEY = McpSseClusterBridge.PRESENCE_PREFIX + SESSION;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        McpRedisBroadcaster broadcaster = mock(McpRedisBroadcaster.class);
        when(broadcaster.getServerId()).thenReturn(MY_INSTANCE);
        bridge = new McpSseClusterBridge(redis, new ObjectMapper(), broadcaster);
    }

    @Test
    void openingSessionRegistersOwnershipWithTtl() {
        bridge.registerSession(SESSION);

        verify(values).set(PRESENCE_KEY, MY_INSTANCE, McpSseClusterBridge.PRESENCE_TTL);
    }

    @Test
    void ownershipIsOnlyRemoteWhenAnotherInstanceRegisteredIt() {
        when(values.get(PRESENCE_KEY)).thenReturn("instance-b");
        assertTrue(bridge.isOwnedElsewhere(SESSION), "其它实例持有 → 需要转发");

        when(values.get(PRESENCE_KEY)).thenReturn(MY_INSTANCE);
        assertFalse(bridge.isOwnedElsewhere(SESSION), "自己持有 → 本地处理");

        when(values.get(PRESENCE_KEY)).thenReturn(null);
        assertFalse(bridge.isOwnedElsewhere(SESSION), "无归属 → 本地处理（不误判）");
    }

    @Test
    void relayedRequestIsDeliveredToTheHoldingInstanceAsync() throws Exception {
        ClusterSseRegistry registry = mock(ClusterSseRegistry.class);
        when(registry.holdsLocalSession(SESSION)).thenReturn(true);
        CountDownLatch delivered = new CountDownLatch(1);
        doAnswer(invocation -> {
            delivered.countDown();
            return null;
        }).when(registry).handleRelayed(eq(SESSION), anyString());
        bridge.bind(registry);

        // 来自另一个实例的转发请求
        bridge.onMessage("{\"type\":\"request\",\"serverId\":\"instance-b\",\"sessionId\":\"" + SESSION
                + "\",\"message\":\"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":1}\"}");

        assertTrue(delivered.await(3, TimeUnit.SECONDS), "持有实例应在异步线程中处理转发请求");
        verify(registry).handleRelayed(SESSION, "{\"jsonrpc\":\"2.0\",\"id\":1}");
    }

    @Test
    void ownEchoIsIgnoredToAvoidDoubleExecution() {
        ClusterSseRegistry registry = mock(ClusterSseRegistry.class);
        bridge.bind(registry);

        bridge.onMessage("{\"type\":\"request\",\"serverId\":\"" + MY_INSTANCE + "\",\"sessionId\":\""
                + SESSION + "\",\"message\":\"{}\"}");

        verify(registry, after(300).never()).handleRelayed(anyString(), anyString());
    }

    @Test
    void relayRequestPublishesToClusterChannel() {
        bridge.relayRequest(SESSION, "{\"jsonrpc\":\"2.0\"}");

        verify(redis).convertAndSend(eq(McpSseClusterBridge.REQUEST_CHANNEL), contains(SESSION));
    }

    @Test
    void unregisterOnlyRemovesOwnershipHeldByThisInstance() {
        when(values.get(PRESENCE_KEY)).thenReturn("instance-b");
        bridge.unregisterSession(SESSION);
        // 归属已换到其它实例：不能把别人的会话删掉
        verify(redis, after(100).never()).delete(PRESENCE_KEY);

        when(values.get(PRESENCE_KEY)).thenReturn(MY_INSTANCE);
        bridge.unregisterSession(SESSION);
        verify(redis).delete(PRESENCE_KEY);
    }

    @Test
    void presenceTtlOutlivesCrashedInstanceWithoutLeakingForever() {
        assertTrue(McpSseClusterBridge.PRESENCE_TTL.getSeconds() > 0);
        assertTrue(Duration.ofMinutes(10).equals(McpSseClusterBridge.PRESENCE_TTL),
                "归属 TTL 应有限，实例崩溃后不会永久把请求转发给死实例");
    }
}
