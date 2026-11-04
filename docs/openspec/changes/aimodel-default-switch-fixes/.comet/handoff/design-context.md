# Comet Design Handoff

- Change: aimodel-default-switch-fixes
- Phase: design
- Mode: compact
- Context hash: b72fb94b856b46c798aabddc39ceeb0e6904f0ec00717a82d6c32f15f1e29f4e

Generated-by: comet-handoff.sh
Task hash policy: task-content-v1. Read tasks.md for live completion; excerpts are design-time context.

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## docs/openspec/changes/aimodel-default-switch-fixes/proposal.md

- Source: docs/openspec/changes/aimodel-default-switch-fixes/proposal.md
- Lines: 1-30
- SHA256: 4811584965917bc90f649fb08e1edbd43e243501e969ef4bdcf4500ad89acb16

```md
# Proposal

## Why

默认模型切换与修改是高频运维动作，但当前实现与已确立规范（ai-model-configuration / ai-model-connectivity）存在多处漂移与缺口：update 把模型设为默认时不清除同类型旧默认，可能同类型出现多个默认；设为/改为默认向量模型时只做连通探测、未复用集合维度冲突校验，维度变化后问答入口直接被「索引待重建」拦死且无引导；强探测在改无关字段时也强制重连，端点抖动即卡死保存；嵌入探测不比对返回向量长度，维度不一致隐患潜伏。本次让实现对齐既有规范并补齐这些缺口。

## What Changes

- 默认唯一性：update 将 default_model 置 true 时，同步清除同类型旧默认（与 setDefault 一致），杜绝同类型多默认。
- 维度冲突拦截：setDefault 与 update（向量默认）复用 storeDimensionConflict，命中且与当前在线集合维度冲突时硬拦截并提示需先重建索引；非默认仅提示。
- 切换前确认与维度提示：切换向量默认前展示「旧维度 → 新维度」确认，维度不同明确指出需重建索引。
- 重建引导打通：切换后常驻「向量索引待重建」提示 + 一键重建入口；重建完成且维度对齐后解除问答拦截（状态 pending 已暴露，前端 banner 对接）。
- 放宽强探测：仅当连线字段（Base URL / API Key / 基础模型 / 协议 / 供应商 / 上游格式）真变化时强制联网探测；纯改名、无关参数编辑不重探。
- 探测口径加固：嵌入探测比对返回向量长度 == dimension；探测失败原因统一走友好文案，不再透传原始异常。
- 清理规范漂移：移除规范中已过时的 valid 列语义（已并入 enable），消除规范/实现不一致。

## Capabilities

### New Capabilities
<!-- 无新增能力，全部为对既有能力的修正与补齐 -->

### Modified Capabilities
- ai-model-configuration：修正「默认模型按类型唯一」「向量维度与存储对齐」「向量默认切换后的索引重建」「可用与启用状态」中关于默认切换/修改的行为与已过时的 valid 语义。
- ai-model-connectivity：修正「连通校验」中关于默认模型探测触发条件、嵌入维度比对与失败文案的要求。

## Impact

- 后端：AiModelService（setDefault / update / probeModel）、ChatModelRouter（已修 valid 残留）、EmbeddingService / VectorStore（storeDimensionConflict / collectionsWithDimensionOtherThan）、EmbeddingIndexService（重建与 pending 状态）。
- 前端：Model.vue 等（切换向量默认确认弹窗、待重建 banner、一键重建入口对接）。
- 行为变化：默认向量模型维度冲突改为硬拦截；非连线字段编辑不再因端点抖动失败而卡死。

```

## docs/openspec/changes/aimodel-default-switch-fixes/design.md

- Source: docs/openspec/changes/aimodel-default-switch-fixes/design.md
- Lines: 1-54
- SHA256: 565dd668a5c3f3977bfef4bed8933a0b1f74debaaebf38d6fadda9b30bb203e6

