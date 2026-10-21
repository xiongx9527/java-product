package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.ContextConfiguration;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 审计切面（A8）验证：写操作经 @AuditLog 自动记录，含真实 execution_time、
 * 资源 ID 提取、成功/失败状态；且审计写入失败不阻断业务。
 */
@ExtendWith({SpringExtension.class, MockitoExtension.class})
@ContextConfiguration(classes = AuditLogAspectTest.Config.class)
class AuditLogAspectTest {

    @Autowired
    private DemoService demo;

    private static AuditRecorder recorder;

    @Captor
    private ArgumentCaptor<AuditModule> moduleCap;

    @Captor
    private ArgumentCaptor<AuditOperationType> opCap;

    @Captor
    private ArgumentCaptor<String> resIdCap;

    @Captor
    private ArgumentCaptor<String> statusCap;

    @Captor
    private ArgumentCaptor<Long> timeCap;

    @BeforeAll
    static void initMocks() {
        recorder = Mockito.mock(AuditRecorder.class);
    }

    @BeforeEach
    void resetMocks() {
        Mockito.reset(recorder);
    }

    @Test
    void recordsSuccessWithRealExecutionTimeAndResourceId() {
        Map<String, Object> result = demo.create();
        assertNotNull(result);
        verify(recorder).recordTimed(opCap.capture(), moduleCap.capture(), resIdCap.capture(),
                any(), any(), any(), statusCap.capture(), any(), timeCap.capture());
        assertEquals(AuditOperationType.CREATE, opCap.getValue());
        assertEquals(AuditModule.DASHBOARD, moduleCap.getValue());
        assertEquals("123", resIdCap.getValue());
        assertEquals("success", statusCap.getValue());
        assertTrue(timeCap.getValue() >= 0, "execution_time should be recorded");
    }

    @Test
    void recordsFailureStatusAndPropagates() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> demo.delete(5L));
        assertEquals("boom", ex.getMessage());
        verify(recorder).recordTimed(opCap.capture(), moduleCap.capture(), resIdCap.capture(),
                any(), any(), any(), statusCap.capture(), any(), timeCap.capture());
        assertEquals(AuditOperationType.DELETE, opCap.getValue());
        assertEquals("5", resIdCap.getValue());
        assertEquals("failed", statusCap.getValue());
    }

    @Test
    void auditWriteFailureDoesNotBlockBusiness() {
        doThrow(new RuntimeException("db down")).when(recorder).recordTimed(any(), any(), any(),
                any(), any(), any(), any(), any(), anyLong());
        // 业务方法仍应正常返回，异常被切面吞掉（仅审计失败）
        Map<String, Object> result = demo.create();
        assertNotNull(result);
        assertEquals(123L, result.get("id"));
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class Config {
        @Bean
        public DemoService demoService() {
            return new DemoService();
        }

        @Bean
        public AuditRecorder auditRecorder() {
            return recorder;
        }

        @Bean
        public AuditLogAspect auditLogAspect(AuditRecorder recorder) {
            return new AuditLogAspect(recorder);
        }
    }

    static class DemoService {
        @AuditLog(module = AuditModule.DASHBOARD, operation = AuditOperationType.CREATE)
        public Map<String, Object> create() {
            Map<String, Object> m = new HashMap<>();
            m.put("id", 123L);
            return m;
        }

        @AuditLog(module = AuditModule.DASHBOARD, operation = AuditOperationType.DELETE)
        public void delete(long id) {
            throw new RuntimeException("boom");
        }
    }
}
