package com.xiongx9527.askdata.chat.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 对话域表（chat / chat_record / chat_log）JDBC 访问层。
 * 表结构移植自 SQLBot {@code apps/chat/models/chat_model.py}（字段名不变）。
 */
@Repository
public class ChatRepository {

    private static final String CHAT_COLUMNS =
            "id, create_by, oid, create_time, update_time, deleted, brief, datasource, engine_type, "
                    + "ds_type, origin, chat_type, question, latest_record_time";

    private static final String RECORD_COLUMNS =
            "id, chat_id, question, create_by, oid, create_time, update_time, finish_time, sql_answer, sql, "
                    + "datasource, data, chart_answer, chart, analysis, analysis_thinking, predict, predict_content, "
                    + "predict_data, finish, error, run_time, first_chat, recommended_question, analysis_record_id, "
                    + "predict_record_id, regenerate_record_id, duration, total_tokens";

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    private final ObjectMapper objectMapper;

    public ChatRepository(JdbcTemplate jdbc, IdGenerator idGenerator, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // chat
    // ------------------------------------------------------------------

    /** GET /chat/list：当前用户工作空间下的会话（按最近活跃时间倒序）。 */
    public List<Map<String, Object>> listChats(long oid) {
        return jdbc.queryForList("SELECT " + CHAT_COLUMNS + " FROM chat WHERE oid = ? AND deleted = 0 "
                + "ORDER BY latest_record_time DESC, create_time DESC", oid);
    }

    /** 会话分页（{@code /chat/question/pager/{page}/{size}}，SQLBot 保留路由）。 */
    public List<Map<String, Object>> pageChats(long oid, int page, int size) {
        return jdbc.queryForList("SELECT " + CHAT_COLUMNS + " FROM chat WHERE oid = ? AND deleted = 0 "
                        + "ORDER BY latest_record_time DESC, create_time DESC LIMIT ? OFFSET ?",
                oid, size, Math.max(0, page - 1) * size);
    }

    public long countChats(long oid) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM chat WHERE oid = ? AND deleted = 0", Long.class, oid);
        return count == null ? 0L : count;
    }

    /** 软删除的会话不再可见（对齐 SQLBot delete_chat 后 list/get 的语义）。 */
    public Optional<Map<String, Object>> findChat(long id) {
        return jdbc.queryForList("SELECT " + CHAT_COLUMNS + " FROM chat WHERE id = ? AND deleted = 0", id)
                .stream().findFirst();
    }

    public long insertChat(long createBy, long oid, String brief, Long datasource, String engineType,
                           String dsType, int origin, String question) {
        long id = idGenerator.nextId();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("INSERT INTO chat (id, create_by, oid, create_time, update_time, deleted, brief, datasource, "
                        + "engine_type, ds_type, origin, chat_type, question, latest_record_time) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, NULL, ?, ?)",
                id, createBy, oid, now, now, brief, datasource, engineType, dsType, origin, question, now);
        return id;
    }

    /** 重命名会话；{@code brief} 为空时保留原名（对齐 SQLBot rename_chat 的空分支）。 */
    public void renameChat(long id, String brief) {
        if (brief == null || brief.trim().isEmpty()) {
            return;
        }
        jdbc.update("UPDATE chat SET brief = ?, update_time = ? WHERE id = ?",
                brief, new Timestamp(System.currentTimeMillis()), id);
    }

    public void deleteChat(long id) {
        jdbc.update("UPDATE chat SET deleted = 1 WHERE id = ?", id);
    }

    public void touchChat(long id) {
        jdbc.update("UPDATE chat SET latest_record_time = ? WHERE id = ?",
                new Timestamp(System.currentTimeMillis()), id);
    }

    public void setChatDatasource(long id, long datasource, String engineType, String dsType) {
        jdbc.update("UPDATE chat SET datasource = ?, engine_type = ?, ds_type = ?, update_time = ? WHERE id = ?",
                datasource, engineType, dsType, new Timestamp(System.currentTimeMillis()), id);
    }

    // ------------------------------------------------------------------
    // chat_record
    // ------------------------------------------------------------------

