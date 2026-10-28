package com.xiongx9527.askdata.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 集群模式配置（{@code askdata.cluster.*}）。
 *
 * <p>缺省关闭：不配置时应用保持单实例语义（H2 文件库 + 本地文件存储 + 进程内会话/缓存），
 * 可零外部依赖启动。开启后启动期执行就绪自检，逐项检查是否满足「任意实例处理任意请求」的
 * 共享化要求（元数据库、应用密钥、会话、缓存、锁、文件存储、雪花 workerId、MCP 会话注册表）。</p>
 */
@Component
@ConfigurationProperties(prefix = "askdata.cluster")
public class ClusterProperties {

    /** 是否启用集群模式（缺省 false，保持单实例零外部依赖启动）。 */
    private boolean enabled = false;

    /** 自检发现不满足项时是否拒绝启动（缺省 false：只告警，不阻断）。 */
    private boolean failFast = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }
}
