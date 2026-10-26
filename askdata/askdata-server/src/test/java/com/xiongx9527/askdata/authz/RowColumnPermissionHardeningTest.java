package com.xiongx9527.askdata.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService;
import com.xiongx9527.askdata.datasource.engine.SqlSafety;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.permission.DsPermissionQueryService;
import com.xiongx9527.askdata.permission.DsPermissionRepository;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.repository.VariableRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A7：权限硬化（验收 P0 安全）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>只读 SQL 校验基于 SQL AST 的函数节点（而非原文正则）：字符串字面量中的 'version(' 不被误判，
 *       而真正的 version() 函数调用被识别拒绝；</li>
 *   <li>行权限按 varType 校验 kv 变量值域并做 key→value 映射：越界值被拒绝（fail-closed），
 *       合法值在范围内被映射为真实列值；</li>
 *   <li>列权限在构造 schema 提示词之前剔除无权限字段（SchemaContextBuilder.tableBlock 调 hiddenColumnsFor）。</li>
 * </ul>
 */
class RowColumnPermissionHardeningTest {

    private static final String ROW_VAR = "{\"logic\":\"AND\",\"items\":[{\"type\":\"item\",\"field_id\":1001,"
            + "\"term\":\"eq\",\"value_type\":\"variable\",\"variable_id\":500}]}";

    private JdbcTemplate jdbc;
    private DsPermissionQueryService service;
    private CurrentUserService baseUserService;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:perm_harden_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE ds_rules (id BIGINT PRIMARY KEY, enable BOOLEAN, name VARCHAR(128), "
                + "description VARCHAR(512), permission_list VARCHAR(10000), user_list VARCHAR(10000), "
                + "white_list_user VARCHAR(10000), create_time BIGINT, oid BIGINT)");
        jdbc.execute("CREATE TABLE ds_permission (id BIGINT PRIMARY KEY, enable BOOLEAN, "
                + "auth_target_type VARCHAR(128), auth_target_id BIGINT, type VARCHAR(64), ds_id BIGINT, "
                + "table_id BIGINT, expression_tree VARCHAR(10000), permissions VARCHAR(10000), "
                + "white_list_user VARCHAR(10000), create_time BIGINT, name VARCHAR(128))");

        TableRepository tableRepo = mock(TableRepository.class);
        FieldRepository fieldRepo = mock(FieldRepository.class);
        DatasourceRepository dsRepo = mock(DatasourceRepository.class);
        CurrentUserService userService = mock(CurrentUserService.class);
        Map<String, Object> user = Map.of("id", 7L, "oid", 1L,
                "system_variables", List.of(Map.of("variableId", 500L, "variableValues", List.of("k1"))));
        when(userService.currentUser()).thenReturn(user);
        when(userService.isAdmin(ArgumentMatchers.any())).thenReturn(false);
        when(dsRepo.findById(10L)).thenReturn(Optional.of(Map.of("type", "mysql")));
        when(tableRepo.findByDsAndName(10L, "orders")).thenReturn(Optional.of(Map.of("id", 100L)));
        when(fieldRepo.listByTableId(100L)).thenReturn(List.of(
                Map.of("id", 1001L, "field_name", "region", "field_type", "varchar"),
                Map.of("id", 1002L, "field_name", "secret", "field_type", "varchar"),
                Map.of("id", 1003L, "field_name", "amount", "field_type", "decimal")));

        VariableRepository variableRepo = mock(VariableRepository.class);
        when(variableRepo.findById(500L)).thenReturn(Optional.of(Map.of("type", "kv", "value",
                List.of(Map.of("key", "k1", "value", "v1"), Map.of("key", "k2", "value", "v2")))));

