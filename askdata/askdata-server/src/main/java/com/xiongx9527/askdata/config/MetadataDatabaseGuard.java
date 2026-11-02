package com.xiongx9527.askdata.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 元数据库守卫：<b>主库只支持 MySQL（含衍生版本）与 PostgreSQL</b>。
 *
 * <p>为什么需要这层守卫：Flyway 社区版对 MySQL 5.7 只给一句
 * 「Flyway Teams Edition or MySQL upgrade required」，运维很难判断是自己配错了还是版本问题。
 * 这里在迁移前直接读出数据库版本，用明确的中文提示失败，避免「看起来能连、迁移时才炸」。</p>
 *
 * <p>支持矩阵：MySQL 8.0+（含 MariaDB / Aurora / Percona 等衍生版本）、PostgreSQL 10+。
 * H2 已从元数据库移除（同时也不再作为数据源类型），配置后直接报错而不是让 Flyway 报「找不到 location」。</p>
 */
@Configuration
public class MetadataDatabaseGuard {

    private static final Logger log = LoggerFactory.getLogger(MetadataDatabaseGuard.class);

    /** MySQL 系最低大版本（MySQL 8.0 起；Flyway 社区版不再支持 5.7）。 */
    static final int MIN_MYSQL_MAJOR = 8;

    /** MariaDB 是 MySQL 衍生版本，版本号独立（10.x 起）。 */
    static final int MIN_MARIADB_MAJOR = 10;

    /** PostgreSQL 最低大版本（10 起为官方支持期内的基线）。 */
    static final int MIN_POSTGRESQL_MAJOR = 10;

    /**
     * 迁移前校验版本，通过后再执行 Flyway 迁移。
     *
     * <p>用 {@link FlywayMigrationStrategy} 而不是 {@code @PostConstruct}：后者与 Flyway 初始化器的
     * 创建顺序没有保证，可能先迁移后校验。</p>
     */
    @Bean
    public FlywayMigrationStrategy metadataDatabaseMigrationStrategy() {
        return flyway -> {
            verify(flyway.getConfiguration().getDataSource());
            flyway.migrate();
        };
    }

    /** 读取真实连接元数据并校验。 */
    static void verify(DataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            verifyVersion(meta.getDatabaseProductName(), meta.getURL(),
                    meta.getDatabaseMajorVersion(), meta.getDatabaseMinorVersion());
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取元数据库版本信息: " + e.getMessage(), e);
        }
    }

    /**
     * 版本判定（纯函数，便于覆盖 5.7 / MariaDB / PostgreSQL / H2 各分支）。
     *
     * @throws IllegalStateException 不在支持矩阵内，或版本低于门槛
     */
    static void verifyVersion(String productName, String url, int major, int minor) {
        String product = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        String jdbcUrl = url == null ? "" : url;
        if (product.contains("mariadb")) {
            if (major < MIN_MARIADB_MAJOR) {
                throw new IllegalStateException("元数据库版本过低：检测到 " + productName + " "
                        + major + "." + minor + "，要求 " + MIN_MARIADB_MAJOR + ".2+。");
            }
            log.info("元数据库：{} {}.{}（MySQL 衍生版本）", productName, major, minor);
            return;
        }
        if (product.contains("mysql")) {
            if (major < MIN_MYSQL_MAJOR) {
                throw new IllegalStateException("元数据库版本过低：检测到 " + productName + " "
                        + major + "." + minor + "，要求 MySQL " + MIN_MYSQL_MAJOR + ".0+"
                        + "（Flyway 社区版已不支持 5.7，仅 Teams/Enterprise 可用）。"
                        + "请升级数据库版本，而不是降级迁移工具。");
            }
            log.info("元数据库：{} {}.{}", productName, major, minor);
            return;
        }
        if (product.contains("postgresql")) {
            if (major < MIN_POSTGRESQL_MAJOR) {
                throw new IllegalStateException("元数据库版本过低：检测到 " + productName + " "
                        + major + "." + minor + "，要求 PostgreSQL " + MIN_POSTGRESQL_MAJOR + "+。");
            }
            log.info("元数据库：{} {}.{}", productName, major, minor);
            return;
        }
        if (product.contains("h2")) {
            throw new IllegalStateException("元数据库不再支持 H2（" + jdbcUrl + "）："
                    + "请改用 MySQL " + MIN_MYSQL_MAJOR + ".0+ 或 PostgreSQL " + MIN_POSTGRESQL_MAJOR + "+。");
        }
        throw new IllegalStateException("元数据库仅支持 MySQL（含衍生版本）与 PostgreSQL，当前为 "
                + (productName == null ? "未知数据库" : productName) + "（" + jdbcUrl + "）。");
    }
}
