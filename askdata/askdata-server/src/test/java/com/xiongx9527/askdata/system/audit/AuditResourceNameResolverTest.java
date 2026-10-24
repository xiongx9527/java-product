package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.audit.AuditModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审计资源名解析（跨表 UNION 查名称；查不到安全返回 null）。
 */
class AuditResourceNameResolverTest {

    private DataSource ds;
    private JdbcTemplate jdbc;
    private AuditResourceNameResolver resolver;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE core_dashboard (id BIGINT, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE terminology (id BIGINT, word VARCHAR(100))");
        jdbc.execute("CREATE TABLE chat (id BIGINT, brief VARCHAR(100))");
        jdbc.update("INSERT INTO core_dashboard VALUES (1, 'Board A')");
        jdbc.update("INSERT INTO terminology VALUES (9, 'term-x')");
        jdbc.update("INSERT INTO chat VALUES (7, '月度销售分析')");
        resolver = new AuditResourceNameResolver(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) {
            try {
                ((org.springframework.jdbc.datasource.embedded.EmbeddedDatabase) ds).shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void resolvesDashboardName() {
        assertEquals("Board A", resolver.resolve(AuditModule.DASHBOARD, "1"));
    }

    @Test
    void resolvesTerminologyWord() {
        assertEquals("term-x", resolver.resolve(AuditModule.TERMINOLOGY, "9"));
    }

    @Test
    void resolvesChatBrief() {
        // F-07：CHAT 资源名取 chat.brief（rename 写入的会话标题）
        assertEquals("月度销售分析", resolver.resolve(AuditModule.CHAT, "7"));
    }

    @Test
    void unknownModuleReturnsNull() {
        assertNull(resolver.resolve(AuditModule.THEME, "1"));
    }

    @Test
    void missingResourceReturnsNull() {
        assertNull(resolver.resolve(AuditModule.DASHBOARD, "999"));
    }
}
