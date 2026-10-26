package com.xiongx9527.askdata.config;

import com.xiongx9527.askdata.common.util.PortableWorkerIdAssigner;
import com.xiongx9527.askdata.common.util.SnowflakeIdGenerator;
import com.xiongx9527.askdata.datasource.engine.ConnectionPoolManager;
import com.xiongx9527.fw.plus.common.uid.UidGenerator;
import com.xiongx9527.fw.plus.common.uid.worker.WorkerIdAssigner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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

    /**
     * 覆盖框架 {@code DisposableWorkerIdAssigner}：后者以 {@code setObject(Date)} 写 WORKER_NODE，
     * PostgreSQL 驱动无法推断类型。此处显式绑定 JDBC 时间类型，保证 H2/PostgreSQL/MySQL 均可启动。
     */
    @Bean
    public WorkerIdAssigner workerIdAssigner(JdbcTemplate jdbcTemplate) {
        return new PortableWorkerIdAssigner(jdbcTemplate);
    }

    /**
     * 对外暴露的 ID 生成器 bean（保持 {@link SnowflakeIdGenerator} 类型与 {@code nextId()} 签名，
     * 使 30+ 处既有调用点、{@code IdGenerator} 包装类零改动）。
     * 优先委托框架 {@code UidGenerator}（由 {@code UIDAutoConfiguration} 创建，底层
     * {@code CachedUidGenerator} + 框架 {@code DisposableWorkerIdAssigner} 写 {@code WORKER_NODE} 表）；
     * 若未启用（{@code fw.plus.uid.enabled} 关闭或上下文无 UidGenerator），回退本地雪花。
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(ObjectProvider<UidGenerator> uidGeneratorProvider,
            @Value("${askdata.id.worker-id:-1}") long workerId,
            @Value("${askdata.id.datacenter-id:0}") long datacenterId,
            @Value("${askdata.id.epoch:0}") long epoch) {
        UidGenerator uid = uidGeneratorProvider.getIfAvailable();
        if (uid != null) {
            return new SnowflakeIdGenerator(uid);
        }
        long resolvedWorkerId = workerId < 0 ? SnowflakeIdGenerator.deriveWorkerId() : workerId;
        return new SnowflakeIdGenerator(resolvedWorkerId, datacenterId, epoch);
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
