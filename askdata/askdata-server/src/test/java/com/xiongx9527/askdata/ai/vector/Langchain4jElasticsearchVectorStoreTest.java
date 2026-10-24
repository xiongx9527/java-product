package com.xiongx9527.askdata.ai.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Elasticsearch 后端的离线单测（不需要 ES 服务）：
 * 1) 索引名必须满足 ES 命名约束（小写、字符集合法、不以 -/_ 开头）；
 * 2) 索引映射必须是合法 JSON，且 vector 为 dense_vector（kNN 必需）、维度正确、
 *    metadata.row_id 带 keyword 子字段（匹配 langchain4j 元数据过滤器的实际查询路径）。
 */
class Langchain4jElasticsearchVectorStoreTest {

    private static Langchain4jElasticsearchVectorStore store() {
        return new Langchain4jElasticsearchVectorStore("http://es:9200", "", "", "", "askdata", "WaitFor");
    }

    @Test
    void collectionForProducesValidElasticsearchIndexName() {
        Langchain4jElasticsearchVectorStore store = store();
        String index = store.collectionFor("core_table");
        assertEquals("askdata-core_table", index);
        assertEquals(index.toLowerCase(Locale.ROOT), index, "ES 索引名必须全小写");
        assertTrue(index.matches("[a-z0-9][a-z0-9_-]*"), "ES 索引名字符集非法: " + index);
    }

    @Test
    void collectionForSanitizesIllegalCharacters() {
        Langchain4jElasticsearchVectorStore store = store();
        String index = store.collectionFor("Data Training/表");
        assertTrue(index.matches("[a-z0-9][a-z0-9_-]*"), "ES 索引名字符集非法: " + index);
        assertFalse(index.contains(" "), "空格应被清洗: " + index);
        assertFalse(index.contains("/"), "斜杠应被清洗: " + index);
    }

    @Test
    void mappingJsonDeclaresDenseVectorAndKeywordRowId() throws Exception {
        String json = Langchain4jElasticsearchVectorStore.mappingJson(768);
        JsonNode props = new ObjectMapper().readTree(json).path("mappings").path("properties");

        assertEquals("dense_vector", props.path("vector").path("type").asText(),
                "向量字段必须是 dense_vector，否则 kNN 不可用");
        assertEquals(768, props.path("vector").path("dims").asInt(), "维度须与实际向量一致");
        assertEquals("cosine", props.path("vector").path("similarity").asText());
        assertEquals("text", props.path("text").path("type").asText());
        assertEquals("object", props.path("metadata").path("type").asText());
        assertEquals("keyword",
                props.path("metadata").path("properties").path("row_id")
                        .path("fields").path("keyword").path("type").asText(),
                "metadata.row_id 需要 keyword 子字段以命中元数据过滤器");
    }
}
