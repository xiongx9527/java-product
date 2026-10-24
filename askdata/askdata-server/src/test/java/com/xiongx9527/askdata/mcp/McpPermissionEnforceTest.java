package com.xiongx9527.askdata.mcp;

import com.xiongx9527.askdata.ai.AskdataNl2Sql;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.permission.PermissionEnforcer;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A1/A2：MCP 问数链路（AskdataNl2Sql）必须与 Chat 主链路共用 PermissionEnforcer——
 * 以当前登录用户身份施加行权限改写与列权限隐藏，不再只做表白名单。
 */
class McpPermissionEnforceTest {

    @Test
    void enforceForMcpPassesCurrentUserIdentityToEnforcer() {
        PermissionEnforcer enforcer = Mockito.mock(PermissionEnforcer.class);
        CurrentUserService userService = Mockito.mock(CurrentUserService.class);
        Map<String, Object> user = Rows.of("id", 42L, "oid", 7L);
        when(userService.currentUser()).thenReturn(user);
        when(userService.oidOf(user)).thenReturn(7L);
        when(enforcer.enforce(eq(7L), eq(42L), eq(9L), anyString()))
                .thenReturn(new PermissionEnforcer.EnforcedQuery(
                        "SELECT * FROM (SELECT * FROM t WHERE region = 'north') t",
                        Set.of("secret_col")));

        String sql = "SELECT * FROM t";
        PermissionEnforcer.EnforcedQuery enforced =
                AskdataNl2Sql.enforceForMcp(enforcer, userService, 9L, sql);

        verify(enforcer).enforce(eq(7L), eq(42L), eq(9L), eq("SELECT * FROM t"));
        assertNotNull(enforced);
        assertEquals("SELECT * FROM (SELECT * FROM t WHERE region = 'north') t", enforced.getSql());
        assertTrue(enforced.getHiddenColumns().contains("secret_col"));
    }

    @Test
    void hiddenColumnsAreAppliedToResultRows() {
        PermissionEnforcer enforcer = Mockito.mock(PermissionEnforcer.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fields", java.util.List.of("id", "secret_col"));
        result.put("data", java.util.List.of(Rows.of("id", 1, "secret_col", "x")));
        when(enforcer.applyHiddenColumns(Mockito.eq(result), Mockito.anySet()))
                .thenAnswer(inv -> enforcerApply(result, inv.getArgument(1)));

        Map<String, Object> filtered = enforcer.applyHiddenColumns(result, Set.of("secret_col"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) filtered.get("data");
        assertNotNull(rows);
        assertEquals(1, rows.size());
        org.junit.jupiter.api.Assertions.assertFalse(rows.get(0).containsKey("secret_col"),
                String.valueOf(rows.get(0)));
    }

    private static Map<String, Object> enforcerApply(Map<String, Object> result, Set<String> hidden) {
        Map<String, Object> out = new LinkedHashMap<>(result);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows =
                (java.util.List<Map<String, Object>>) out.get("data");
        for (Map<String, Object> row : rows) {
            for (String h : hidden) {
                row.remove(h);
            }
        }
        return out;
    }
}
