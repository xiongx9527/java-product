package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.repository.RecommendedQuestionRepository;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.chat.support.ChatExcel;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话与记录（对齐 SQLBot {@code apps/chat/curd/chat.py} + {@code apps/chat/api/chat.py}）：
 * 列表/详情/重命名/删除/创建、记录数据（data/data_live/predict_data/log/usage）、
 * 最近问题、Excel 导出。
 *
 * <p>归属判定与 SQLBot {@code get_chat_with_records} 一致：会话须属于当前工作空间
 * （oid 相同）且由当前用户创建，管理员不受此限。</p>
 */
@Service
public class ChatService {

    private final ChatRepository chatRepository;

    private final RecommendedQuestionRepository recommendedQuestionRepository;

    private final DatasourceRepository datasourceRepository;

    private final com.xiongx9527.askdata.permission.DsGrantService dsGrantService;

    private final DatasourceService datasourceService;

    private final CurrentUserService currentUserService;

    private final ObjectMapper objectMapper;

    public ChatService(ChatRepository chatRepository, RecommendedQuestionRepository recommendedQuestionRepository,
                       DatasourceRepository datasourceRepository, DatasourceService datasourceService,
                       CurrentUserService currentUserService, ObjectMapper objectMapper,
                       com.xiongx9527.askdata.permission.DsGrantService dsGrantService) {
        this.chatRepository = chatRepository;
        this.recommendedQuestionRepository = recommendedQuestionRepository;
        this.datasourceRepository = datasourceRepository;
        this.datasourceService = datasourceService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.dsGrantService = dsGrantService;
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /** GET /chat/list：当前工作空间会话（按最近活跃倒序）。 */
    public List<Map<String, Object>> list() {
        Map<String, Object> user = currentUserService.currentUser();
        return toChatItems(chatRepository.listChats(currentUserService.oidOf(user)));
    }

    /**
     * GET /chat/question/pager/{page}/{size}：会话分页（SQLBot 保留路由，A6「会话分页」）。
     */
    public PageResult<Map<String, Object>> pager(int page, int size) {
        Map<String, Object> user = currentUserService.currentUser();
        long oid = currentUserService.oidOf(user);
        long total = chatRepository.countChats(oid);
        return new PageResult<>(toChatItems(chatRepository.pageChats(oid, page, size)), total, page, size);
    }

    /** GET /chat/{id} 与 /chat/{id}/with_data：会话详情（含记录）。 */
    public Map<String, Object> get(long chatId, boolean withData) {
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> chat = requireOwnedChat(chatId, user);
        Map<String, Object> info = chatRepository.toChatInfo(chat, withData);
        long dsId = Rows.lng(chat, "datasource", 0L);
        info.put("datasource_name", datasourceName(dsId));
        info.put("datasource_exists", dsId > 0 && datasourceRepository.findById(dsId).isPresent());
        return info;
    }

    /** POST /chat/start（{@code origin=2} 时为嵌入通道 /assistant/start）；
     *  审计对齐 SQLBot mcp_start 的 {@code @system_log} CHAT（MCP chat_start 复用本方法，同样落审计）。 */
    @AuditLog(module = AuditModule.CHAT, operation = AuditOperationType.CREATE, resourceId = "#result['id']")
    public Map<String, Object> start(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        long uid = currentUserService.uid();
        long oid = currentUserService.oidOf(user);
        Long datasource = Rows.lngOrNull(body, "datasource");
        int origin = Rows.intg(body, "origin", 1);
        String engineType = null;
        String dsType = null;
        if (datasource != null) {
            Map<String, Object> ds = datasourceRepository.findById(datasource)
                    .orElseThrow(() -> new BadRequestException("Datasource with id " + datasource + " not found"));
            if (!currentUserService.isAdmin(user) && Rows.lng(ds, "oid", 0L) != oid) {
                throw new BadRequestException("Datasource with id " + datasource + " not found");
            }
            // 资源级使用授权（对齐 SQLBot @require_permissions(type='ds')）：白名单覆盖语义，
            // 该数据源存在授权记录时未授权成员拒绝建会话
            dsGrantService.checkUsable(oid, uid, currentUserService.isAdmin(user), datasource);
            engineType = Rows.str(ds, "type_name");
            dsType = Rows.str(ds, "type");
        }
        long chatId = chatRepository.insertChat(uid, oid, null, datasource, engineType, dsType, origin,
                Rows.str(body, "question"));
        return get(chatId, false);
    }

    /** POST /chat/rename：{@code brief} 为空时保留原名（对齐 SQLBot rename_chat）。 */
    @AuditLog(module = AuditModule.CHAT, operation = AuditOperationType.UPDATE, resourceId = "#body['id']")
    public void rename(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        long chatId = Rows.lng(body, "id", 0L);
        requireOwnedChat(chatId, user);
        chatRepository.renameChat(chatId, Rows.str(body, "brief"));
    }

    /** DELETE /chat/{id}：软删除（对齐 SQLBot delete_chat_with_user）。 */
    @AuditLog(module = AuditModule.CHAT, operation = AuditOperationType.DELETE, resourceId = "#chatId")
    public void delete(long chatId, Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        requireOwnedChat(chatId, user);
        chatRepository.deleteChat(chatId);
    }

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    /** GET /chat/record/{rid}/data：表格数据 {fields, data}（落库快照，离线可回放）。 */
    public Map<String, Object> recordData(long recordId) {
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> record = requireOwnedRecord(recordId, user);
        return parseResult(Rows.str(record, "data"));
    }

    /** GET /chat/record/{rid}/data_live：按记录 SQL 重新执行取实时数据（对齐 get_chart_data_live）。 */
    public Map<String, Object> recordDataLive(long recordId) {
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> record = requireOwnedRecord(recordId, user);
        Long dsId = Rows.lngOrNull(record, "datasource");
        String sql = Rows.str(record, "sql");
        if (dsId == null || sql == null || sql.trim().isEmpty()) {
            return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>());
        }
        Map<String, Object> result = datasourceService.execSql(dsId, Rows.of("sql", sql));
        return Rows.of("fields", result.get("fields"), "data", result.get("data"));
    }

