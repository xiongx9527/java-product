package com.xiongx9527.askdata.common.lock;

/**
 * 集群内单 worker 守卫的锁后端抽象（由 {@code askdata.lock.type} 选择）。
 *
 * <p>实现：</p>
 * <ul>
 *   <li>{@link DbWorkerLock}（缺省）：元数据库表行独占锁，锁随事务持有；</li>
 *   <li>{@link RedisWorkerLock}：Redis {@code SET NX PX} 锁，带持有者标识与 TTL。</li>
 * </ul>
 *
 * <p>语义约束：<b>锁的作用域应与被保护数据的作用域一致</b>。默认的 DB 锁按「每个元数据库
 * 一次」互斥——多实例共享同一元数据库时集群内只跑一次；各实例各用独立库时各跑各的（正确）。
 * Redis 锁是全局的，仅在「共享 Redis + 独立库」这类部署下才有意义，且会改变上述语义。</p>
 */
public interface WorkerLock {

    /**
     * 尝试获取锁（单次尝试，不重试）。
     *
     * @param key 任务键
     * @return true 表示获得锁
     */
    boolean tryAcquire(String key);

    /**
     * 释放锁（只释放自己持有的）。
     *
     * @param key 任务键
     */
    void release(String key);

    /** 后端名称（用于日志）。 */
    String name();
}
