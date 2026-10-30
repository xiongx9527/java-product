package com.xiongx9527.askdata.config;

import com.xiongx9527.askdata.common.util.Rows;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可配置化能力的单测（不需要外部服务）：
 * 1) OpenAPI 元数据（标题 / 版本 / 说明）可配置，版本缺省取构建版本；
 * 2) 缓存后端与 Sa-Token 会话存储的归一化（memory / redis / none）；
 * 3) 缓存边界的 key 归一化（大小写），保证缓存命中与直查数据库的取值语义一致。
 */
class ConfigurableRuntimeOptionsTest {

    @Test
    void openApiTitleAndDescriptionAreConfigurable() {
        OpenApiProperties props = new OpenApiProperties();
        props.setTitle("自定义标题");
        props.setDescription("自定义说明 {tokenHeader}");
        assertEquals("自定义标题", props.getTitle());
        assertTrue(props.getDescription().contains("{tokenHeader}"));
    }

    @Test
    void openApiVersionDefaultsToBuildVersion() {
        OpenApiProperties props = new OpenApiProperties();
        // 构建版本来自 Maven 过滤的 askdata-build.properties（正常构建下即 project.version）
        assertFalse(props.getVersion().trim().isEmpty(), "版本不应为空");
        assertEquals(props.getVersion(), OpenApiProperties.buildVersion());
    }

    @Test
    void cacheTypeAndSaTokenStoreAreNormalized() {
        CacheProperties props = new CacheProperties();
        assertEquals(CacheProperties.TYPE_MEMORY, props.resolveType());
        assertEquals(CacheProperties.TYPE_MEMORY, props.resolveSaTokenStore());

        props.setType("REDIS");
        assertEquals(CacheProperties.TYPE_REDIS, props.resolveType());
        // 留空时 Sa-Token 跟随缓存后端
        assertEquals(CacheProperties.TYPE_REDIS, props.resolveSaTokenStore());

        props.setSaTokenStore("memory");
        assertEquals(CacheProperties.TYPE_MEMORY, props.resolveSaTokenStore());

        props.setType("none");
        assertEquals(CacheProperties.TYPE_NONE, props.resolveType());
        assertEquals(CacheProperties.TYPE_MEMORY, props.resolveSaTokenStore());
    }

    @Test
    void lowerCaseKeysNormalizesJdbcRowKeys() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", 1L);
        row.put("PASSWORD", "hash");
        Map<String, Object> normalized = Rows.lowerCaseKeys(row);
        assertEquals(1L, normalized.get("id"));
        assertEquals("hash", normalized.get("password"),
                "缓存往返后小写 key 直取必须可用（否则登录/鉴权会静默取空）");
        // 原行不被修改
        assertTrue(row.containsKey("ID"));
    }
}
