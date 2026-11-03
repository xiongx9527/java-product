-- 「只留一个」状态字段：ai_model.valid 的「连通校验回写」语义已合并进 enable，
-- enable 同时承载「已校验可用」与「是否生效」（连通校验通过即置 1、失败置 0）。
-- 故删除冗余的 valid 列，避免与 enable 含义重叠。

ALTER TABLE ai_model DROP COLUMN valid;