```md
# Design

## 背景与决策

切换/修改默认模型的健壮性缺口，绝大多数已在既有 spec（ai-model-configuration / ai-model-connectivity）中规定，本次是「实现对齐 + 补缺口」，而非引入新能力。下列决策对应 proposal 的改动点。

## 关键决策

### D1. 默认唯一性：update 入口补 clearDefaults
update 在 default_model 由 false 变 true 时，必须先调用 aiModelRepository.clearDefaults(modelType)，再执行 update。与 setDefault 共用同一约束，杜绝同类型多默认。只读入口（chatModelById / firstAvailable）此前已修掉 valid 残留，此处补齐写入口。

### D2. 维度冲突拦截：复用 storeDimensionConflict
EmbeddingService.storeDimensionConflict(int)（比对在线集合维度）已存在。在 setDefault 与 update（仅当目标为向量默认模型）调用：
- 命中冲突且对象是当前默认向量模型：BadRequest 硬拦截，提示先执行一键重建。
- 命中冲突但对象非默认（普通向量模型编辑）：仅透传给前端提示，不阻断保存。
前端切换向量默认前额外展示「旧维度 → 新维度」确认框（spec 要求），后端以 D2 硬拦截兜底。

### D3. 放宽强探测：连线字段变化才重探
update 计算 connectionChanged(existing, body) = 比较 api_domain / api_key / base_model / protocol / supplier / upstream_format 是否变化。仅当 isDefault && connectionChanged 或 defaultModel && connectionChanged 时执行 probeModel；否则跳过探测直接落库。这既遵守校验失败允许保存不可用模型，又保留默认模型必须连得上的强约束。

### D4. 嵌入探测维度比对
probeModel 对向量类型在拿到 vectors.get(0) 后，比对 vectors.get(0).length 与传入的 dimension；不一致返回友好原因「维度不一致（实际 X 与配置 Y 不符）」。需要把 dimension 作为入参传入 probeModel（当前签名只有 modelType）。

### D5. 重建可控性（进度 / 停止 / 重启 / 重试 / 崩溃恢复）
EmbeddingIndexService.status() 已暴露 pending / dimension_consistent / store_dimension / running / total / done / failed / current_scope。在此基础上补齐可控性：

- **进度**：前端轮询 status() 的 total/done/failed/current_scope 即可展示台阶+总量，无需新增接口。
- **停止**：新增 `POST /system/aimodel/embedding/rebuild/stop`，置内存 `AtomicBoolean stopped`；runRebuild 在每个 workspace 开始前检查 `stopped`，为真则跳出循环，state 置 `interrupted`（保留 done/failed/current_scope）。
- **重新开始**：`POST /embedding/rebuild` 增加请求体 `{mode:"full"|"resume"}`。resume 模式不调用 `resetCollections()`，从 `current_scope` 之后的 workspace 续做（仅当维度未变）；full 等同当前行为（清集合+全量）。维度变化时强制 full。
- **仅重试失败**：`POST /embedding/rebuild` 支持 `{mode:"retry-failed"}`，解析上次 state.message 中的失败 oid 列表，仅对这些 workspace 重算，done/failed 累加而非清零。
- **崩溃恢复（孤儿状态自愈）**：新增 `EmbeddingIndexService.selfHealOnStartup()`，由 `ApplicationRunner` 在 Spring 就绪后调用：读 state，若 `status=='running'` 且本进程 `running==false` → 标记 `interrupted`；同时若 `singleWorkerGuard` 为 Redis 且锁 owner 非本进程，主动释放旧锁。
- **pending 修正**：`status()` 中 `interrupted` 须视同 `failed` 计入 pending（`boolean interrupted = "interrupted".equals(status)`），否则崩溃后空集合 + pending=false 会让问答静默返回缺数据结果（数据正确性坑）。
- **running 判定去误判**：`status()` 的 `running` 改为以内存 `running.get()` 为主 + 启动 selfHeal 兜底，避免孤儿 `status=='running'` 导致前端永久「重建中」。
- **锁后端兜底**：`RedisWorkerLock.ttl` 配置须小于预期单次重建耗时；selfHeal 比对 owner/pid 释放陈旧锁，避免 Redis 后端 TTL 内点重建静默跳过。
- **重建期间锁定向量默认模型操作**：重建未完成（running/pending/interrupted/failed，即 `isRebuildActive()=running||pending`）时，前端禁用「设为默认」（向量类型）与「编辑默认向量模型」入口；后端 `setDefault` 在目标为向量模型且 `isRebuildActive()` 时返回 4xx 拒绝，`update` 在编辑对象为当前默认向量模型且 `isRebuildActive()` 时拒绝，统一文案「向量索引重建中，暂不可修改默认向量模型」。避免重建中途默认向量模型被改导致索引与模型语义错乱。
- **表结构变更（V7 迁移）**：在 `ai_embedding_index_state` 增加列 `failed_scopes`（TEXT/JSON，记录失败 workspace oid 列表，retry-failed 的权威来源）、`stop_requested`（BOOLEAN/TINYINT，停止信号，runRebuild 每空间前轮询）、`mode`（VARCHAR(16)，上次重建模式 full/resume/retry_failed 供 UI 展示）；`status` 值域新增 `interrupted`（停止 / 崩溃恢复后标记）。`AiEmbeddingIndexStateRepository.find()/markRunning()/markInterrupted()/markFailed()/requestStop()` 同步读写新列。MySQL 与 PostgreSQL 各加一份 V7 迁移脚本；`EmbeddingIndexService` 的状态机与 `status()` 的 `running`/`pending` 判定同步支持 `interrupted`。

### D6. 统一失败文案
probeModel 异常分支不再直接返回 e.getMessage()；统一构造友好文案（复用 messageResolver 现有 key，如 aimodel.embedding.unavailable / aimodel.rerank.unavailable / 通用 aimodel.default.probe_failed_generic），与 streamStatus 的 NDJSON 口径一致。

## 兼容性 / 风险

- 维度硬拦截会在旧集合 1024、新默认 768 时阻断切换，符合规范；但已有此类不一致环境需先一键重建再切，属预期行为。
- update 免探测范围仅限连线字段未变，避免误把连不上的模型长期当默认而不知。
- 前端切换确认框与 banner 依赖 status() 字段，需同步前端联调。

## 验证策略

- 后端单测：update 设默认清旧默认；维度冲突硬拦截/非默认仅提示；连线字段未变跳过探测；嵌入维度比对。
- 集成：切换向量默认触发确认 + 硬拦截 + 重建后解除。
- 全量回归：askdata-server 测试套件保持通过（当前基线 1124 通过）。

## Open Questions
<!-- 暂无；若重建 UI 交互细节有分歧，先与用户确认再落地 tasks -->

```