    /** GET /chat/record/{rid}/predict_data：预测数据列表。 */
    public List<Object> recordPredictData(long recordId) {
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> record = requireOwnedRecord(recordId, user);
        String json = Rows.str(record, "predict_data");
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * GET /chat/record/{rid}/log|usage：对话日志（{@code usageOnly} 时只给聚合口径）。
     * 对齐 SQLBot {@code get_chat_log_history}：{@code {start_time, finish_time, duration,
     * total_tokens, steps[]}}。
     */
    public Map<String, Object> recordLog(long recordId, boolean usageOnly) {
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> record = requireOwnedRecord(recordId, user);
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("start_time", Rows.str(record, "create_time"));
        history.put("finish_time", Rows.str(record, "finish_time"));
        history.put("duration", Rows.lngOrNull(record, "duration"));
        history.put("total_tokens", Rows.lngOrNull(record, "total_tokens"));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> row : chatRepository.listLogsByRecord(recordId)) {
            if (usageOnly && Rows.lng(row, "total_tokens", 0L) <= 0L) {
                continue;
            }
            steps.add(chatRepository.toLogItem(row));
        }
        history.put("steps", steps);
        return history;
    }

    /**
     * GET /chat/record/{rid}/excel/export/{chat_id}：导出表格（含预测数据）。
     * 对齐 SQLBot export_excel：空结果时抛 {@code 数据为空}。
     */
    public ChatExcel.ExportFile exportExcel(long recordId) {
        return exportExcel(recordId, ChatExcel.Format.XLSX);
    }

