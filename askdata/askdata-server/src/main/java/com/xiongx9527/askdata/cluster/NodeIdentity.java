package com.xiongx9527.askdata.cluster;

import java.net.InetAddress;
import java.util.UUID;

/**
 * 当前实例标识（{@code host:pid:random}），用于定时任务执行权租约的持有者字段。
 *
 * <p>带随机后缀是为了容器内 PID 恒为 1 时仍能区分实例；长度限制在 64 字符内以适配列宽。</p>
 *
 * <p>以静态工具形式提供，避免为「只读一个字符串」给各服务的构造函数增加依赖。</p>
 */
public final class NodeIdentity {

    private static final String ID = build();

    private NodeIdentity() {
    }

    /** 当前实例标识（进程内恒定）。 */
    public static String value() {
        return ID;
    }

    private static String build() {
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // 主机名不可解析时退化为 unknown
        }
        if (host.length() > 40) {
            host = host.substring(0, 40);
        }
        return host + ":" + currentPid() + ":"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 当前进程号（Java 8 无 ProcessHandle，退回 RuntimeMXBean 的 "pid@host" 前缀）。 */
    private static long currentPid() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return Long.parseLong(at > 0 ? name.substring(0, at) : name);
        } catch (Exception e) {
            return -1L;
        }
    }
}
