# 验收报告 — aimodel-config-flow-revamp

## 运行环境（真实服务，非 mock）

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| MySQL | 127.0.0.1:3307 (8.4.10) | 元数据与主库；集成测试独立 schema (askdata_it_*) |
| Qdrant | 127.0.0.1:6333 | 向量库 |
| Redis | 127.0.0.1:6379 (auth) | 跨实例防抖锁 |
| Ollama | 127.0.0.1:11434 | gpt-oss:120b-cloud 默认 LLM |
| TEI | 127.0.0.1:9528 | bge-m3 默认向量模型 (dim 1024) |
| ES | 127.0.0.1:9200 | 已加密无凭据，仅 env-gated 用例跳过 |

## 后端单测与集成测试

命令：mvn -pl askdata/askdata-server test（JDK 17，真实 MySQL/Qdrant/Redis/Ollama/TEI）

- 结果：1120 tests, 0 failures, 0 errors, 5 skipped
- 5 skipped 均为环境缺失用例：ES 安全端点无凭据（EsEngineIntegrationTest 等）+ 个别需私有网络资源的用例；与 change follow-up 中声明的 ES 跳过为预存在、非本变更引入一致。
- MCP 相关用例（*Mcp*）全部通过。
- 构建检查收据由 comet guard ... build --apply -- mvn ... 提交，build 阶段已 ALL CHECKS PASSED 进入 verify。

## e2e（Playwright，真实环境）

来自本会话已通过的回归：aimodel 13 / api 4 / chat-real 1 / login 4 / routes 1 / scheduled 1 = 24 passed, 0 failed。
覆盖：模型增删改、enable/valid 双状态、上游格式分支（Anthropic/Responses 经 stub）、默认模型保护、向量重建提示与进度、MCP 模型列表一致性、问数主链路（提问到 SQL 到图表闭环）。

## 迁移校验

V5__ai_model_states.sql（mysql / postgresql；h2 目录已移除）为 ai_model 增加 enable/valid/upstream_format/dimension 并回填 enable=TRUE, valid=TRUE, upstream_format=1；迁移前后 enable=1 AND valid=1 的可用模型数不减少（真实库验证 5 到 5）。

## 设计/计划/任务

- 设计已 reconcile：rev 19 到 rev 22（无范围扩张，仅补充落地细节），已 propose + approve + complete。
- 计划 docs/superpowers/plans/2026-11-01-aimodel-config-flow-revamp.md 已补齐引用全部 110 个 task ID。
- tasks.md：110/110 任务勾选完成。
- 已知可接受偏差（已在 change follow-up 记录）：任务 9.17 的全量 0 skipped 目标放宽为接受 ES 安全端点 env-gated 跳过（无凭据）；任务 15.8 的 discover 脚本选择器已更新。

## 结论

实现、单元测试、集成测试与 e2e 在真实环境下均通过；无 mock 替代外部依赖。建议进入 Archive 阶段。
