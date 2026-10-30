package com.xiongx9527.askdata.terminology;

import com.xiongx9527.askdata.common.util.Lists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.datasource.DatasourceNameLookup;
import com.xiongx9527.askdata.common.i18n.TestMessageResolver;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A21 术语数据源归属回归：{@code datasource_ids} 必须属于当前工作空间，
 * 跨空间数据源在保存与按名称解析（导入）时都被拒绝。
 */
class TerminologyWorkspaceScopeTest {

    private static final long OID = 1L;

    private static final long OWN_DS = 1L;

    private static final long FOREIGN_DS = 2L;

    private JdbcTemplate jdbc;

    private TerminologyService terminologyService;

    private DatasourceNameLookup datasourceNameLookup;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:terminology_scope_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        jdbc.update("INSERT INTO sys_workspace (id, name, create_time) VALUES (2, 'ws-2', 1672531200000L)");
        jdbc.update("INSERT INTO core_datasource (id, name, type, oid) VALUES (?, ?, 'mysql', 2)", FOREIGN_DS, "外部空间数据源");

        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", 2L, "account", "user2", "oid", OID, "language", "zh-CN"));
        Mockito.when(currentUserService.oidOf(Mockito.any())).thenReturn(OID);

        datasourceNameLookup = new DatasourceNameLookup(jdbc);
        terminologyService = new TerminologyService(
                new TerminologyRepository(jdbc, new ObjectMapper(), new IdGenerator()), currentUserService,
                datasourceNameLookup, Mockito.mock(AuditRecorder.class));
        ReflectionTestUtils.setField(terminologyService, "messageResolver", TestMessageResolver.create());
    }

    private java.util.Map<String, Object> body(List<Long> datasourceIds) {
        return Rows.of("word", "销售额", "description", "订单金额合计", "specific_ds", true,
                "datasource_ids", datasourceIds, "other_words", Lists.of());
    }

    @Test
    void acceptsDatasourceOfCurrentWorkspace() {
        long id = terminologyService.createOrUpdate(body(Lists.of(OWN_DS)));
        assertEquals(OID, jdbc.queryForObject("SELECT oid FROM terminology WHERE id = ?", Long.class, id));
    }

    @Test
    void rejectsDatasourceFromAnotherWorkspace() {
        assertThrows(BadRequestException.class, () -> terminologyService.createOrUpdate(body(Lists.of(FOREIGN_DS))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM terminology", Integer.class));
    }

    @Test
    void importNameLookupIsScopedToCurrentWorkspace() {
        assertNull(datasourceNameLookup.idByName("外部空间数据源", OID),
                "导入按名称解析时不得命中其他空间的数据源");
        assertEquals(FOREIGN_DS, datasourceNameLookup.idByName("外部空间数据源", 2L));
    }
}
