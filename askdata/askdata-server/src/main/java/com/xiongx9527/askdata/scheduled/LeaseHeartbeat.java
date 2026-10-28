package com.xiongx9527.askdata.scheduled;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务执行权租约续租器。
 *
 * <p>任务开始执行时启动，按租约的 1/3 周期调用 {@link ScheduledTaskRepository#renew}；
 * 执行结束（正常或异常）时关闭。续租失败说明租约已被其它实例回收，此时只告警——
 * 继续执行不会写坏数据（运行记录按 runId 落库），但会与回收后的新执行重复。
 *</p>
 */
final class LeaseHeartbeat implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    /** 共享调度线程（守护线程，避免阻止 JVM 退出）。 */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "scheduled-lease-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledFuture<?> future;

    private LeaseHeartbeat(ScheduledFuture<?> future) {
        this.future = future;
    }

    /** 启动续租；leaseMs 为租约总时长，续租周期取 1/3。 */
    static LeaseHeartbeat start(ScheduledTaskRepository repository, long taskId, String owner, long leaseMs) {
        long period = Math.max(1000L, leaseMs / 3);
        ScheduledFuture<?> future = SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                long until = System.currentTimeMillis() + leaseMs;
                if (!repository.renew(taskId, owner, until)) {
                    log.warn("定时任务租约已丢失 task={} owner={}（可能已被其它实例回收）", taskId, owner);
                }
            } catch (Exception e) {
                log.warn("定时任务续租失败 task={}: {}", taskId, e.getMessage());
            }
        }, period, period, TimeUnit.MILLISECONDS);
        return new LeaseHeartbeat(future);
    }

    @Override
    public void close() {
        future.cancel(false);
    }
}
