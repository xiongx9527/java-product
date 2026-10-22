package com.xiongx9527.askdata.scheduled;

/**
 * 定时任务运行历史（A10）：每次调度/手动/重试执行落一条记录，支持失败重试。
 */
public class ScheduledTaskRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_RETRY = "RETRY";

    private long id;

    private long taskId;

    private long oid;

    private String triggerType;

    private String status;

    private String sql;

    private int rowCount;

    private String exportPath;

    private String error;

    private Long startedAt;

    private Long finishedAt;

    private Long durationMs;

    private long createTime;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getTaskId() { return taskId; }
    public void setTaskId(long taskId) { this.taskId = taskId; }
    public long getOid() { return oid; }
    public void setOid(long oid) { this.oid = oid; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public String getExportPath() { return exportPath; }
    public void setExportPath(String exportPath) { this.exportPath = exportPath; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
}

