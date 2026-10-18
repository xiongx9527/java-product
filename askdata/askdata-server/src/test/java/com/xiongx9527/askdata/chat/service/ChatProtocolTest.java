package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.common.util.Rows;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式协议与 SQL 安全校验（A6 协议逐字兼容的离线证据）：
 * 帧格式 {@code data:{json}\n\n}、SQL 抽取/只读校验/表名白名单/排版、推荐问题解析。
 */
class ChatProtocolTest {

    private ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // 帧格式（对齐 views/chat/answer/*.vue 的解析正则 /data:.*}\n\n/g）
    // ------------------------------------------------------------------

    @Test
    void frameIsDataJsonWithBlankLine() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (ChatModelClient.FrameWriter writer = new ChatModelClient.FrameWriter(response, objectMapper)) {
            writer.write(Rows.of("type", "id", "id", 123L));
            writer.write(Rows.of("type", "finish"));
        }
        String body = response.getContentAsString();
        assertEquals("data:{\"type\":\"id\",\"id\":123}\n\ndata:{\"type\":\"finish\"}\n\n", body);
        // 前端按 /data:.*}\n\n/g 切分后逐帧 JSON.parse
        String[] frames = body.split("\n\n");
        assertEquals(2, frames.length);
        for (String frame : frames) {
            assertTrue(frame.startsWith("data:{"));
            assertTrue(frame.endsWith("}"));
            assertNotNull(objectMapper.readTree(frame.substring("data:".length())));
        }
        assertEquals("text/event-stream;charset=UTF-8", response.getContentType());
    }

    // ------------------------------------------------------------------
    // SQL 抽取
    // ------------------------------------------------------------------

    @Test
    void extractSqlFromFencedOrPlainText() {
        assertEquals("SELECT a FROM b", ChatQuestionService.extractSql("```sql\nSELECT a FROM b\n```"));
        assertEquals("SELECT a FROM b", ChatQuestionService.extractSql("```\nSELECT a FROM b\n```"));
        assertEquals("SELECT a FROM b", ChatQuestionService.extractSql("闲聊\nSELECT a FROM b;\n结束"));
        assertEquals("WITH t AS (SELECT 1) SELECT * FROM t",
                ChatQuestionService.extractSql("WITH t AS (SELECT 1) SELECT * FROM t"));
        // 只取首条语句（单条执行语义）
        assertEquals("SELECT 1", ChatQuestionService.extractSql("```sql\nSELECT 1; SELECT 2\n```"));
        assertNull(ChatQuestionService.extractSql("我没有找到合适的表"));
        assertNull(ChatQuestionService.extractSql(""));
        assertNull(ChatQuestionService.extractSql(null));
    }

    // ------------------------------------------------------------------
    // 只读校验（与 /datasource/execSql 同一安全子集）
    // ------------------------------------------------------------------

    @Test
    void validateSqlRejectsWritesAndUnauthorizedTables() {
        assertNull(ChatQuestionService.validateSqlSafety("SELECT id FROM orders", "h2"));
        assertNull(ChatQuestionService.validateSqlSafety("WITH t AS (SELECT 1) SELECT * FROM t", "h2"));
        assertNotNull(ChatQuestionService.validateSqlSafety("DELETE FROM orders", "h2"));
        assertNotNull(ChatQuestionService.validateSqlSafety("UPDATE orders SET a = 1", "h2"));
        assertNotNull(ChatQuestionService.validateSqlSafety("DROP TABLE orders", "h2"));
        assertNotNull(ChatQuestionService.validateSqlSafety("INSERT INTO orders VALUES (1)", "h2"));
        assertNotNull(ChatQuestionService.validateSqlSafety("SELECT * INTO OUTFILE '/tmp/a'", "mysql"));
        assertNotNull(ChatQuestionService.validateSqlSafety("SELECT version()", "mysql"));
        assertNotNull(ChatQuestionService.validateSqlSafety("SELECT load_file('/etc/passwd')", "mysql"));
        assertNotNull(ChatQuestionService.validateSqlSafety("", "h2"));

        // 表名白名单（防幻觉到未勾选的表）
        List<String> allowed = Arrays.asList("orders", "users");
        assertNull(ChatQuestionService.validateTables("SELECT * FROM orders o JOIN users u ON o.id = u.id", allowed));
        assertNotNull(ChatQuestionService.validateTables("SELECT * FROM secrets", allowed));
        assertNotNull(ChatQuestionService.validateTables("SELECT * FROM orders JOIN secrets ON 1=1", allowed));
        // 未勾选表时不做拦截（保持与数据源勾选态一致的宽松语义）
        assertNull(ChatQuestionService.validateTables("SELECT * FROM secrets", Collections.emptyList()));
    }

    @Test
    void usedTablesParsesFromAndJoin() {
        assertEquals(Arrays.asList("orders"), ChatQuestionService.usedTables("SELECT * FROM orders"));
        assertEquals(Arrays.asList("orders", "users"),
                ChatQuestionService.usedTables("SELECT * FROM orders o JOIN users u ON o.id = u.id"));
        assertEquals(Arrays.asList("orders"),
                ChatQuestionService.usedTables("SELECT * FROM \"orders\" WHERE 1 = 1"));
        assertTrue(ChatQuestionService.usedTables("SELECT 1").isEmpty());
    }

    // ------------------------------------------------------------------
    // 排版 / 摘要 / 推荐问题解析
    // ------------------------------------------------------------------

    @Test
    void formatSqlAndBrief() {
        String formatted = ChatQuestionService.formatSql(
                "SELECT status, COUNT(*) AS cnt FROM orders WHERE status = 'PAID' GROUP BY status ORDER BY cnt DESC LIMIT 10");
        assertTrue(formatted.contains("\nFROM orders"));
        assertTrue(formatted.contains("\nWHERE status = 'PAID'"));
        assertTrue(formatted.contains("\nGROUP BY status"));
        assertTrue(formatted.contains("\nORDER BY cnt DESC"));
        assertTrue(formatted.contains("\nLIMIT 10"));

        assertEquals("新会话", ChatQuestionService.briefOf("  "));
        assertEquals("每个状态的订单数", ChatQuestionService.briefOf("每个状态的订单数"));
        assertEquals(20, ChatQuestionService.briefOf("这是一个非常非常非常非常非常长的问题文本用于验证截断".trim()).length());
    }

    @Test
    void parseRecommendQuestions() {
        List<String> parsed = ChatQuestionService.parseStringList(
                "[\"各状态订单金额？\",\"哪个用户下单最多？\"]", objectMapper);
        assertEquals(2, parsed.size());
        assertEquals("各状态订单金额？", parsed.get(0));

        // 逐行文本形式（模型未按要求输出 JSON 时）
        List<String> lines = ChatQuestionService.parseStringList("1. 各状态订单金额？\n- 哪个用户下单最多？",
                objectMapper);
        assertEquals(2, lines.size());
        assertEquals("各状态订单金额？", lines.get(0));

        // 带围栏
        assertEquals(1, ChatQuestionService.parseStringList("```json\n[\"追问\"]\n```", objectMapper).size());

        assertTrue(ChatQuestionService.parseStringList(null, objectMapper).isEmpty());
        assertTrue(ChatQuestionService.parseStringList("  ", objectMapper).isEmpty());
    }

    // ------------------------------------------------------------------
    // 快捷命令 /regenerate（前端 index.vue askAgain 的发送形态）
    // ------------------------------------------------------------------

    @Test
    void regenerateCommandParsing() {
        assertTrue(ChatQuestionService.isRegenerateCommand("/regenerate 123456"));
        assertTrue(ChatQuestionService.isRegenerateCommand("/regenerate"));
        assertTrue(ChatQuestionService.isRegenerateCommand("  /regenerate 99  "));
        assertFalse(ChatQuestionService.isRegenerateCommand("每个状态的订单数"));
        assertFalse(ChatQuestionService.isRegenerateCommand("re /regenerate 的用处"));
        assertFalse(ChatQuestionService.isRegenerateCommand(""));

        assertEquals(123456L, ChatQuestionService.extractCommandRecordId("/regenerate 123456").longValue());
        assertEquals(null, ChatQuestionService.extractCommandRecordId("/regenerate"));
        // 其他文本原样作为提问处理（不按命令解析）
        assertFalse(ChatQuestionService.isRegenerateCommand("/help 怎么导出"));
    }

    // ------------------------------------------------------------------
    // 端点规范化（Ollama / OpenAI 兼容，D7）
    // ------------------------------------------------------------------

    @Test
    void modelEndpointNormalization() {
        assertEquals("http://127.0.0.1:11434/v1/chat/completions",
                ChatModelClient.chatCompletionsUrl("http://127.0.0.1:11434"));
        assertEquals("http://127.0.0.1:11434/v1/chat/completions",
                ChatModelClient.chatCompletionsUrl("http://127.0.0.1:11434/"));
        assertEquals("http://x/v1/chat/completions", ChatModelClient.chatCompletionsUrl("http://x/v1"));
        assertEquals("http://x/v1/chat/completions",
                ChatModelClient.chatCompletionsUrl("http://x/v1/chat/completions"));
        assertEquals("http://127.0.0.1:11434/v1/embeddings",
                ChatModelClient.embeddingsUrl("http://127.0.0.1:11434"));
        // 余弦
        double[] a = {1, 0};
        double[] b = {1, 0};
        double[] c = {0, 1};
        assertEquals(1.0, ChatModelClient.cosine(a, b), 1e-9);
        assertEquals(0.0, ChatModelClient.cosine(a, c), 1e-9);
        assertEquals(0.0, ChatModelClient.cosine(a, null), 1e-9);
    }
}
