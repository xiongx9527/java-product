-- 同 mysql/V6：删除冗余的 valid 列（语义已合并进 enable）。
ALTER TABLE ai_model DROP COLUMN valid;
