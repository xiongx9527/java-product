package com.xiongx9527.askdata.cluster;

import java.util.List;

/**
 * 集群就绪自检结果。
 *
 * <p>每项包含检查名、结论、现场值与修复建议；{@link #hasFailure()} 为 true 表示存在硬性
 * 不满足项（多实例下必然出错），{@link #hasWarning()} 为 true 表示需要运维确认但可启动。</p>
 */
public record ClusterReadinessReport(List<Item> items) {

    /** 单项检查结论。 */
    public enum Level {
        /** 满足集群要求。 */
        OK,
        /** 可启动，但多实例下行为需要运维确认。 */
        WARN,
        /** 多实例下必然出错。 */
        FAIL
    }

    /** 单项检查。
     *
     * @param name   检查项名称
     * @param level  结论
     * @param detail 现场值（不含敏感信息）
     * @param fix    修复建议；OK 时为空
     */
    public record Item(String name, Level level, String detail, String fix) {
    }

    public boolean hasFailure() {
        return items.stream().anyMatch(i -> i.level() == Level.FAIL);
    }

    public boolean hasWarning() {
        return items.stream().anyMatch(i -> i.level() == Level.WARN);
    }

    /** 第一处硬性不满足项（用于 fail-fast 的错误信息）。 */
    public Item firstFailure() {
        return items.stream().filter(i -> i.level() == Level.FAIL).findFirst().orElse(null);
    }
}
