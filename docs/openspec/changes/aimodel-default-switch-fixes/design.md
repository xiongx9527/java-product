---
comet_change: aimodel-default-switch-fixes
role: technical-design
canonical_spec: openspec
---

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
