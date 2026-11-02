-- AI 模型配置：可用/启用状态、上游格式、向量维度与索引重建状态
-- enable/valid：valid 只由连通校验回写，enable 仅在 valid=1 时可开启
-- upstream_format：0 自动识别 / 1 Chat Completions / 2 Anthropic Messages / 3 Responses / 4 Azure OpenAI
-- dimension：向量维度，连通校验自动探测，可手动覆盖

ALTER TABLE ai_model ADD COLUMN enable BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE ai_model ADD COLUMN valid BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE ai_model ADD COLUMN upstream_format INT NOT NULL DEFAULT 1;

ALTER TABLE ai_model ADD COLUMN dimension INT;

-- 数据源向量由哪个向量模型生成：模型切换后据此判定旧向量是否失效
ALTER TABLE core_datasource ADD COLUMN embedding_model_id BIGINT;

-- 记录本次问答使用的模型名（ai_modal_id 早已存在但从未写入，对齐 SQLBot chat_question.ai_modal_name）
ALTER TABLE chat_record ADD COLUMN ai_modal_name VARCHAR(255);

UPDATE ai_model SET enable = TRUE, valid = TRUE, upstream_format = 1;

CREATE TABLE ai_embedding_index_state (
    id            INT PRIMARY KEY,
    model_id      BIGINT,
    dimension     INT,
    status        VARCHAR(16) NOT NULL DEFAULT 'idle',
    total         INT         NOT NULL DEFAULT 0,
    done          INT         NOT NULL DEFAULT 0,
    failed        INT         NOT NULL DEFAULT 0,
    current_scope VARCHAR(64),
    started_at    BIGINT,
    finished_at   BIGINT,
    message       VARCHAR(1000)
);

INSERT INTO ai_embedding_index_state (id, status) VALUES (1, 'idle');

-- MySQL：TEXT 上限 65535 字节，utf8mb4 下不足以容纳 1024 维向量文本
ALTER TABLE data_training MODIFY COLUMN embedding MEDIUMTEXT;

ALTER TABLE terminology MODIFY COLUMN embedding MEDIUMTEXT;