## docs/openspec/changes/aimodel-default-switch-fixes/tasks.md

- Source: docs/openspec/changes/aimodel-default-switch-fixes/tasks.md
- Lines: 1-40
- SHA256: fbd9c64a62b4362c285601098d57e23daf4afcd3cf3fd672f65898f1b2fa6857

```md
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

```

## docs/openspec/changes/aimodel-default-switch-fixes/.openspec.yaml

- Source: docs/openspec/changes/aimodel-default-switch-fixes/.openspec.yaml
- Lines: 1-2
- SHA256: b6d27c089c6ee9596ce0433bd8f7f771b477758efe1ce7f7a7505af47316ecd0

```md
schema: spec-driven
created: 2026-11-03

```

## docs/openspec/changes/aimodel-default-switch-fixes/specs/ai-model-configuration/spec.md

- Source: docs/openspec/changes/aimodel-default-switch-fixes/specs/ai-model-configuration/spec.md
- Lines: 1-155
- SHA256: fc73af2aee20f3565380ce0796845d42379f40c5b0fb3d58240b88606218bda2

[TRUNCATED]

```md
# ai-model-configuration Spec Delta

## MODIFIED Requirements

### Requirement: 默认模型按类型唯一
每个模型类型 MUST 至多存在一个默认模型；设置某个模型为默认时，系统 SHALL 仅清除同类型的默认标记，不影响其他类型。该约束 MUST 同时适用于「设为默认」动作与「编辑时把 default_model 改为 true」两种入口——后者 MUST 在落库前先清除同类型旧默认，否则 MUST 拒绝该编辑并提示先取消旧默认。默认模型 MUST 同时满足 enable=1（valid 语义已并入 enable），否则 MUST 拒绝设默认。默认模型 MUST NOT 被停用，也 MUST NOT 被删除；用户若要调整，MUST 先将同类型另一个可用模型设为默认。新建模型 MUST NOT 自动成为默认。当某类型尚无默认模型时，模型路由 SHALL 回退到该类型下第一个 enable=1 的模型，以保证首次配置后即可使用。

#### Scenario: 跨类型互不影响
- **WHEN** 用户将某向量模型设为向量默认
- **THEN** 当前 LLM 默认模型保持不变

#### Scenario: 默认模型不可停用
- **WHEN** 用户尝试关闭当前默认模型的「是否生效」开关
- **THEN** 系统拒绝操作并提示先切换默认模型

#### Scenario: 无默认时回退
- **WHEN** 某类型没有任何默认模型，但存在一个已启用且校验通过的模型
- **THEN** 该类型请求使用该模型

#### Scenario: 编辑时把模型设为默认会清除同类型旧默认
- **WHEN** 类型 1 已存在默认模型 A，把同类型模型 B 通过 update 将 default_model 改为 true
- **THEN** 落库后 A 的 default_model 为 false、B 为 true，该类型仅一个默认

### Requirement: 向量维度与存储对齐
向量模型 MUST 具备向量维度属性，并持久化在 ai_model.dimension。维度 SHALL 由连通校验自动探测（取 embeddings 响应中 data[0].embedding 的长度）；用户 MAY 在高级配置中手动覆盖，覆盖值 MUST 为正整数。维度取值 SHALL 生效于三处：模型列表展示、向量重建、向量存储集合创建与校验，三者 MUST 使用同一来源。向量存储 MUST 与该维度对齐：重建时按模型维度创建/校验集合；维度不匹配的存量向量 MUST 视为无效并在重建中被覆盖，MUST NOT 与不同维度向量混存。查询时 MUST 跳过维度不匹配的向量并记录数量，MUST NOT 因长度不一致抛出异常或返回错误分数。维度校验 MUST 覆盖三处，任一处不一致即视为「未对齐」：

| 校验点 | 规则 |
| --- | --- |
| 启用（enable） | 模型 dimension 必须已探测（非空）且 enable=1，否则拒绝开启 |
| 设为默认 | 当前在线集合维度 MUST 与即将成为默认的向量模型维度一致；若不一致，MUST 拒绝设默认并提示先执行索引重建（非默认向量模型的维度变更仅提示、不拦截） |
| 问答入口 | 每次请求前校验当前默认向量模型与 state 一致，不一致则阻断并提示重建 |

运行期 MUST 校验上游实际返回的向量长度：与模型 dimension 不一致时 MUST 判定本次调用失败，MUST NOT 写入存储。写入存储时 MUST 校验 vector.length == dimension，不一致 MUST 拒绝写入并记日志。切换默认向量模型时，提示 MUST 展示「旧维度 → 新维度」，维度不同时提示文案 MUST 明确指出需要重建索引。

#### Scenario: 校验自动探测维度
- **WHEN** 用户对向量模型执行连通校验，上游返回 1024 维向量
- **THEN** 模型维度被记录为 1024，并在表单中展示

#### Scenario: 手动覆盖维度
- **WHEN** 用户在高级配置中把维度改为 1536
- **THEN** 保存后模型维度为 1536，重建按该维度执行

#### Scenario: 维度不匹配的存量向量
- **WHEN** 索引中存在 768 维旧向量，当前模型为 1024 维
- **THEN** 查询跳过这批旧向量，重建后全部覆盖为 1024 维

#### Scenario: 未探测维度不可启用
- **WHEN** 向量模型从未执行过连通校验，dimension 为空
- **THEN** 「是否生效」开关不可开启

#### Scenario: 未对齐不可设默认
- **WHEN** 模型的 dimension 与 state.dimension 不一致
- **THEN** 设为默认被拒绝，并提示先执行重建

#### Scenario: 未对齐阻断问答
- **WHEN** 切换默认向量模型后尚未重建，用户发起问数
- **THEN** 请求被阻断并提示「向量索引待重建」

#### Scenario: 返回维度不符
- **WHEN** 上游返回的向量长度与模型 dimension 不一致
- **THEN** 本次调用判定失败，向量不写入存储

#### Scenario: 切换时提示维度变化
- **WHEN** 用户从 768 维模型切到 1024 维模型
- **THEN** 确认框展示「768 → 1024」并说明需重建索引

#### Scenario: 维度冲突时拒绝设为默认向量模型
- **WHEN** 在线集合维度为 1024，待设默认的向量模型 dimension=768
- **THEN** 返回明确错误，提示先执行一键重新向量

#### Scenario: 非默认向量模型维度变更仅提示
- **WHEN** 某非默认向量模型 dimension 与在线集合不一致
- **THEN** 编辑保存该模型成功，仅向用户提示维度不一致，不阻断

### Requirement: 向量默认切换后的索引重建
当用户将某个向量模型设为默认，且与当前默认向量模型不同，系统 SHALL 在切换前弹出确认，说明维度可能变化、已有向量将失效、影响术语/训练/表/数据源四类召回；确认框 MUST 展示「旧维度 → 新维度」。切换完成后，系统 SHALL 在模型页常驻展示「向量索引待重建」提示，直到重建成功；该状态 MUST 持久化，刷新页面、重新登录或重启服务后仍然可见。系统 SHALL 提供「一键重新向量」入口；点击后 SHALL 触发全量重建，完成后 MUST 自动解除常驻提示与问答拦截（pending=false）。重建 MUST 对四类向量做全量重算，MUST NOT 只补 embedding IS NULL 的存量行。重建范围 MUST 覆盖全部工作空间，MUST NOT 只处理当前登录用户所在空间。重建 SHALL 复用既有单 worker 守卫，同一时刻只允许一个重建任务；重复点击 MUST 返回「任务进行中」而不是并发启动。重建 SHALL 按工作空间分批执行并汇总，进度 MUST 同时给出台阶（当前空间）与总量（已完成/总数）。重建过程 SHALL 展示进度与当前阶段，完成后 SHALL 报告各空间、各类的成功数、失败数与耗时；失败项 SHALL 可重试，且 MUST NOT 因单个空间失败而中断其余空间。维度变化时，系统 SHALL 重建向量集合，MUST NOT 在旧维度集合上追加新维度向量。重建 MUST 提供「停止」入口：用户可中途终止，终止后 state 标记为 interrupted，已完成空间保留、未做空间留待续做。重建处于 interrupted（含服务重启导致的孤儿 running 状态）或失败状态时，MUST 提供「重新开始」入口，可从断点续做（维度未变）或全量重来，且 MUST NOT 要求用户手动清理集合。重建 MUST 支持「仅重试失败」：只对上次失败的工作空间重算，已成功空间不动、计数累加。系统 MUST 在启动完成后自检重建孤儿状态：若 state.status='running' 但本进程无活动重建线程，则自动标记为 interrupted 并令 pending 变为 true（崩溃恢复），Redis 锁后端的 TTL MUST 小于单次正常重建耗时上限，或在恢复时主动释放旧 owner 的锁，避免重建入口静默失效。

#### Scenario: 切换前确认
- **WHEN** 用户把默认向量模型从 bge-m3 切换为另一个模型
- **THEN** 弹出确认框说明需重新向量化且影响所有工作空间，用户确认后才执行切换

```

