package com.xiongx9527.askdata.scheduled;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时任务轮询器（A10）：按配置间隔扫描到期任务，抢占后交给执行器。
 *
 * <p>抢占（{@link ScheduledTaskRepository#claim}）保证同一任务不会被并发执行；
 * 执行器在 finally 中释放并计算下一次执行时间。</p>
 */
@Component
public class ScheduledTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskScheduler.class);

    private final ScheduledTaskRepository repository;

    private final ScheduledTaskExecutor executor;

    private final ScheduledTaskProperties properties;

    public ScheduledTaskScheduler(ScheduledTaskRepository repository, ScheduledTaskExecutor executor,
                                  ScheduledTaskProperties properties) {
        this.repository = repository;
        this.executor = executor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${askdata.scheduled.poll-interval-ms:30000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<ScheduledTask> due = repository.dueTasks(now, 20);
        for (ScheduledTask task : due) {
            if (!repository.claim(task.getId(), now)) {
                continue;
            }
            try {
                executor.execute(task, ScheduledTaskRun.TRIGGER_SCHEDULE);
            } catch (Exception e) {
                log.warn("定时任务调度异常 task={}: {}", task.getId(), e.getMessage());
                repository.release(task.getId(), task.getLastRunAt(), task.getNextRunAt(),
                        System.currentTimeMillis());
            }
        }
    }
}

