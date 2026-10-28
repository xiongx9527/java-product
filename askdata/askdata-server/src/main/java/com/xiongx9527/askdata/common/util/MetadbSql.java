package com.xiongx9527.askdata.common.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.Locale;

/**
 * 元数据库跨方言标识符处理。
 *
 * <p>应用元数据库同时支持 H2、PostgreSQL 与 MySQL/MariaDB。MySQL/MariaDB 把 {@code SQL}
 * 视为保留字，`chat_record.sql`、`scheduled_task_run.sql` 这类列名在 SELECT/UPDATE 中
 * 必须使用反引号；H2（未加引号的标识符按大写存储）与 PostgreSQL 不能使用反引号，保持原样。</p>
 */
public final class MetadbSql {

    private MetadbSql() {
    }

    /**
     * 当前元数据库产品名（小写；取不到时返回空串）。
     *
     * <p>用于需要按方言选择 DDL 类型的场景（例如 MySQL 的 VARCHAR 长度上限远小于 H2）。</p>
     */
    public static String databaseProduct(JdbcTemplate jdbc) {
        try {
            if (jdbc.getDataSource() == null) {
                return "";
            }
            try (Connection connection = jdbc.getDataSource().getConnection()) {
                String product = connection.getMetaData().getDatabaseProductName();
                return product == null ? "" : product.toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 返回当前元数据库下 {@code sql} 列可用的标识符写法。
     *
     * @param jdbc 元数据库 JdbcTemplate
     * @return MySQL/MariaDB 返回反引号形式，其它数据库返回原始列名
     */
    public static String sqlColumnName(JdbcTemplate jdbc) {
        try {
            if (jdbc.getDataSource() == null) {
                return "sql";
            }
            try (Connection connection = jdbc.getDataSource().getConnection()) {
                String product = connection.getMetaData().getDatabaseProductName();
                String normalized = product == null ? "" : product.toLowerCase(Locale.ROOT);
                if (normalized.contains("mysql") || normalized.contains("mariadb")) {
                    return "`sql`";
                }
            }
        } catch (Exception ignored) {
            // 元数据不可用时按通用写法处理，不阻断仓储初始化
        }
        return "sql";
    }
}
