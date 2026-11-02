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
 * 与 {@code model_id} 不同，或状态为 {@code failed}。</p>
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
                        + "finished_at, message FROM ai_embedding_index_state WHERE id = ?", STATE_ID);
        return rows.stream().findFirst();
    }

    /** 任务开始：重置计数并置 running。 */
    public void markRunning(Long modelId, Integer dimension, int total) {
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = ?, status = 'running', "
                        + "total = ?, done = 0, failed = 0, current_scope = NULL, started_at = ?, finished_at = NULL, "
                        + "message = NULL WHERE id = ?",
                modelId, dimension, total, System.currentTimeMillis(), STATE_ID);
    }

    public void updateProgress(int done, int failed, String currentScope) {
        jdbc.update("UPDATE ai_embedding_index_state SET done = ?, failed = ?, current_scope = ? WHERE id = ?",
                done, failed, currentScope, STATE_ID);
    }

    /** 全部空间完成：写入本次模型与维度，状态回到 idle。 */
    public void markFinished(Long modelId, Integer dimension, int done, int failed, String message) {
        jdbc.update("UPDATE ai_embedding_index_state SET model_id = ?, dimension = ?, status = 'idle', "
                        + "done = ?, failed = ?, current_scope = NULL, finished_at = ?, message = ? WHERE id = ?",
                modelId, dimension, done, failed, System.currentTimeMillis(), message, STATE_ID);
    }

    /** 存在失败空间：状态置 failed，保留可重试入口。 */
    public void markFailed(int done, int failed, String message) {
        jdbc.update("UPDATE ai_embedding_index_state SET status = 'failed', done = ?, failed = ?, "
                        + "current_scope = NULL, finished_at = ?, message = ? WHERE id = ?",
                done, failed, System.currentTimeMillis(), message, STATE_ID);
    }
}
