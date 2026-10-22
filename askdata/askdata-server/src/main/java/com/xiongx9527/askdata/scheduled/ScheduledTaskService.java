package com.xiongx9527.askdata.scheduled;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时问数任务管理（A10）：CRUD、启停、立即执行、失败重试、运行历史。
 */
@Service
public class ScheduledTaskService {

    private final ScheduledTaskRepository repository;

    private final ScheduledTaskExecutor executor;

    private final CurrentUserService currentUserService;

    public ScheduledTaskService(ScheduledTaskRepository repository, ScheduledTaskExecutor executor,
                                CurrentUserService currentUserService) {
        this.repository = repository;
        this.executor = executor;
        this.currentUserService = currentUserService;
    }

    /** 当前工作空间（管理员全部，普通用户仅自己创建）的定时任务。 */
    public List<Map<String, Object>> list() {
        Map<String, Object> user = currentUserService.currentUser();
        long oid = currentUserService.oidOf(user);
        long uid = Rows.lng(user, "id", 0L);
        boolean admin = currentUserService.isAdmin(user);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScheduledTask task : repository.listByOid(oid)) {
            if (!admin && task.getCreateBy() != uid) {
                continue;
            }
            result.add(view(task));
        }
        return result;
    }

    public Map<String, Object> get(long id) {
        return view(requireOwned(id, currentUserService.currentUser()));
    }

    /** 创建或更新任务（cron 与 interval 二选一，优先 cron）。 */
    @Transactional
    public Map<String, Object> save(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        long oid = currentUserService.oidOf(user);
        long uid = Rows.lng(user, "id", 0L);
        long now = System.currentTimeMillis();
        String name = required(body, "name");
        String question = required(body, "question");
        Long datasourceId = Rows.lngOrNull(body, "datasource_id");
        if (datasourceId == null) {
            throw new BadRequestException("datasource_id is required");
        }
        Long id = Rows.lngOrNull(body, "id");
        ScheduledTask task;
        if (id != null) {
            task = requireOwned(id, user);
        } else {
            task = new ScheduledTask();
            task.setOid(oid);
            task.setCreateBy(uid);
            task.setCreateTime(now);
        }
        task.setName(name);
        task.setQuestion(question);
        task.setDatasourceId(datasourceId);
        task.setCron(Rows.str(body, "cron"));
        task.setIntervalSeconds(Rows.lng(body, "interval_seconds", 0L));
        task.setExportFormat(Rows.str(body, "export_format"));
        task.setExportPath(Rows.str(body, "export_path"));
        task.setPushUrl(Rows.str(body, "push_url"));
        task.setEnabled(Rows.bool(body, "enabled", false));
        task.setUpdateTime(now);
        try {
            ScheduledTaskSchedule.validate(task);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        task.setNextRunAt(task.isEnabled() ? ScheduledTaskSchedule.nextRunAt(task, now) : null);
        if (id == null) {
            long newId = repository.insert(task);
            return view(repository.findById(newId).orElse(task));
        }
        repository.update(task);
        return view(repository.findById(task.getId()).orElse(task));
    }

    @Transactional
    public void delete(long id) {
        requireOwned(id, currentUserService.currentUser());
        repository.delete(id);
    }

    /** 启用任务（下一次执行时间从当前时刻计算）。 */
    public Map<String, Object> start(long id) {
        ScheduledTask task = requireOwned(id, currentUserService.currentUser());
        try {
            ScheduledTaskSchedule.validate(task);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        long now = System.currentTimeMillis();
        repository.setEnabled(id, true, ScheduledTaskSchedule.nextRunAt(task, now), now);
        return get(id);
    }

    /** 停用任务（清空下一次执行时间）。 */
    public Map<String, Object> stop(long id) {
        requireOwned(id, currentUserService.currentUser());
        repository.setEnabled(id, false, null, System.currentTimeMillis());
        return get(id);
    }

    /** 立即执行一次（不影响调度计划）。 */
    public Map<String, Object> runNow(long id) {
        ScheduledTask task = requireOwned(id, currentUserService.currentUser());
        long now = System.currentTimeMillis();
        if (!repository.claim(id, now)) {
            throw new BadRequestException("Scheduled task with id " + id + " is running");
        }
        return runView(executor.execute(task, ScheduledTaskRun.TRIGGER_MANUAL));
    }

    /** 重试一次历史运行（失败重试入口）。 */
    public Map<String, Object> retry(long runId) {
        ScheduledTaskRun previous = repository.findRun(runId)
                .orElseThrow(() -> new BadRequestException("Scheduled task run with id " + runId + " not found"));
        ScheduledTask task = requireOwned(previous.getTaskId(), currentUserService.currentUser());
        long now = System.currentTimeMillis();
        if (!repository.claim(task.getId(), now)) {
            throw new BadRequestException("Scheduled task with id " + task.getId() + " is running");
        }
        return runView(executor.execute(task, ScheduledTaskRun.TRIGGER_RETRY));
    }

    /** 运行历史（倒序，最多 50 条）。 */
    public List<Map<String, Object>> runs(long taskId) {
        requireOwned(taskId, currentUserService.currentUser());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScheduledTaskRun run : repository.listRuns(taskId, 50)) {
            result.add(runView(run));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private ScheduledTask requireOwned(long id, Map<String, Object> user) {
        ScheduledTask task = repository.findById(id)
                .orElseThrow(() -> new BadRequestException("Scheduled task with id " + id + " not found"));
        if (currentUserService.isAdmin(user)) {
            return task;
        }
        if (task.getOid() != currentUserService.oidOf(user)
                || task.getCreateBy() != Rows.lng(user, "id", 0L)) {
            throw new BadRequestException("Scheduled task with id " + id + " not found");
        }
        return task;
    }

    private String required(Map<String, Object> body, String key) {
        String value = Rows.str(body, key);
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(key + " is required");
        }
        return value.trim();
    }

    private Map<String, Object> view(ScheduledTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("name", task.getName());
        view.put("oid", task.getOid());
        view.put("create_by", task.getCreateBy());
        view.put("datasource_id", task.getDatasourceId());
        view.put("question", task.getQuestion());
        view.put("cron", task.getCron());
        view.put("interval_seconds", task.getIntervalSeconds());
        view.put("export_format", task.getExportFormat());
        view.put("export_path", task.getExportPath());
        view.put("push_url", task.getPushUrl());
        view.put("enabled", task.isEnabled());
        view.put("running", task.isRunning());
        view.put("last_run_at", task.getLastRunAt());
        view.put("next_run_at", task.getNextRunAt());
        view.put("create_time", task.getCreateTime());
        view.put("update_time", task.getUpdateTime());
        return view;
    }

    private Map<String, Object> runView(ScheduledTaskRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.getId());
        view.put("task_id", run.getTaskId());
        view.put("oid", run.getOid());
        view.put("trigger_type", run.getTriggerType());
        view.put("status", run.getStatus());
        view.put("sql", run.getSql());
        view.put("row_count", run.getRowCount());
        view.put("export_path", run.getExportPath());
        view.put("error", run.getError());
        view.put("started_at", run.getStartedAt());
        view.put("finished_at", run.getFinishedAt());
        view.put("duration_ms", run.getDurationMs());
        view.put("create_time", run.getCreateTime());
        return view;
    }
}

