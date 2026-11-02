package com.xiongx9527.askdata.support;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实 MySQL 元数据库测试基建：按本次运行创建独立 schema，跑与运行时相同的 Flyway 脚本，结束即删除。
 *
 * <p>不使用内存库——方言差异（自增、时间类型、DDL、保留字）只有打真库才暴露。
 * 连接信息只从环境读取，不可达时整类 assume-skip：</p>
 *
 * <pre>
 * ASKDATA_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:3306 ASKDATA_IT_MYSQL_USER=root ASKDATA_IT_MYSQL_PASSWORD=... mvn test
 * </pre>
 *
 * <p>Spring 上下文测试用 {@link #createEmptySchema()} 建空库，把连接通过
 * {@link #bindSpringDatasource} 注入测试上下文，让应用自己的 Flyway 迁移链路（含
 * {@code MetadataDatabaseGuard}）真实跑一遍。</p>
 */
public final class MysqlTestSupport {

    private static final List<JdbcTemplate> CREATED = new ArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<JdbcTemplate> copy;
            synchronized (CREATED) {
                copy = new ArrayList<>(CREATED);
                CREATED.clear();
            }
            for (JdbcTemplate jdbc : copy) {
                dropSchema(jdbc);
            }
        }, "askdata-it-mysql-cleanup"));
    }

    private MysqlTestSupport() {
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            value = System.getProperty(name.toLowerCase().replace('_', '.'));
        }
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** 不带库名的 JDBC 前缀（缺省本机 3306）。 */
    public static String baseUrl() {
        return env("ASKDATA_IT_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306");
    }

    public static String user() {
        return env("ASKDATA_IT_MYSQL_USER", "root");
    }

    public static String password() {
        return env("ASKDATA_IT_MYSQL_PASSWORD", "");
    }

    private static String url(String schema) {
        String base = baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true";
    }

    /** 真实 MySQL 是否可连接（凭据有效且端口可达）。 */
    public static boolean available() {
        try (Connection ignored = DriverManager.getConnection(url("information_schema"), user(), password())) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 不可达时跳过，并在报告里给出配置方式。 */
    public static void requireAvailable() {
        Assumptions.assumeTrue(available(),
                "MySQL 不可达：" + baseUrl() + "（设置 ASKDATA_IT_MYSQL_USER / ASKDATA_IT_MYSQL_PASSWORD 后重跑）");
    }

    /** 创建一个空 schema（不迁移），用于 Spring 上下文测试或作为被查询的目标库。 */
    public static JdbcTemplate createEmptySchema() {
        requireAvailable();
        String schema = "askdata_it_" + Long.toHexString(System.nanoTime());
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url(schema), user(), password());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(5)) {
                throw new IllegalStateException("MySQL 连接不可用");
            }
        } catch (Exception e) {
            throw new IllegalStateException("MySQL 测试 schema 创建失败: " + e.getMessage(), e);
        }
        synchronized (CREATED) {
            CREATED.add(jdbc);
        }
        return jdbc;
    }

    /**
     * 创建本次运行专用的 schema 并执行全部迁移。
     *
     * @return 指向该 schema 的 JdbcTemplate；schema 名可通过 {@link #jdbcUrl(JdbcTemplate)} 取回
     */
    public static JdbcTemplate createSchema() {
        JdbcTemplate jdbc = createEmptySchema();
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection, "mysql");
        } catch (Exception e) {
            throw new IllegalStateException("MySQL 测试 schema 迁移失败: " + e.getMessage(), e);
        }
        return jdbc;
    }

    /** 该 schema 的完整 JDBC URL（给需要自己建连的代码用）。 */
    public static String jdbcUrl(JdbcTemplate jdbc) {
        String schema = schemaOf(jdbc);
        return url(schema);
    }

    /** 测试 MySQL 的 host（从 {@link #baseUrl()} 解析）。 */
    public static String host() {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^jdbc:mysql://([^:/]+)").matcher(baseUrl());
        return matcher.find() ? matcher.group(1) : "127.0.0.1";
    }

    /** 测试 MySQL 的端口（从 {@link #baseUrl()} 解析，缺省 3306）。 */
    public static int port() {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^jdbc:mysql://[^:/]+:(\\d+)").matcher(baseUrl());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 3306;
    }

    /** 该 schema 的名字（作为 MySQL 的 database 名）。 */
    public static String schemaName(JdbcTemplate jdbc) {
        return schemaOf(jdbc);
    }

    /**
     * 把该 schema 配成一个「被查询的目标 MySQL 数据源」。
     *
     * <p>替代过去用 H2 内存库充当目标库的写法：被测引擎走真实的 MySQL 方言路径。</p>
     */
    public static void bindTarget(com.xiongx9527.askdata.datasource.engine.DatasourceConf conf, JdbcTemplate jdbc) {
        conf.setHost(host());
        conf.setPort(port());
        conf.setDatabase(schemaName(jdbc));
        conf.setUsername(user());
        conf.setPassword(password());
    }

    /** 把该 schema 注册为 Spring 测试上下文的元数据库（应用自己的 Flyway 负责迁移）。 */
    public static void bindSpringDatasource(DynamicPropertyRegistry registry, JdbcTemplate jdbc) {
        registry.add("spring.datasource.url", () -> jdbcUrl(jdbc));
        registry.add("spring.datasource.username", MysqlTestSupport::user);
        registry.add("spring.datasource.password", MysqlTestSupport::password);
        // 上下文按测试类缓存，连接池开太大容易撞 MySQL max_connections
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    /** 删除本次运行创建的 schema。 */
    public static void dropSchema(JdbcTemplate jdbc) {
        if (jdbc == null) {
            return;
        }
        String schema = schemaOf(jdbc);
        if (schema.isEmpty()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(url("information_schema"), user(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + schema);
        } catch (Exception ignored) {
            // 清理失败不影响断言结果
        }
    }

    /** 从 JdbcTemplate 反解 schema 名（DROP DATABASE 用）。 */
    private static String schemaOf(JdbcTemplate jdbc) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String catalog = connection.getCatalog();
            return catalog == null ? "" : catalog;
        } catch (Exception e) {
            return "";
        }
    }
}
