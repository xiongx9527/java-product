package com.xiongx9527.askdata.scheduled;

/**
 * 定时问数任务（A10）：按 cron 或固定间隔周期执行一条自然语言问题。
 *
 * <p>任务本身只保存「问题 + 数据源 + 调度 + 输出方式」，不保存 SQL；每次执行都复用
 * NL2SQL 主链路重新生成 SQL，保证 schema 变化后仍能正确查询。</p>
 */
public class ScheduledTask {

    private long id;

    private String name;

    private long oid;

    private long createBy;

    private Long datasourceId;

    private String question;

    /** cron 表达式（与 intervalSeconds 二选一，优先 cron）。 */
    private String cron;

    /** 固定间隔秒数（cron 为空时生效）。 */
    private long intervalSeconds;

    /** 导出格式：csv / xlsx / 空（不导出）。 */
    private String exportFormat;

    /** 导出目录（空则使用全局配置 askdata.scheduled.export-dir）。 */
    private String exportPath;

    /** 推送 webhook（空则不推送）。 */
    private String pushUrl;

    private boolean enabled;

    private boolean running;

    private Long lastRunAt;

    private Long nextRunAt;

    private long createTime;

    private long updateTime;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getOid() { return oid; }
    public void setOid(long oid) { this.oid = oid; }
    public long getCreateBy() { return createBy; }
    public void setCreateBy(long createBy) { this.createBy = createBy; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public long getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(long intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    public String getExportFormat() { return exportFormat; }
    public void setExportFormat(String exportFormat) { this.exportFormat = exportFormat; }
    public String getExportPath() { return exportPath; }
    public void setExportPath(String exportPath) { this.exportPath = exportPath; }
    public String getPushUrl() { return pushUrl; }
    public void setPushUrl(String pushUrl) { this.pushUrl = pushUrl; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }
    public Long getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Long lastRunAt) { this.lastRunAt = lastRunAt; }
    public Long getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Long nextRunAt) { this.nextRunAt = nextRunAt; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }
}

