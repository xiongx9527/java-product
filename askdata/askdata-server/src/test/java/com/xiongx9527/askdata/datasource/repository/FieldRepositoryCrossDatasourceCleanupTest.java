package com.xiongx9527.askdata.datasource.repository;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.support.MetadbMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：{@code chooseTables} 的字段清理必须按数据源收窄。
 *
 * <p>历史缺陷：{@code deleteByTableIdsNotIn} 只带 {@code table_id NOT IN (...)}，在数据源 B 选表时
 * 会删掉数据源 A 的全部字段元数据，导致 A 后续问数拿不到字段（模型只能输出 {@code success=false}）。</p>
 */
class FieldRepositoryCrossDatasourceCleanupTest {

    private static final long DS_A = 101L;
    private static final long DS_B = 202L;
    private static final long TABLE_A1 = 1001L;
    private static final long TABLE_A2 = 1002L;
    private static final long TABLE_B1 = 2001L;

    private JdbcTemplate jdbc;

    private FieldRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:field_repo_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        repository = new FieldRepository(jdbc, new IdGenerator());
        repository.insert(DS_A, TABLE_A1, true, "id", "BIGINT", "", "", 0);
        repository.insert(DS_A, TABLE_A1, true, "amount", "DECIMAL", "金额", "", 1);
        repository.insert(DS_A, TABLE_A2, true, "id", "BIGINT", "", "", 0);
        repository.insert(DS_B, TABLE_B1, true, "id", "BIGINT", "", "", 0);
        repository.insert(DS_B, TABLE_B1, true, "channel", "VARCHAR", "渠道", "", 1);
    }

    @Test
    void cleanupOnlyTouchesOwnDatasource() {
        // 数据源 B 重新同步，只保留 TABLE_B1
        repository.deleteByTableIdsNotIn(DS_B, Collections.singletonList(TABLE_B1));

        // 数据源 A 的字段完好无损（历史缺陷点）
        assertEquals(2, repository.listByTableId(TABLE_A1).size(), "数据源 A 的字段不应被 B 的同步清掉");
        assertEquals(1, repository.listByTableId(TABLE_A2).size());
        // 数据源 B 自身仍保留勾选表的字段
        assertEquals(2, repository.listByTableId(TABLE_B1).size());
    }

    @Test
    void cleanupRemovesUnselectedTablesWithinDatasource() {
        // 数据源 A 重新同步，只保留 TABLE_A1：A 的 TABLE_A2 字段应被清理，B 不受影响
        repository.deleteByTableIdsNotIn(DS_A, Collections.singletonList(TABLE_A1));

        assertEquals(2, repository.listByTableId(TABLE_A1).size());
        assertTrue(repository.listByTableId(TABLE_A2).isEmpty(), "未勾选表的字段应被清理");
        List<?> bFields = repository.listByTableId(TABLE_B1);
        assertEquals(2, bFields.size(), "其它数据源字段必须保留");
    }
}
