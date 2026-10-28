package com.xiongx9527.askdata.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 雪花 ID 跨实例唯一（验收 A12）：两个实例用不同 workerId 各生成 1 万个 ID，合并后无重复。
 *
 * <p>集群默认由框架 {@code WORKER_NODE} 表分配 workerId；本测试验证「workerId 不同即不撞号」
 * 这一前提，配合启动自检对「关闭框架分配且未显式配置」的告警形成完整证据。</p>
 */
class SnowflakeIdUniquenessTest {

    @Test
    void twoInstancesGenerateNoDuplicateIds() {
        SnowflakeIdGenerator nodeA = new SnowflakeIdGenerator(1L, 0L);
        SnowflakeIdGenerator nodeB = new SnowflakeIdGenerator(2L, 0L);

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(nodeA.nextId());
            ids.add(nodeB.nextId());
        }
        assertEquals(20_000, ids.size(), "两实例各 1 万个 ID 必须互不重复");
    }

}
