-- 集群就绪：定时任务执行权租约
-- running 标志之外记录持有者与租约到期时间，使执行实例异常退出后任务可被其它实例回收。
ALTER TABLE scheduled_task ADD COLUMN lock_owner VARCHAR(64);
ALTER TABLE scheduled_task ADD COLUMN lock_expire_at BIGINT;
