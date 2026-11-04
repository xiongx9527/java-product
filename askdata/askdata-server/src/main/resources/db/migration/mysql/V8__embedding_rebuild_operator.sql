ALTER TABLE ai_embedding_index_state ADD COLUMN operator VARCHAR(255) NULL COMMENT 'trigger rebuild/cancel operator account';
