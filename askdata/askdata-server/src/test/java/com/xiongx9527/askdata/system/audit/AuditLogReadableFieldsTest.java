package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.i18n.TestMessageResolver;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A22 审计日志可读字段回归：{@code operation_type_name} 为本地化标签、
 * {@code operation_detail_info} 为真实详情、{@code oid_name} 为工作空间名称，
 * 且切换语言后标签同步变化。
 */
class AuditLogReadableFieldsTest {

    private static final long OID = 1L;

    private JdbcTemplate jdbc;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:audit_readable_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        insertLog(1L, "create", null, "销售看板", "POST", "/api/v1/dashboard/create_canvas");
        insertLog(2L, "update", "修改画布布局", null, "PUT", "/api/v1/dashboard/update_canvas");
        insertLog(3L, "delete", null, null, "DELETE", "/api/v1/dashboard/delete_resource/board-1");
        insertLog(4L, "view", null, null, null, null);

        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", 1L, "account", "admin", "oid", OID, "language", "zh-CN"));
        Mockito.when(currentUserService.isAdmin(Mockito.any())).thenReturn(true);

        auditLogService = new AuditLogService(new AuditLogRepository(jdbc), currentUserService,
                new WorkspaceRepository(jdbc));
        ReflectionTestUtils.setField(auditLogService, "messageResolver", TestMessageResolver.create());
    }

    private void insertLog(long id, String type, String detail, String resourceName, String method, String path) {
        jdbc.update("INSERT INTO sys_logs (id, operation_type, operation_detail, user_id, user_name, operation_status, "
                        + "oid, ip_address, execution_time, create_time, module, resource_id, resource_name, "
                        + "request_method, request_path) VALUES (?, ?, ?, 1, 'Administrator', 'success', ?, "
                        + "'127.0.0.1', 0, ?, 'dashboard', 'r" + id + "', ?, ?, ?)",
                id, type, detail, OID, 1672531199000L + id, resourceName, method, path);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> items, long id) {
        return items.stream().filter(item -> String.valueOf(id).equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("missing audit row " + id));
    }

    private List<Map<String, Object>> page() {
        PagerResult<Map<String, Object>> page = auditLogService.pager(1, 10, null, null, null, null, null, null, null);
        return page.getData();
    }

    @Test
    void operationTypeNameIsLocalizedLabel() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        List<Map<String, Object>> items = page();
        assertEquals("新建", rowOf(items, 1L).get("operation_type_name"));
        assertEquals("更新", rowOf(items, 2L).get("operation_type_name"));
        assertEquals("删除", rowOf(items, 3L).get("operation_type_name"));
        assertEquals("查看", rowOf(items, 4L).get("operation_type_name"));
    }

    @Test
    void labelsFollowLanguageSwitch() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("新建", rowOf(page(), 1L).get("operation_type_name"));
        assertEquals("默认工作空间", rowOf(page(), 1L).get("oid_name"));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("Create", rowOf(page(), 1L).get("operation_type_name"));
        assertEquals("Default Workspace", rowOf(page(), 1L).get("oid_name"));
    }

    @Test
    void operationDetailInfoIsNeverEmpty() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        List<Map<String, Object>> items = page();
        for (Map<String, Object> item : items) {
            Object detail = item.get("operation_detail_info");
            assertNotNull(detail, "操作详情不得为空: " + item.get("id"));
            assertFalse(String.valueOf(detail).trim().isEmpty(), "操作详情不得为空串: " + item.get("id"));
        }
        assertTrue(String.valueOf(rowOf(items, 1L).get("operation_detail_info")).contains("销售看板"),
                "无 detail 时应回退到真实资源名");
        assertEquals("修改画布布局", rowOf(items, 2L).get("operation_detail_info"));
        assertTrue(String.valueOf(rowOf(items, 3L).get("operation_detail_info"))
                .contains("/api/v1/dashboard/delete_resource/board-1"));
    }

    @Test
    void oidNameIsWorkspaceNameNotNumericId() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        Object oidName = rowOf(page(), 1L).get("oid_name");
        assertEquals("默认工作空间", oidName);
        assertFalse(String.valueOf(oidName).matches("\\d+"), "oid_name 不得是数字 id");
    }

    @Test
    void unknownOperationTypeFallsBackToCode() {
        insertLog(9L, "custom_op", "自定义操作", null, "POST", "/api/v1/custom");
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("custom_op", rowOf(page(), 9L).get("operation_type_name"));
    }
}
