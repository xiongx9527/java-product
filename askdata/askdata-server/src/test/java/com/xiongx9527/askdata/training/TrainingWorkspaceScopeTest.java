package com.xiongx9527.askdata.training;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A21 训练数据归属回归：训练示例的数据源必须属于当前工作空间，
 * 跨空间数据源在保存时被拒绝。
 */
class TrainingWorkspaceScopeTest {

    private static final long OID = 1L;

    private static final long OWN_DS = 1L;

    private static final long FOREIGN_DS = 2L;

    private JdbcTemplate jdbc;

    private TrainingService trainingService;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:training_scope_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        jdbc.update("INSERT INTO sys_workspace (id, name, create_time) VALUES (2, 'ws-2', 1672531200000L)");
        jdbc.update("INSERT INTO core_datasource (id, name, type, oid) VALUES (?, ?, 'mysql', 2)", FOREIGN_DS, "外部空间数据源");

        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", 2L, "account", "user2", "oid", OID, "language", "zh-CN"));
        Mockito.when(currentUserService.oidOf(Mockito.any())).thenReturn(OID);

        trainingService = new TrainingService(new TrainingRepository(jdbc, new IdGenerator()), currentUserService,
                new DatasourceNameLookup(jdbc), Mockito.mock(AuditRecorder.class), new ObjectMapper());
        ReflectionTestUtils.setField(trainingService, "messageResolver", TestMessageResolver.create());
    }

    @Test
    void acceptsDatasourceOfCurrentWorkspace() {
        long id = trainingService.createOrUpdate(Rows.of("question", "今年销售额", "description",
                "SELECT SUM(amount) FROM orders", "datasource", OWN_DS));
        assertEquals(OID, jdbc.queryForObject("SELECT oid FROM data_training WHERE id = ?", Long.class, id));
    }

    @Test
    void rejectsDatasourceFromAnotherWorkspace() {
        assertThrows(BadRequestException.class, () -> trainingService.createOrUpdate(Rows.of("question", "今年销售额",
                "description", "SELECT SUM(amount) FROM orders", "datasource", FOREIGN_DS)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM data_training", Integer.class));
    }
}