    @AuditLog(module = AuditModule.CHAT, operation = AuditOperationType.EXPORT, resourceId = "#recordId")
    public ChatExcel.ExportFile exportExcel(long recordId, ChatExcel.Format format) {
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> record = requireOwnedRecord(recordId, user);
        Map<String, Object> result = parseResult(Rows.str(record, "data"));
        List<Map<String, Object>> data = Rows.mapList(result.get("data"));
        if (data.isEmpty()) {
            throw new BadRequestException("数据为空");
        }
        Map<String, Object> chart = parseChart(Rows.str(record, "chart"));
        // 按完整图表配置还原导出字段（对齐 SQLBot export_excel：columns → axis.x/y/series，
        // 多指标 y 为数组）；无图表配置时回落查询结果 fields
        List<Map<String, Object>> fields = ChatChartBuilder.chartFields(chart);
        if (fields.isEmpty()) {
            fields = new ArrayList<>();
            for (Object field : objectList(result.get("fields"))) {
                fields.add(Rows.of("name", String.valueOf(field), "value", String.valueOf(field)));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(data);
        rows.addAll(Rows.mapList(recordPredictDataRaw(record)));
        String title = chartTitle(chart);
        return ChatExcel.exportResult(format, fields, rows, title);
    }

    // ------------------------------------------------------------------
    // 推荐 / 最近问题
    // ------------------------------------------------------------------

    /** GET /chat/recent_questions/{ds}：该数据源下最近的提问（去重、倒序、限 20 条）；
     *  按当前用户工作空间过滤（对齐 SQLBot recent_questions 的可见范围，防跨工作空间读历史提问）。 */
    public List<String> recentQuestions(long datasourceId) {
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> ds = datasourceRepository.findById(datasourceId)
                .orElseThrow(() -> new BadRequestException("Datasource with id " + datasourceId + " not found"));
        if (!currentUserService.isAdmin(user) && Rows.lng(ds, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new BadRequestException("Datasource with id " + datasourceId + " not found");
        }
        return recommendedQuestionRepository.recentByDatasource(datasourceId, currentUserService.oidOf(user), 20);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private List<Map<String, Object>> toChatItems(List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(chatRepository.toChatItem(row));
        }
        return items;
    }

    private Map<String, Object> requireOwnedChat(long chatId, Map<String, Object> user) {
        Map<String, Object> chat = chatRepository.findChat(chatId)
                .orElseThrow(() -> new BadRequestException("Chat with id " + chatId + " not found"));
        requireOwner(chat, user, "Chat");
        return chat;
    }

    private Map<String, Object> requireOwnedRecord(long recordId, Map<String, Object> user) {
        Map<String, Object> record = chatRepository.findRecord(recordId)
                .orElseThrow(() -> new BadRequestException("ChatRecord with id " + recordId + " not found"));
        Map<String, Object> chat = chatRepository.findChat(Rows.lng(record, "chat_id", 0L))
                .orElseThrow(() -> new BadRequestException("ChatRecord with id " + recordId + " not found"));
        requireOwner(chat, user, "ChatRecord");
        return record;
    }

    private void requireOwner(Map<String, Object> chat, Map<String, Object> user, String label) {
        if (currentUserService.isAdmin(user)) {
            return;
        }
        long id = Rows.lng(chat, "id", 0L);
        if (Rows.lng(chat, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new BadRequestException(label + " with id " + id + " does not belong to current workspace");
        }
        if (Rows.lng(chat, "create_by", 0L) != Rows.lng(user, "id", 0L)) {
            throw new BadRequestException(label + " with id " + id + " not Owned by the current user");
        }
    }

    private Map<String, Object> parseResult(String json) {
        if (json == null || json.isEmpty()) {
            return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>());
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return Rows.of("fields", parsed.get("fields"), "data", parsed.get("data"));
        } catch (Exception e) {
            return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>());
        }
    }

    private List<Object> objectList(Object value) {
        if (value instanceof List) {
            return new ArrayList<>((List<?>) value);
        }
        return Collections.emptyList();
    }

    private Object recordPredictDataRaw(Map<String, Object> record) {
        String json = Rows.str(record, "predict_data");
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> parseChart(String chartJson) {
        if (chartJson == null || chartJson.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(chartJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private String chartTitle(Map<String, Object> chart) {
        if (chart == null) {
            return "Excel";
        }
        Object title = chart.get("title");
        return title == null || String.valueOf(title).isEmpty() ? "Excel" : String.valueOf(title);
    }

    private String datasourceName(long dsId) {
        if (dsId <= 0) {
            return null;
        }
        return datasourceRepository.findById(dsId).map(row -> Rows.str(row, "name")).orElse(null);
    }
}
