package com.xiongx9527.askdata.mcp;

import com.ajaxjs.mcp.server.feature.annotation.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLBot 预设客户端兼容层（A1/A2 结构与纯函数部分）：
 * - 7 个 SQLBot operation_id 在 AskdataMcpTools 上均有 @Tool 注册；
 * - mcp_start 响应组装含 access_token 与 chat_id；
 * - int|str 联合类型参数转换。
 */
class McpSqlbotCompatTest {

    private static Set<String> toolNames() {
        Set<String> names = new HashSet<String>();
        for (Method m : AskdataMcpTools.class.getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool != null) {
                names.add(tool.value());
            }
        }
        return names;
    }

    @Test
    void allSqlbotOperationIdsRegistered() {
        Set<String> names = toolNames();
        for (String sqlbotName : Arrays.asList("access_token", "mcp_start", "mcp_ws_list",
                "mcp_datasource_list", "mcp_model_list", "mcp_question", "mcp_assistant")) {
            assertTrue(names.contains(sqlbotName), "缺少 SQLBot 兼容工具名: " + sqlbotName);
        }
        // 既有 askdata 原生工具名不受影响
        for (String nativeName : Arrays.asList("datasource_list", "workspace_list", "model_list",
                "chat_start", "question_answer", "assistant", "server_info")) {
            assertTrue(names.contains(nativeName), "原生工具名缺失: " + nativeName);
        }
    }

    @Test
    void mcpStartResponseContainsAccessTokenAndChatId() {
        Map<String, Object> chatInfo = new LinkedHashMap<String, Object>();
        chatInfo.put("id", 9L);
        chatInfo.put("brief", "会话");
        Map<String, Object> out = AskdataMcpTools.mcpStartResponse("tok-123", chatInfo);
        assertEquals("tok-123", out.get("access_token"));
        assertEquals(9L, out.get("chat_id"));
        assertEquals("会话", out.get("brief"));
    }

    @Test
    void mcpStartResponseWithoutTokenOmitsAccessTokenKey() {
        Map<String, Object> out = AskdataMcpTools.mcpStartResponse(null,
                new HashMap<String, Object>() {{ put("id", 3L); }});
        assertNull(out.get("access_token"));
        assertEquals(3L, out.get("chat_id"));
    }

    @Test
    void longArgOrNullHandlesIntAndStrUnion() {
        assertEquals(12L, AskdataMcpTools.longArgOrNull(12));
        assertEquals(12L, AskdataMcpTools.longArgOrNull(12L));
        assertEquals(12L, AskdataMcpTools.longArgOrNull("12"));
        assertEquals(12L, AskdataMcpTools.longArgOrNull(" 12 "));
        assertNull(AskdataMcpTools.longArgOrNull(null));
        assertNull(AskdataMcpTools.longArgOrNull(""));
        assertNull(AskdataMcpTools.longArgOrNull("  "));
        assertThrows(NumberFormatException.class, () -> AskdataMcpTools.longArgOrNull("abc"));
    }
}

