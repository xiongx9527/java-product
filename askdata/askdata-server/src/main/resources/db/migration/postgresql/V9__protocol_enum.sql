-- 协议枚举化：把冗余的数字列 protocol(INT) + upstream_format(INT) 收敛为单一 VARCHAR 枚举，
-- 取值与框架 LlmProtocol 对齐：OPENAI_CHAT_COMPLETIONS / AZURE_OPENAI_CHAT_COMPLETIONS /
-- OPENAI_RESPONSES / ANTHROPIC_MESSAGES / GEMINI。仅 Gemini 后端经 LlmPortResolver 闭环。

-- 1) 先把基线里既有的 protocol INT 改名保留，避免与新 protocol VARCHAR 重名
ALTER TABLE ai_model RENAME COLUMN protocol TO protocol_old;

-- 2) 新增单一事实源：protocol VARCHAR
ALTER TABLE ai_model ADD COLUMN protocol VARCHAR(64) NOT NULL DEFAULT 'OPENAI_CHAT_COMPLETIONS';

-- 3) 回填：Gemini 厂商(supplier=6) 直接置 GEMINI；其余按 upstream_format 映射
UPDATE ai_model
SET protocol = CASE
    WHEN supplier = 6 THEN 'GEMINI'
    WHEN upstream_format = 2 THEN 'ANTHROPIC_MESSAGES'
    WHEN upstream_format = 3 THEN 'OPENAI_RESPONSES'
    WHEN upstream_format = 4 THEN 'AZURE_OPENAI_CHAT_COMPLETIONS'
    ELSE 'OPENAI_CHAT_COMPLETIONS'
    END;

-- 4) 删除冗余列
ALTER TABLE ai_model DROP COLUMN upstream_format;
ALTER TABLE ai_model DROP COLUMN protocol_old;
