# Tasks

## 1. 默认唯一性（写入口）
- [ ] 1.1 在 AiModelService.update 中，当 default_model 由 false 变 true 时，落库前调用 clearDefaults(modelType)，与 setDefault 一致 <!-- comet-task:c5f48ef9-3835-4ae1-aa27-bdac20f9b18d -->
- [ ] 1.2 补单测：同一类型存在旧默认时，update 置新默认后旧默认的 default_model 应为 false <!-- comet-task:449d8362-29cb-4eca-87bd-c62305b6adfa -->

## 2. 维度冲突拦截
- [ ] 2.1 抽取 setDefault / update 共用的向量默认维度冲突判断：命中且对象为当前默认向量模型时 BadRequest 硬拦截，提示先重建索引 <!-- comet-task:895bc3c7-d743-4056-b254-0e6a435d1706 -->
- [ ] 2.2 非默认向量模型编辑命中维度冲突时仅透传提示、不阻断保存 <!-- comet-task:3d015fc2-8316-4c1c-918a-1539631a9d1b -->
- [ ] 2.3 补单测：维度冲突 + 当前默认向量模型 → 拒绝；非默认 → 允许并带提示 <!-- comet-task:43281d14-3c0c-4eb2-bccd-66567197176e -->

## 3. 放宽强探测（连线字段变化才重探）
- [ ] 3.1 实现 connectionChanged(existing, body) 比较 api_domain/api_key/base_model/protocol/supplier/upstream_format <!-- comet-task:d9a43eb5-c52d-4fad-bc17-558d5e45b996 -->
- [ ] 3.2 update 仅当 (isDefault || defaultModel) && connectionChanged 时执行 probeModel；否则跳过 <!-- comet-task:14d2c38a-de96-4df6-9278-7d5f730eee85 -->
- [ ] 3.3 补单测：仅改名/改无关参数时不触发探测（用探测计数或 spy 验证） <!-- comet-task:40692b6f-3d52-4c1c-bd39-7c73be92b538 -->

## 4. 嵌入探测维度比对
- [ ] 4.1 调整 probeModel 签名，传入 dimension；向量分支比对 vectors.get(0).length 与 dimension，不一致返回友好原因 <!-- comet-task:e780a471-a6e3-48dc-b8bb-2e4c725c4b5a -->
- [ ] 4.2 补单测：端点返回维度与配置不符时探测失败并给出维度不一致原因 <!-- comet-task:f9558131-f77a-4470-bed1-a0f6206ad50c -->

## 5. 重建可控性（进度 / 停止 / 重启 / 重试 / 崩溃恢复）
- [ ] 5.1 确认 status() 返回 total/done/failed/current_scope/status/running 语义正确，补回归测试（含 running 去孤儿误判） <!-- comet-task:2f37c01d-3eaa-424c-81df-dd0316352f9a -->
- [ ] 5.2 停止：`POST /embedding/rebuild/stop` + 内存 `stopped` 标志，runRebuild 每空间前检查，终止后置 state=interrupted 并保留已完成进度 <!-- comet-task:5a9b0826-e0ff-454b-ad5e-332de45c2fc7 -->
- [ ] 5.3 重新开始：`POST /embedding/rebuild` 支持 `{mode:"full"|"resume"}`；resume 从 current_scope 续做（维度未变），full 清集合全量；维度变化强制 full <!-- comet-task:69d3fa6b-db0a-43c2-bf8a-b058ea7cc744 -->
- [ ] 5.4 仅重试失败：`POST /embedding/rebuild` 支持 `{mode:"retry-failed"}`，解析上次失败 oid 列表仅重算失败空间，计数累加 <!-- comet-task:faf328e7-44a6-4232-b019-cf1eaf01d7ba -->
- [ ] 5.5 崩溃恢复：`selfHealOnStartup()`（ApplicationRunner）检测 state.status='running' 且本进程无活动线程 → 标记 interrupted；pending 将 interrupted 视同 failed；Redis 锁陈旧 owner 主动释放 <!-- comet-task:a35f5a0d-a528-45e9-bd4b-db127d4c05df -->
- [ ] 5.6 status() 的 running 判定去除孤儿误判（以内存 running 为主 + 启动 selfHeal 兜底）；interrupted 计入 pending 以免问答静默返回缺数据结果 <!-- comet-task:54e4dab9-6702-4ff6-bbf6-0b062f2a7bb7 -->
- [ ] 5.7 前端 Model.vue：扩展**现有** embedding-banner（含进度/重试）与右下角进度悬浮，增加 停止 / 重新开始 / 仅重试失败 按钮；rebuildActive 时禁用向量「设为默认」与「编辑默认向量模型」入口；崩溃后自动出现重建入口 <!-- comet-task:1483b3ad-139c-4c2f-9338-0b5bbf04f826 -->
- [ ] 5.8 集成测试：模拟停止、维度变化全量、重试失败、服务重启孤儿状态自愈 <!-- comet-task:69390f4b-1c1d-4076-8af3-667f585a84b2 -->
- [ ] 5.9 表结构 V7 迁移：ai_embedding_index_state 增加 failed_scopes / stop_requested / mode 列，status 支持 interrupted（mysql + postgresql 各一份）；AiEmbeddingIndexStateRepository 同步读写新列 <!-- comet-task:34222d80-e157-4e0f-b5c5-3609b23b84f0 -->
- [ ] 5.10 重建期间锁定（前端）：rebuildActive 时禁用向量「设为默认」与「编辑默认向量模型」入口 <!-- comet-task:1399b644-e818-4832-89ee-1cf17ead76f3 -->
- [ ] 5.11 重建期间锁定（后端）：setDefault（向量模型）与 update（默认向量模型）在 isRebuildActive() 时拒绝并返回友好文案 <!-- comet-task:20bf188a-7196-44f5-be71-fcbb39daf962 -->

## 6. 统一探测失败文案
- [ ] 6.1 probeModel 异常分支改为统一友好文案（复用 messageResolver 现有 key），不再透传原始异常 <!-- comet-task:24909cd8-0189-4853-a4f0-aff599b1c5d6 -->
- [ ] 6.2 补单测/断言：探测失败返回可读文案而非异常堆栈 <!-- comet-task:d7866968-76e7-40af-92d6-02d958888be9 -->

## 7. 规范漂移清理与回归
- [ ] 7.1 将 ai-model-configuration spec 中过时的 valid 列语义统一为 enable 描述（包括在本次 delta 中已修正的条目） <!-- comet-task:e58c0e2a-83d5-402f-ba46-8ea2d918f146 -->
- [ ] 7.2 全量回归：askdata-server 测试套件保持通过（基线 1124 通过） <!-- comet-task:43b216d3-817e-4218-9316-b389cf5cdfaa -->