    public Optional<Map<String, Object>> findRecord(long id) {
        return jdbc.queryForList("SELECT " + RECORD_COLUMNS + " FROM chat_record WHERE id = ?", id).stream()
                .findFirst();
    }

    public List<Map<String, Object>> listRecordsByChat(long chatId) {
        return jdbc.queryForList("SELECT " + RECORD_COLUMNS + " FROM chat_record WHERE chat_id = ? "
                + "ORDER BY create_time ASC", chatId);
    }

    /**
     * 会话中是否存在真实问答记录（A17：排除创建会话时生成的 first_chat 空记录）。
     *
     * <p>用于「首问」判定（会话摘要生成、前端首问引导），对齐 SQLBot 侧
     * {@code first_chat == False} 的过滤语义。</p>
     */
    public boolean hasUserRecord(long chatId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_record WHERE chat_id = ? AND first_chat = FALSE",
                Long.class, chatId);
        return count != null && count > 0L;
    }

    /**
     * 最近一条可用于「重新生成」的记录 id：排除首条记录与 analysis/predict 子记录
     * （对齐 SQLBot {@code question_answer_inner} 的 last record 查询条件）。
     */
    public Optional<Long> lastUsableRecordId(long chatId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM chat_record WHERE chat_id = ? AND first_chat = FALSE "
                        + "AND analysis_record_id IS NULL AND predict_record_id IS NULL "
                        + "ORDER BY create_time DESC LIMIT 1", chatId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(Rows.lng(rows.get(0), "id", 0L));
    }

    public long insertRecord(long chatId, String question, long createBy, long oid, Long datasource,
                             boolean firstChat, Long regenerateRecordId) {
        long id = idGenerator.nextId();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("INSERT INTO chat_record (id, chat_id, question, create_by, oid, create_time, update_time, "
                        + "datasource, finish, first_chat, regenerate_record_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)",
                id, chatId, question, createBy, oid, now, now, datasource, firstChat, regenerateRecordId);
        return id;
    }

    /**
     * A17：创建会话时的首条空记录（first_chat=TRUE，question 为空）。
     *
     * <p>对齐 SQLBot {@code curd/chat.py:783-800} 的 {@code create_chat}：会话一经创建就存在
     * 一条首记录，前端据此渲染首问引导；{@code lastUsableRecordId} 等查询已排除
     * {@code first_chat=TRUE} 的记录，不影响重新生成语义。</p>
     */
    public long insertFirstChatRecord(long chatId, long createBy, long oid, Long datasource) {
        long id = idGenerator.nextId();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("INSERT INTO chat_record (id, chat_id, question, create_by, oid, create_time, update_time, "
                        + "datasource, finish, first_chat) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, FALSE, TRUE)",
                id, chatId, createBy, oid, now, now, datasource);
        return id;
    }

    public void updateRecordField(long id, String column, Object value) {
        jdbc.update("UPDATE chat_record SET " + column + " = ?, update_time = ? WHERE id = ?",
                value, new Timestamp(System.currentTimeMillis()), id);
    }

    /** 保存生成的 SQL 与其思考流（sql / sql_answer）。 */
    public void saveSql(long id, String sql, String sqlAnswer) {
        updateRecordField(id, "sql", sql);
        updateRecordField(id, "sql_answer", sqlAnswer);
    }

    /** 保存执行的表格数据（JSON 文本）：data 列。 */
    public void saveSqlData(long id, String dataJson) {
        updateRecordField(id, "data", dataJson);
    }

    /** 保存图表配置与其思考流（chart / chart_answer）。 */
    public void saveChart(long id, String chartJson, String chartAnswer) {
        updateRecordField(id, "chart", chartJson);
        updateRecordField(id, "chart_answer", chartAnswer);
    }

    public void saveRecommendedQuestion(long id, String questionsJson) {
        updateRecordField(id, "recommended_question", questionsJson);
    }

    /** 保存分析结论与其思考流（analysis / analysis_thinking）。 */
    public void saveAnalysis(long id, String analysis, String analysisThinking) {
        updateRecordField(id, "analysis", analysis);
        updateRecordField(id, "analysis_thinking", analysisThinking);
    }

    /** 保存预测结论与其思考流（predict_content / predict）。 */
    public void savePredictContent(long id, String predictContent, String predict) {
        updateRecordField(id, "predict_content", predictContent);
        updateRecordField(id, "predict", predict);
    }

    public void savePredictData(long id, String predictDataJson) {
        updateRecordField(id, "predict_data", predictDataJson);
    }

    /**
     * 保存问数知识链路的关键词（A2：extracted_keywords / expanded_keywords）。
     * 未命中术语时写空串（列非 NULL，便于前端与日志判读）。
     */
    public void saveKeywords(long id, String extractedKeywords, String expandedKeywords) {
        updateRecordField(id, "extracted_keywords", extractedKeywords == null ? "" : extractedKeywords);
        updateRecordField(id, "expanded_keywords", expandedKeywords == null ? "" : expandedKeywords);
    }

    public void finishRecord(long id, long runTime, long totalTokens) {
        jdbc.update("UPDATE chat_record SET finish = TRUE, finish_time = ?, run_time = ?, total_tokens = ? "
                        + "WHERE id = ?",
                new Timestamp(System.currentTimeMillis()), runTime, totalTokens, id);
    }

    public void saveError(long id, String error) {
        jdbc.update("UPDATE chat_record SET error = ?, finish = TRUE, finish_time = ? WHERE id = ?",
                error, new Timestamp(System.currentTimeMillis()), id);
    }

    /**
     * 记录级 analysis/predict 子记录：独立新增一条子记录，外键只写 child → base。
     *
     * <p>对齐 SQLBot {@code curd/chat.py:841-865}：基础记录的 analysis_record_id /
     * predict_record_id 保持 NULL。前端 index.vue 要求基础气泡两个外键为 NULL 才渲染
     * ChartAnswer；若反向回写基础记录，刷新后基础气泡的 SQL/数据/图表全部丢失。</p>
     *
     * @param actionType analysis 或 predict
     */
    public long insertSubRecord(long chatId, String question, long createBy, long oid, Long datasource,
                                String actionType, long baseRecordId) {
        long id = idGenerator.nextId();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        boolean analysis = "analysis".equals(actionType);
        String linkColumn = analysis ? "analysis_record_id" : "predict_record_id";
        jdbc.update("INSERT INTO chat_record (id, chat_id, question, create_by, oid, create_time, update_time, "
                        + "datasource, finish, first_chat, " + linkColumn + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, FALSE, ?)",
                id, chatId, question, createBy, oid, now, now, datasource, baseRecordId);
        return id;
    }

    // ------------------------------------------------------------------
    // chat_log
    // ------------------------------------------------------------------

    public long insertLog(long chatRecordId, long oid, long createBy, Timestamp startTime, Timestamp finishTime,
                          long duration, long totalTokens, String operate, boolean localOperation, boolean error,
                          String message) {
        return insertLog(chatRecordId, oid, createBy, startTime, finishTime, duration, totalTokens, operate,
                localOperation, error, message, 0L, 0L);
    }

    /**
     * A12：记录操作日志（token/耗时/错误），input/output/total 三项落库，
     * 支撑 /chat/record/{rid}/log 与 usage。
     */
    public long insertLog(long chatRecordId, long oid, long createBy, Timestamp startTime, Timestamp finishTime,
                          long duration, long totalTokens, String operate, boolean localOperation, boolean error,
                          String message, long inputTokens, long outputTokens) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO chat_log (id, chat_record_id, oid, create_by, start_time, finish_time, duration, "
                        + "total_tokens, operate, local_operation, error, message, input_tokens, output_tokens) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, chatRecordId, oid, createBy, startTime, finishTime, duration, totalTokens, operate,
                localOperation, error, message, inputTokens, outputTokens);
        return id;
    }

    public List<Map<String, Object>> listLogsByRecord(long chatRecordId) {
        return jdbc.queryForList("SELECT id, chat_record_id, start_time, finish_time, duration, total_tokens, "
                + "operate, local_operation, error, message FROM chat_log WHERE chat_record_id = ? "
                + "ORDER BY start_time ASC", chatRecordId);
    }

    // ------------------------------------------------------------------
    // 视图转换（对齐前端 ChatInfo / ChatRecord 契约）
    // ------------------------------------------------------------------

    /** chat 行 → 前端 ChatInfo 子集（列表项）。 */
    public Map<String, Object> toChatItem(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", Rows.lng(row, "id", 0L));
        item.put("create_time", isoTime(Rows.get(row, "create_time")));
        item.put("latest_record_time", isoTime(Rows.get(row, "latest_record_time")));
        item.put("create_by", Rows.lng(row, "create_by", 0L));
        item.put("brief", Rows.str(row, "brief"));
        item.put("datasource", Rows.lngOrNull(row, "datasource"));
        item.put("engine_type", Rows.str(row, "engine_type"));
        item.put("ds_type", Rows.str(row, "ds_type"));
        item.put("chat_type", Rows.str(row, "chat_type"));
        return item;
    }

    /** chat 行 → 前端 ChatInfo（含 records）。 */
    public Map<String, Object> toChatInfo(Map<String, Object> row, boolean withData) {
        Map<String, Object> info = toChatItem(row);
        info.put("records", toRecordList(listRecordsByChat(Rows.lng(row, "id", 0L)), withData));
        return info;
    }

    /**
     * chat_record 行列表 → 前端 ChatRecord 列表。
     *
     * <p>analysis/predict 子记录不单独渲染为对话气泡：子记录与其基础记录互链
     * （基础记录指向子记录，子记录反指基础记录，与 SQLBot
     * {@code save_analysis_predict_record} 一致），故按创建顺序判定——
     * 链接目标已出现在前面的记录即为子记录，予以跳过。</p>
     */
    public List<Map<String, Object>> toRecordList(List<Map<String, Object>> rows, boolean withData) {
        List<Map<String, Object>> records = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map<String, Object> row : rows) {
            long analysisLink = Rows.lng(row, "analysis_record_id", 0L);
            long predictLink = Rows.lng(row, "predict_record_id", 0L);
            boolean isChild = (analysisLink > 0L && seen.contains(analysisLink))
                    || (predictLink > 0L && seen.contains(predictLink));
            seen.add(Rows.lng(row, "id", 0L));
            if (isChild) {
                continue;
            }
            records.add(toRecord(row, withData));
        }
        return records;
    }

    /** chat_record 行 → 前端 ChatRecord。 */
    public Map<String, Object> toRecord(Map<String, Object> row, boolean withData) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", Rows.lng(row, "id", 0L));
        record.put("chat_id", Rows.lng(row, "chat_id", 0L));
        record.put("question", Rows.str(row, "question"));
        record.put("create_time", isoTime(Rows.get(row, "create_time")));
        record.put("finish_time", isoTime(Rows.get(row, "finish_time")));
        record.put("sql_answer", Rows.str(row, "sql_answer"));
        record.put("sql", Rows.str(row, "sql"));
        record.put("datasource", Rows.lngOrNull(row, "datasource"));
        // A26：chart_answer 落库为 {"content","reasoning_content"}（对齐 SQLBot
        // curd/chat.py:517-522：JSON 时取 reasoning_content），历史纯文本/图片 URL 原样返回
        record.put("chart_answer", unwrapThinking(Rows.str(row, "chart_answer")));
        record.put("chart", Rows.str(row, "chart"));
        record.put("analysis", Rows.str(row, "analysis"));
        record.put("analysis_thinking", Rows.str(row, "analysis_thinking"));
        record.put("predict", Rows.str(row, "predict"));
        record.put("predict_content", Rows.str(row, "predict_content"));
        record.put("finish", Rows.bool(row, "finish", false));
        record.put("error", Rows.str(row, "error"));
        record.put("run_time", Rows.lngOrNull(row, "run_time"));
        record.put("first_chat", Rows.bool(row, "first_chat", false));
        record.put("recommended_question", Rows.str(row, "recommended_question"));
        record.put("analysis_record_id", Rows.lngOrNull(row, "analysis_record_id"));
        record.put("predict_record_id", Rows.lngOrNull(row, "predict_record_id"));
        record.put("regenerate_record_id", Rows.lngOrNull(row, "regenerate_record_id"));
        record.put("duration", Rows.lngOrNull(row, "duration"));
        record.put("total_tokens", Rows.lngOrNull(row, "total_tokens"));
        if (withData) {
            record.put("data", parseJson(Rows.str(row, "data"), Collections.emptyMap()));
            record.put("predict_data", parseJson(Rows.str(row, "predict_data"), Collections.emptyList()));
        } else {
            record.put("data", null);
            record.put("predict_data", null);
        }
        return record;
    }

    /** chat_log 行 → 前端 ChatLogHistoryItem（{@code operate} 保留原值，前端按 i18n key 翻译）。 */
    public Map<String, Object> toLogItem(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("start_time", isoTime(Rows.get(row, "start_time")));
        item.put("finish_time", isoTime(Rows.get(row, "finish_time")));
        item.put("duration", Rows.lngOrNull(row, "duration"));
        item.put("total_tokens", Rows.lngOrNull(row, "total_tokens"));
        item.put("operate", Rows.str(row, "operate"));
        item.put("local_operation", Rows.bool(row, "local_operation", false));
        item.put("error", Rows.bool(row, "error", false));
        // A26：message 为结构化对象（对齐 SQLBot get_chat_log：非 CHOOSE_TABLE 时 orjson.loads，
        // 失败回落原始文本；CHOOSE_TABLE 的 messages 本身就是纯文本表名列表）
        item.put("message", parseLogMessage(Rows.str(row, "message"), Rows.str(row, "operate")));
        return item;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 序列化为 JSON 文本（失败时返回空对象/空数组文本，不抛异常）。 */
    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * chat_log.messages → 结构化对象（A26：对齐 SQLBot {@code get_chat_log}：
     * {@code orjson.loads} 后作为 {@code message} 返回；CHOOSE_TABLE 例外保持纯文本）。
     *
     * <p>解析失败时回落原始文本，不抛异常。</p>
     */
    private Object parseLogMessage(String messages, String operate) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        if ("CHOOSE_TABLE".equals(operate)) {
            return messages;
        }
        String trimmed = messages.trim();
        try {
            if (trimmed.startsWith("[")) {
                return objectMapper.readValue(trimmed, new TypeReference<List<Object>>() {
                });
            }
            if (trimmed.startsWith("{")) {
                return objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {
                });
            }
        } catch (Exception e) {
            return messages;
        }
        return messages;
    }

    /**
     * 思考流字段解包（A26）：{@code {"reasoning_content": "...", "content": "..."}} 取
     * reasoning_content（空则 content）；非 JSON（历史纯文本/旧图片 URL）原样返回。
     *
     * <p>对齐 SQLBot {@code curd/chat.py:517-522}。</p>
     */
    static String unwrapThinking(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        String trimmed = stored.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return stored;
        }
        try {
            Map<String, Object> obj = new ObjectMapper().readValue(trimmed,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object reasoning = obj.get("reasoning_content");
            if (reasoning != null && !String.valueOf(reasoning).isEmpty()) {
                return String.valueOf(reasoning);
            }
            Object content = obj.get("content");
            return content == null ? "" : String.valueOf(content);
        } catch (Exception e) {
            return stored;
        }
    }

    private Object parseJson(String json, Object fallback) {
        if (json == null || json.isEmpty()) {
            return fallback;
        }
        try {
            if (fallback instanceof List) {
                return objectMapper.readValue(json, new TypeReference<List<Object>>() {
                });
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 时间戳 → 前端 getDate() 可解析的 {@code yyyy-MM-dd'T'HH:mm:ss}。 */
    private static String isoTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format((Timestamp) value);
        }
        String text = String.valueOf(value);
        return text.length() > 19 ? text.substring(0, 19).replace(' ', 'T') : text;
    }
}
