package com.xiongx9527.askdata.scheduled;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A10：调度时间计算测试（cron / 固定间隔 / 非法配置）。
 */
class ScheduledTaskScheduleTest {

    @Test
    void intervalAddsSeconds() {
        ScheduledTask task = new ScheduledTask();
        task.setIntervalSeconds(60);
        assertEquals(61000L, ScheduledTaskSchedule.nextRunAt(task, 1000L));
    }

    @Test
    void cronComputesNextOccurrence() {
        ScheduledTask task = new ScheduledTask();
        task.setCron("0 0 * * * *");
        Long next = ScheduledTaskSchedule.nextRunAt(task, 1000L);
        assertNotNull(next);
        assertTrue(next > 1000L, String.valueOf(next));
    }

    @Test
    void cronTakesPrecedenceOverInterval() {
        ScheduledTask task = new ScheduledTask();
        task.setCron("0 0 * * * *");
        task.setIntervalSeconds(60);
        Long next = ScheduledTaskSchedule.nextRunAt(task, 1000L);
        assertNotNull(next);
        assertTrue(next > 61000L, String.valueOf(next));
    }

    @Test
    void invalidCronRejected() {
        ScheduledTask task = new ScheduledTask();
        task.setCron("not-a-cron");
        assertThrows(IllegalArgumentException.class, () -> ScheduledTaskSchedule.validate(task));
    }

    @Test
    void requiresCronOrPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> ScheduledTaskSchedule.validate(new ScheduledTask()));
    }
}

