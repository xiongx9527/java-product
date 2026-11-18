-- 工作空间级模型策略（对齐 SQLBot WorkspaceModelSettings）：
-- enable_custom_model=true 且 custom_model 为空时禁止该空间一切模型调用；
-- 指定模型时必须是映射到该工作空间的对话模型。
ALTER TABLE sys_workspace
  ADD COLUMN enable_custom_model BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN custom_model VARCHAR(255) DEFAULT NULL;
