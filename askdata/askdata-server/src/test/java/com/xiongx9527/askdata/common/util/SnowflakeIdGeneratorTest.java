package com.xiongx9527.askdata.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花 ID 生成器单测（对齐 SQLBot {@code common/utils/snowflake.py}）。
 *
 * <p>覆盖：位段布局正确性、序列溢出等待下一毫秒、时钟回拨拒绝生成、
 * 跨进程（不同 worker/datacenter）唯一性、并发唯一性。</p>
 */
class SnowflakeIdGeneratorTest {

    /** 可注入时间的生成器（便于确定性地测序列溢出与时钟回拨）。 */
    private static class Testable extends SnowflakeIdGenerator {

        private long now;

        /** true 时每次读取时间后自动推进 1ms（用于让「等待下一毫秒」能收敛）。 */
        private boolean advancing;

        Testable(long workerId, long datacenterId, long startMillis) {
            super(workerId, datacenterId);
            this.now = startMillis;
        }

        void setNow(long millis) {
            this.now = millis;
        }

        /** 开启/关闭「读取即推进」（仅在需要等待下一毫秒时开启）。 */
        void setAdvancing(boolean value) {
            this.advancing = value;
        }

        @Override
        protected long currentTimeMillis() {
            long value = now;
            if (advancing) {
                now = now + 1L;
            }
            return value;
        }
    }

    @Test
    void bitLayoutFollowsSqlBotLayout() {
        Testable generator = new Testable(3, 5, 1_700_000_000_000L);
        long id = generator.nextId();

        long timestamp = SnowflakeIdGenerator.timestampOf(id);
        long datacenter = SnowflakeIdGenerator.datacenterOf(id);
        long worker = SnowflakeIdGenerator.workerOf(id);
        long sequence = SnowflakeIdGenerator.sequenceOf(id);

        assertEquals(1_700_000_000_000L, timestamp);
        assertEquals(5L, datacenter);
        assertEquals(3L, worker);
        assertEquals(0L, sequence);
    }

    @Test
    void sequenceIncrementsWithinSameMillisecond() {
        Testable generator = new Testable(1, 1, 1_700_000_000_000L);
        long first = generator.nextId();
        long second = generator.nextId();
        long third = generator.nextId();

        assertEquals(0L, SnowflakeIdGenerator.sequenceOf(first));
        assertEquals(1L, SnowflakeIdGenerator.sequenceOf(second));
        assertEquals(2L, SnowflakeIdGenerator.sequenceOf(third));
        assertTrue(second > first);
        assertTrue(third > second);
    }

    @Test
    void sequenceResetsOnNextMillisecond() {
        Testable generator = new Testable(1, 1, 1_700_000_000_000L);
        generator.nextId();
        generator.nextId();
        generator.setNow(1_700_000_000_001L);
        long id = generator.nextId();
        assertEquals(0L, SnowflakeIdGenerator.sequenceOf(id));
        assertEquals(1_700_000_000_001L, SnowflakeIdGenerator.timestampOf(id));
    }

    @Test
    void sequenceOverflowWaitsForNextMillisecond() {
        Testable generator = new Testable(1, 1, 1_700_000_000_000L);
        // 12 位序列 → 同毫秒内最多 4096 个
        for (int i = 0; i < 4096; i++) {
            generator.nextId();
        }
        // 第 4097 个：序列溢出 → 等待下一毫秒
        // 注入时钟需在等待期间推进，否则「等待下一毫秒」永不收敛（死等）。
        generator.setAdvancing(true);
        long id = generator.nextId();
        assertEquals(0L, SnowflakeIdGenerator.sequenceOf(id));
        assertTrue(SnowflakeIdGenerator.timestampOf(id) > 1_700_000_000_000L,
                "序列溢出后应等待到下一毫秒");
    }

    @Test
    void clockBackwardsRefusesToGenerate() {
        Testable generator = new Testable(1, 1, 1_700_000_000_000L);
        generator.nextId();
        generator.setNow(1_699_999_999_999L);
        assertThrows(IllegalStateException.class, generator::nextId);
    }

    @Test
    void differentWorkerOrDatacenterProduceDifferentIds() {
        Testable a = new Testable(1, 1, 1_700_000_000_000L);
        Testable b = new Testable(2, 1, 1_700_000_000_000L);
        Testable c = new Testable(1, 2, 1_700_000_000_000L);
        long idA = a.nextId();
        long idB = b.nextId();
        long idC = c.nextId();
        assertNotEquals(idA, idB);
        assertNotEquals(idA, idC);
        assertNotEquals(idB, idC);
    }

    @Test
    void idsAreUniqueAcrossManyInstances() {
        Set<Long> ids = new HashSet<>();
        for (int worker = 0; worker < 32; worker++) {
            SnowflakeIdGenerator generator = new SnowflakeIdGenerator(worker, 0);
            for (int i = 0; i < 100; i++) {
                assertTrue(ids.add(generator.nextId()), "生成的 ID 必须唯一");
            }
        }
        assertEquals(3200, ids.size());
    }

    @Test
    void idsAreUniqueUnderConcurrency() throws Exception {
        final SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit((Callable<List<Long>>) () -> {
                    List<Long> local = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        local.add(generator.nextId());
                    }
                    return local;
                }));
            }
            Set<Long> all = new HashSet<>();
            for (Future<List<Long>> future : futures) {
                all.addAll(future.get());
            }
            assertEquals(threads * perThread, all.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidWorkerOrDatacenterIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, -1));
    }

    @Test
    void idGeneratorDelegatesToSnowflake() {
        IdGenerator generator = new IdGenerator();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(ids.add(generator.nextId()));
        }
    }
}