Full source: docs/openspec/changes/aimodel-default-switch-fixes/specs/ai-model-configuration/spec.md

## docs/openspec/changes/aimodel-default-switch-fixes/specs/ai-model-connectivity/spec.md

- Source: docs/openspec/changes/aimodel-default-switch-fixes/specs/ai-model-connectivity/spec.md
- Lines: 1-30
- SHA256: 00605784e1c231dfc70a2edbb52d4a955009027d136d1b1ee393664f1e85ff1f

```md
# ai-model-connectivity Spec Delta

## MODIFIED Requirements

### Requirement: 连通校验
系统 SHALL 提供连通校验能力，并依据模型类型选择端点：LLM 使用对话补全接口，向量模型使用 embeddings 接口，重排模型使用 rerank 接口。校验 MUST 使用当前表单中的 Base URL、凭据与模型参数，无需先保存。校验结果 MUST 回写模型的 enable 状态：通过置为可用，失败置为不可用。校验耗时中 MUST 禁止重复提交，并在界面上显示进行中状态。嵌入类校验 MUST 在返回时比对响应中 data[0].embedding 的长度与模型 dimension 字段：长度不一致 MUST 判定本次校验失败并指明维度不一致（实际 X 与配置 Y 不符）。将某模型设为默认、或编辑已是默认的模型时，若其连线字段（Base URL / API Key / 基础模型 / 协议 / 供应商 / 上游格式）相对既有值发生任一变化，MUST 重新执行联网探测；仅当连线字段无变化时才允许免探测直接落库（避免端点抖动时纯无关编辑被卡死）。探测失败 MUST 以统一、可读的友好文案返回原因，MUST NOT 直接透传原始异常或乱码。

#### Scenario: LLM 校验
- **WHEN** 用户对 LLM 模型点击校验
- **THEN** 系统向其对话补全端点发起最小请求，并根据响应回写 enable

#### Scenario: 向量模型校验
- **WHEN** 用户对向量模型点击校验
- **THEN** 系统向其 embeddings 端点发起最小请求，而不是对话补全端点

#### Scenario: 校验通过后可启用
- **WHEN** 校验成功且模型原为不可用
- **THEN** 模型变为可用，「是否生效」开关解除置灰

#### Scenario: 嵌入探测比对返回向量长度
- **WHEN** 模型 dimension=1024，上游返回 512 维向量
- **THEN** 校验失败，结果指明维度不一致（实际 512 与配置 1024 不符）

#### Scenario: 默认模型连线字段变化才重探
- **WHEN** 默认模型 A 的 Base URL 未变化、仅改显示名
- **THEN** 保存时免探测直接落库；而改 Base URL 时重新探测，失败则拒绝

#### Scenario: 探测失败返回友好文案
- **WHEN** 上游不可达，设为默认触发探测
- **THEN** 返回「联网探测未通过（Connection refused）」之类可读文案，而非异常堆栈

```
