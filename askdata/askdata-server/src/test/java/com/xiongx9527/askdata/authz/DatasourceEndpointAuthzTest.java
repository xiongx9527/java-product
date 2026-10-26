package com.xiongx9527.askdata.authz;

import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.engine.ConnectionPoolManager;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.RecommendedProblemRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.datasource.service.RecommendedProblemService;
import com.xiongx9527.askdata.datasource.service.TableRelationService;
import com.xiongx9527.askdata.datasource.support.DatasourceCrypto;
import com.xiongx9527.askdata.permission.DsGrantService;
import com.xiongx9527.askdata.permission.PermissionEnforcer;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A6：数据源端点授权（验收 P0 安全）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>表关系（table_relation save/get）要求 ws_admin 且数据源通过资源授权；</li>
 *   <li>推荐问题（recommended_problem list/base/save）同上的授权要求；</li>
 *   <li>execSql 端点（POST /datasource/execSql/{id}）要求 ws_admin，且在执行前应用
 *       行/列权限强制（hiddenColumnsFor + rowFilterWhereFor 的运行时版本），
 *       使非 ws_admin 无法绕过行/列权限。</li>
 * </ul>
 *
 * <p>数据源仓储使用真实 H2（Flyway 同运行时 schema），用户与资源授权用 mock 注入，
 * 以便精确断言授权分支与权限强制链路的接驳。</p>
 */
class DatasourceEndpointAuthzTest {

    private static final MessageResolver MESSAGE_RESOLVER = new MessageResolver(new StaticMessageSource());

    private static final long DS_ID = 10L;

    private JdbcTemplate jdbc;
    private DatasourceRepository datasourceRepository;

