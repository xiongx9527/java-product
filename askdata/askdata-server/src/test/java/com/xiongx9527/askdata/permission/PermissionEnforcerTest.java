package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * A3 权限强制单测：行权限 WHERE 注入（过滤条件出现在执行 SQL 中）、列权限隐藏、管理员跳过、
 * 以及改写后的 SQL 在真实 H2 上确实过滤了行。
 */
@ExtendWith(MockitoExtension.class)
class PermissionEnforcerTest {

    @Mock
    private DsPermissionQueryService permQuery;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private PermissionEnforcer enforcer;

    // ---- 行权限 WHERE 注入 ----

    @Test
    void singleTableFilterInjectedAsSubquery() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("orders", "(region = 'east')");
        String rewritten = enforcer.applyRowFilters("SELECT id, amount FROM orders", filters);
        assertTrue(rewritten.contains("region = 'east'"), "filter must appear in executed SQL: " + rewritten);
        assertTrue(rewritten.toLowerCase().contains("from (select * from orders where"), rewritten);
    }

    @Test
    void joinedTableFilterInjectedIntoItsOwnSubquery() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("o", "(status = 'active')");
        String rewritten = enforcer.applyRowFilters(
                "SELECT o.id FROM o JOIN u ON o.uid = u.id", filters);
        assertTrue(rewritten.contains("status = 'active'"), rewritten);
        assertTrue(rewritten.toLowerCase().contains("(select * from o where"), rewritten);
    }

    @Test
    void noFilterReturnsOriginal() {
        String sql = "SELECT 1 FROM t";
        assertEquals(sql, enforcer.applyRowFilters(sql, Collections.emptyMap()));
    }

    // ---- 改写后的 SQL 在真实 H2 上确实过滤行 ----

    @Test
    void rewrittenSqlActuallyFiltersRowsOnH2() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:perm_a3;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE orders (id INT, region VARCHAR(20))");
                s.execute("INSERT INTO orders VALUES (1, 'east'), (2, 'west'), (3, 'east')");
            }
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("orders", "(region = 'east')");
            String rewritten = enforcer.applyRowFilters("SELECT id, region FROM orders", filters);
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(rewritten)) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    assertEquals("east", rs.getString("region"));
                }
                assertEquals(2, count, "row permission must filter to east region only");
            }
        }
    }

    // ---- 列权限隐藏 ----

    @Test
    void hiddenColumnsRemovedFromResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fields", List.of("id", "secret"));
        result.put("fields_info", List.of(
                Map.of("name", "id", "is_numeric", true),
                Map.of("name", "secret", "is_numeric", false)));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("secret", "x");
        result.put("data", List.of(row));

        enforcer.applyHiddenColumns(result, Set.of("secret"));

        @SuppressWarnings("unchecked")
        List<Object> fields = (List<Object>) result.get("fields");
        assertEquals(List.of("id"), fields);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        assertFalse(data.get(0).containsKey("secret"), "hidden column must be stripped from result set");
        assertTrue(data.get(0).containsKey("id"));
    }

    @Test
    void noHiddenColumnsLeavesResultUntouched() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fields", List.of("id", "name"));
        result.put("fields_info", List.of(Map.of("name", "id"), Map.of("name", "name")));
        result.put("data", List.of(Map.of("id", 1, "name", "a")));
        enforcer.applyHiddenColumns(result, Collections.emptySet());
        assertEquals(List.of("id", "name"), result.get("fields"));
    }

    // ---- enforce：普通用户注入，管理员跳过 ----

    @Test
    void ordinaryUserGetsRowFilterInjected() {
        Map<String, Object> user = Map.of("id", 7L, "is_admin", false);
        when(currentUserService.currentUser()).thenReturn(user);
        when(currentUserService.isAdmin(user)).thenReturn(false);
        when(permQuery.rowFilters(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(List.of(Map.of("table", "orders", "filter", "(region = 'east')")));
        when(permQuery.hiddenColumns(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(Set.of("secret"));

        PermissionEnforcer.EnforcedQuery enforced = enforcer.enforce(1L, 7L, 10L, "SELECT id FROM orders");
        assertTrue(enforced.getSql().contains("region = 'east'"), enforced.getSql());
        assertTrue(enforced.getHiddenColumns().contains("secret"));
    }

    @Test
    void adminBypassesPermission() {
        Map<String, Object> user = Map.of("id", 1L, "is_admin", true);
        when(currentUserService.currentUser()).thenReturn(user);
        when(currentUserService.isAdmin(user)).thenReturn(true);

        PermissionEnforcer.EnforcedQuery enforced = enforcer.enforce(1L, 1L, 10L, "SELECT id FROM orders");
        assertEquals("SELECT id FROM orders", enforced.getSql());
        assertTrue(enforced.getHiddenColumns().isEmpty());
    }
}
