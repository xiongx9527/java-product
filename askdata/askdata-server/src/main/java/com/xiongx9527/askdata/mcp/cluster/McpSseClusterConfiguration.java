package com.xiongx9527.askdata.mcp.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.cluster.McpRedisBroadcaster;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.runtime.McpRuntimeFactory;
import com.xiongx9527.fw.plus.ai.autoconfigure.mcp.server.runtime.McpServerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * MCP SSE 跨实例装配（验收 A13）。
 *
 * <p>仅在确实启用 Redis 时激活（与 {@code RedisClientConfig} 同一条件），未启用 Redis 时
 * 容器内没有 {@code StringRedisTemplate}，框架也不会启用 Redis 集群注册表，本配置不生效，
 * 单实例行为不变（验收 A14）。</p>
 */
@Configuration
@ConditionalOnExpression("'${askdata.cache.type:memory}' == 'redis'"
        + " || '${askdata.cache.sa-token-store:}' == 'redis'"
        + " || '${askdata.lock.type:db}' == 'redis'")
public class McpSseClusterConfiguration {

    @Bean
    public McpSseClusterBridge mcpSseClusterBridge(StringRedisTemplate stringRedisTemplate,
                                                   ObjectMapper objectMapper,
                                                   ObjectProvider<McpRedisBroadcaster> broadcasterProvider) {
        return new McpSseClusterBridge(stringRedisTemplate, objectMapper, broadcasterProvider.getIfAvailable());
    }

    @Bean
    public RedisMessageListenerContainer mcpSseRelayListenerContainer(RedisConnectionFactory connectionFactory,
                                                                     McpSseClusterBridge bridge) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> bridge.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(McpSseClusterBridge.REQUEST_CHANNEL));
        return container;
    }

    /**
     * 用集群感知的 {@link ClusterMcpRuntimeFactory} 覆盖框架默认的工厂。
     *
     * <p>框架的 {@code mcpServerRuntimes(...)} 按类型注入 {@link McpRuntimeFactory}，
     * {@code @Primary} 让本实例胜出；避免额外创建一套运行时。</p>
     */
    @Bean
    @Primary
    public McpRuntimeFactory clusterMcpRuntimeFactory(ObjectProvider<McpServerFactory> serverFactory,
                                                      McpSseClusterBridge bridge,
                                                      ObjectProvider<McpRedisBroadcaster> broadcasterProvider) {
        return new ClusterMcpRuntimeFactory(serverFactory.getIfAvailable(), bridge,
                broadcasterProvider.getIfAvailable());
    }
}
