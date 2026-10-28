package com.xiongx9527.askdata.scheduled;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时问数任务配置（A10）。
 */
@Component
@ConfigurationProperties(prefix = "askdata.scheduled")
public class ScheduledTaskProperties {

    /** 是否启用调度轮询。 */
    private boolean enabled = true;

    /** 轮询间隔（毫秒）。 */
    private long pollIntervalMs = 30000L;

    /** 默认导出目录。 */
    private String exportDir = "./data/exports";

    /** 任务执行权租约（秒）：执行期间按 1/3 周期续租，进程被强杀后到期可被其它实例回收。 */
    private long leaseSeconds = 300L;

    /** 推送 webhook 超时（秒）。 */
    private int pushTimeoutSeconds = 10;

    /** 并发执行任务的线程数（单任务耗时不应阻塞其他任务的调度）。 */
    private int workerThreads = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public String getExportDir() { return exportDir; }
    public void setExportDir(String exportDir) { this.exportDir = exportDir; }
    public long getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(long leaseSeconds) { this.leaseSeconds = Math.max(10L, leaseSeconds); }
    public int getPushTimeoutSeconds() { return pushTimeoutSeconds; }
    public void setPushTimeoutSeconds(int pushTimeoutSeconds) { this.pushTimeoutSeconds = pushTimeoutSeconds; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = Math.max(1, workerThreads); }
}

