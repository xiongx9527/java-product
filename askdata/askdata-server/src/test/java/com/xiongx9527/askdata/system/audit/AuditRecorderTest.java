package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.audit.AuditClientIpResolver;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.system.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuditRecorder 数据库落地验证：登录审计、写操作审计（含真实 execution_time、
 * 资源名解析、sys_logs_resource 关联行），以及审计写入永不抛错。
 */
class AuditRecorderTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private UserRepository userRepository;
    private TestableRecorder recorder;
    private long idSeq = 100;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE sys_logs (id BIGINT, operation_type VARCHAR(255), operation_detail VARCHAR(10000), "
                + "user_id BIGINT, user_name VARCHAR(255), operation_status VARCHAR(255), oid BIGINT, ip_address VARCHAR(255), "
                + "user_agent VARCHAR(500), execution_time BIGINT, error_message VARCHAR(10000), "
                + "create_time BIGINT, module VARCHAR(255), remark VARCHAR(255), resource_id VARCHAR(10000), "
                + "resource_name VARCHAR(10000), request_method VARCHAR(255), request_path VARCHAR(255))");
        jdbc.execute("CREATE TABLE sys_logs_resource (id BIGINT, log_id BIGINT, resource_id VARCHAR(10000), "
                + "resource_name VARCHAR(10000), module VARCHAR(255))");
        jdbc.execute("CREATE TABLE core_dashboard (id BIGINT, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT, oid BIGINT, name VARCHAR(255))");
        jdbc.update("INSERT INTO core_dashboard VALUES (5, 'Board5')");
        jdbc.update("INSERT INTO sys_user VALUES (5, 7, 'alice')");

        userRepository = mock(UserRepository.class);
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(Map.of("oid", 7L, "name", "alice")));

        IdGenerator idGen = mock(IdGenerator.class);
        when(idGen.nextId()).thenAnswer(inv -> idSeq++);

        recorder = new TestableRecorder(jdbc, idGen, userRepository,
                new AuditClientIpResolver(), new AuditResourceNameResolver(jdbc), 5L);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    @Test
    void recordLoginStoresUserAndExecutionTime() {
        recorder.recordLogin("accX", AuditOperationType.LOGIN, "success", null, 12L);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM sys_logs");
        assertEquals("accX", row.get("user_name"));
        assertEquals("success", row.get("operation_status"));
        assertEquals(12L, ((Number) row.get("execution_time")).longValue());
        assertEquals("login", row.get("operation_type"));
    }

    @Test
    void recordLoginFailureStoresErrorMessage() {
        recorder.recordLogin("accY", AuditOperationType.LOGIN, "failed", "bad pwd", 3L);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM sys_logs");
        assertEquals("failed", row.get("operation_status"));
        assertEquals("bad pwd", row.get("error_message"));
    }

    @Test
    void recordTimedStoresResourceRowAndResolvedName() {
        recorder.recordTimed(AuditOperationType.CREATE, AuditModule.DASHBOARD, "5", null, null, null,
                "success", null, 7L);
        Map<String, Object> log = jdbc.queryForMap("SELECT * FROM sys_logs WHERE module = 'dashboard'");
        assertEquals("5", log.get("resource_id"));
        assertEquals(7L, ((Number) log.get("execution_time")).longValue());
        Map<String, Object> res = jdbc.queryForMap("SELECT * FROM sys_logs_resource");
        assertEquals("5", res.get("resource_id"));
        assertEquals("Board5", res.get("resource_name"));
        assertEquals("dashboard", res.get("module"));
    }

    @Test
    void recordTimedFailureDoesNotThrow() {
        // 业务方法调用方不应因审计异常而中断
        recorder.recordTimed(AuditOperationType.UPDATE, AuditModule.DASHBOARD, "5", null, null, null,
                "failed", "boom", 1L);
        Map<String, Object> log = jdbc.queryForMap("SELECT * FROM sys_logs WHERE operation_status = 'failed'");
        assertEquals("boom", log.get("error_message"));
    }

    @Test
    void auditWriteFailureNeverPropagates() {
        // 破坏 sys_logs 表，强制审计写入抛错，确认方法不向上抛（容错）
        jdbc.execute("DROP TABLE sys_logs");
        assertDoesNotThrow(() -> recorder.recordLogin("accZ", AuditOperationType.LOGIN, "success", null, 1L));
    }

    /** 测试子类：固定当前用户，绕过 Sa-Token 依赖。 */
    static class TestableRecorder extends AuditRecorder {
        private final long fixedUid;

        TestableRecorder(JdbcTemplate jdbc, IdGenerator idGenerator, UserRepository userRepository,
                        AuditClientIpResolver clientIpResolver, AuditResourceNameResolver resourceNameResolver,
                        long fixedUid) {
            super(jdbc, idGenerator, userRepository, clientIpResolver, resourceNameResolver);
            this.fixedUid = fixedUid;
        }

        @Override
        protected Long currentUid() {
            return fixedUid;
        }
    }
}
