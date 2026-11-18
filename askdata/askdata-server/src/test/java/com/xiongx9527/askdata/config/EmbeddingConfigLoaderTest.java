package com.xiongx9527.askdata.config;

import com.xiongx9527.askdata.system.repository.SysArgRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** sys_arg → EmbeddingConfig 快照：一次读取、重复键取最大 id、非法值拒绝。 */
class EmbeddingConfigLoaderTest {

    private static Map<String, Object> row(long id, String pkey, String pval) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("pkey", pkey);
        row.put("pval", pval);
        return row;
    }

    @Test
    void noRowsReturnsDefaults() {
        SysArgRepository repo = mock(SysArgRepository.class);
        when(repo.listByPrefix("embedding")).thenReturn(Collections.emptyList());
        EmbeddingConfig config = new EmbeddingConfigLoader(repo).load();
        assertEquals(0.4D, config.getTableAlpha());
        assertEquals(10, config.getTableCount());
    }

    @Test
    void loadsValidOverrides() {
        SysArgRepository repo = mock(SysArgRepository.class);
        List<Map<String, Object>> rows = Arrays.asList(
                row(1, "embedding.table_alpha", "0.85"),
                row(2, "embedding.table_enabled", "false"),
                row(3, "embedding.datasource_count", "4"));
        when(repo.listByPrefix("embedding")).thenReturn(rows);
        EmbeddingConfig config = new EmbeddingConfigLoader(repo).load();
        assertEquals(0.85D, config.getTableAlpha());
        assertFalse(config.isTableEnabled());
        assertEquals(4, config.getDatasourceCount());
        assertTrue(config.isKeywordEnabled());
    }

    @Test
    void duplicateKeyUsesGreatestId() {
        SysArgRepository repo = mock(SysArgRepository.class);
        when(repo.listByPrefix("embedding")).thenReturn(Arrays.asList(
                row(9, "embedding.table_count", "6"),
                row(3, "embedding.table_count", "20")));
        EmbeddingConfig config = new EmbeddingConfigLoader(repo).load();
        assertEquals(6, config.getTableCount());
    }

    @Test
    void invalidValueKeepsDefault() {
        SysArgRepository repo = mock(SysArgRepository.class);
        when(repo.listByPrefix("embedding")).thenReturn(Arrays.asList(
                row(1, "embedding.table_alpha", "9.9"),
                row(2, "embedding.enabled", "yes"),
                row(3, "embedding.table_count", "-3")));
        EmbeddingConfig config = new EmbeddingConfigLoader(repo).load();
        assertEquals(0.4D, config.getTableAlpha());
        assertTrue(config.isEnabled());
        assertEquals(10, config.getTableCount());
    }

    @Test
    void queryFailureFallsBackToDefaults() {
        SysArgRepository repo = mock(SysArgRepository.class);
        when(repo.listByPrefix("embedding")).thenThrow(new RuntimeException("db down"));
        EmbeddingConfig config = new EmbeddingConfigLoader(repo).load();
        assertEquals(0.4D, config.getTableAlpha());
    }
}
