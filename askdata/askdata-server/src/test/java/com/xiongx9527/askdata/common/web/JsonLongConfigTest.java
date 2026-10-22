package com.xiongx9527.askdata.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花 ID 必须以字符串下发（超出 JS 安全整数），时间戳仍保持数字。
 */
class JsonLongConfigTest {

    private ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JsonLongConfig().safeLongCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    void snowflakeIdIsSerializedAsString() throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", 7518975088025182208L);
        body.put("create_time", 1792663356772L);
        body.put("count", 3L);
        String json = mapper().writeValueAsString(body);

        assertTrue(json.contains("\"id\":\"7518975088025182208\""), json);
        assertTrue(json.contains("\"create_time\":1792663356772"), json);
        assertTrue(json.contains("\"count\":3"), json);
    }

    @Test
    void negativeSnowflakeIdIsSerializedAsString() throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", -7518975088025182208L);
        String json = mapper().writeValueAsString(body);
        assertTrue(json.contains("\"id\":\"-7518975088025182208\""), json);
    }
}

