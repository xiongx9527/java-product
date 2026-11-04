package com.xiongx9527.askdata.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 向量索引状态表（{@code ai_embedding_index_state}，单行 id=1）。
 *
 * <p>记录上次成功重建使用的向量模型与维度，用于判定「待重建」：当前默认向量模型 id
 * 与 {@code model_id} 不同，或状态为 {@code failed}/{@code interrupted}。</p>
 *
 * <p>V7 起增加 {@code failed_scopes}（失败空间 oid 列表，retry-failed 权威来源）、
 * {@code stop_requested}（停止信号）、{@code mode}（上次重建模式）。</p>
 */
@Repository
public class AiEmbeddingIndexStateRepository {

    private static final int STATE_ID = 1;

    private final JdbcTemplate jdbc;

    public AiEmbeddingIndexStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> find() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, model_id, dimension, status, total, done, failed, current_scope, started_at, "
                        + "finished_at, message, failed_scopes, stop_requested, mode "
                        + "FROM ai_embedding_index_state WHERE id = ?", STATE_ID);
        return rows.stream().findFirst();
    }

    /** 任务开始（full 模式）：重置计数并置 running，清空失败记录与停止信号。 */
    public void markRunning(Long modelId, Integer dimension, int total, String mode, String operator) {
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = ?, status = 'running', "
                        + "total = ?, done = 0, failed = 0, current_scope = NULL, started_at = ?, finished_at = NULL, "
                        + "message = NULL, failed_scopes = NULL, stop_requested = 0, mode = ?, operator = ? WHERE id = ?",
                modelId, dimension, total, System.currentTimeMillis(), mode, operator, STATE_ID);
    }

    /** 任务开始（resume / retry-failed 模式）：保留已完成进度，仅置 running 并清停止信号。 */
    public void markRunningResuming(Long modelId, Integer dimension, int total, String mode, String operator) {
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = ?, status = 'running', "
                        + "total = ?, started_at = ?, finished_at = NULL, stop_requested = 0, mode = ?, operator = ? WHERE id = ?",
                modelId, dimension, total, System.currentTimeMillis(), mode, operator, STATE_ID);
    }

    public void updateProgress(int done, int failed, String currentScope) {
        jdbc.update("UPDATE ai_embedding_index_state SET done = ?, failed = ?, current_scope = ? WHERE id = ?",
                done, failed, currentScope, STATE_ID);
    }

    /** 用户请求停止或崩溃恢复：置 interrupted，保留已完成进度与失败空间清单。 */
    public void markInterrupted(int done, int failed) {
        jdbc.update("UPDATE ai_embedding_index_state SET status = 'interrupted', done = ?, failed = ?, "
                        + "current_scope = NULL, finished_at = ?, stop_requested = 0 WHERE id = ?",
                done, failed, System.currentTimeMillis(), STATE_ID);
    }

    /** 全部空间完成：写入本次模型与维度，状态回到 idle。 */
    public void markFinished(Long modelId, Integer dimension, int done, int failed, String message) {
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = ?, status = 'idle', "
                        + "done = ?, failed = ?, current_scope = NULL, finished_at = ?, message = ? WHERE id = ?",
                modelId, dimension, done, failed, System.currentTimeMillis(), message, STATE_ID);
    }

    /** 存在失败空间：状态置 failed，保留可重试入口（failed_scopes 为权威失败清单）。 */
    public void markFailed(int done, int failed, String message, String failedScopesJson) {
        jdbc.update("UPDATE ai_embedding_index_state SET status = 'failed', done = ?, failed = ?, "
                        + "current_scope = NULL, finished_at = ?, message = ?, failed_scopes = ? WHERE id = ?",
                done, failed, System.currentTimeMillis(), message, failedScopesJson, STATE_ID);
    }

    /** 取消未完成的重建记录：以当前默认向量模型为基线置 idle，释放锁定约束。 */
    public void markCanceled(Long modelId, Integer dimension, String operator) {
        int n = jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = ?, status = 'idle', "
                        + "done = 0, failed = 0, current_scope = NULL, started_at = NULL, finished_at = NULL, "
                        + "message = NULL, failed_scopes = NULL, stop_requested = 0, mode = NULL, operator = ? WHERE id = ?",
                modelId, dimension, operator, STATE_ID);
        if (n == 0) {
            jdbc.update("INSERT INTO ai_embedding_index_state (id, model_id, dimension, status, operator) "
                            + "VALUES (?, ?, ?, 'idle', ?)", STATE_ID, modelId, dimension, operator);
        }
    }

    /** 设置停止信号（runRebuild 每空间前轮询）。 */
    public void requestStop() {
        jdbc.update("UPDATE ai_embedding_index_state SET stop_requested = 1 WHERE id = ?", STATE_ID);
    }
}
