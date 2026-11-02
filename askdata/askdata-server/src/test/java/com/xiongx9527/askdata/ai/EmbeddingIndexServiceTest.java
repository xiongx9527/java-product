package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.support.MysqlTestSupport;

import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.common.lock.SingleWorkerGuard;
import com.xiongx9527.askdata.common.startup.EmbeddingBackfillRunner;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.repository.AiEmbeddingIndexStateRepository;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A8.x：向量索引重建的状态判定、全空间范围与重建结果落库。
 */
class EmbeddingIndexServiceTest {

    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final IdGenerator ids = new IdGenerator();

    private AiModelRepository modelRepository;

    private EmbeddingIndexService service;

    private EmbeddingBackfillRunner runner;

    private SingleWorkerGuard guard;

    private long modelId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = MysqlTestSupport.createSchema();
        modelRepository = new AiModelRepository(jdbc, ids);
        modelId = ids.nextId();
        modelRepository.insert(modelId, "", "https://api.example.com", 1, 0, "bge-m3", 1, "bge-m3",
                false, "[]", 1, System.currentTimeMillis(), true, true, 1, 1024);
        ChatModelRouter router = new ChatModelRouter(modelRepository, objectMapper);
        runner = mock(EmbeddingBackfillRunner.class);
        guard = mock(SingleWorkerGuard.class);
        when(guard.tryAcquire(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service = new EmbeddingIndexService(jdbc, router, runner,
                new AiEmbeddingIndexStateRepository(jdbc), guard);
    }

    @Test
    void pendingBeforeFirstRebuild() {
        Map<String, Object> status = service.status();
        assertEquals(Boolean.TRUE, status.get("pending"));
        assertEquals(modelId, status.get("model_id"));
        assertEquals(1024, status.get("dimension"));
        assertEquals(Boolean.TRUE, status.get("available"));
    }

    @Test
    void workspacesUnionCoversBusinessTablesAndWorkspaceTable() {
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (1, 11, '订单', TRUE)");
        jdbc.update("INSERT INTO data_training (id, oid, question, enabled) VALUES (2, 22, 'q', TRUE)");
        jdbc.update("INSERT INTO core_datasource (id, oid, name) VALUES (3, 33, 'ds')");
        jdbc.update("INSERT INTO sys_workspace (id, name, create_time) VALUES (44, 'ws', 0)");
        List<Long> scopes = service.workspaces();
        assertTrue(scopes.contains(11L));
        assertTrue(scopes.contains(22L));
        assertTrue(scopes.contains(33L));
        assertTrue(scopes.contains(44L));
    }

    @Test
    void rebuildWritesStateAndClearsPending() throws Exception {
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (1, 11, '订单', TRUE)");
        Map<String, Object> started = service.rebuild();
        assertEquals(Boolean.TRUE, started.get("started"));
        // 等待后台线程完成（daemon，单线程执行器）
        for (int i = 0; i < 100 && !Boolean.FALSE.equals(service.status().get("running")); i++) {
            Thread.sleep(50);
        }
        Map<String, Object> status = service.status();
        assertEquals("idle", status.get("status"));
        assertEquals(modelId, status.get("rebuilt_model_id"));
        assertEquals(1024, status.get("rebuilt_dimension"));
        assertFalse((Boolean) status.get("pending"));
    }

    @Test
    void singleWorkspaceFailureDoesNotAbortRemainingScopes() throws Exception {
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (1, 101, 'a', TRUE)");
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (2, 102, 'b', TRUE)");
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (3, 103, 'c', TRUE)");
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(runner).rebuildWorkspace(102L);

        service.rebuild();
        for (int i = 0; i < 100 && !Boolean.FALSE.equals(service.status().get("running")); i++) {
            Thread.sleep(50);
        }

        org.mockito.Mockito.verify(runner).rebuildWorkspace(103L);
        Map<String, Object> status = service.status();
        assertEquals("failed", status.get("status"));
        assertTrue(String.valueOf(status.get("message")).contains("102"), String.valueOf(status.get("message")));
    }

    @Test
    void concurrentRebuildStartsOnlyOneTask() throws Exception {
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (1, 201, 'a', TRUE)");
        java.util.concurrent.CountDownLatch blocker = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            blocker.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return 0;
        }).when(runner).rebuildWorkspace(org.mockito.ArgumentMatchers.anyLong());

        Map<String, Object> first = service.rebuild();
        Map<String, Object> second = service.rebuild();
        assertEquals(Boolean.TRUE, first.get("started"));
        assertEquals(Boolean.FALSE, second.get("started"));
        assertEquals("running", second.get("reason"));
        blocker.countDown();
        for (int i = 0; i < 100 && !Boolean.FALSE.equals(service.status().get("running")); i++) {
            Thread.sleep(50);
        }
        org.mockito.Mockito.verify(runner, org.mockito.Mockito.atLeastOnce())
                .rebuildWorkspace(201L);
    }

    @Test
    void statusReportsPendingAfterModelSwitchAndAfterFailure() {
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = 768, status = 'idle' WHERE id = 1",
                modelId);
        assertFalse((Boolean) service.status().get("pending"), "同一模型已重建过，不待重建");

        jdbc.update("UPDATE ai_embedding_index_state SET model_id = 999999 WHERE id = 1");
        assertTrue((Boolean) service.status().get("pending"), "切换默认向量模型后待重建");

        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, status = 'failed' WHERE id = 1", modelId);
        assertTrue((Boolean) service.status().get("pending"), "上次失败待重试");
    }

    @Test
    void rebuildRejectsWhenNoEmbeddingModel() {
        jdbc.update("DELETE FROM ai_model");
        Map<String, Object> started = service.rebuild();
        assertEquals(Boolean.FALSE, started.get("started"));
        assertEquals("no_embedding_model", started.get("reason"));
        assertFalse((Boolean) service.status().get("available"));
    }
}
