package com.xiongx9527.askdata.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Embedding 参数解析与快照语义（对齐上游 parse_parameter 的严格规则）。 */
class EmbeddingConfigTest {

    @Test
    void defaultsMatchUpstreamSettings() {
        EmbeddingConfig c = EmbeddingConfig.defaults();
        assertTrue(c.isEnabled());
        assertTrue(c.isTableEnabled());
        assertTrue(c.isKeywordEnabled());
        assertEquals(0.4D, c.getTableAlpha());
        assertEquals(10, c.getTableCount());
        assertEquals(10, c.getDatasourceCount());
        assertEquals(0.4D, c.getTerminologySimilarity());
        assertEquals(5, c.getTerminologyTopCount());
        assertEquals(0.4D, c.getDataTrainingSimilarity());
        assertEquals(5, c.getDataTrainingTopCount());
    }

    @Test
    void booleanParseIsStrict() {
        assertEquals(Boolean.TRUE, EmbeddingConfig.parse("enabled", "true"));
        assertEquals(Boolean.FALSE, EmbeddingConfig.parse("table_enabled", Boolean.FALSE));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.parse("enabled", "1"));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.parse("keyword_enabled", "yes"));
    }

    @Test
    void integerParseRejectsNonNumericAndNegative() {
        assertEquals(7, EmbeddingConfig.parse("table_count", "7"));
        assertEquals(3, EmbeddingConfig.parse("terminology_top_count", 3));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.parse("table_count", "-1"));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.parse("table_count", "1.5"));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.parse("terminology_top_count", "0"));
    }

    @Test
    void datasourceCountAllowsZeroForNoPrescreen() {
        assertEquals(0, EmbeddingConfig.parse("datasource_count", "0"));
        assertEquals(10, EmbeddingConfig.parse("datasource_count", "10"));
    }

    @Test
    void floatParseLimitedToUnitInterval() {
        assertEquals(0.75D, EmbeddingConfig.parse("table_alpha", "0.75"));
        assertEquals(0D, EmbeddingConfig.parse("table_alpha", "0"));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.parse("table_alpha", "1.2"));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.parse("table_alpha", "-0.1"));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.parse("table_alpha", "NaN"));
    }

    @Test
    void withOverridesOnlyOneFieldAndKeepsOthers() {
        EmbeddingConfig c = EmbeddingConfig.defaults()
                .with("table_alpha", 0.9D)
                .with("table_enabled", Boolean.FALSE)
                .with("table_count", 4);
        assertEquals(0.9D, c.getTableAlpha());
        assertFalse(c.isTableEnabled());
        assertEquals(4, c.getTableCount());
        assertTrue(c.isKeywordEnabled());
        assertEquals(0.4D, c.getTerminologySimilarity());
        // 原快照不可变
        assertEquals(0.4D, EmbeddingConfig.defaults().getTableAlpha());
    }
}
