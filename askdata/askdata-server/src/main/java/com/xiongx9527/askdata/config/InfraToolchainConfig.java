package com.xiongx9527.askdata.config;

import com.xiongx9527.askdata.common.util.SnowflakeIdGenerator;
import com.xiongx9527.askdata.datasource.engine.ConnectionPoolManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础设施工具链的 Bean 装配（连接池上限、雪花 ID 位段参数）。
 *
 * <p>配置项：</p>
 * <ul>
 *   <li>{@code askdata.datasource.pool.max-pools}：连接池上限（默认 500，对齐 SQLBot
 *       {@code ConnectionPoolManager(max_pools=500)}）；</li>
 *   <li>{@code askdata.id.worker-id}：雪花工作节点 id（缺省按 PID 派生，同机多实例不撞号）；</li>
 *   <li>{@code askdata.id.datacenter-id}：雪花数据中心 id（缺省 0）；</li>
 *   <li>{@code askdata.id.epoch}：雪花纪元毫秒（缺省 0，与 SQLBot 逐位一致）。</li>
 * </ul>
 */
@Configuration
public class InfraToolchainConfig {

    /** 连接池管理器（上限可配，默认 500）。 */
    @Bean
    public ConnectionPoolManager connectionPoolManager(
            @Value("${askdata.datasource.pool.max-pools:500}") int maxPools) {
        return new ConnectionPoolManager(maxPools);
    }

    /** 雪花 ID 生成器（工作节点/数据中心/纪元可配）。 */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${askdata.id.worker-id:-1}") long workerId,
            @Value("${askdata.id.datacenter-id:0}") long datacenterId,
            @Value("${askdata.id.epoch:0}") long epoch) {
        return new SnowflakeIdGenerator(workerId < 0 ? SnowflakeIdGenerator.deriveWorkerId() : workerId,
                datacenterId, epoch);
    }

    /**
     * 应用密钥加解密器：把配置化的密钥注入 {@link com.xiongx9527.askdata.datasource.support.DatasourceCrypto}
     * （该类为静态工具，保留静态入口以便既有调用点零改动）。
     *
     * <p>由 {@link com.xiongx9527.askdata.system.auth.CryptoUtil} 自身的 {@code @Component}
     * 装配完成，此处只做「把已装配实例推给静态工具」的动作（应用启动早期执行）。</p>
     */
    @Bean
    public com.xiongx9527.askdata.datasource.support.DatasourceCryptoInitializer datasourceCryptoInitializer(
            com.xiongx9527.askdata.system.auth.CryptoUtil util) {
        com.xiongx9527.askdata.datasource.support.DatasourceCrypto.configure(util);
        return new com.xiongx9527.askdata.datasource.support.DatasourceCryptoInitializer();
    }
}
