package com.xiongx9527.askdata.parity;

import com.xiongx9527.askdata.common.util.Lists;

import com.xiongx9527.askdata.support.MetadbMigration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A9：H2 默认元数据库迁移。
 *
 * <p>覆盖空库 V1→V2 完整迁移，以及 schema.sql 时代旧库（已有表+种子、无 Flyway 历史）
 * 通过 {@code baseline-version=2} 平滑接管且不重复写入种子。</p>
 */
class H2MetadbMigrationTest {

    @Test
    void emptyDatabaseMigratesAndSeeds() throws Exception {
        String url = "jdbc:h2:mem:h2_empty_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            MetadbMigration.migrate(connection);
            assertEquals(1, count(connection, "sys_user"));
            assertEquals(12, count(connection, "sys_arg"));
            assertEquals(36, tableCount(connection));
            assertEquals(Lists.of("1", "2", "3", "4"), versions(connection));
        }
    }

    @Test
    void legacySchemaIsBaselinedWithoutDuplicateSeed() throws Exception {
        String url = "jdbc:h2:mem:h2_legacy_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            // 模拟 schema.sql 时代：表 + 种子已经存在，但没有 flyway_schema_history
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/h2/V1__baseline.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/common/V2__seed.sql"));

            MetadbMigration.migrate(connection);

            assertEquals(1, count(connection, "sys_user"), "旧库种子不应重复插入");
            assertEquals(12, count(connection, "sys_arg"), "旧库种子不应重复插入");
            assertEquals(36, tableCount(connection));
            assertEquals(Lists.of("2", "3", "4"), versions(connection), "旧库应以 V2 为基线，后续版本继续增量迁移");
        }
    }

    private int tableCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE UPPER(table_schema) = 'PUBLIC' AND UPPER(table_name) <> 'FLYWAY_SCHEMA_HISTORY'")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private List<String> versions(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT \"version\" FROM \"flyway_schema_history\" "
                             + "WHERE \"success\" AND \"version\" IS NOT NULL ORDER BY \"installed_rank\"")) {
            List<String> versions = new java.util.ArrayList<>();
            while (result.next()) {
                versions.add(result.getString(1));
            }
            return versions;
        }
    }
}
