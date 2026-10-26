package com.xiongx9527.askdata.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.repository.RecommendedQuestionRepository;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.permission.DsGrantService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 会话与记录归属回归：同一工作空间内不同用户的列表/分页/计数/最近问题隔离，
 * 管理员不再拥有隐式 owner 豁免。
 */
class ChatOwnershipParityTest {

    private static final MessageResolver messageResolver = new MessageResolver(new StaticMessageSource());

    private static final long DS_ID = 1L;

    private static final long OID = 1L;

    private JdbcTemplate appJdbc;

    private ChatRepository chatRepository;

    private ChatService chatService;

    private CurrentUserService currentUserService;

    private Map<String, Object> currentUser;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat_ownership_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        IdGenerator idGenerator = new IdGenerator();
        chatRepository = new ChatRepository(appJdbc, idGenerator, objectMapper);
        RecommendedQuestionRepository recommendedQuestionRepository =
                new RecommendedQuestionRepository(appJdbc, idGenerator);
        DatasourceRepository datasourceRepository = new DatasourceRepository(appJdbc, idGenerator);
        currentUserService = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUserService.currentUser()).thenAnswer(invocation -> currentUser);
        Mockito.when(currentUserService.oidOf(Mockito.any()))
                .thenAnswer(invocation -> Rows.lng(invocation.getArgument(0), "oid", 0L));
        Mockito.when(currentUserService.uid())
                .thenAnswer(invocation -> Rows.lng(currentUser, "id", 0L));
        Mockito.when(currentUserService.isAdmin(Mockito.any())).thenAnswer(invocation -> {
            Map<String, Object> user = invocation.getArgument(0);
            return Rows.lng(user, "id", 0L) == 1L && "admin".equals(Rows.str(user, "account"));
        });
        chatService = new ChatService(chatRepository, recommendedQuestionRepository, datasourceRepository,
                Mockito.mock(DatasourceService.class), currentUserService, objectMapper,
                Mockito.mock(DsGrantService.class), messageResolver);
        switchUser(2L, "user-a", OID);
    }

    @Test
    void listPagerCountAndRecentQuestionsAreScopedToCreatorAndWorkspace() {
        long chatA = newChat(2L, OID, "A 的会话");
        long chatAOtherWorkspace = newChat(2L, 2L, "A 在另一个工作空间的会话");
        long chatB = newChat(3L, OID, "B 的会话");
        long chatAdmin = newChat(1L, OID, "管理员的会话");

        // 同一 oid 下 A 只看到自己的会话；分页与总数同口径
        switchUser(2L, "user-a", OID);
        assertEquals(List.of(chatA), chatIds(chatService.list()));
        assertEquals(List.of(chatA), chatIds(chatService.pager(1, 10).getItems()));
        assertEquals(1L, chatService.pager(1, 10).getTotal());

        switchUser(3L, "user-b", OID);
        assertEquals(List.of(chatB), chatIds(chatService.list()));
        assertEquals(1L, chatService.pager(1, 10).getTotal());

        // 管理员也只看到自己创建的会话，不因管理员身份放大可见范围
        switchUser(1L, "admin", OID);
        assertEquals(List.of(chatAdmin), chatIds(chatService.list()));
        assertEquals(1L, chatService.pager(1, 10).getTotal());

        // 最近问题：12 条 A 提问只返回最近 10 条，时间倒序且稳定；B/管理员/跨空间提问不泄漏
        Timestamp base = Timestamp.valueOf("2026-01-01 00:00:00");
        for (int i = 0; i < 12; i++) {
            long recordId = chatRepository.insertRecord(chatA, String.format("A-q-%02d", i), 2L, OID,
                    DS_ID, false, null);
            appJdbc.update("UPDATE chat_record SET create_time = ? WHERE id = ?",
                    new Timestamp(base.getTime() + i * 60_000L), recordId);
        }
        long bRecord = chatRepository.insertRecord(chatB, "B-q-01", 3L, OID, DS_ID, false, null);
        appJdbc.update("UPDATE chat_record SET create_time = ? WHERE id = ?",
                new Timestamp(base.getTime() + 20 * 60_000L), bRecord);
        long adminRecord = chatRepository.insertRecord(chatAdmin, "admin-q-01", 1L, OID, DS_ID, false, null);
        appJdbc.update("UPDATE chat_record SET create_time = ? WHERE id = ?",
                new Timestamp(base.getTime() + 30 * 60_000L), adminRecord);
        long otherWsRecord = chatRepository.insertRecord(chatAOtherWorkspace, "A-other-workspace-q", 2L, 2L,
                DS_ID, false, null);
        appJdbc.update("UPDATE chat_record SET create_time = ? WHERE id = ?",
                new Timestamp(base.getTime() + 40 * 60_000L), otherWsRecord);

        switchUser(2L, "user-a", OID);
        List<String> recentA = chatService.recentQuestions(DS_ID);
        assertEquals(10, recentA.size(), recentA.toString());
        assertEquals(List.of("A-q-11", "A-q-10", "A-q-09", "A-q-08", "A-q-07",
                "A-q-06", "A-q-05", "A-q-04", "A-q-03", "A-q-02"), recentA);
        assertTrue(!recentA.contains("B-q-01") && !recentA.contains("admin-q-01")
                && !recentA.contains("A-other-workspace-q"), recentA.toString());

        switchUser(3L, "user-b", OID);
        assertEquals(List.of("B-q-01"), chatService.recentQuestions(DS_ID));

        switchUser(1L, "admin", OID);
        assertEquals(List.of("admin-q-01"), chatService.recentQuestions(DS_ID));
    }

    @Test
    void adminAndSameWorkspaceUsersCannotReadOthersChatOrRecordSubresources() {
        long chatA = newChat(2L, OID, "A 的会话");
        long recordA = chatRepository.insertRecord(chatA, "A 的提问", 2L, OID, DS_ID, false, null);
        chatRepository.saveSqlData(recordA,
                "{\"fields\":[\"status\"],\"data\":[{\"status\":\"PAID\"}]}");
        chatRepository.saveChart(recordA,
                "{\"type\":\"column\",\"title\":\"订单\",\"columns\":[{\"name\":\"status\",\"value\":\"status\"}]}",
                null);
        chatRepository.savePredictData(recordA, "[{\"status\":\"FORECAST\"}]");
        chatRepository.finishRecord(recordA, 10L, 5L);

        // 所有者可正常读取与导出（保证后续拒绝来自归属校验，而不是数据缺失）
        switchUser(2L, "user-a", OID);
        assertEquals("A 的会话", chatService.get(chatA, false).get("brief"));
        assertEquals(1, ((List<?>) chatService.recordData(recordA).get("data")).size());
        assertTrue(chatService.exportExcel(recordA).content.length > 0);

        // 管理员即便在同一个工作空间，也不能读取他人会话或记录子资源
        switchUser(1L, "admin", OID);
        assertThrows(BadRequestException.class, () -> chatService.get(chatA, false));
        assertThrows(BadRequestException.class, () -> chatService.get(chatA, true));
        assertThrows(BadRequestException.class, () -> chatService.recordData(recordA));
        assertThrows(BadRequestException.class, () -> chatService.recordDataLive(recordA));
        assertThrows(BadRequestException.class, () -> chatService.recordPredictData(recordA));
        assertThrows(BadRequestException.class, () -> chatService.recordLog(recordA, false));
        assertThrows(BadRequestException.class, () -> chatService.recordLog(recordA, true));
        assertThrows(BadRequestException.class, () -> chatService.exportExcel(recordA));
        assertTrue(chatIds(chatService.list()).stream().noneMatch(id -> id == chatA));

        // 同工作空间的普通用户 B 同样被拒绝
        switchUser(3L, "user-b", OID);
        assertThrows(BadRequestException.class, () -> chatService.get(chatA, false));
        assertThrows(BadRequestException.class, () -> chatService.recordData(recordA));

        // 管理员仍可访问自己创建的会话
        switchUser(1L, "admin", OID);
        long adminChat = newChat(1L, OID, "管理员自己的会话");
        assertEquals("管理员自己的会话", chatService.get(adminChat, false).get("brief"));
    }

    private long newChat(long createBy, long oid, String brief) {
        return chatRepository.insertChat(createBy, oid, brief, DS_ID, "MySQL", "mysql", 1, brief);
    }

    private void switchUser(long id, String account, long oid) {
        currentUser = Rows.of("id", id, "account", account, "oid", oid);
    }

    private static List<Long> chatIds(List<Map<String, Object>> chats) {
        return chats.stream().map(chat -> Rows.lng(chat, "id", 0L)).toList();
    }
}