    @BeforeEach
    void setUp() throws Exception {
        DatasourceCrypto.configure(new CryptoUtil("AskDataSecret001"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:authz_endpoint_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        datasourceRepository = new DatasourceRepository(jdbc, new IdGenerator());
    }

    private void seedDatasource(long id, String configuration) {
        jdbc.update("INSERT INTO core_datasource (id, name, type, type_name, configuration, create_time, "
                + "create_by, status, num, oid) VALUES (?, 'ds', 'h2', 'H2', ?, NOW(), 1, 'Success', '0/0', 1)",
                id, configuration);
    }

    // ------------------------------------------------------------------
    // A6：表关系端点授权
    // ------------------------------------------------------------------

    @Test
    void tableRelationRejectsNonWsAdmin() {
        seedDatasource(DS_ID, "{}");
        CurrentUserService noAdmin = mock(CurrentUserService.class);
        doThrow(new BadRequestException("perm.ws.admin.only")).when(noAdmin).requireWsAdmin();
        DsGrantService grant = mock(DsGrantService.class);
        TableRelationService service = new TableRelationService(datasourceRepository,
                new com.fasterxml.jackson.databind.ObjectMapper(), MESSAGE_RESOLVER, noAdmin, grant);

        assertThrows(BadRequestException.class, () -> service.save(DS_ID, List.of()));
        assertThrows(BadRequestException.class, () -> service.get(DS_ID));
    }

    @Test
    void tableRelationRejectsResourceNotGranted() {
        seedDatasource(DS_ID, "{}");
        CurrentUserService user = wsAdminUser();
        DsGrantService grant = mock(DsGrantService.class);
        doThrow(new BadRequestException("ds.not.authorized")).when(grant)
                .checkUsable(anyLong(), anyLong(), anyBoolean(), anyLong());
        TableRelationService service = new TableRelationService(datasourceRepository,
                new com.fasterxml.jackson.databind.ObjectMapper(), MESSAGE_RESOLVER, user, grant);

        assertThrows(BadRequestException.class, () -> service.save(DS_ID, List.of()));
        assertThrows(BadRequestException.class, () -> service.get(DS_ID));
    }

    @Test
    void tableRelationAllowsWsAdminAndGranted() {
        seedDatasource(DS_ID, "{}");
        TableRelationService service = new TableRelationService(datasourceRepository,
                new com.fasterxml.jackson.databind.ObjectMapper(), MESSAGE_RESOLVER,
                wsAdminUser(), grantingGrant());

        service.save(DS_ID, List.of(Map.of("shape", "edge")));
        assertEquals(1, service.get(DS_ID).size());
    }

    // ------------------------------------------------------------------
    // A6：推荐问题端点授权
    // ------------------------------------------------------------------

    @Test
    void recommendedProblemRejectsNonWsAdmin() {
        seedDatasource(DS_ID, "{}");
        CurrentUserService noAdmin = mock(CurrentUserService.class);
        doThrow(new BadRequestException("perm.ws.admin.only")).when(noAdmin).requireWsAdmin();
        RecommendedProblemRepository problemRepo = new RecommendedProblemRepository(jdbc, new IdGenerator());
        RecommendedProblemService service = new RecommendedProblemService(problemRepo, datasourceRepository,
                noAdmin, new com.fasterxml.jackson.databind.ObjectMapper(), mock(DsGrantService.class));

        assertThrows(BadRequestException.class, () -> service.list(DS_ID));
        assertThrows(BadRequestException.class, () -> service.base(DS_ID));
        assertThrows(BadRequestException.class,
                () -> service.save(Map.of("datasource_id", DS_ID)));
    }

    @Test
    void recommendedProblemRejectsResourceNotGranted() {
        seedDatasource(DS_ID, "{}");
        CurrentUserService user = wsAdminUser();
        DsGrantService grant = mock(DsGrantService.class);
        doThrow(new BadRequestException("ds.not.authorized")).when(grant)
                .checkUsable(anyLong(), anyLong(), anyBoolean(), anyLong());
        RecommendedProblemRepository problemRepo = new RecommendedProblemRepository(jdbc, new IdGenerator());
        RecommendedProblemService service = new RecommendedProblemService(problemRepo, datasourceRepository,
                user, new com.fasterxml.jackson.databind.ObjectMapper(), grant);

        assertThrows(BadRequestException.class, () -> service.list(DS_ID));
        assertThrows(BadRequestException.class, () -> service.base(DS_ID));
    }

    @Test
    void recommendedProblemAllowsWsAdminAndGranted() {
        seedDatasource(DS_ID, "{}");
        RecommendedProblemRepository problemRepo = new RecommendedProblemRepository(jdbc, new IdGenerator());
        RecommendedProblemService service = new RecommendedProblemService(problemRepo, datasourceRepository,
                wsAdminUser(), new com.fasterxml.jackson.databind.ObjectMapper(), grantingGrant());

        service.save(Map.of("datasource_id", DS_ID, "recommended_config", 2,
                "problemInfo", List.of(Map.of("question", "每个状态的订单数", "sort", 0))));
        assertEquals(1, service.list(DS_ID).size());
        assertTrue(String.valueOf(service.base(DS_ID).get("questions")).contains("每个状态的订单数"));
    }

    // ------------------------------------------------------------------
    // A6：execSql 端点 —— 要求 ws_admin 且应用行/列权限强制
    // ------------------------------------------------------------------

    @Test
    void execSqlRejectsNonWsAdmin() {
        seedDatasource(DS_ID, "{}");
        CurrentUserService noAdmin = mock(CurrentUserService.class);
        doThrow(new BadRequestException("perm.ws.admin.only")).when(noAdmin).requireWsAdmin();
        JdbcEngine engine = mock(JdbcEngine.class);
        DatasourceService service = new DatasourceService(datasourceRepository,
                mock(com.xiongx9527.askdata.datasource.repository.TableRepository.class),
                mock(com.xiongx9527.askdata.datasource.repository.FieldRepository.class),
                mock(com.xiongx9527.askdata.datasource.excel.ExcelService.class),
                engine, noAdmin, new com.fasterxml.jackson.databind.ObjectMapper(), MESSAGE_RESOLVER,
                mock(com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService.class),
                new ConnectionPoolManager(), null);

        assertThrows(BadRequestException.class,
                () -> service.execSqlAuthorized(DS_ID, Map.of("sql", "SELECT 1")));
    }

    @Test
    void execSqlAppliesPermissionEnforcementBeforeExecuting() {
        String configuration = "{\"host\":\"\",\"port\":0,\"username\":\"sa\",\"password\":\"\","
                + "\"database\":\"x\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\",\"sheets\":[],"
                + "\"mode\":\"\",\"timeout\":10,\"lowVersion\":false,\"ssl\":false,\"poolSize\":5,\"engine\":\"hive\"}";
        seedDatasource(DS_ID, configuration);

        JdbcEngine engine = mock(JdbcEngine.class);
        CurrentUserService user = wsAdminUser();
        DatasourceService service = new DatasourceService(datasourceRepository,
                mock(com.xiongx9527.askdata.datasource.repository.TableRepository.class),
                mock(com.xiongx9527.askdata.datasource.repository.FieldRepository.class),
                mock(com.xiongx9527.askdata.datasource.excel.ExcelService.class),
                engine, user, new com.fasterxml.jackson.databind.ObjectMapper(), MESSAGE_RESOLVER,
                mock(com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService.class),
                new ConnectionPoolManager(), null);

        PermissionEnforcer enforcer = mock(PermissionEnforcer.class);
        PermissionEnforcer.EnforcedQuery enforced =
                new PermissionEnforcer.EnforcedQuery("SELECT enforced_col FROM t WHERE row_filter = 1",
                        Set.of("hidden_col"));
        when(enforcer.enforce(anyLong(), anyLong(), eq(DS_ID), eq("SELECT 1"))).thenReturn(enforced);
        when(enforcer.applyHiddenColumns(any(), any())).thenAnswer(inv -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>(inv.getArgument(0));
            m.put("hidden_applied", true);
            return m;
        });
        java.util.Map<String, Object> engineResult = new java.util.HashMap<>();
        engineResult.put("data", List.of());
        when(engine.execSql(any(), any(), eq("SELECT enforced_col FROM t WHERE row_filter = 1"), eq(DS_ID)))
                .thenReturn(engineResult);
        service.setPermissionEnforcer(enforcer);

        java.util.Map<String, Object> result = service.execSqlAuthorized(DS_ID, Map.of("sql", "SELECT 1"));

        verify(engine).execSql(any(), any(), eq("SELECT enforced_col FROM t WHERE row_filter = 1"), eq(DS_ID));
        assertEquals(true, result.get("hidden_applied"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static CurrentUserService wsAdminUser() {
        CurrentUserService user = mock(CurrentUserService.class);
        when(user.currentUser()).thenReturn(Map.of("id", 7L, "account", "u", "oid", 1L));
        when(user.isAdmin(ArgumentMatchers.any())).thenReturn(false);
        doNothing().when(user).requireWsAdmin();
        when(user.uid()).thenReturn(7L);
        when(user.oidOf(ArgumentMatchers.any())).thenReturn(1L);
        return user;
    }

    private static DsGrantService grantingGrant() {
        DsGrantService grant = mock(DsGrantService.class);
        doNothing().when(grant).checkUsable(anyLong(), anyLong(), anyBoolean(), anyLong());
        return grant;
    }
}
