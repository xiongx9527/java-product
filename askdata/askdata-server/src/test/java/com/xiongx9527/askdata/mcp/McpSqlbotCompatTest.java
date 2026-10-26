package com.xiongx9527.askdata.mcp;

import com.ajaxjs.mcp.server.feature.annotation.Tool;
import com.xiongx9527.askdata.common.util.Rows;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLBot 预设客户端兼容层（A7/A11 与纯函数部分）：
 * - 7 个 SQLBot operation_id 在 AskdataMcpTools 上均有 @Tool 注册；
 * - mcp_start 响应只含 access_token 与 chat_id；
 * - MCP 数据源列表裁掉内部字段并按名称排序；
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
    void mcpStartResponseContainsOnlyAccessTokenAndChatId() {
        Map<String, Object> chatInfo = new LinkedHashMap<String, Object>();
        chatInfo.put("id", 9L);
        chatInfo.put("brief", "会话");
        chatInfo.put("datasource", 7L);
        chatInfo.put("engine_type", "mysql");
        Map<String, Object> out = AskdataMcpTools.mcpStartResponse("tok-123", chatInfo);
        assertEquals(2, out.size(), out.toString());
        assertEquals("tok-123", out.get("access_token"));
        assertEquals(9L, out.get("chat_id"));
        assertFalse(out.containsKey("brief"));
        assertFalse(out.containsKey("datasource"));
        assertFalse(out.containsKey("engine_type"));
    }

    @Test
    void mcpStartResponseWithoutTokenOmitsAccessTokenKey() {
        Map<String, Object> out = AskdataMcpTools.mcpStartResponse(null,
                new HashMap<String, Object>() {{ put("id", 3L); }});
        assertNull(out.get("access_token"));
        assertEquals(3L, out.get("chat_id"));
        assertEquals(1, out.size(), out.toString());
    }

    @Test
    void datasourceListStripsInternalFieldsAndSortsByName() {
        Map<String, Object> zeta = Rows.of("id", 2L, "name", "zeta", "configuration", "secret",
                "embedding", "[]", "table_relation", "{}", "recommended_config", 2, "type", "mysql");
        Map<String, Object> alpha = Rows.of("id", 1L, "name", "alpha", "configuration", "secret",
                "embedding", "[]", "table_relation", "{}", "recommended_config", 0, "type", "h2");
        List<Map<String, Object>> list = new ArrayList<>(Arrays.asList(zeta, alpha));

        AskdataMcpTools.sanitizeDatasourceList(list);

        assertEquals(Arrays.asList("alpha", "zeta"),
                Arrays.asList(Rows.str(list.get(0), "name"), Rows.str(list.get(1), "name")));
        for (Map<String, Object> row : list) {
            for (String removed : Arrays.asList("configuration", "embedding", "table_relation",
                    "recommended_config")) {
                assertFalse(row.containsKey(removed), "MCP 数据源列表不应包含 " + removed + ": " + row);
            }
            assertTrue(row.containsKey("id") && row.containsKey("name") && row.containsKey("type"),
                    "其余既有字段应保留: " + row);
        }
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
