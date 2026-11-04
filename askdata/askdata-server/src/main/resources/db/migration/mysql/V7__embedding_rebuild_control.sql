-- V7：向量索引重建可控性增强
-- failed_scopes：上次失败的工作空间 oid 列表（JSON 数组），retry-failed 的权威来源
-- stop_requested：停止信号（runRebuild 每空间前轮询）
-- mode：上次重建模式 full / resume / retry_failed（供 UI 展示）
-- status 值域新增 interrupted（停止 / 崩溃恢复后标记），VARCHAR(16) 已容纳

ALTER TABLE ai_embedding_index_state ADD COLUMN failed_scopes MEDIUMTEXT;
ALTER TABLE ai_embedding_index_state ADD COLUMN stop_requested TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE ai_embedding_index_state ADD COLUMN mode VARCHAR(16);
