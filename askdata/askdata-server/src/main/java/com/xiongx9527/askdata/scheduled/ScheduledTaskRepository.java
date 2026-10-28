package com.xiongx9527.askdata.scheduled;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.MetadbSql;
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

    private static final String RUN_COLUMNS_TEMPLATE =
            "id, task_id, oid, trigger_type, status, %SQL%, row_count, export_path, error, "
                    + "started_at, finished_at, duration_ms, create_time";

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    /** 元数据库方言无关的 {@code sql} 列写法（MySQL/MariaDB 需要反引号）。 */
    private final String sqlColumn;

    private final String runColumns;

    private final RowMapper<ScheduledTask> taskMapper = (rs, rowNum) -> mapTask(rs);

    private final RowMapper<ScheduledTaskRun> runMapper = (rs, rowNum) -> mapRun(rs);

    public ScheduledTaskRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
        this.sqlColumn = MetadbSql.sqlColumnName(jdbc);
        this.runColumns = RUN_COLUMNS_TEMPLATE.replace("%SQL%", sqlColumn);
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

    /**
     * 到期待执行任务（启用、可抢占、next_run_at 已到）。
     *
     * <p>可抢占 = 未运行，或运行中但租约已过期（执行实例被强杀留下的 running 残留）。
     * {@code lock_expire_at} 为 NULL 的历史行不参与回收，避免滚动升级时抢走旧版本仍在执行的任务。</p>
     */
    public List<ScheduledTask> dueTasks(long now, int limit) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM scheduled_task "
                        + "WHERE enabled = TRUE AND (running = FALSE OR lock_expire_at < ?) "
                        + "AND next_run_at IS NOT NULL AND next_run_at <= ? "
                        + "ORDER BY next_run_at LIMIT ?",
                taskMapper, now, now, limit);
    }

    /**
     * 抢占任务（并发安全：未运行，或原持有者租约已过期）。
     *
     * @param owner      当前实例标识
     * @param leaseUntil 租约到期时间（毫秒时间戳）
     * @return true 表示本实例取得执行权
     */
    public boolean claim(long id, String owner, long now, long leaseUntil) {
        return jdbc.update("UPDATE scheduled_task SET running = TRUE, lock_owner = ?, lock_expire_at = ?, "
                + "update_time = ? WHERE id = ? AND (running = FALSE OR lock_expire_at < ?)",
                owner, leaseUntil, now, id, now) == 1;
    }

    /** 续租（仅持有者可以续；返回 false 表示租约已丢失）。 */
    public boolean renew(long id, String owner, long leaseUntil) {
        return jdbc.update("UPDATE scheduled_task SET lock_expire_at = ? WHERE id = ? AND lock_owner = ?",
                leaseUntil, id, owner) == 1;
    }

    /** 释放任务并写入上次执行时间与下一次执行时间。 */
    public void release(long id, Long lastRunAt, Long nextRunAt, long now) {
        jdbc.update("UPDATE scheduled_task SET running = FALSE, lock_owner = NULL, lock_expire_at = NULL, "
                + "last_run_at = ?, next_run_at = ?, update_time = ? WHERE id = ?",
                lastRunAt, nextRunAt, now, id);
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
        jdbc.update("INSERT INTO scheduled_task_run (" + runColumns + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, run.getTaskId(), run.getOid(), run.getTriggerType(), run.getStatus(), run.getSql(),
                run.getRowCount(), run.getExportPath(), run.getError(), run.getStartedAt(),
                run.getFinishedAt(), run.getDurationMs(), run.getCreateTime());
        return id;
    }

    public void finishRun(long id, String status, String sql, int rowCount, String exportPath, String error,
                          Long finishedAt, Long durationMs) {
        jdbc.update("UPDATE scheduled_task_run SET status = ?, " + sqlColumn + " = ?, row_count = ?, export_path = ?, "
                        + "error = ?, finished_at = ?, duration_ms = ? WHERE id = ?",
                status, sql, rowCount, exportPath, error, finishedAt, durationMs, id);
    }

    public Optional<ScheduledTaskRun> findRun(long id) {
        return jdbc.query("SELECT " + runColumns + " FROM scheduled_task_run WHERE id = ?", runMapper, id)
                .stream().findFirst();
    }

    public List<ScheduledTaskRun> listRuns(long taskId, int limit) {
        return jdbc.query("SELECT " + runColumns + " FROM scheduled_task_run WHERE task_id = ? "
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

