package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A21 工作空间删除回归：删除空间后用户 {@code oid} 重新选择其仍所属的空间，无归属时置 0，
 * 不再一律改成默认空间 1。
 */
class WorkspaceDeleteOidTest {

    private static final long DEFAULT_WS = 1L;

    private static final long DELETED_WS = 2L;

    private static final long REMAINING_WS = 3L;

    private JdbcTemplate jdbc;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:workspace_delete_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        jdbc.update("INSERT INTO sys_workspace (id, name, create_time) VALUES (?, ?, ?)", DELETED_WS, "aa-ws", 1672531200000L);
        jdbc.update("INSERT INTO sys_workspace (id, name, create_time) VALUES (?, ?, ?)", REMAINING_WS, "bb-ws", 1672531200000L);
        insertUser(2L, "only_deleted");
        insertUser(3L, "has_remaining");
        insertUser(4L, "has_default");
        insertUser(5L, "has_both");
        bind(2L, DELETED_WS);
        bind(3L, DELETED_WS);
        bind(3L, REMAINING_WS);
        bind(4L, DELETED_WS);
        bind(4L, DEFAULT_WS);
        bind(5L, DELETED_WS);
        bind(5L, DEFAULT_WS);
        bind(5L, REMAINING_WS);

        workspaceService = new WorkspaceService(new WorkspaceRepository(jdbc), new UserWsRepository(jdbc),
                new UserRepository(jdbc), Mockito.mock(UserService.class), Mockito.mock(CurrentUserService.class),
                new IdGenerator());
        org.springframework.test.util.ReflectionTestUtils.setField(workspaceService, "messageResolver",
                com.xiongx9527.askdata.common.i18n.TestMessageResolver.create());
    }

    private void insertUser(long id, String account) {
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, language, origin) "
                + "VALUES (?, ?, ?, 'x', ?, ?, 1, 1672531199000, 'zh-CN', 0)",
                id, account, account, account + "@x.com", DELETED_WS);
    }

    private void bind(long uid, long oid) {
        jdbc.update("INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (?, ?, ?, 0)",
                uid * 100 + oid, uid, oid);
    }

    private long oidOf(long uid) {
        return jdbc.queryForObject("SELECT oid FROM sys_user WHERE id = ?", Long.class, uid);
    }

    @Test
    void usersWithoutRemainingWorkspaceFallBackToZero() {
        workspaceService.delete(DELETED_WS);
        assertEquals(0L, oidOf(2L), "无剩余空间时应置 0，而不是默认空间 1");
    }

    @Test
    void usersWithRemainingWorkspaceReselectIt() {
        workspaceService.delete(DELETED_WS);
        assertEquals(REMAINING_WS, oidOf(3L), "应重新选择仍所属的空间");
        assertEquals(DEFAULT_WS, oidOf(4L), "仅剩默认空间时应选择该空间");
    }

    @Test
    void reselectUsesStableWorkspaceOrder() {
        workspaceService.delete(DELETED_WS);
        // 剩余空间按名称排序：bb-ws(3) 在 i18n_default_workspace(1) 之前
        assertEquals(REMAINING_WS, oidOf(5L));
    }

    @Test
    void adminInDefaultWorkspaceIsUnaffected() {
        workspaceService.delete(DELETED_WS);
        assertEquals(DEFAULT_WS, oidOf(1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_ws WHERE oid = ?", Integer.class, DELETED_WS));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_workspace WHERE id = ?", Integer.class, DELETED_WS));
    }

    @Test
    void defaultWorkspaceStillCannotBeDeleted() {
        assertThrows(BadRequestException.class, () -> workspaceService.delete(DEFAULT_WS));
    }
}
