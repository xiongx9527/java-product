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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A8.x：向量索引重建的状态判定、全空间范围与重建结果落库；
 * 以及 V7 的停止 / 继续 / 仅重试失败 / 崩溃恢复（孤儿状态自愈）。
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
                false, "[]", 1, System.currentTimeMillis(), true, 1, 1024);
        ChatModelRouter router = new ChatModelRouter(modelRepository, objectMapper);
        runner = mock(EmbeddingBackfillRunner.class);
        guard = mock(SingleWorkerGuard.class);
        when(guard.tryAcquire(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service = new EmbeddingIndexService(jdbc, router, runner,
                new AiEmbeddingIndexStateRepository(jdbc), guard);
    }

    private void waitUntilRunning() throws Exception {
        for (int i = 0; i < 100 && !Boolean.TRUE.equals(service.status().get("running")); i++) {
            Thread.sleep(50);
        }
    }

    private void waitUntilNotRunning() throws Exception {
        for (int i = 0; i < 100 && Boolean.TRUE.equals(service.status().get("running")); i++) {
            Thread.sleep(50);
        }
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
        Map<String, Object> started = service.rebuild(null);
        assertEquals(Boolean.TRUE, started.get("started"));
        waitUntilNotRunning();
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

        service.rebuild(null);
        waitUntilNotRunning();

        verify(runner).rebuildWorkspace(103L);
        Map<String, Object> status = service.status();
        assertEquals("failed", status.get("status"));
        assertTrue(String.valueOf(status.get("message")).contains("102"), String.valueOf(status.get("message")));
    }

    @Test
    void concurrentRebuildStartsOnlyOneTask() throws Exception {
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (1, 201, 'a', TRUE)");
        CountDownLatch blocker = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            blocker.await(5, TimeUnit.SECONDS);
            return 0;
        }).when(runner).rebuildWorkspace(org.mockito.ArgumentMatchers.anyLong());

        Map<String, Object> first = service.rebuild(null);
        Map<String, Object> second = service.rebuild(null);
        assertEquals(Boolean.TRUE, first.get("started"));
        assertEquals(Boolean.FALSE, second.get("started"));
        assertEquals("running", second.get("reason"));
        blocker.countDown();
        waitUntilNotRunning();
        verify(runner, org.mockito.Mockito.atLeastOnce()).rebuildWorkspace(201L);
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
        Map<String, Object> started = service.rebuild(null);
        assertEquals(Boolean.FALSE, started.get("started"));
        assertEquals("no_embedding_model", started.get("reason"));
        assertFalse((Boolean) service.status().get("available"));
    }

    // ------------------------------------------------------------------
    // V7：停止 / 继续 / 仅重试失败 / 崩溃恢复
    // ------------------------------------------------------------------

    @Test
    void orphanRunningStateSelfHealsToInterrupted() {
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = 1024, status = 'running', "
                + "total = 3, done = 1, failed = 0, current_scope = '102' WHERE id = 1", modelId);
        // 崩溃恢复前：状态机误以为仍在重建（孤儿 running 误判）
        assertTrue((Boolean) service.status().get("running"));
        service.selfHealOnStartup();
        Map<String, Object> status = service.status();
        assertEquals("interrupted", status.get("status"));
        assertEquals(Boolean.TRUE, status.get("interrupted"));
        assertTrue((Boolean) status.get("pending"), "interrupted 计入 pending，避免问答静默返回缺数据结果");
        assertFalse((Boolean) status.get("running"));
    }

    @Test
    void stopRequestInterruptsRebuildAndMarksInterrupted() throws Exception {
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (1, 101, 'a', TRUE)");
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (2, 102, 'b', TRUE)");
        CountDownLatch latch = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            latch.await(5, TimeUnit.SECONDS);
            return 0;
        }).when(runner).rebuildWorkspace(org.mockito.ArgumentMatchers.anyLong());

        service.rebuild(null);
        waitUntilRunning();
        assertTrue((Boolean) service.status().get("running"));
        Map<String, Object> stop = service.stop();
        assertEquals(Boolean.TRUE, stop.get("stopped"));
        latch.countDown();
        waitUntilNotRunning();

        Map<String, Object> status = service.status();
        assertEquals("interrupted", status.get("status"));
        assertEquals(Boolean.TRUE, status.get("interrupted"));
        assertEquals(Boolean.FALSE, status.get("running"));
    }

    @Test
    void resumeContinuesFromLastTouchedScope() throws Exception {
        jdbc.update("DELETE FROM sys_workspace WHERE id = 1");
        jdbc.update("DELETE FROM core_datasource WHERE oid = 1");
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (1, 101, 'a', TRUE)");
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (2, 102, 'b', TRUE)");
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (3, 103, 'c', TRUE)");
        // 模拟中断：current_scope=102（未完成），done=1，total=3
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = 1024, status = 'interrupted', "
                + "total = 3, done = 1, failed = 0, current_scope = '102' WHERE id = 1", modelId);

        Map<String, Object> started = service.rebuild("resume");
        assertEquals(Boolean.TRUE, started.get("started"));
        assertEquals("resume", started.get("mode"));
        waitUntilNotRunning();

        verify(runner, never()).rebuildWorkspace(101L);
        verify(runner).rebuildWorkspace(102L);
        verify(runner).rebuildWorkspace(103L);
        Map<String, Object> status = service.status();
        assertEquals("idle", status.get("status"));
        assertEquals(3, status.get("done"));
        assertFalse((Boolean) status.get("pending"));
    }

    @Test
    void retryFailedOnlyRecomputesFailedScopes() throws Exception {
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (1, 101, 'a', TRUE)");
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (2, 102, 'b', TRUE)");
        jdbc.update("INSERT INTO terminology (id, oid, word, enabled) VALUES (3, 103, 'c', TRUE)");
        // 仅 102 失败：failed_scopes='[102]'，done=2, failed=1, total=3
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = 1024, status = 'failed', "
                + "total = 3, done = 2, failed = 1, current_scope = '103', failed_scopes = '[102]' WHERE id = 1",
                modelId);

        Map<String, Object> started = service.rebuild("retry-failed");
        assertEquals(Boolean.TRUE, started.get("started"));
        assertEquals("retry-failed", started.get("mode"));
        waitUntilNotRunning();

        verify(runner, never()).rebuildWorkspace(101L);
        verify(runner, never()).rebuildWorkspace(103L);
        verify(runner).rebuildWorkspace(102L);
        Map<String, Object> status = service.status();
        assertEquals("idle", status.get("status"));
        assertEquals(0, status.get("failed"));
    }

    @Test
    void retryFailedWithoutFailedScopesIsRejected() {
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = 1024, status = 'idle', "
                + "total = 0, done = 0, failed = 0, current_scope = NULL, failed_scopes = NULL WHERE id = 1", modelId);
        Map<String, Object> started = service.rebuild("retry-failed");
        assertEquals(Boolean.FALSE, started.get("started"));
        assertEquals("no_failed", started.get("reason"));
    }
}
