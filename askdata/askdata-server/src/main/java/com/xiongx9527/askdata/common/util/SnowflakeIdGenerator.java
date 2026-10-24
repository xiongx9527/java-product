package com.xiongx9527.askdata.common.util;


import com.xiongx9527.fw.plus.common.uid.UidGenerator;
import com.xiongx9527.fw.plus.common.uid.exception.UidGenerateException;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 雪花 ID 生成器（对齐 SQLBot {@code common/utils/snowflake.py} 的 SnowflakeGenerator）。
 *
 * <p><b>位段布局</b>（共 63 位，最高位恒为 0，故取值恒为正数）：</p>
 * <pre>
 *  0 | 41 位毫秒时间戳 | 5 位数据中心 | 5 位工作节点 | 12 位序列
 * </pre>
 * <ul>
 *   <li>时间戳相对 {@link #epoch}（毫秒），默认 epoch=0（与 SQLBot 逐位一致）；</li>
 *   <li>序列在同一毫秒内自增，溢出（12 位用尽）时等待下一毫秒；</li>
 *   <li>检测到时钟回拨（now &lt; lastTimestamp）时按 SQLBot 语义拒绝生成。</li>
 * </ul>
 *
 * <p><b>为什么不再满足 2^53−1</b>：41+5+5+12 = 63 位的信息量无法压进 53 位——
 * 只要时间戳字段的取值 ≥ 2^31，ID 数值就必然超过 {@code Number.MAX_SAFE_INTEGER}
 * （2^53−1 ≈ 9.007e15）。这是雪花位段正确性与「ID ≤ 2^53−1」两条要求本身的数学冲突。
 * 取舍：以 SQLBot 语义为准（63 位雪花，保证跨进程/跨实例唯一），并在响应序列化层
 * （{@code common/web/JacksonSafeLongConfig}）把超过 2^53−1 的整数值序列化为字符串
 * ——这正是 SQLBot {@code SnowflakeBase.Config.json_encoders} 的做法
 * （{@code str(v) if v > 2**53-1 else v}）。前端 {@code json-bigint} 对字符串原样保留，
 * 两个等值 ID 以字符串比较（{@code ===} 按值比较）不再退化为 BigNumber 对象的引用比较。</p>
 *
 * <p><b>跨进程唯一</b>：不同进程/实例必须配置不同的 {@code workerId}（本机默认取进程 PID 派生），
 * 不同机房配置不同的 {@code datacenterId}；两者组合共 1024 个并发节点。</p>
 */
public class SnowflakeIdGenerator {

    /** 工作节点位宽。 */
    public static final int WORKER_ID_BITS = 5;

    /** 数据中心位宽。 */
    public static final int DATACENTER_ID_BITS = 5;

    /** 序列位宽。 */
    public static final int SEQUENCE_BITS = 12;

    /** 工作节点最大值（2^5−1）。 */
    public static final long MAX_WORKER_ID = -1L ^ (-1L << WORKER_ID_BITS);

    /** 数据中心最大值（2^5−1）。 */
    public static final long MAX_DATACENTER_ID = -1L ^ (-1L << DATACENTER_ID_BITS);

    /** 序列掩码。 */
    public static final long SEQUENCE_MASK = -1L ^ (-1L << SEQUENCE_BITS);

    /** 工作节点左移位数（序列位之上）。 */
    public static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    /** 数据中心左移位数（工作节点位之上）。 */
    public static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 时间戳左移位数（数据中心位之上）。 */
    public static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long workerId;

    private final long datacenterId;

    private final long epoch;

    /** 委托目标：非空时 {@link #nextId()} 直接转发给框架 {@code UidGenerator}（统一 ID 来源）。 */
    private final UidGenerator delegate;

    private final AtomicLong lastTimestamp = new AtomicLong(-1L);

    private long sequence = 0L;

    /**
     * 构造（默认工作节点/数据中心由本机环境派生，epoch=0）。
     *
     * <p>工作节点默认取 {@code PID % 32}：同一台机器上多实例（不同端口/容器）PID 不同，
     * 天然落在不同的 5 位槽位内；跨机器部署时由配置显式指定
     * （{@code askdata.id.worker-id} / {@code askdata.id.datacenter-id}）。</p>
     */
    public SnowflakeIdGenerator() {
        this(deriveWorkerId(), 0L, 0L, true);
    }

    /** 构造（显式工作节点与数据中心；epoch=0）。 */
    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        this(workerId, datacenterId, 0L, true);
    }

    /** 构造（显式工作节点、数据中心与 epoch 毫秒）。 */
    public SnowflakeIdGenerator(long workerId, long datacenterId, long epoch) {
        this(workerId, datacenterId, epoch, true);
    }

    /** 内部构造（{@code useWorkerId=false} 时工作节点按 PID 派生）。 */
    private SnowflakeIdGenerator(long workerId, long datacenterId, long epoch, boolean useWorkerId) {
        long resolvedWorkerId = useWorkerId ? workerId : deriveWorkerId();
        if (resolvedWorkerId < 0 || resolvedWorkerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "worker ID can't be greater than " + MAX_WORKER_ID + " or less than 0");
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    "datacenter ID can't be greater than " + MAX_DATACENTER_ID + " or less than 0");
        }
        this.delegate = null;
        this.workerId = resolvedWorkerId;
        this.datacenterId = datacenterId;
        this.epoch = epoch;
    }

    /**
     * 委托构造：内部由框架 {@code UidGenerator}（{@code CachedUidGenerator}）产出全局唯一 long。
     * 保留 {@link SnowflakeIdGenerator} 类型与 {@code nextId()} 签名，使 30+ 处既有调用点、
     * {@code IdGenerator} 包装类零改动；{@code delegate==null} 时退化为本地雪花（非 Spring 上下文/测试）。
     */
    public SnowflakeIdGenerator(UidGenerator delegate) {
        this.delegate = delegate;
        this.workerId = 0L;
        this.datacenterId = 0L;
        this.epoch = 0L;
    }

    /** 生成下一个 ID（线程安全）。 */
    public synchronized long nextId() {
        if (delegate != null) {
            try {
                return delegate.getUID();
            } catch (UidGenerateException e) {
                throw new IllegalStateException("UidGenerator 生成 ID 失败", e);
            }
        }
        long timestamp = currentTimeMillis();
        long last = lastTimestamp.get();
        if (timestamp < last) {
            // SQLBot 语义：时钟回拨拒绝生成（拒绝优于生成可能重复的 ID）
            throw new IllegalStateException("Clock moved backwards. Refusing to generate ID");
        }
        if (timestamp == last) {
            sequence = (sequence + 1L) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = waitNextMillis(last);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp.set(timestamp);
        return ((timestamp - epoch) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /** 当前工作节点 id（供诊断/审计展示）。 */
    public long workerId() {
        return workerId;
    }

    /** 当前数据中心 id（供诊断/审计展示）。 */
    public long datacenterId() {
        return datacenterId;
    }

    /** 当前纪元毫秒。 */
    public long epoch() {
        return epoch;
    }

    /** 解析 ID 中的时间戳部分（毫秒，epoch 相对值）。 */
    public static long timestampOf(long id) {
        return id >> TIMESTAMP_LEFT_SHIFT;
    }

    /** 解析 ID 中的数据中心位段。 */
    public static long datacenterOf(long id) {
        return (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
    }

    /** 解析 ID 中的工作节点位段。 */
    public static long workerOf(long id) {
        return (id >> WORKER_ID_SHIFT) & MAX_WORKER_ID;
    }

    /** 解析 ID 中的序列位段。 */
    public static long sequenceOf(long id) {
        return id & SEQUENCE_MASK;
    }

    /** 等待到下一毫秒（序列位溢出时）。 */
    private long waitNextMillis(long last) {
        long timestamp = currentTimeMillis();
        while (timestamp <= last) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    /** 当前时间（可覆写以便单测注入）。 */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 默认工作节点：PID 低 5 位。
     *
     * <p>取 PID 而非常量 0，是为了同机多实例（不同端口/容器）不撞号；
     * 跨机器部署用配置显式指定即可。</p>
     */
    public static long deriveWorkerId() {
        try {
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = pid.indexOf('@');
            if (at > 0) {
                long value = Long.parseLong(pid.substring(0, at).trim());
                return Math.floorMod(value, MAX_WORKER_ID + 1);
            }
        } catch (Exception e) {
            // 忽略：退回固定 0
        }
        return 0L;
    }
}
