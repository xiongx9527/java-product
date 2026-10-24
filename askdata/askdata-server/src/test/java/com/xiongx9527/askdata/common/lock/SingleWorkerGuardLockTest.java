package com.xiongx9527.askdata.common.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单 worker 守卫的锁后端契约（纯 JUnit + H2，不需要 Redis）：
 * 1) DB 后端：首次获取成功，释放后在<b>防抖窗口内</b>不再重复执行；
 * 2) 防抖关闭（窗口 0）时，释放后可再次获取；
 * 3) 守卫在锁不可用时退化为进程内守卫。
 */
class SingleWorkerGuardLockTest {

    private static final String KEY = "sqlbot:startup:embedding";

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:worker_lock_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    @Test
    @DisplayName("DB 后端：释放后落在防抖窗口内则跳过（防重启风暴重复触发一次性任务）")
    void dbLockDebouncesWithinWindow() {
        DbWorkerLock lock = new DbWorkerLock(jdbc, Duration.ofSeconds(300L));
        assertTrue(lock.tryAcquire(KEY), "首次应获得锁");
        lock.release(KEY);
        assertFalse(lock.tryAcquire(KEY), "防抖窗口内不应再次获得锁");
    }

    @Test
    @DisplayName("DB 后端：防抖关闭（窗口 0）时释放后可再次获取")
    void dbLockWithoutDebounceCanReacquire() {
        DbWorkerLock lock = new DbWorkerLock(jdbc, Duration.ZERO);
        assertTrue(lock.tryAcquire(KEY));
        lock.release(KEY);
        assertTrue(lock.tryAcquire(KEY), "未开启防抖时应可再次获取");
        lock.release(KEY);
    }

    @Test
    @DisplayName("守卫：未获得锁时跳过且不重试；进程内兜底可用")
    void guardFallsBackToInProcess() {
        SingleWorkerGuard guard = new SingleWorkerGuard(new DbWorkerLock(jdbc, Duration.ZERO));
        assertTrue(guard.tryAcquire(KEY));
        guard.release(KEY);

        // 锁后端不可用时退化为进程内守卫
        SingleWorkerGuard broken = new SingleWorkerGuard(new WorkerLock() {
            @Override public boolean tryAcquire(String key) { throw new IllegalStateException("lock unavailable"); }
            @Override public void release(String key) { }
            @Override public String name() { return "broken"; }
        });
        assertTrue(broken.tryAcquire(KEY), "锁不可用时应退化为进程内守卫");
        assertFalse(broken.tryAcquire("other-key"), "进程内守卫同一时刻只允许一个任务");
    }
}
