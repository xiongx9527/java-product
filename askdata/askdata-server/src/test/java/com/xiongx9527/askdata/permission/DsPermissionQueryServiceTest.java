package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.common.util.Lists;
import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.system.repository.VariableRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A9：数据权限商业深度测试（真实 H2 ds_rules/ds_permission + 仓储查询）。
 *
 * <p>覆盖：多条规则 AND 组合行过滤、多条列规则取并集（可见列交集）、
 * 规则组/条目白名单豁免、非命中用户与跨工作空间规则不生效。</p>
 */
class DsPermissionQueryServiceTest {

    private static final String ROW_REGION = "{\"logic\":\"AND\",\"items\":[{\"type\":\"item\",\"field_id\":1001,"
            + "\"term\":\"eq\",\"value\":\"east\"}]}";
    private static final String ROW_AMOUNT = "{\"logic\":\"AND\",\"items\":[{\"type\":\"item\",\"field_id\":1003,"
            + "\"term\":\"gt\",\"value\":\"100\"}]}";

    private JdbcTemplate jdbc;
    private DsPermissionQueryService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:perm_depth_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE ds_rules (id BIGINT PRIMARY KEY, enable BOOLEAN, name VARCHAR(128), "
                + "description VARCHAR(512), permission_list VARCHAR(10000), user_list VARCHAR(10000), "
                + "white_list_user VARCHAR(10000), create_time BIGINT, oid BIGINT)");
        jdbc.execute("CREATE TABLE ds_permission (id BIGINT PRIMARY KEY, enable BOOLEAN, "
                + "auth_target_type VARCHAR(128), auth_target_id BIGINT, type VARCHAR(64), ds_id BIGINT, "
                + "table_id BIGINT, expression_tree VARCHAR(10000), permissions VARCHAR(10000), "
                + "white_list_user VARCHAR(10000), create_time BIGINT, name VARCHAR(128))");

        TableRepository tableRepo = Mockito.mock(TableRepository.class);
        FieldRepository fieldRepo = Mockito.mock(FieldRepository.class);
        DatasourceRepository dsRepo = Mockito.mock(DatasourceRepository.class);
        CurrentUserService userService = Mockito.mock(CurrentUserService.class);
        Map<String, Object> user = Maps.of("id", 7L);
        Mockito.when(userService.currentUser()).thenReturn(user);
        Mockito.when(userService.isAdmin(user)).thenReturn(false);
        Mockito.when(dsRepo.findById(10L)).thenReturn(Optional.of(Maps.of("type", "mysql")));
        Mockito.when(tableRepo.findByDsAndName(10L, "orders")).thenReturn(Optional.of(Maps.of("id", 100L)));
        Mockito.when(fieldRepo.listByTableId(100L)).thenReturn(Lists.of(
                Maps.of("id", 1001L, "field_name", "region", "field_type", "varchar"),
                Maps.of("id", 1002L, "field_name", "secret", "field_type", "varchar"),
                Maps.of("id", 1003L, "field_name", "amount", "field_type", "decimal")));

        DsPermissionRepository repo = new DsPermissionRepository(jdbc, new ObjectMapper(), new IdGenerator());
        service = new DsPermissionQueryService(repo, tableRepo, fieldRepo,
                Mockito.mock(VariableRepository.class), dsRepo, userService);
    }

    @Test
    void multipleRulesAreCombinedWithAnd() {
        insertRule(1, 1, "[11]", "[7]", null);
        insertRule(2, 1, "[12]", "[7]", null);
        insertRowPermission(11, 1, 100, ROW_REGION, null);
        insertRowPermission(12, 2, 100, ROW_AMOUNT, null);

        List<Map<String, Object>> filters = service.rowFilters(1, 7, 10, Lists.of("orders"));
        assertEquals(1, filters.size(), filters.toString());
        String filter = Rows.str(filters.get(0), "filter");
        assertTrue(filter.contains("region"), filter);
        assertTrue(filter.contains("amount"), filter);
        assertTrue(filter.contains(" AND "), filter);
    }

    @Test
    void multipleColumnRulesUnionHiddenColumns() {
        insertRule(3, 1, "[13,14]", "[7]", null);
        insertColumnPermission(13, 3, 100, "[{\"field_id\":1002,\"enable\":false}]", null);
        insertColumnPermission(14, 3, 100, "[{\"field_id\":1003,\"enable\":false}]", null);

        Set<String> hidden = service.hiddenColumns(1, 7, 10, "orders");
        assertTrue(hidden.contains("secret"), hidden.toString());
        assertTrue(hidden.contains("amount"), hidden.toString());
    }

    @Test
    void ruleWhiteListBypassesRowAndColumnPermissions() {
        insertRule(5, 1, "[15,16]", "[7]", "[7]");
        insertRowPermission(15, 5, 100, ROW_REGION, null);
        insertColumnPermission(16, 5, 100, "[{\"field_id\":1002,\"enable\":false}]", null);

        assertTrue(service.rowFilters(1, 7, 10, Lists.of("orders")).isEmpty());
        assertTrue(service.hiddenColumns(1, 7, 10, "orders").isEmpty());
    }

    @Test
    void entryWhiteListBypassesPermission() {
        insertRule(8, 1, "[19]", "[7]", null);
        insertRowPermission(19, 8, 100, ROW_REGION, "[7]");

        assertTrue(service.rowFilters(1, 7, 10, Lists.of("orders")).isEmpty());
    }

    @Test
    void userNotInRuleGetsNoPermission() {
        insertRule(6, 1, "[17]", "[8]", null);
        insertRowPermission(17, 6, 100, ROW_REGION, null);

        assertTrue(service.rowFilters(1, 7, 10, Lists.of("orders")).isEmpty());
    }

    @Test
    void ruleFromAnotherWorkspaceIsIgnored() {
        insertRule(7, 999, "[18]", "[7]", null);
        insertRowPermission(18, 7, 100, ROW_REGION, null);

        assertTrue(service.rowFilters(1, 7, 10, Lists.of("orders")).isEmpty());
    }

    private void insertRule(long id, long oid, String permissionList, String userList, String whiteList) {
        jdbc.update("INSERT INTO ds_rules (id, enable, name, permission_list, user_list, white_list_user, "
                        + "create_time, oid) VALUES (?, TRUE, ?, ?, ?, ?, 0, ?)",
                id, "rule-" + id, permissionList, userList, whiteList, oid);
    }

    private void insertRowPermission(long id, long ruleId, long tableId, String tree, String whiteList) {
        jdbc.update("INSERT INTO ds_permission (id, enable, auth_target_type, auth_target_id, type, ds_id, "
                        + "table_id, expression_tree, permissions, white_list_user, create_time, name) "
                        + "VALUES (?, TRUE, 'rule', ?, 'row', 10, ?, ?, '[]', ?, 0, ?)",
                id, ruleId, tableId, tree, whiteList, "perm-" + id);
    }

    private void insertColumnPermission(long id, long ruleId, long tableId, String permissions, String whiteList) {
        jdbc.update("INSERT INTO ds_permission (id, enable, auth_target_type, auth_target_id, type, ds_id, "
                        + "table_id, expression_tree, permissions, white_list_user, create_time, name) "
                        + "VALUES (?, TRUE, 'rule', ?, 'column', 10, ?, '{}', ?, ?, 0, ?)",
                id, ruleId, tableId, permissions, whiteList, "perm-" + id);
    }
}

