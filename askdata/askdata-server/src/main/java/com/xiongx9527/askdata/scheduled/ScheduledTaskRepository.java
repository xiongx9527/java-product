package com.xiongx9527.askdata.scheduled;

import com.xiongx9527.askdata.common.util.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务与运行历史 JDBC 访问层（A10）。
 */
@Repository
public class ScheduledTaskRepository {

    private static final String TASK_COLUMNS =
            "id, name, oid, create_by, datasource_id, question, cron, interval_seconds, "
                    + "export_format, export_path, push_url, enabled, running, last_run_at, next_run_at, "
                    + "create_time, update_time";

    private static final String RUN_COLUMNS =
            "id, task_id, oid, trigger_type, status, sql, row_count, export_path, error, "
                    + "started_at, finished_at, duration_ms, create_time";

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    private final RowMapper<ScheduledTask> taskMapper = (rs, rowNum) -> mapTask(rs);

    private final RowMapper<ScheduledTaskRun> runMapper = (rs, rowNum) -> mapRun(rs);

    public ScheduledTaskRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    public long insert(ScheduledTask task) {
        long id = idGenerator.nextId();
        task.setId(id);
        jdbc.update("INSERT INTO scheduled_task (" + TASK_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, task.getName(), task.getOid(), task.getCreateBy(), task.getDatasourceId(),
                task.getQuestion(), task.getCron(), task.getIntervalSeconds(), task.getExportFormat(),
                task.getExportPath(), task.getPushUrl(), task.isEnabled(), task.isRunning(),
                task.getLastRunAt(), task.getNextRunAt(), task.getCreateTime(), task.getUpdateTime());
        return id;
    }

    public void update(ScheduledTask task) {
        jdbc.update("UPDATE scheduled_task SET name = ?, datasource_id = ?, question = ?, cron = ?, "
                        + "interval_seconds = ?, export_format = ?, export_path = ?, push_url = ?, "
                        + "enabled = ?, next_run_at = ?, update_time = ? WHERE id = ?",
                task.getName(), task.getDatasourceId(), task.getQuestion(), task.getCron(),
                task.getIntervalSeconds(), task.getExportFormat(), task.getExportPath(), task.getPushUrl(),
                task.isEnabled(), task.getNextRunAt(), task.getUpdateTime(), task.getId());
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM scheduled_task_run WHERE task_id = ?", id);
        jdbc.update("DELETE FROM scheduled_task WHERE id = ?", id);
    }

    public Optional<ScheduledTask> findById(long id) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM scheduled_task WHERE id = ?", taskMapper, id)
                .stream().findFirst();
    }

    public List<ScheduledTask> listByOid(long oid) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM scheduled_task WHERE oid = ? "
                + "ORDER BY create_time DESC", taskMapper, oid);
    }

    /** 到期待执行任务（启用、未在运行、next_run_at 已到）。 */
    public List<ScheduledTask> dueTasks(long now, int limit) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM scheduled_task "
                        + "WHERE enabled = TRUE AND running = FALSE AND next_run_at IS NOT NULL "
                        + "AND next_run_at <= ? ORDER BY next_run_at LIMIT ?",
                taskMapper, now, limit);
    }

    /** 抢占任务（并发安全：仅当未运行时置 running=TRUE）。 */
    public boolean claim(long id, long now) {
        return jdbc.update("UPDATE scheduled_task SET running = TRUE, update_time = ? "
                + "WHERE id = ? AND running = FALSE", now, id) == 1;
    }

    /** 释放任务并写入上次执行时间与下一次执行时间。 */
    public void release(long id, Long lastRunAt, Long nextRunAt, long now) {
        jdbc.update("UPDATE scheduled_task SET running = FALSE, last_run_at = ?, next_run_at = ?, "
                + "update_time = ? WHERE id = ?", lastRunAt, nextRunAt, now, id);
    }

    /** 启用/停用任务并重置下一次执行时间。 */
    public void setEnabled(long id, boolean enabled, Long nextRunAt, long now) {
        jdbc.update("UPDATE scheduled_task SET enabled = ?, next_run_at = ?, update_time = ? WHERE id = ?",
                enabled, nextRunAt, now, id);
    }

    // ------------------------------------------------------------------
    // 运行历史
    // ------------------------------------------------------------------

    public long insertRun(ScheduledTaskRun run) {
        long id = idGenerator.nextId();
        run.setId(id);
        jdbc.update("INSERT INTO scheduled_task_run (" + RUN_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, run.getTaskId(), run.getOid(), run.getTriggerType(), run.getStatus(), run.getSql(),
                run.getRowCount(), run.getExportPath(), run.getError(), run.getStartedAt(),
                run.getFinishedAt(), run.getDurationMs(), run.getCreateTime());
        return id;
    }

    public void finishRun(long id, String status, String sql, int rowCount, String exportPath, String error,
                          Long finishedAt, Long durationMs) {
        jdbc.update("UPDATE scheduled_task_run SET status = ?, sql = ?, row_count = ?, export_path = ?, "
                        + "error = ?, finished_at = ?, duration_ms = ? WHERE id = ?",
                status, sql, rowCount, exportPath, error, finishedAt, durationMs, id);
    }

    public Optional<ScheduledTaskRun> findRun(long id) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM scheduled_task_run WHERE id = ?", runMapper, id)
                .stream().findFirst();
    }

    public List<ScheduledTaskRun> listRuns(long taskId, int limit) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM scheduled_task_run WHERE task_id = ? "
                + "ORDER BY started_at DESC LIMIT ?", runMapper, taskId, limit);
    }

    // ------------------------------------------------------------------
    // 映射
    // ------------------------------------------------------------------

    private static ScheduledTask mapTask(ResultSet rs) throws SQLException {
        ScheduledTask task = new ScheduledTask();
        task.setId(rs.getLong("id"));
        task.setName(rs.getString("name"));
        task.setOid(rs.getLong("oid"));
        task.setCreateBy(rs.getLong("create_by"));
        task.setDatasourceId(nullableLong(rs, "datasource_id"));
        task.setQuestion(rs.getString("question"));
        task.setCron(rs.getString("cron"));
        task.setIntervalSeconds(rs.getLong("interval_seconds"));
        task.setExportFormat(rs.getString("export_format"));
        task.setExportPath(rs.getString("export_path"));
        task.setPushUrl(rs.getString("push_url"));
        task.setEnabled(rs.getBoolean("enabled"));
        task.setRunning(rs.getBoolean("running"));
        task.setLastRunAt(nullableLong(rs, "last_run_at"));
        task.setNextRunAt(nullableLong(rs, "next_run_at"));
        task.setCreateTime(rs.getLong("create_time"));
        task.setUpdateTime(rs.getLong("update_time"));
        return task;
    }

    private static ScheduledTaskRun mapRun(ResultSet rs) throws SQLException {
        ScheduledTaskRun run = new ScheduledTaskRun();
        run.setId(rs.getLong("id"));
        run.setTaskId(rs.getLong("task_id"));
        run.setOid(rs.getLong("oid"));
        run.setTriggerType(rs.getString("trigger_type"));
        run.setStatus(rs.getString("status"));
        run.setSql(rs.getString("sql"));
        run.setRowCount(rs.getInt("row_count"));
        run.setExportPath(rs.getString("export_path"));
        run.setError(rs.getString("error"));
        run.setStartedAt(nullableLong(rs, "started_at"));
        run.setFinishedAt(nullableLong(rs, "finished_at"));
        run.setDurationMs(nullableLong(rs, "duration_ms"));
        run.setCreateTime(rs.getLong("create_time"));
        return run;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}

