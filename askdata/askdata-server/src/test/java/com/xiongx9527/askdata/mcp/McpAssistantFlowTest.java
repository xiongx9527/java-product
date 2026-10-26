package com.xiongx9527.askdata.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.ForbiddenException;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * mcp_assistant 合成身份链路（A4/D3/D4）：
 * 以真实 Spring 上下文验证「url/authorization 必填 → 拉取宿主数据源 → 合成用户建会话/记录 →
 * QUERY_DATA 问数（无图表/图片）→ 审计与 chat_log 落库」，以及 stream 缺省分流。
 *
 * <p>宿主 API 与 OpenAI 兼容模型均为本地 HTTP 桩；目标库为独立 H2。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp_assistant_flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class McpAssistantFlowTest {

    private static final String TARGET_URL =
            "jdbc:h2:mem:mcp_assistant_target;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static final String STUB_SQL =
            "SELECT \"status\", COUNT(*) AS \"cnt\" FROM \"orders\" GROUP BY \"status\" ORDER BY \"cnt\" DESC";

    private static final String QUESTION = "统计每个状态的订单数";

    @Autowired
    private McpAssistantSupport support;

    private final AskdataMcpTools tools = new AskdataMcpTools();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private AiModelRepository aiModelRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DatasourceRepository datasourceRepository;

    @Autowired
    private TableRepository tableRepository;

    private HttpServer llmServer;

    private HttpServer hostServer;

    private String hostUrl;

    private long defaultModelId;

    private final List<String> hostAuthorizations = new ArrayList<>();

    private final List<String> stubCalls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        cleanMetadata();
        prepareTargetDatabase();
        startLlmStub();
        startHostStub();
        defaultModelId = idGenerator.nextId();
        aiModelRepository.insert(defaultModelId, "", "http://127.0.0.1:" + llmServer.getAddress().getPort(),
                1, 0, "stub-gemma3", 0, "gemma3", true, "[]", 1, System.currentTimeMillis());
    }

    @AfterEach
    void tearDown() {
        if (llmServer != null) {
            llmServer.stop(0);
        }
        if (hostServer != null) {
            hostServer.stop(0);
        }
        AuthContext.clear();
    }

    @Test
    void askCreatesSyntheticChatRecordAndAuditWithoutChart() throws Exception {
        Map<String, Object> result = support.ask(QUESTION, hostUrl, "Bearer host-token", null);

        assertEquals(Boolean.TRUE, result.get("success"), result.toString());
        long recordId = ((Number) result.get("record_id")).longValue();
        assertTrue(recordId > 0L, result.toString());
        assertEquals(STUB_SQL, result.get("sql"));
        assertEquals(Arrays.asList("status", "cnt"),
                ((Map<?, ?>) result.get("data")).get("fields"), result.toString());
        assertEquals(2, ((List<?>) ((Map<?, ?>) result.get("data")).get("data")).size(), result.toString());
        assertFalse(result.containsKey("chart"), "QUERY_DATA 不应生成图表: " + result);
        assertFalse(result.containsKey("image_url"), "QUERY_DATA 不应生成图片: " + result);

        Map<String, Object> record = jdbc.queryForMap(
                "SELECT chat_id, create_by, oid, datasource, chart, first_chat, data FROM chat_record WHERE id = ?",
                recordId);
        long chatId = ((Number) record.get("chat_id")).longValue();
        assertEquals(-1L, ((Number) record.get("create_by")).longValue());
        assertEquals(1L, ((Number) record.get("oid")).longValue());
        assertNull(record.get("datasource"));
        assertNull(record.get("chart"), "QUERY_DATA 记录 chart 应为空: " + record);
        assertEquals(Boolean.FALSE, record.get("first_chat"), record.toString());
        assertNotNull(record.get("data"), "问数数据应落库: " + record);

        Map<String, Object> chat = jdbc.queryForMap(
                "SELECT create_by, oid, brief, datasource FROM chat WHERE id = ?", chatId);
        assertEquals(-1L, ((Number) chat.get("create_by")).longValue());
        assertEquals(1L, ((Number) chat.get("oid")).longValue());
        assertEquals(QUESTION, chat.get("brief"));
        assertNull(chat.get("datasource"));

        assertEquals(1L, count("SELECT COUNT(*) FROM chat_record WHERE chat_id = ?", chatId),
                "合成会话应只有一条真实记录，无 first_chat 空记录");
        assertEquals(0L, count("SELECT COUNT(*) FROM sys_user WHERE id = -1"),
                "合成用户不应写入 sys_user");

        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT operation_type FROM sys_logs WHERE user_id = -1");
        assertFalse(audit.isEmpty(), "合成用户应落 CHAT 创建审计");
        List<Map<String, Object>> chatLogs = jdbc.queryForList(
                "SELECT operate, create_by FROM chat_log WHERE chat_record_id = ?", recordId);
        assertTrue(chatLogs.stream().anyMatch(row -> "GENERATE_SQL".equals(row.get("operate"))),
                chatLogs.toString());
        assertEquals(-1L, ((Number) chatLogs.get(0).get("create_by")).longValue());

        assertTrue(hostAuthorizations.contains("Bearer host-token"),
                "宿主请求应携带 authorization: " + hostAuthorizations);
        assertTrue(stubCalls.contains("sql"), stubCalls.toString());
        assertFalse(stubCalls.contains("chart"), "QUERY_DATA 不应调用图表生成: " + stubCalls);
        assertNull(AuthContext.current(), "合成用户上下文应在请求结束后恢复");
    }

    @Test
    void streamEmitsSseWithoutChartAndRestoresContext() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        support.stream(QUESTION, hostUrl, "Bearer host-token", null, response);

        String body = response.getContentAsString();
        List<JsonNode> frames = parseFrames(body);
        List<String> types = typesOf(frames);
        assertTrue(types.contains("sql"), types.toString());
        assertTrue(types.contains("sql-data"), types.toString());
        assertFalse(types.contains("chart"), types.toString());
        assertFalse(types.contains("imageUrl"), types.toString());
        assertEquals("finish", types.get(types.size() - 1), types.toString());
        assertNull(AuthContext.current());

        long recordId = frameOf(frames, "id").get("id").asLong();
        assertNull(jdbc.queryForObject("SELECT chart FROM chat_record WHERE id = ?", String.class, recordId));
    }

    @Test
    void missingQuestionUrlOrAuthorizationIsRejectedBeforeCreatingChat() {
        assertThrows(BadRequestException.class, () -> support.ask(null, hostUrl, "Bearer x", null));
        assertThrows(BadRequestException.class, () -> support.ask(QUESTION, null, "Bearer x", null));
        assertThrows(BadRequestException.class, () -> support.ask(QUESTION, hostUrl, null, null));

        assertEquals(0L, count("SELECT COUNT(*) FROM chat"),
                "参数校验失败不应创建会话");
        assertNull(AuthContext.current());
    }

    @Test
    void invalidTokenRejectedBySqlbotToolBindingIncludingModelList() {
        assertThrows(ForbiddenException.class, () -> tools.bindTokenStrict("definitely-invalid-token"));
        assertThrows(ForbiddenException.class,
                () -> tools.mcp_model_list("definitely-invalid-token", 1L),
                "mcp_model_list 也必须校验 body token（A8）");
        assertNull(AuthContext.current());
    }

    // ------------------------------------------------------------------
    // SQLBot MCP HTTP 兼容层 + 真实 H2 元数据/记录落库（A1/A8/A11 端到端）
    // ------------------------------------------------------------------

    /** A11：mcp_start 经真实 HTTP 只返回 access_token/chat_id，并落 chat（不建 first_chat 空记录）。 */
    @Test
    void httpMcpStartReturnsTwoKeysAndPersistsChatWithoutFirstRecord() throws Exception {
        String token = loginThroughHttp();
        long dsId = insertTargetDatasource("mcp http start " + System.nanoTime());

        MvcResult result = mockMvc.perform(post("/api/v1/mcp/mcp_start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"oid\":1,\"datasource_id\":\"" + dsId
                                + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value(token))
                .andExpect(jsonPath("$.chat_id").isNumber())
                .andExpect(jsonPath("$.brief").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        JsonNode json = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        long chatId = json.get("chat_id").asLong();
        assertTrue(chatId > 0L, json.toString());

        Map<String, Object> chat = jdbc.queryForMap(
                "SELECT datasource, brief, origin, create_by, oid FROM chat WHERE id = ?", chatId);
        assertEquals(dsId, ((Number) chat.get("datasource")).longValue(), chat.toString());
        assertEquals(QUESTION, chat.get("brief"), chat.toString());
        assertEquals(1, ((Number) chat.get("origin")).intValue(), chat.toString());
        assertEquals(1L, ((Number) chat.get("create_by")).longValue(), chat.toString());
        assertEquals(1L, ((Number) chat.get("oid")).longValue(), chat.toString());
        assertEquals(0L, count("SELECT COUNT(*) FROM chat_record WHERE chat_id = ?", chatId),
                "mcp_start 不应创建 first_chat 空记录");
    }

    /** A1：mcp_question 不带 chat_id 经真实 HTTP 创建 MCP 会话并落真实记录（无 first_chat 空记录）。 */
    @Test
    void httpMcpQuestionWithoutChatIdCreatesChatAndPersistsRecord() throws Exception {
        String token = loginThroughHttp();
        long dsId = insertTargetDatasource("mcp http question " + System.nanoTime());

        MvcResult result = mockMvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"question\":\"" + QUESTION
                                + "\",\"datasource_id\":\"" + dsId + "\",\"stream\":false,\"return_img\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.record_id").isNumber())
                .andExpect(jsonPath("$.sql").value(STUB_SQL))
                .andReturn();

        JsonNode json = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        long recordId = json.get("record_id").asLong();
        JsonNode data = json.get("data");
        assertNotNull(data, "H1：非流式响应必须包含 data 对象: " + json);
        assertEquals(Arrays.asList("status", "cnt"),
                objectMapper.convertValue(data.get("fields"), List.class), json.toString());
        assertEquals(2, data.get("data").size(), json.toString());
        assertEquals(dsId, data.get("datasource").asLong(), json.toString());
        assertTrue(json.has("chart") && !json.get("chart").isNull(),
                "H1：非流式响应必须包含 chart: " + json);
        assertFalse(json.has("image_url"), "return_img=false 不应返回图片: " + json);

        Map<String, Object> record = jdbc.queryForMap(
                "SELECT chat_id, sql, data, chart, first_chat, create_by, oid FROM chat_record WHERE id = ?", recordId);
        assertEquals(STUB_SQL, record.get("sql"), record.toString());
        assertNotNull(record.get("data"), "问数数据应落库: " + record);
        assertEquals(Boolean.FALSE, record.get("first_chat"), record.toString());
        assertEquals(1L, ((Number) record.get("create_by")).longValue(), record.toString());
        assertEquals(1L, ((Number) record.get("oid")).longValue(), record.toString());
        // H1：落库 data 必须是 {fields,data,datasource} 对象，且与响应 data 完全一致
        Map<?, ?> persisted = objectMapper.readValue(Rows.str(record, "data"), Map.class);
        assertEquals(objectMapper.convertValue(data, Map.class), persisted,
                "H1：chat_record.data 应与响应 data 一致: " + record);
        assertNotNull(record.get("chart"), "chart 应同时落库: " + record);

        long chatId = ((Number) record.get("chat_id")).longValue();
        Map<String, Object> chat = jdbc.queryForMap(
                "SELECT datasource, brief, origin FROM chat WHERE id = ?", chatId);
        assertEquals(dsId, ((Number) chat.get("datasource")).longValue(), chat.toString());
        assertEquals(QUESTION, chat.get("brief"), chat.toString());
        assertEquals(1, ((Number) chat.get("origin")).intValue(), chat.toString());
        assertEquals(1L, count("SELECT COUNT(*) FROM chat_record WHERE chat_id = ?", chatId),
                "mcp_question 自动建会话不应产生 first_chat 空记录");
    }

    /** H2/H3：mcp_question 缺省 stream=true（SSE），流式 return_img=false 不出图且落库 data 完整。 */
    @Test
    void httpMcpQuestionDefaultsToStreamAndHonorsReturnImgFalse() throws Exception {
        String token = loginThroughHttp();
        long dsId = insertTargetDatasource("mcp http stream " + System.nanoTime());

        MvcResult result = mockMvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"question\":\"" + QUESTION
                                + "\",\"datasource_id\":\"" + dsId + "\",\"return_img\":false}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        List<JsonNode> frames = parseFrames(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> types = typesOf(frames);
        assertTrue(types.contains("sql"), types.toString());
        assertTrue(types.contains("sql-data"), types.toString());
        assertTrue(types.contains("chart"), "流式仍应生成图表配置: " + types);
        assertFalse(types.contains("imageUrl"), "return_img=false 不应出图: " + types);
        assertEquals("finish", types.get(types.size() - 1), types.toString());

        long recordId = frameOf(frames, "id").get("id").asLong();
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT chat_id, data, first_chat FROM chat_record WHERE id = ?", recordId);
        Map<?, ?> persisted = objectMapper.readValue(Rows.str(record, "data"), Map.class);
        assertEquals(Arrays.asList("status", "cnt"), persisted.get("fields"), persisted.toString());
        assertEquals(2, ((List<?>) persisted.get("data")).size(), persisted.toString());
        assertEquals(dsId, ((Number) persisted.get("datasource")).longValue(), persisted.toString());
        assertEquals(Boolean.FALSE, record.get("first_chat"), record.toString());

        long chatId = ((Number) record.get("chat_id")).longValue();
        Map<String, Object> chat = jdbc.queryForMap(
                "SELECT datasource, brief, origin FROM chat WHERE id = ?", chatId);
        assertEquals(dsId, ((Number) chat.get("datasource")).longValue(), chat.toString());
        assertEquals(QUESTION, chat.get("brief"), chat.toString());
        assertEquals(1, ((Number) chat.get("origin")).intValue(), chat.toString());
    }

    /** H3：mcp_question 流式带 custom_model 仍走 SSE，不退回同步 JSON。 */
    @Test
    void httpMcpQuestionStreamsWithCustomModelInsteadOfFallingBackToJson() throws Exception {
        String token = loginThroughHttp();
        long dsId = insertTargetDatasource("mcp http custom model " + System.nanoTime());

        MvcResult result = mockMvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"question\":\"" + QUESTION
                                + "\",\"datasource_id\":\"" + dsId + "\",\"custom_model\":\""
                                + defaultModelId + "\",\"return_img\":false}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        List<JsonNode> frames = parseFrames(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> types = typesOf(frames);
        assertTrue(types.contains("sql"), types.toString());
        assertTrue(types.contains("chart"), types.toString());
        assertFalse(types.contains("imageUrl"), types.toString());
        assertEquals("finish", types.get(types.size() - 1), types.toString());
    }

    /** M1：mcp_assistant 缺省 stream=true，合成用户建会话/真实记录/审计，QUERY_DATA 不出图。 */
    @Test
    void httpMcpAssistantDefaultsToStreamAndPersistsSyntheticAudit() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\",\"url\":\"" + hostUrl
                                + "\",\"authorization\":\"Bearer host-token\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        List<JsonNode> frames = parseFrames(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> types = typesOf(frames);
        assertTrue(types.contains("sql"), types.toString());
        assertTrue(types.contains("sql-data"), types.toString());
        assertFalse(types.contains("chart"), "QUERY_DATA 不应生成图表: " + types);
        assertFalse(types.contains("imageUrl"), "QUERY_DATA 不应生成图片: " + types);
        assertEquals("finish", types.get(types.size() - 1), types.toString());

        long recordId = frameOf(frames, "id").get("id").asLong();
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT chat_id, create_by, oid, first_chat, chart, data FROM chat_record WHERE id = ?", recordId);
        assertEquals(-1L, ((Number) record.get("create_by")).longValue(), record.toString());
        assertEquals(1L, ((Number) record.get("oid")).longValue(), record.toString());
        assertEquals(Boolean.FALSE, record.get("first_chat"), record.toString());
        assertNull(record.get("chart"), "QUERY_DATA 记录 chart 应为空: " + record);
        assertNotNull(record.get("data"), "问数数据应落库: " + record);

        long chatId = ((Number) record.get("chat_id")).longValue();
        assertEquals(1L, count("SELECT COUNT(*) FROM chat_record WHERE chat_id = ?", chatId),
                "合成会话应只有一条真实记录，无 first_chat 空记录");
        assertFalse(jdbc.queryForList("SELECT id FROM sys_logs WHERE user_id = -1").isEmpty(),
                "合成用户应落 CHAT 创建审计");
        assertTrue(hostAuthorizations.contains("Bearer host-token"),
                "宿主请求应携带 authorization: " + hostAuthorizations);
        assertNull(AuthContext.current(), "合成用户上下文应在请求结束后恢复");
    }

    /** M1：mcp_assistant 缺 url / authorization 时在创建会话前返回 400 {"detail"}。 */
    @Test
    void httpMcpAssistantRequiresUrlAndAuthorizationBeforeSideEffects() throws Exception {
        mockMvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\",\"authorization\":\"Bearer host-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
        mockMvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\",\"url\":\"" + hostUrl + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
        assertEquals(0L, count("SELECT COUNT(*) FROM chat"), "参数校验失败不应创建会话");
    }

    /** M1：mcp_assistant 显式 stream=false 返回 JSON，且仍为 QUERY_DATA（无图表/图片）。 */
    @Test
    void httpMcpAssistantStreamFalseReturnsJsonWithoutChart() throws Exception {
        mockMvc.perform(post("/api/v1/mcp/mcp_assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + QUESTION + "\",\"url\":\"" + hostUrl
                                + "\",\"authorization\":\"Bearer host-token\",\"stream\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sql").value(STUB_SQL))
                .andExpect(jsonPath("$.data.fields").isArray())
                .andExpect(jsonPath("$.chart").doesNotExist())
                .andExpect(jsonPath("$.image_url").doesNotExist());
    }

    /** M2：会话已绑定数据源时，请求里的 datasource_id 不生效（HTTP+DB 证据）。 */
    @Test
    void httpMcpQuestionBoundChatIgnoresRequestedDatasource() throws Exception {
        String token = loginThroughHttp();
        long boundDs = insertTargetDatasource("mcp http bound " + System.nanoTime());
        long requestedDs = insertTargetDatasource("mcp http requested " + System.nanoTime());

        MvcResult start = mockMvc.perform(post("/api/v1/mcp/mcp_start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"datasource_id\":\"" + boundDs
                                + "\",\"question\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long chatId = objectMapper.readTree(
                start.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("chat_id").asLong();

        MvcResult result = mockMvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"chat_id\":" + chatId
                                + ",\"question\":\"" + QUESTION + "\",\"datasource_id\":\"" + requestedDs
                                + "\",\"stream\":false,\"return_img\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        long recordId = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("record_id").asLong();
        assertEquals(boundDs, jdbc.queryForObject(
                "SELECT datasource FROM chat_record WHERE id = ?", Long.class, recordId).longValue(),
                "已绑定会话应忽略请求 datasource_id");
        assertEquals(boundDs, jdbc.queryForObject(
                "SELECT datasource FROM chat WHERE id = ?", Long.class, chatId).longValue(),
                "已绑定会话的 chat.datasource 不应被改绑");
    }

    /** M4：mcp_ds_list 经真实 HTTP 统一裁掉内部字段，保留 id/name/type 等展示字段。 */
    @Test
    void httpMcpDatasourceListStripsInternalFields() throws Exception {
        String token = loginThroughHttp();
        long dsId = insertTargetDatasource("mcp http ds list " + System.nanoTime());

        MvcResult result = mockMvc.perform(post("/api/v1/mcp/mcp_ds_list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"oid\":1}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode list = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(list.isArray(), list.toString());
        boolean found = false;
        for (JsonNode row : list) {
            if (row.get("id").asLong() == dsId) {
                found = true;
                assertTrue(row.has("name") && row.has("type"), "其余展示字段应保留: " + row);
            }
            for (String removed : Arrays.asList("configuration", "embedding", "table_relation",
                    "recommended_config")) {
                assertFalse(row.has(removed), "MCP 数据源列表不应包含 " + removed + ": " + row);
            }
        }
        assertTrue(found, "列表应包含刚注册的数据源 " + dsId + ": " + list);
    }

    /** M5：所有 SQLBot MCP 端点在 token 无效时统一 403 {"detail"}。 */
    @Test
    void httpMcpToolsRejectInvalidTokenWith403Detail() throws Exception {
        for (String path : Arrays.asList("/api/v1/mcp/mcp_start", "/api/v1/mcp/mcp_ws_list",
                "/api/v1/mcp/mcp_ds_list", "/api/v1/mcp/mcp_model_list", "/api/v1/mcp/mcp_question")) {
            mockMvc.perform(post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"definitely-invalid-token\",\"question\":\"" + QUESTION + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").exists());
        }
    }

    /** A8：HTTP 层 403/400/500 的 SQLBot 形态（含对象型 id 的 400 校验）。 */
    @Test
    void httpMcpQuestionMapsAuthValidationAndRuntimeErrorsToSqlbotShapes() throws Exception {
        String token = loginThroughHttp();
        long dsId = insertTargetDatasource("mcp http errors " + System.nanoTime());

        mockMvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"definitely-invalid-token\",\"question\":\"" + QUESTION
                                + "\",\"stream\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").exists());

        mockMvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"question\":\"" + QUESTION
                                + "\",\"datasource_id\":\"abc\",\"stream\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());

        mockMvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"question\":\"" + QUESTION
                                + "\",\"datasource_id\":{\"id\":" + dsId + "},\"stream\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());

        mockMvc.perform(post("/api/v1/mcp/mcp_question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"chat_id\":999999999,\"question\":\""
                                + QUESTION + "\",\"stream\":false}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertEquals(0L, count("SELECT COUNT(*) FROM chat_record WHERE chat_id = ?", 999999999L),
                "无效会话不应产生记录");
    }

    /** 真实登录换取 Sa-Token（与 SQLBot 调用方一致：加密表单参数）。 */
    private String loginThroughHttp() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/login/access-token")
                        .param("username", CryptoUtil.encryptSqlbot("admin"))
                        .param("password", CryptoUtil.encryptSqlbot("SQLBot@123456")))
                .andExpect(status().isOk())
                .andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int start = content.indexOf("\"access_token\":\"");
        assertTrue(start > 0, "login response missing access_token: " + content);
        int from = start + "\"access_token\":\"".length();
        int end = content.indexOf('"', from);
        return content.substring(from, end);
    }

    /** 注册一个指向测试目标 H2 的数据源（configuration 走与生产一致的落库加密）。 */
    private long insertTargetDatasource(String name) {
        long dsId = idGenerator.nextId();
        String plain = "{\"host\":\"\",\"port\":0,\"username\":\"sa\",\"password\":\"\",\"database\":\""
                + TARGET_URL + "\",\"extraJdbc\":\"\",\"dbSchema\":\"\",\"filename\":\"\",\"sheets\":[],"
                + "\"mode\":\"\",\"timeout\":10,\"lowVersion\":false,\"ssl\":false,\"poolSize\":5,"
                + "\"engine\":\"hive\"}";
        datasourceRepository.insert(dsId, name, "", "h2", "H2",
                CryptoUtil.encryptSqlbot(plain), 1L, 1L);
        tableRepository.insert(dsId, true, "orders", "订单表", null);
        return dsId;
    }

    private void cleanMetadata() {
        jdbc.update("DELETE FROM chat_log");
        jdbc.update("DELETE FROM chat_record");
        jdbc.update("DELETE FROM chat");
        jdbc.update("DELETE FROM recommended_questions");
        jdbc.update("DELETE FROM sys_logs_resource");
        jdbc.update("DELETE FROM sys_logs");
        jdbc.update("DELETE FROM ai_model");
        jdbc.update("DELETE FROM ds_grant");
        jdbc.update("DELETE FROM core_table WHERE ds_id <> 1");
        jdbc.update("DELETE FROM core_datasource WHERE id <> 1");
    }

    private void prepareTargetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS \"orders\"");
            statement.execute("CREATE TABLE \"orders\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"user_id\" BIGINT, \"amount\" DECIMAL(10,2), \"status\" VARCHAR(20))");
            statement.execute("INSERT INTO \"orders\" VALUES (1, 100, 99.50, 'PAID'),"
                    + " (2, 101, 12.00, 'PAID'), (3, 102, 30.00, 'NEW')");
        }
    }

    private void startLlmStub() throws Exception {
        stubCalls.clear();
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/v1/chat/completions", this::handleChatCompletions);
        llmServer.start();
    }

    private void startHostStub() throws Exception {
        hostAuthorizations.clear();
        hostServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hostServer.createContext("/datasources", this::handleHostDatasources);
        hostServer.start();
        hostUrl = "http://127.0.0.1:" + hostServer.getAddress().getPort() + "/datasources";
    }

    private void handleChatCompletions(HttpExchange exchange) {
        try {
            String request = readBody(exchange.getRequestBody());
            JsonNode messages = objectMapper.readTree(request).get("messages");
            String lastUser = "";
            for (int i = 0; i < messages.size(); i++) {
                if ("user".equals(messages.get(i).get("role").asText())) {
                    lastUser = messages.get(i).get("content").asText();
                }
            }
            String content = "";
            if (lastUser.contains("请只输出 SQL") || lastUser.contains("请修正后只输出一条正确的 SQL")) {
                stubCalls.add("sql");
                content = "```sql\n" + STUB_SQL + "\n```";
            } else if (lastUser.contains("请只输出一个 G2 图表配置 JSON")) {
                stubCalls.add("chart");
                content = "{\"type\":\"column\",\"title\":\"t\",\"columns\":[],\"axis\":{}}";
            } else if (lastUser.contains("个相关的追问问题")) {
                stubCalls.add("recommend");
                content = "[\"各状态订单数是多少？\",\"哪个状态金额最高？\"]";
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data:" + objectMapper.writeValueAsString(
                        Collections.singletonMap("choices", Collections.singletonList(
                                Collections.singletonMap("delta", Collections.singletonMap("content", content)))))
                        + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.write("data:[DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (Exception ignored) {
                // 忽略响应已提交
            }
        } finally {
            exchange.close();
        }
    }

    private void handleHostDatasources(HttpExchange exchange) {
        try {
            hostAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = objectMapper.writeValueAsBytes(Rows.of(
                    "code", 0,
                    "data", Collections.singletonList(Rows.of(
                            "id", 501L,
                            "name", "宿主订单库",
                            "type", "h2",
                            "configuration", Rows.of(
                                    "database", TARGET_URL,
                                    "username", "sa",
                                    "password", "")))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (Exception e) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (Exception ignored) {
                // 忽略响应已提交
            }
        } finally {
            exchange.close();
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static String readBody(InputStream inputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int length;
        while ((length = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, length);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private List<JsonNode> parseFrames(String body) throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        for (String raw : body.split("\n\n")) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("data:{")) {
                frames.add(objectMapper.readTree(trimmed.substring("data:".length())));
            }
        }
        return frames;
    }

    private static List<String> typesOf(List<JsonNode> frames) {
        List<String> types = new ArrayList<>();
        for (JsonNode frame : frames) {
            types.add(frame.get("type").asText());
        }
        return types;
    }

    private static JsonNode frameOf(List<JsonNode> frames, String type) {
        for (JsonNode frame : frames) {
            if (type.equals(frame.get("type").asText())) {
                return frame;
            }
        }
        throw new AssertionError("缺少帧: " + type);
    }
}
