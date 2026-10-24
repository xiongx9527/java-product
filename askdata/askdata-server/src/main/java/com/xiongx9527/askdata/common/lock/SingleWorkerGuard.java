package com.xiongx9527.askdata.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单 worker 启动任务守卫（对齐 SQLBot {@code common/utils/distributed_lock.py} 的 SingleWorkerGuard）。
 *
 * <p><b>目标</b>：启动期的一次性任务（如向量回填）在<b>集群内只由一个实例执行</b>；
 * 未获得守卫的实例直接跳过且<b>不重试</b>；应用关闭时释放守卫。</p>
 *
 * <p><b>锁后端可配置</b>（{@code askdata.lock.type}）：</p>
 * <ul>
 *   <li>{@code db}（缺省）：元数据库表行独占锁（{@link DbWorkerLock}）——H2 无 advisory lock
 *       的等价实现，锁作用域与被保护数据（元数据库）一致；</li>
 *   <li>{@code redis}：Redis {@code SET NX PX} 锁（{@link RedisWorkerLock}）——全局作用域，
 *       仅在「共享 Redis + 各实例独立元数据库」的部署下有意义。</li>
 * </ul>
 *
 * <p><b>兜底</b>：锁不可用（表建不出来 / Redis 不可达）时退化为进程内守卫
 * （{@link #tryAcquireInProcess(String)}），此时每个实例都会执行回填——回填本身幂等
 * （只填空向量），不会造成数据错误。</p>
 */
@Component
public class SingleWorkerGuard {

    private static final Logger log = LoggerFactory.getLogger(SingleWorkerGuard.class);

    /** 向量回填任务的守卫键（对齐 SQLBot 的 {@code sqlbot:startup:embedding}）。 */
    public static final String EMBEDDING_BACKFILL_KEY = "sqlbot:startup:embedding";

    /** 选定的锁后端（db / redis）。 */
    private final WorkerLock workerLock;

    /** 本进程持有的守卫（键 → 持有状态）。 */
    private final Map<String, Boolean> acquired = new ConcurrentHashMap<>();

    /** 进程内守卫（同一 JVM 多上下文的兜底）。 */
    private final AtomicBoolean inProcess = new AtomicBoolean(false);

    public SingleWorkerGuard(WorkerLock workerLock) {
        this.workerLock = workerLock;
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 尝试获取守卫（单次尝试，不重试）。
     *
     * @param key 任务键
     * @return true 表示本实例获得守卫、应当执行任务
     */
    public boolean tryAcquire(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (acquired.containsKey(key)) {
            return true;
        }
        try {
            if (workerLock.tryAcquire(key)) {
                acquired.put(key, Boolean.TRUE);
                log.info("已获取单 worker 守卫：{}（后端：{}）", key, workerLock.name());
                return true;
            }
            log.info("未获取单 worker 守卫（其它实例已持有，跳过且不重试）：{}", key);
            return false;
        } catch (Exception e) {
            log.info("获取单 worker 守卫失败（退化为进程内守卫）：{} - {}", key, e.getMessage());
            return tryAcquireInProcess(key);
        }
    }

    /**
     * 释放守卫（应用关闭时调用）。
     *
     * @param key 任务键
     */
    public void release(String key) {
        if (key == null) {
            return;
        }
        if (acquired.remove(key) != null) {
            try {
                workerLock.release(key);
            } catch (Exception e) {
                log.info("释放单 worker 守卫失败 {}: {}", key, e.getMessage());
            }
            log.info("已释放单 worker 守卫：{}（后端：{}）", key, workerLock.name());
        }
        inProcess.set(false);
    }

    /** 释放全部守卫（应用关闭时调用）。 */
    public void releaseAll() {
        for (String key : new ArrayList<>(acquired.keySet())) {
            release(key);
        }
    }

    /** 进程内守卫（同一 JVM 内只允许一个实例执行）。 */
    public boolean tryAcquireInProcess(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (acquired.containsKey(key)) {
            return true;
        }
        if (inProcess.compareAndSet(false, true)) {
            acquired.put(key, Boolean.TRUE);
            return true;
        }
        return false;
    }
}
