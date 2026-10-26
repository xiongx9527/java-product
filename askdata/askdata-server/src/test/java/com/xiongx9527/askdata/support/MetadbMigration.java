package com.xiongx9527.askdata.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;

/**
 * 测试用元数据库迁移：执行与运行时完全相同的 Flyway 脚本
 * （{@code db/migration/common} 种子 + {@code db/migration/h2} baseline）。
 *
 * <p>历史测试直接执行 {@code schema.sql}；A9 起 schema.sql 已由 Flyway 接管，
 * 这里用现有连接包一层不关闭连接的 DataSource，避免测试各自复制迁移逻辑。</p>
 */
public final class MetadbMigration {

    private MetadbMigration() {
    }

    public static void migrate(Connection connection) {
        migrate(connection, "h2");
    }

    /**
     * 按指定方言执行迁移（测试用）：common 种子 + {@code db/migration/{vendor}} baseline。
     * MySQL 无真库时可在 H2 {@code MODE=MySQL} 上验证脚本可解析/可执行。
     */
    public static void migrate(Connection connection, String vendor) {
        Flyway.configure()
                .dataSource(new SingleConnectionDataSource(connection, true))
                .locations("classpath:db/migration/common", "classpath:db/migration/" + vendor)
                .baselineOnMigrate(true)
                .baselineVersion("2")
                .load()
                .migrate();
    }
}
