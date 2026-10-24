package com.xiongx9527.askdata.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.fw.plus.common.model.JsonResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一响应包装 {@link Result}（继承框架 {@link JsonResult}）的信封形状回归。
 *
 * <p>要点：序列化结果必须<b>恰好</b>是 {@code {"code","data","msg"}} 三个键
 * （SQLBot ResponseMiddleware 契约，前端只解一层 data），继承框架基类后不得多出
 * {@code success} / {@code message} 等键，也不得改变 {@code ok(String)} 的 data 语义。</p>
 */
class ResultEnvelopeShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resultExtendsFrameworkJsonResult() {
        assertTrue(JsonResult.class.isAssignableFrom(Result.class), "Result 应继承框架 JsonResult");
        assertTrue(Map.class.isAssignableFrom(Result.class), "JsonResult 是 Map，便于直接序列化");
    }

    @Test
    void envelopeHasExactlyCodeDataMsg() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(Result.ok("payload")));
        assertEquals(0, node.get("code").asInt());
        assertEquals("payload", node.get("data").asText());
        assertTrue(node.has("msg"), "必须有 msg 键（SQLBot 契约）");
        assertTrue(node.get("msg").isNull(), "成功响应的 msg 为 null");
        assertFalse(node.has("success"), "不应出现 success 键（继承后需去掉）");
        assertFalse(node.has("message"), "不应出现 message 键（消息键应重命名为 msg）");
        assertEquals(3, node.size(), "信封只应有 code / data / msg 三个键");
    }

    @Test
    void okWithObjectKeepsDataSemantics() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(Result.ok(List.of("a", "b"))));
        assertEquals(2, node.get("data").size());
        assertEquals(0, node.get("code").asInt());
    }

    @Test
    void msgGetterSetterRoundTrip() {
        // 显式类型见证 + 转型：基类同名 ok(String) 对 null 更具体，缺省会解析到它
        Result<Object> result = Result.<Object>ok((Object) null);
        result.setMsg("提示");
        assertEquals("提示", result.getMsg());
        assertEquals(0, result.getCode());
    }
}
