package com.xiongx9527.askdata.scheduled;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.support.MetadbMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时任务执行权租约（验收 A9）：抢占互斥、过期回收、持有者续租与释放。
 *
 * <p>用真实 Flyway 迁移建表（含 V3 新增的 lock_owner / lock_expire_at），验证多实例语义：
 * 实例 A 抢占后 B 抢不到；A 的租约过期（等价于 A 被强杀）后 B 可以回收。</p>
 */
class ScheduledTaskLeaseTest {

    private ScheduledTaskRepository repository;

    private long taskId;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:sched_lease_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        repository = new ScheduledTaskRepository(jdbc, new IdGenerator());
        ScheduledTask task = new ScheduledTask();
        task.setName("lease");
        task.setOid(1L);
        task.setCreateBy(1L);
        task.setQuestion("q");
        task.setEnabled(true);
        task.setNextRunAt(System.currentTimeMillis() - 1000L);
        task.setCreateTime(System.currentTimeMillis());
        task.setUpdateTime(System.currentTimeMillis());
        taskId = repository.insert(task);
    }

    @Test
    void secondInstanceCannotStealLiveLease() {
        long now = System.currentTimeMillis();
        assertTrue(repository.claim(taskId, "node-a", now, now + 60_000L));
        // 租约未过期：另一个实例抢不到（等价于任务正在 A 上执行）
        assertFalse(repository.claim(taskId, "node-b", now + 1000L, now + 61_000L));
    }

    @Test
    void expiredLeaseIsReclaimed() {
        long now = System.currentTimeMillis();
        assertTrue(repository.claim(taskId, "node-a", now, now - 1L));
        // 租约已过期：等价于 A 被强杀留下 running 残留，B 可以回收
        assertTrue(repository.claim(taskId, "node-b", now + 1000L, now + 60_000L));
    }

    @Test
    void onlyOwnerCanRenew() {
        long now = System.currentTimeMillis();
        assertTrue(repository.claim(taskId, "node-a", now, now + 30_000L));
        assertTrue(repository.renew(taskId, "node-a", now + 60_000L));
        assertFalse(repository.renew(taskId, "node-b", now + 60_000L));
    }

    @Test
    void releaseClearsOwnerAndLease() {
        long now = System.currentTimeMillis();
        assertTrue(repository.claim(taskId, "node-a", now, now + 30_000L));
        repository.release(taskId, now, now + 60_000L, now);

        ScheduledTask task = repository.findById(taskId).orElseThrow();
        assertFalse(task.isRunning());
        // 释放后可被任意实例重新抢占
        assertTrue(repository.claim(taskId, "node-b", now + 1L, now + 60_000L));
    }

    @Test
    void dueTasksIncludesExpiredLeaseRows() {
        long now = System.currentTimeMillis();
        assertTrue(repository.claim(taskId, "node-a", now, now - 1L));

        List<ScheduledTask> due = repository.dueTasks(now, 10);
        assertTrue(due.stream().anyMatch(t -> t.getId() == taskId),
                "租约过期的任务必须重新出现在待执行列表中");
    }

    @Test
    void dueTasksExcludesLiveLeaseRows() {
        long now = System.currentTimeMillis();
        assertTrue(repository.claim(taskId, "node-a", now, now + 60_000L));

        List<ScheduledTask> due = repository.dueTasks(now, 10);
        assertTrue(due.stream().noneMatch(t -> t.getId() == taskId),
                "租约有效的任务不应被其它实例调度");
        assertEquals(1, repository.findById(taskId).orElseThrow().isRunning() ? 1 : 0);
    }
}
