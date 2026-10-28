package com.xiongx9527.askdata.mcp.cluster;

import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.cluster.McpRedisBroadcaster;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.cluster.RedisServerSseRegistry;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.config.McpProperties;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.runtime.McpRuntimeFactory;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.runtime.McpServerFactory;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.runtime.McpServerRuntime;
import com.xiongx9527.fw.plus.auth.core.InstanceAuthRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把框架创建的 Redis SSE 注册表换成 {@link ClusterSseRegistry}（验收 A13）。
 *
 * <p>框架的 {@code McpServerRuntime} 在构造时就固定了注册表，故这里重写
 * {@link McpRuntimeFactory#createRuntime(String, McpProperties, InstanceAuthRegistry, McpRedisBroadcaster)}
 * ——这是 {@code createRuntimes} 实际调用的重载：先用父类建好运行时，再用集群感知的注册表
 * 重新组装一个运行时，并把同样的注册表交给 {@code McpServer} 作为 transport，
 * 保证服务端主动推送与请求处理走同一个注册表。</p>
 */
class ClusterMcpRuntimeFactory extends McpRuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(ClusterMcpRuntimeFactory.class);

    private final McpSseClusterBridge bridge;

    private final McpRedisBroadcaster broadcaster;

    ClusterMcpRuntimeFactory(McpServerFactory serverFactory, McpSseClusterBridge bridge,
                             McpRedisBroadcaster broadcaster) {
        super(serverFactory);
        this.bridge = bridge;
        this.broadcaster = broadcaster;
    }

    @Override
    public McpServerRuntime createRuntime(String key, McpProperties properties,
                                          InstanceAuthRegistry authRegistry,
                                          McpRedisBroadcaster inheritedBroadcaster) {
        McpServerRuntime runtime = super.createRuntime(key, properties, authRegistry, inheritedBroadcaster);
        McpRedisBroadcaster effective = inheritedBroadcaster != null ? inheritedBroadcaster : broadcaster;
        if (effective == null
                || !(runtime.getRegistry() instanceof RedisServerSseRegistry)
                || runtime.getRegistry() instanceof ClusterSseRegistry) {
            // 没有 Redis（单实例）或不是 SSE 传输：保持框架原行为
            return runtime;
        }
        ClusterSseRegistry registry = new ClusterSseRegistry(runtime.getServer(), effective, bridge);
        runtime.getServer().setTransport(registry);
        log.info("MCP SSE 注册表已切换为集群感知（server={}，instance={}）",
                runtime.getKey(), bridge.instanceId());
        return new McpServerRuntime(runtime.getKey(), runtime.getProperties(), runtime.getServer(),
                runtime.getFeatureMgr(), registry, runtime.getAuthService(), runtime.getBasePackages());
    }
}
