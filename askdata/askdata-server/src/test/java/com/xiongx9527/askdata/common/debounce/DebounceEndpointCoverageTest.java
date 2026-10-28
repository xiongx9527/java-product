package com.xiongx9527.askdata.common.debounce;

import com.xiongx9527.askdata.scheduled.ScheduledTaskController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防抖必须落在真实业务接口上（验收 A11）。
 *
 * <p>此前唯一的 {@code @Debounce} 用法是测试控制器，生产代码没有任何受保护接口，
 * 跨实例防抖因此无法被触发。这里锁定真实端点（定时任务立即执行）带注解且按任务 id 分维度，
 * 防止将来被无声移除。</p>
 */
class DebounceEndpointCoverageTest {

    @Test
    void scheduledTaskRunNowIsDebouncedPerTaskId() throws Exception {
        Method runNow = ScheduledTaskController.class.getMethod("runNow", long.class);
        Debounce debounce = runNow.getAnnotation(Debounce.class);

        assertNotNull(debounce, "ScheduledTaskController.runNow 必须标注 @Debounce");
        assertArrayEquals(new String[]{"0"}, debounce.args(),
                "按任务 id（第一个参数）防抖：同一任务重复点击只执行一次，不同任务互不影响");
        assertTrue(debounce.user(), "默认按当前用户隔离，避免不同用户互相拦截");
    }
}
