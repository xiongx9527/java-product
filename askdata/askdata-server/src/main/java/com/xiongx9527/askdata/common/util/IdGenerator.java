package com.xiongx9527.askdata.common.util;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 元数据库主键生成器。
 *
 * SQLBot 使用雪花 ID（SnowflakeBase）作为各表主键；askdata 采用等价的自增语义：
 * 毫秒时间戳 × 1000 + 进程内序列，保证单实例唯一且严格递增，同时不超过 JS
 * Number.MAX_SAFE_INTEGER（2^53），前端无需 bigint 解析即可比较 id。
 */
@Component
public class IdGenerator {

    /** 每毫秒预留 1000 个序列位。 */
    private static final long SEQ_PER_MS = 1000L;

    private final AtomicLong lastTimestamp = new AtomicLong(0L);

    private final AtomicLong sequence = new AtomicLong(0L);

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        long last = lastTimestamp.get();
        if (now > last) {
            lastTimestamp.set(now);
            sequence.set(0L);
        }
        long seq = sequence.incrementAndGet();
        if (seq >= SEQ_PER_MS) {
            // 单毫秒内溢出：借用下一毫秒，最多等待 1ms
            now = Math.max(now, last + 1);
            lastTimestamp.set(now);
            sequence.set(0L);
            seq = sequence.incrementAndGet();
        }
        return now * SEQ_PER_MS + seq;
    }
}
