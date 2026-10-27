package com.xiongx9527.askdata.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.i18n.TestMessageResolver;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A21 仪表盘可见性回归：同工作空间内可见即可列表/加载/保存/移动/删除，
 * 不再要求 {@code create_by=当前用户}；跨工作空间访问被拒绝且不泄露名称/配置。
 */
class DashboardWorkspaceVisibilityTest {

    private static final long WS_1 = 1L;

    private static final long WS_2 = 2L;

    /** 同空间其他用户的仪表盘（ws=1, create_by=2）。 */
    private static final String SAME_WS_ID = "board_same_ws";

    /** 其他空间的仪表盘（ws=2, create_by=3）。 */
    private static final String OTHER_WS_ID = "board_other_ws";

    private static final String OTHER_WS_NAME = "外部空间看板";

    private JdbcTemplate jdbc;

    private DashboardService dashboardService;

    private Map<String, Object> currentUser;

    private final MessageResolver messageResolver = TestMessageResolver.create();

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:dashboard_visibility_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        jdbc.update("INSERT INTO sys_workspace (id, name, create_time) VALUES (?, ?, ?)", WS_2, "ws-2", 1672531200000L);
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, language, origin) "
                + "VALUES (2, 'user2', 'User 2', 'x', 'u2@x.com', 1, 1, 1672531199000, 'zh-CN', 0)");
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, language, origin) "
                + "VALUES (3, 'user3', 'User 3', 'x', 'u3@x.com', 2, 1, 1672531199000, 'zh-CN', 0)");
        jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (2, 2, 1, 0)");
        jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (3, 3, 2, 0)");

        DashboardRepository repository = new DashboardRepository(jdbc);
        repository.insert(SAME_WS_ID, "同空间看板", "root", String.valueOf(WS_1), null, 0, "leaf", "dashboard",
                null, "{\"same\":true}", null, 1, "2", 1672531199L);
        repository.insert(OTHER_WS_ID, OTHER_WS_NAME, "root", String.valueOf(WS_2), null, 0, "leaf", "dashboard",
                null, "{\"secret\":\"other-workspace-config\"}", null, 1, "3", 1672531199L);

        currentUser = Rows.of("id", 2L, "account", "user2", "name", "User 2", "oid", WS_1, "language", "zh-CN");
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUserService.currentUser()).thenAnswer(invocation -> currentUser);
        Mockito.when(currentUserService.oidOf(Mockito.any()))
                .thenAnswer(invocation -> Rows.lng(invocation.getArgument(0), "oid", 0L));
        Mockito.when(currentUserService.isAdmin(Mockito.any())).thenReturn(false);
        dashboardService = new DashboardService(repository, currentUserService,
                Mockito.mock(ChartDataExecutor.class), Mockito.mock(AuditRecorder.class), new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(dashboardService, "messageResolver", messageResolver);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void listResourceIncludesOtherUsersDashboardInSameWorkspace() {
        List<Map<String, Object>> tree = dashboardService.listResource(Rows.of("node_type", "leaf"));
        assertEquals(1, tree.size(), "同空间内他人创建的仪表盘应可见");
        assertEquals(SAME_WS_ID, tree.get(0).get("id"));
    }

    @Test
    void loadResourceAllowsSameWorkspaceDashboardCreatedByAnotherUser() {
        Map<String, Object> loaded = dashboardService.loadResource(Rows.of("id", SAME_WS_ID));
        assertEquals("同空间看板", loaded.get("name"));
        assertEquals("{\"same\":true}", loaded.get("component_data"));
    }

    @Test
    void updateAndMoveAndDeleteAllowSameWorkspaceDashboardCreatedByAnotherUser() {
        dashboardService.updateCanvas(Rows.of("id", SAME_WS_ID, "name", "改名后", "component_data", "{}"));
        assertEquals("改名后", dashboardService.loadResource(Rows.of("id", SAME_WS_ID)).get("name"));

        dashboardService.moveResource(SAME_WS_ID, Rows.of("pid", "root"));
        assertTrue(dashboardService.deleteResource(SAME_WS_ID));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM core_dashboard WHERE id = ?", Integer.class, SAME_WS_ID));
    }

    @Test
    void crossWorkspaceLoadIsRejectedWithoutLeakingNameOrConfig() {
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> dashboardService.loadResource(Rows.of("id", OTHER_WS_ID)));
        assertFalse(error.getMessage().contains(OTHER_WS_NAME), "拒绝信息不得泄露其他空间仪表盘名称");
        assertFalse(error.getMessage().contains("other-workspace-config"), "拒绝信息不得泄露其他空间配置");
    }

    @Test
    void crossWorkspaceWriteOperationsAreRejected() {
        assertThrows(BadRequestException.class,
                () -> dashboardService.updateCanvas(Rows.of("id", OTHER_WS_ID, "name", "hacked")));
        assertThrows(BadRequestException.class,
                () -> dashboardService.moveResource(OTHER_WS_ID, Rows.of("pid", "root")));
        assertThrows(BadRequestException.class, () -> dashboardService.deleteResource(OTHER_WS_ID));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_dashboard WHERE id = ?", Integer.class, OTHER_WS_ID));
    }

    @Test
    void checkNameIsScopedToWorkspace() {
        assertFalse(dashboardService.checkName(Rows.of("opt", "newLeaf", "name", "同空间看板")),
                "同空间已有同名资源时应提示重名");
    }
}
