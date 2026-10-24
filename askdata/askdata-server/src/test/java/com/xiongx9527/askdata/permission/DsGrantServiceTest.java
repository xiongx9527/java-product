package com.xiongx9527.askdata.permission;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.web.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源使用授权（ds_grant）白名单覆盖语义：
 * 无授权记录 → 工作空间内共享（向后兼容）；有授权记录 → 未授权成员拒绝建会话；
 * 管理员始终放行；save 校验授权对象须同工作空间；空 user_ids 清空授权。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ds_grant;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class DsGrantServiceTest {

    @Autowired
    private DsGrantService dsGrantService;

    @Autowired
    private DsGrantRepository dsGrantRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private static final long OID = 1L;
    private static final long DS_ID = 901L;
    private static final long USER_ALICE = 902L;
    private static final long USER_BOB = 903L;

    @BeforeEach
    void setUp() {
        SaTokenContextMockUtil.setMockContext();
        StpUtil.login(1L);
        jdbc.update("INSERT INTO core_datasource (id, name, type, oid, create_time) VALUES (?, ?, ?, ?, ?)",
                DS_ID, "grant-test-ds", "mysql", OID, new java.sql.Timestamp(System.currentTimeMillis()));
        for (long uid : new long[]{USER_ALICE, USER_BOB}) {
            jdbc.update("INSERT INTO sys_user (id, account, name, password, oid, status, origin, create_time) "
                            + "VALUES (?, ?, ?, ?, ?, 1, 0, ?)",
                    uid, "grant_user_" + uid, "用户" + uid, "x", OID, System.currentTimeMillis());
        }
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ds_grant WHERE ds_id = ?", DS_ID);
        jdbc.update("DELETE FROM core_datasource WHERE id = ?", DS_ID);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?, ?)", USER_ALICE, USER_BOB);
        StpUtil.logout();
        SaTokenContextMockUtil.clearContext();
    }

    @Test
    void noGrantRecordKeepsWorkspaceSharing() {
        // 未配置任何授权：工作空间内成员（非管理员）可直接使用——向后兼容
        assertDoesNotThrow(() -> dsGrantService.checkUsable(OID, USER_ALICE, false, DS_ID));
        assertDoesNotThrow(() -> dsGrantService.checkUsable(OID, USER_BOB, false, DS_ID));
    }

    @Test
    void whitelistBlocksUngrantedMemberButAllowsAdmin() {
        dsGrantService.save(Map.of("ds_id", DS_ID, "user_ids", List.of(USER_ALICE)));
        // 命中白名单
        assertDoesNotThrow(() -> dsGrantService.checkUsable(OID, USER_ALICE, false, DS_ID));
        // 未命中：拒绝（资源级授权，对齐 SQLBot @require_permissions(type='ds')）
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> dsGrantService.checkUsable(OID, USER_BOB, false, DS_ID));
        assertTrue(ex.getMessage().contains("901"), "错误信息应含数据源 id: " + ex.getMessage());
        // 管理员始终放行
        assertDoesNotThrow(() -> dsGrantService.checkUsable(OID, USER_BOB, true, DS_ID));
        assertTrue(dsGrantRepository.hasAnyGrant(OID, DS_ID));
    }

    @Test
    void saveRejectsUserOutsideWorkspace() {
        long outsider = 999L;
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> dsGrantService.save(Map.of("ds_id", DS_ID, "user_ids", List.of(outsider))));
        assertTrue(ex.getMessage().contains("999"), "错误信息应含用户 id: " + ex.getMessage());
        assertFalse(dsGrantRepository.hasAnyGrant(OID, DS_ID));
    }

    @Test
    void emptyUserIdsClearsGrantBackToSharing() {
        dsGrantService.save(Map.of("ds_id", DS_ID, "user_ids", List.of(USER_ALICE)));
        assertTrue(dsGrantRepository.hasAnyGrant(OID, DS_ID));
        dsGrantService.save(Map.of("ds_id", DS_ID, "user_ids", List.of()));
        assertFalse(dsGrantRepository.hasAnyGrant(OID, DS_ID));
        assertDoesNotThrow(() -> dsGrantService.checkUsable(OID, USER_BOB, false, DS_ID));
    }
}
