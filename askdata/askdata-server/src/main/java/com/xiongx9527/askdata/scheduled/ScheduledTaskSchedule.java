package com.xiongx9527.askdata.scheduled;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 调度时间计算（A10）：cron 优先，其次固定间隔。
 */
public final class ScheduledTaskSchedule {

    private ScheduledTaskSchedule() {
    }

    /**
     * 计算下一次执行时间（epoch millis）。
     *
     * @return 下一次执行时间；既无 cron 也无正间隔时返回 null
     */
    public static Long nextRunAt(ScheduledTask task, long fromMillis) {
        String cron = task.getCron();
        if (cron != null && !cron.trim().isEmpty()) {
            CronExpression expression = parseCron(cron);
            ZonedDateTime next = expression.next(
                    Instant.ofEpochMilli(fromMillis).atZone(ZoneId.systemDefault()));
            return next == null ? null : next.toInstant().toEpochMilli();
        }
        if (task.getIntervalSeconds() > 0L) {
            return fromMillis + task.getIntervalSeconds() * 1000L;
        }
        return null;
    }

    /** 解析并校验 cron（非法表达式抛出 IllegalArgumentException）。 */
    public static CronExpression parseCron(String cron) {
        try {
            return CronExpression.parse(cron.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron, e);
        }
    }

    /** 调度配置校验：必须提供合法 cron 或正间隔。 */
    public static void validate(ScheduledTask task) {
        String cron = task.getCron();
        boolean hasCron = cron != null && !cron.trim().isEmpty();
        if (!hasCron && task.getIntervalSeconds() <= 0L) {
            throw new IllegalArgumentException("Either cron or a positive intervalSeconds is required");
        }
        if (hasCron) {
            parseCron(cron);
        }
    }
}

