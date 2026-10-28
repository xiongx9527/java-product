package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.repository.RecommendedQuestionRepository;
import com.xiongx9527.askdata.chat.service.ChatService;
import com.xiongx9527.askdata.chat.support.ChatExcel;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import com.xiongx9527.askdata.datasource.excel.ExcelService;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.xiongx9527.askdata.permission.DsGrantService;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.context.support.StaticMessageSource;

/**
 * 会话与记录 CRUD smoke（A6 离线证据，H2）：start/list/get/with_data/rename/delete、
 * 记录 data/predict_data/log/usage、最近问题、Excel 导出、归属校验。
 */
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.context.support.StaticMessageSource;

class ChatServiceSmokeTest {

    private static final MessageResolver messageResolver = new MessageResolver(new StaticMessageSource());

    @TempDir
    Path excelDir;

    private JdbcTemplate appJdbc;

    private ObjectMapper objectMapper = new ObjectMapper();

    private ChatService chatService;

    private ChatRepository chatRepository;

    private RecommendedQuestionRepository recommendedQuestionRepository;

    private long dsId;

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat_crud_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        IdGenerator idGenerator = new IdGenerator();
        JdbcEngine engine = new JdbcEngine(appJdbc, objectMapper);
        DatasourceRepository datasourceRepository = new DatasourceRepository(appJdbc, idGenerator);
        TableRepository tableRepository = new TableRepository(appJdbc, idGenerator);
        FieldRepository fieldRepository = new FieldRepository(appJdbc, idGenerator);
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", 1L, "account", "admin", "oid", 1L));
        Mockito.when(currentUserService.isAdmin(Mockito.any())).thenReturn(true);
        Mockito.when(currentUserService.oidOf(Mockito.any())).thenReturn(1L);
        Mockito.when(currentUserService.uid()).thenReturn(1L);
        DatasourceService datasourceService = new DatasourceService(datasourceRepository, tableRepository,
                fieldRepository, new ExcelService(appJdbc, engine, excelDir.toString(), messageResolver), engine,
                currentUserService, objectMapper, messageResolver,
                new TableEmbeddingService(tableRepository, fieldRepository,
                        new ChatModelRouter(new AiModelRepository(appJdbc, idGenerator), objectMapper),
                        new ChatModelClient(objectMapper), appJdbc, objectMapper));
        dsId = 1L;
        chatRepository = new ChatRepository(appJdbc, idGenerator, objectMapper);
        recommendedQuestionRepository = new RecommendedQuestionRepository(appJdbc, idGenerator);
        chatService = new ChatService(chatRepository, recommendedQuestionRepository, datasourceRepository,
                datasourceService, currentUserService, objectMapper, Mockito.mock(DsGrantService.class),
                messageResolver);
    }

    @Test
    void chatCrudLifecycle() {
        // start
        Map<String, Object> chat = chatService.start(Rows.of("datasource", dsId));
        long chatId = Rows.lng(chat, "id", 0L);
        assertEquals(dsId, Rows.lng(chat, "datasource", 0L));
        assertEquals("askdata_demo 演示库", chat.get("datasource_name"));
        assertEquals(true, chat.get("datasource_exists"));
        // A17：创建会话即生成 first_chat=TRUE 的空记录（对齐 SQLBot create_chat），
        // 前端据 first_chat 标记跳过该记录的提问气泡
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> startRecords = (List<Map<String, Object>>) chat.get("records");
        assertEquals(1, startRecords.size());
        assertEquals(true, startRecords.get(0).get("first_chat"));
        assertNull(startRecords.get(0).get("question"));

        // assistant/start（嵌入通道 origin=2）
        Map<String, Object> embedded = chatService.start(Rows.of("datasource", dsId, "origin", 2));
        assertTrue(Rows.lng(embedded, "id", 0L) > 0L);

        // list / get / with_data
        assertEquals(2, chatService.list().size());
        assertNull(chatService.get(chatId, false).get("brief"));
        Map<String, Object> withData = chatService.get(chatId, true);
        assertNotNull(withData.get("records"));

        // 插入记录（模拟主链路落库）→ with_data 解析 data 为对象
        long recordId = chatRepository.insertRecord(chatId, "每个状态的订单数", 1L, 1L, dsId, true, null);
        chatRepository.saveSql(recordId, "SELECT status FROM orders", "sql 生成过程");
        chatRepository.saveSqlData(recordId, "{\"fields\":[\"status\"],\"data\":[{\"status\":\"PAID\"}]}");
        chatRepository.saveChart(recordId, "{\"type\":\"column\",\"title\":\"t\"}", "chart 过程");
        chatRepository.savePredictData(recordId, "[{\"status\":\"FORECAST\"}]");
        chatRepository.saveRecommendedQuestion(recordId, "[\"追问1\"]");
        chatRepository.finishRecord(recordId, 1200L, 33L);

        Map<String, Object> loaded = chatService.get(chatId, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) loaded.get("records");
        // A17：首条 first_chat 空记录 + 本条真实记录
        assertEquals(2, records.size());
        assertEquals(true, records.get(0).get("first_chat"));
        Map<String, Object> real = records.get(1);
        assertEquals("每个状态的订单数", real.get("question"));
        assertEquals("SELECT status FROM orders", real.get("sql"));
        assertEquals(true, real.get("finish"));
        assertEquals(33L, ((Number) real.get("total_tokens")).longValue());
        // 前端「耗时 s」读取 duration（秒，向上取整）；1200ms → 2s，历史缺陷只写 run_time，UI 恒为空
        assertEquals(2L, ((Number) real.get("duration")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) real.get("data");
        assertEquals(1, ((List<?>) data.get("data")).size());
        assertEquals("[\"追问1\"]", real.get("recommended_question"));

        // 记录端点（data / predict_data / log / usage）
        assertEquals(1, ((List<?>) chatService.recordData(recordId).get("data")).size());
        assertEquals(1, chatService.recordPredictData(recordId).size());
        Map<String, Object> log = chatService.recordLog(recordId, false);
        assertNotNull(log.get("steps"));
        assertNotNull(chatService.recordLog(recordId, true));

        // 操作日志（token/耗时）
        chatRepository.insertLog(recordId, 1L, 1L, new java.sql.Timestamp(System.currentTimeMillis() - 2000),
                new java.sql.Timestamp(System.currentTimeMillis()), 2000L, 45L, "generate_sql", false, false,
                "{\"sql\":\"SELECT status FROM orders\"}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) chatService.recordLog(recordId, false)
                .get("steps");
        assertEquals(1, steps.size());
        assertEquals("generate_sql", steps.get(0).get("operate"));
        assertEquals(45L, ((Number) steps.get(0).get("total_tokens")).longValue());
        // usage 只保留有 token 的步骤
        chatRepository.insertLog(recordId, 1L, 1L, new java.sql.Timestamp(System.currentTimeMillis()),
                new java.sql.Timestamp(System.currentTimeMillis()), 5L, 0L, "EXECUTE_SQL", true, false, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> usageSteps = (List<Map<String, Object>>) chatService.recordLog(recordId, true)
                .get("steps");
        assertEquals(1, usageSteps.size());

        // 最近问题
        assertTrue(chatService.recentQuestions(dsId).contains("每个状态的订单数"));

        // rename → delete
        chatService.rename(Rows.of("id", chatId, "brief", "订单分析"));
        assertEquals("订单分析", chatService.get(chatId, false).get("brief"));
        // brief 为空时保留原名（SQLBot rename_chat 空分支）
        chatService.rename(Rows.of("id", chatId, "brief", ""));
        assertEquals("订单分析", chatService.get(chatId, false).get("brief"));

        chatService.delete(chatId, Rows.of("id", chatId, "brief", "订单分析"));
        assertEquals(1, chatService.list().size());
        assertThrows(BadRequestException.class, () -> chatService.get(chatId, false));
    }

    @Test
    void exportExcelAndRecommendations() {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        long recordId = chatRepository.insertRecord(chatId, "订单", 1L, 1L, dsId, true, null);
        chatRepository.saveChart(recordId, "{\"type\":\"column\",\"title\":\"各状态订单数\"}", null);

        // 空结果 → 数据为空
        assertThrows(BadRequestException.class, () -> chatService.exportExcel(recordId));

        chatRepository.saveSqlData(recordId,
                "{\"fields\":[\"status\",\"cnt\"],\"data\":[{\"status\":\"PAID\",\"cnt\":2}]}");
        chatRepository.savePredictData(recordId, "[{\"status\":\"FORECAST\",\"cnt\":5}]");
        ChatExcel.ExportFile file = chatService.exportExcel(recordId);
        assertEquals("各状态订单数.xlsx", file.filename);
        assertTrue(file.content.length > 0);
        // xlsx 魔数（PK 头）
        assertEquals('P', file.content[0]);
        assertEquals('K', file.content[1]);

        // 推荐问题表：覆盖写
        recommendedQuestionRepository.replaceByChat(chatId,
                java.util.Arrays.asList("追问1", "追问2"));
        assertEquals(2, recommendedQuestionRepository.listByChat(chatId).size());
        recommendedQuestionRepository.replaceByChat(chatId, Collections.singletonList("追问A"));
        assertEquals(1, recommendedQuestionRepository.listByChat(chatId).size());
        assertEquals("追问A", recommendedQuestionRepository.listByChat(chatId).get(0).get("question"));
    }

    @Test
    void ownershipIsEnforced() {
        long chatId = Rows.lng(chatService.start(Rows.of("datasource", dsId)), "id", 0L);
        CurrentUserService normal = Mockito.mock(CurrentUserService.class);
        Mockito.when(normal.currentUser()).thenReturn(Rows.of("id", 2L, "account", "u", "oid", 2L));
        Mockito.when(normal.isAdmin(Mockito.any())).thenReturn(false);
        Mockito.when(normal.oidOf(Mockito.any())).thenReturn(2L);
        ChatService normalService = new ChatService(chatRepository, recommendedQuestionRepository,
                new DatasourceRepository(appJdbc, new IdGenerator()),
                Mockito.mock(DatasourceService.class), normal, objectMapper, Mockito.mock(DsGrantService.class),
                messageResolver);
        // 他人会话不可见（list 只返回自己 oid 的）
        assertEquals(0, normalService.list().size());
        assertThrows(com.xiongx9527.askdata.common.web.ForbiddenException.class,
                () -> normalService.get(chatId, false));
    }

    @Test
    void pagerDefaultsToEmptyOnOtherPage() {
        chatService.start(Rows.of("datasource", dsId));
        assertEquals(1, chatService.pager(1, 10).getTotal());
        assertEquals(1, chatService.pager(1, 10).getTotal_pages());
        assertEquals(0, chatService.pager(2, 10).getItems().size());
        assertEquals(new ArrayList<>(), chatService.pager(2, 10).getItems());
    }
}
