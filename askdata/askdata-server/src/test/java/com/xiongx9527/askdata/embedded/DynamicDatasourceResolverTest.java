package com.xiongx9527.askdata.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A8：高级应用（type 1/3）动态数据源解析测试。
 *
 * <p>验证宿主下发的连接配置被解析为不落库的 {@link DynamicDatasource}；普通小助手（type=0）
 * 与页面嵌入（type=4）不允许使用动态数据源。</p>
 */
class DynamicDatasourceResolverTest {

    private final DynamicDatasourceResolver resolver = new DynamicDatasourceResolver(new ObjectMapper());

    @Test
    void advancedApplicationTypesAreAccepted() {
        assertTrue(DynamicDatasourceResolver.isAdvancedApplication(1));
        assertTrue(DynamicDatasourceResolver.isAdvancedApplication(3));
        assertFalse(DynamicDatasourceResolver.isAdvancedApplication(0));
        assertFalse(DynamicDatasourceResolver.isAdvancedApplication(4));
    }

    @Test
    void resolvesMapConfiguration() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("host", "127.0.0.1");
        configuration.put("port", 3306);
        configuration.put("database", "demo");
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("type", "mysql");
        host.put("name", "host-ds");
        host.put("configuration", configuration);

        DynamicDatasource ds = resolver.resolve(1, host);
        assertEquals(DatasourceType.MYSQL, ds.getType());
        assertEquals("host-ds", ds.getName());
        assertEquals("127.0.0.1", ds.getConf().getHost());
        assertEquals(3306, ds.getConf().getPort());
        assertEquals("demo", ds.getConf().getDatabase());
    }

    @Test
    void resolvesJsonStringConfiguration() {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("type", "h2");
        host.put("configuration", "{\"database\":\"jdbc:h2:mem:x\",\"username\":\"sa\"}");
        DynamicDatasource ds = resolver.resolve(3, host);
        assertEquals(DatasourceType.H2, ds.getType());
        assertEquals("jdbc:h2:mem:x", ds.getConf().getDatabase());
        assertEquals("sa", ds.getConf().getUsername());
    }

    @Test
    void nonAdvancedApplicationIsRejected() {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("type", "mysql");
        BadRequestException error = assertThrows(BadRequestException.class, () -> resolver.resolve(0, host));
        assertTrue(error.getMessage().contains("advanced applications"), error.getMessage());
        assertThrows(BadRequestException.class, () -> resolver.resolve(4, host));
    }

    @Test
    void missingTypeIsRejected() {
        assertThrows(BadRequestException.class, () -> resolver.resolve(1, new LinkedHashMap<>()));
    }

    @Test
    void unsupportedTypeIsRejected() {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("type", "not-a-db");
        assertThrows(BadRequestException.class, () -> resolver.resolve(1, host));
    }

    @Test
    void missingNameFallsBackToTypeDisplayName() {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("type", "mysql");
        DynamicDatasource ds = resolver.resolve(1, host);
        assertEquals(DatasourceType.MYSQL.getDbName(), ds.getName());
    }
}

