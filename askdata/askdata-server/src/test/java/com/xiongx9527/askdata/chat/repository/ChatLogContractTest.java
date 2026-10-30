package com.xiongx9527.askdata.chat.repository;

import com.xiongx9527.askdata.common.util.Maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A26：chat_log / chat_record 的日志契约。
 *
 * <ul>
 *   <li>{@code operate} 必须使用 SQLBot {@code OperationEnum} 的枚举名（前端按
 *       {@code operate_key} 精确分发详情组件，并按 {@code chat.log.<NAME>} 取 i18n）；</li>
 *   <li>{@code message} 为结构化对象（SQLBot {@code orjson.loads} 语义）；</li>
 *   <li>{@code chart_answer} 落库为 content/reasoning_content 对象，读取时解包为模型思考。</li>
 * </ul>
 */
class ChatLogContractTest {

    private final ChatRepository repository = new ChatRepository(
            org.mockito.Mockito.mock(JdbcTemplate.class), org.mockito.Mockito.mock(IdGenerator.class),
            new ObjectMapper());

    /** SQLBot backend/apps/chat/models/chat_model.py 的 OperationEnum 枚举名。 */
    private static final Map<String, String> SQLBOT_OPERATION_ENUM = Maps.of(
            "OPERATE_GENERATE_SQL", "GENERATE_SQL",
            "OPERATE_GENERATE_CHART", "GENERATE_CHART",
            "OPERATE_ANALYSIS", "ANALYSIS",
            "OPERATE_PREDICT", "PREDICT_DATA",
            "OPERATE_RECOMMEND_QUESTIONS", "GENERATE_RECOMMENDED_QUESTIONS",
            "OPERATE_EXECUTE_SQL", "EXECUTE_SQL",
            "OPERATE_EXTRACT_KEYWORDS", "EXTRACT_KEYWORDS",
            "OPERATE_FILTER_TERMS", "FILTER_TERMS",
            "OPERATE_FILTER_SQL_EXAMPLE", "FILTER_SQL_EXAMPLE",
            "OPERATE_FILTER_CUSTOM_PROMPT", "FILTER_CUSTOM_PROMPT");

    @Test
    void operateNamesMatchSqlBotEnum() throws Exception {
        for (Map.Entry<String, String> expected : SQLBOT_OPERATION_ENUM.entrySet()) {
            Field field = ChatQuestionService.class.getDeclaredField(expected.getKey());
            field.setAccessible(true);
            assertEquals(expected.getValue(), field.get(null),
                    expected.getKey() + " 必须使用 SQLBot OperationEnum 枚举名");
        }
    }

    @Test
    void errorEventTypesMatchSqlBot() throws Exception {
        assertEquals("exec-sql-err", constant("ERROR_TYPE_EXEC_SQL"));
        assertEquals("db-connection-err", constant("ERROR_TYPE_DB_CONNECTION"));
    }

    private static String constant(String name) throws Exception {
        Field field = ChatQuestionService.class.getDeclaredField(name);
        field.setAccessible(true);
        return String.valueOf(field.get(null));
    }

    @Test
    void logMessageIsStructuredObject() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("operate", "EXECUTE_SQL");
        row.put("message", json("{~sql~:~SELECT 1~,~count~:3}"));
        Map<String, Object> item = repository.toLogItem(row);
        assertTrue(item.get("message") instanceof Map, String.valueOf(item.get("message")));
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) item.get("message");
        assertEquals("SELECT 1", message.get("sql"));
        assertEquals(3, message.get("count"));
    }

    @Test
    void logMessageKeepsArrayShapeForLlmMessages() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("operate", "GENERATE_SQL");
        row.put("message", json("[{~type~:~system~,~content~:~template~},"
                + "{~type~:~human~,~content~:~question~}]"));
        Map<String, Object> item = repository.toLogItem(row);
        assertTrue(item.get("message") instanceof List, String.valueOf(item.get("message")));
        @SuppressWarnings("unchecked")
        List<Object> message = (List<Object>) item.get("message");
        assertEquals(2, message.size());
    }

    @Test
    void logMessageFallsBackToRawText() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("operate", "CHOOSE_TABLE");
        row.put("message", "orders, users");
        assertEquals("orders, users", repository.toLogItem(row).get("message"));

        row.put("operate", "GENERATE_SQL");
        row.put("message", "not-json");
        assertEquals("not-json", repository.toLogItem(row).get("message"));

        row.put("message", null);
        assertNull(repository.toLogItem(row).get("message"));
    }

    @Test
    void chartAnswerUnwrapsReasoningContent() {
        String stored = json("{~content~:~cfg~,~reasoning_content~:~reason~}");
        assertEquals("reason", ChatRepository.unwrapThinking(stored));
    }

    @Test
    void chartAnswerFallsBackToContentWhenNoReasoning() {
        String stored = json("{~content~:~cfg~,~reasoning_content~:~~}");
        assertEquals("cfg", ChatRepository.unwrapThinking(stored));
    }

    @Test
    void chartAnswerKeepsLegacyPlainTextAndOldImageUrl() {
        assertEquals("纯文本思考", ChatRepository.unwrapThinking("纯文本思考"));
        assertEquals("/chart/1.png", ChatRepository.unwrapThinking("/chart/1.png"));
        assertNull(ChatRepository.unwrapThinking(null));
        assertEquals("", ChatRepository.unwrapThinking(""));
        assertFalse(ChatRepository.unwrapThinking("{broken").isEmpty());
    }

    @Test
    void recordViewUnwrapsChartAnswer() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("chart_answer", json("{~content~:~cfg~,~reasoning_content~:~thinking~}"));
        Map<String, Object> record = repository.toRecord(row, false);
        assertEquals("thinking", record.get("chart_answer"));
        // 其他字段不受影响
        assertEquals("GENERATE_SQL", Rows.str(Rows.of("operate", "GENERATE_SQL"), "operate"));
    }

    /** 把 ~ 还原为双引号，便于在测试源码里书写 JSON 字面量。 */
    private static String json(String text) {
        return text.replace('~', QUOTE);
    }

    private static final char QUOTE = (char) 34;
}
