package com.xiongx9527.askdata.common.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 元数据库主键生成器（委托 {@link SnowflakeIdGenerator}）。
 *
 * <p>ID 采用 SQLBot 的雪花位段（41 位毫秒 + 5 位数据中心 + 5 位工作节点 + 12 位序列），
 * 保证<b>跨进程/跨实例唯一</b>——旧的 {@code now*1000+seq} 方案在多实例部署下
 * 会因毫秒时间戳与序列同时相同而产生重复主键。</p>
 *
 * <p>关于「ID ≤ 2^53−1（JS 安全整数）」：41+5+5+12 = 63 位的雪花布局在数学上无法
 * 压进 53 位（时间戳字段一旦 ≥ 2^31，ID 数值必然超过 {@code Number.MAX_SAFE_INTEGER}）。
 * 故以 SQLBot 语义为准采用 63 位雪花，并在响应序列化层把超过 2^53−1 的整数值
 * 序列化为字符串（对齐 SQLBot {@code SnowflakeBase.Config.json_encoders}），
 * 前端 {@code json-bigint} 原样保留字符串，避免精度丢失与对象引用比较问题。</p>
 *
 * <p>本类保留原名与 {@link #nextId()} 签名，使 30+ 处既有调用点无需改动。</p>
 */
@Component
public class IdGenerator {

    private final SnowflakeIdGenerator snowflake;

    public IdGenerator() {
        this(new SnowflakeIdGenerator());
    }

    @Autowired
    public IdGenerator(SnowflakeIdGenerator snowflake) {
        this.snowflake = snowflake;
    }

    /** 生成下一个全局唯一 ID（雪花算法）。 */
    public long nextId() {
        return snowflake.nextId();
    }
}