        DsPermissionRepository repo = new DsPermissionRepository(jdbc, new ObjectMapper(), new IdGenerator());
        service = new DsPermissionQueryService(repo, tableRepo, fieldRepo,
                variableRepo, dsRepo, userService);
        baseUserService = userService;
    }

    // ------------------------------------------------------------------
    // A7：只读 SQL 校验基于 AST 函数节点
    // ------------------------------------------------------------------

    @Test
    void dangerousFunctionCallIsRejected() {
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("SELECT version()", "mysql"));
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("SELECT current_user()", "mysql"));
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("SELECT version ( ) FROM t", "mysql"));
    }

    @Test
    void functionNameInsideStringLiteralIsNotRejected() {
        assertDoesNotThrow(() -> SqlSafety.checkRead(
                "SELECT id FROM t WHERE name = 'version(' AND id = 2", "mysql"));
        assertDoesNotThrow(() -> SqlSafety.checkRead("SELECT count(*) FROM t", "mysql"));
    }

    // ------------------------------------------------------------------
    // A7：行权限 kv 值域校验 + key→value 映射
    // ------------------------------------------------------------------

    @Test
    void kvVariableOutOfRangeIsRejected() {
        insertRule(1, 1, "[11]", "[7]", null);
        insertRowPermission(11, 1, 100, ROW_VAR, null);
        setUserVariable("kX");
        assertThrows(IllegalArgumentException.class,
                () -> service.rowFilters(1, 7, 10, List.of("orders")));
    }

    @Test
    void kvVariableInRangeIsMappedToRealValue() {
        insertRule(1, 1, "[11]", "[7]", null);
        insertRowPermission(11, 1, 100, ROW_VAR, null);
        setUserVariable("k1");
        List<Map<String, Object>> filters = service.rowFilters(1, 7, 10, List.of("orders"));
        assertTrue(filters.size() == 1, filters.toString());
        String filter = Rows.str(filters.get(0), "filter");
        assertTrue(filter.contains("region"), filter);
        assertTrue(filter.contains("'v1'"), filter);
    }

    // ------------------------------------------------------------------
    // A7：列权限在构造 schema 提示词前剔除无权限字段
    // ------------------------------------------------------------------

    @Test
    void schemaPromptStripsHiddenColumns() throws Exception {
        JdbcTemplate appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:scb_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        appJdbc.update("INSERT INTO core_table (id, ds_id, checked, table_name, table_comment) "
                + "VALUES (9001, 10, TRUE, 'orders', '订单')");
        appJdbc.update("INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type) "
                + "VALUES (9001, 10, 9001, TRUE, 'id', 'bigint')");
        appJdbc.update("INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type) "
                + "VALUES (9002, 10, 9001, TRUE, 'secret', 'varchar')");
        appJdbc.update("INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type) "
                + "VALUES (9003, 10, 9001, TRUE, 'amount', 'decimal')");

        DatasourceRepository dsRepo = new DatasourceRepository(appJdbc, new IdGenerator());
        TableRepository tableRepo = new TableRepository(appJdbc, new IdGenerator());
        FieldRepository fieldRepo = new FieldRepository(appJdbc, new IdGenerator());
        DatasourceService datasourceService = mock(DatasourceService.class);
        ChatModelClient modelClient = mock(ChatModelClient.class);
        ChatModelRouter modelRouter = mock(ChatModelRouter.class);
        TableEmbeddingService embedding = mock(TableEmbeddingService.class);

        SchemaContextBuilder builder = new SchemaContextBuilder(dsRepo, tableRepo, fieldRepo,
                datasourceService, modelClient, modelRouter, appJdbc, new ObjectMapper(), embedding);

        DsPermissionQueryService permissionQuery = mock(DsPermissionQueryService.class);
        when(permissionQuery.hiddenColumns(anyLong(), anyLong(), eq(10L), eq("orders")))
                .thenReturn(Set.of("secret"));
        builder.setPermissionQuery(permissionQuery);

        CurrentUserService userService = mock(CurrentUserService.class);
        when(userService.currentUser()).thenReturn(Map.of("id", 7L, "oid", 1L));
        when(userService.isAdmin(ArgumentMatchers.any())).thenReturn(false);
        when(userService.oidOf(ArgumentMatchers.any())).thenReturn(1L);
        builder.setCurrentUserService(userService);

        Method m = SchemaContextBuilder.class.getDeclaredMethod("tableBlock", long.class, Map.class);
        m.setAccessible(true);
        String prompt = (String) m.invoke(builder, 10L, Map.of("id", 9001L, "table_name", "orders"));

        assertTrue(prompt.contains("id"), prompt);
        assertTrue(prompt.contains("amount"), prompt);
        assertTrue(!prompt.contains("secret"), "hidden column 'secret' must be stripped: " + prompt);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void setUserVariable(String key) {
        Map<String, Object> user = Map.of("id", 7L, "oid", 1L,
                "system_variables", List.of(Map.of("variableId", 500L, "variableValues", List.of(key))));
        when(baseUserService.currentUser()).thenReturn(user);
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
}
