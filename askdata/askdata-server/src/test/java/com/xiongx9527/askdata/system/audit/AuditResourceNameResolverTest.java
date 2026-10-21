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
        jdbc.update("INSERT INTO core_dashboard VALUES (1, 'Board A')");
        jdbc.update("INSERT INTO terminology VALUES (9, 'term-x')");
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
    void unknownModuleReturnsNull() {
        assertNull(resolver.resolve(AuditModule.CHAT, "1"));
    }

    @Test
    void missingResourceReturnsNull() {
        assertNull(resolver.resolve(AuditModule.DASHBOARD, "999"));
    }
}
