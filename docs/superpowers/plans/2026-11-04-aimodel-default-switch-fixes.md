---
change: aimodel-default-switch-fixes
design-doc: docs/openspec/changes/aimodel-default-switch-fixes/design.md
base-ref: 6356f453ecee5b9a2e0ffc61704a3f705ba7d777
---
<!-- comet-task-authority: docs/openspec/changes/aimodel-default-switch-fixes/tasks.md -->

# aimodel-default-switch-fixes Implementation Plan

> 本计划引用 `tasks.md` 作为任务权威（勾选在其内完成），此处只记录方案、依赖、文件映射与跨任务接口约定，不复写 checkbox 清单。

**Goal:** 修复切换/修改默认模型的健壮性缺口，并让向量索引重建具备进度可见、可停止、可重新开始、可重试失败以及崩溃（孤儿状态）自愈的能力。

**Architecture:** 后端在 `AiModelService` 收敛默认唯一性/维度冲突/连线探测逻辑，在 `EmbeddingIndexService` 增加停止/续做/重试失败/启动自愈与 `interrupted` 状态机；前端在现有 `Model.vue` 的 `embedding-banner` 进度载体上扩展 停止/重新开始/仅重试失败 按钮，并在重建未完成时禁用向量默认模型操作。表结构新增列由 V7 迁移落地（MySQL + PostgreSQL）。

**Tech Stack:** Java 17 + Spring Boot (askdata-server)；Vue 3 + Element Plus (web)；Flyway 迁移；单 worker 守卫（DB / Redis）。

**Spec:** `docs/openspec/changes/aimodel-default-switch-fixes/design.md`（D1–D5 与锁/表结构决策）+ `tasks.md`（任务权威）。

## Global Constraints

- 沿用 `valid` 已并入 `enable` 的语义：所有读/写只作用于 `enable`，不再引用已删除的 `ai_model.valid` 列（V6 已删）。
- 维度单一来源：模型列表展示、重建、集合创建/校验三处共用 `ai_model.dimension`。
- 重建复用既有单 worker 守卫，同一时刻仅一个重建任务；重复点击返回「任务进行中」。
- 重建必须覆盖全部工作空间，不只对当前用户空间；对四类向量全量重算，不只补 `embedding IS NULL`。
- 不引入新能力（capability），仅对齐既有 spec + 补缺口。
- 提交前保持 askdata-server 测试套件通过（基线 1124 通过）。

## Review Focus

1. **崩溃恢复孤儿状态**：`status()=='running'` 但本进程无活动重建线程时，必须标 `interrupted` 且令 `pending=true`；否则问答不拦截却返回缺向量结果（静默错误）。
2. **重建期间锁定**：`isRebuildActive()`（running||pending）时，`setDefault`(向量类型) 与 `update`(当前默认向量模型) 必须 4xx 拒绝，前端同步禁用入口。
3. **resume 与 full 边界**：`resume` 仅当维度未变才允许从 `current_scope` 续做；维度变化必须 `full`（先 `resetCollections`）。
4. **Redis 锁 TTL**：`RedisWorkerLock.ttl` 须小于单次正常重建耗时；崩溃恢复须比对 owner/pid 主动释放陈旧锁，否则重建入口静默失效。
5. **interrupted 计入 pending**：`pending` 须将 `interrupted` 视同 `failed`，否则不一致状态会解除问答拦截。

---

## 文件结构与任务映射

**后端**（`src/main/java/com/xiongx9527/askdata/` 下）
- `system/service/AiModelService.java` — 任务 1.1/2.1/3.1/3.2/4.1/5.11/6.1 落点
- `chat/service/ChatModelRouter.java` — 已修 `valid` 残留，本计划不再改
- `ai/EmbeddingService.java` — 复用 `storeDimensionConflict(int)` 与 `storeDimensions()`
- `ai/EmbeddingIndexService.java` — 任务 5.1–5.6 落点（stop/rebuild(mode)/selfHeal/isRebuildActive/status）
- `system/repository/AiEmbeddingIndexStateRepository.java` — 任务 5.9 新列读写
- `system/controller/AiModelController.java` — 任务 5.2/5.3 新增/改造端点
- `common/lock/RedisWorkerLock.java` — 任务 5.5 陈旧 owner 释放
- `src/main/resources/db/migration/{mysql,postgresql}/V7__embedding_rebuild_control.sql` — 任务 5.9 迁移

**前端**（`web/src/`）
- `views/system/model/Model.vue` — 任务 5.7/5.10
- `api/system.ts` — 任务 5.2/5.3 可能新增 `stopRebuild`/`rebuildEmbedding(mode)`

## 各组方案要点（对应 tasks.md 任务 ID）

### Group 1 默认唯一性 <!-- comet-task-ref:c5f48ef9-3835-4ae1-aa27-bdac20f9b18d --> <!-- comet-task-ref:449d8362-29cb-4eca-87bd-c62305b6adfa -->
`update` 在 `default_model` 由 false→true 时，落库前调用 `aiModelRepository.clearDefaults(modelType)`，与 `setDefault` 共用同一约束；补单测验证旧默认被清除。

### Group 2 维度冲突拦截 <!-- comet-task-ref:895bc3c7-d743-4056-b254-0e6a435d1706 --> <!-- comet-task-ref:3d015fc2-8316-4c1c-918a-1539631a9d1b --> <!-- comet-task-ref:43281d14-3c0c-4eb2-bccd-66567197176e -->
抽取 `setDefault`/`update` 共用的维度冲突判定，命中且对象为当前默认向量模型时 `BadRequest` 硬拦截（提示先重建索引）；非默认向量模型编辑仅透传提示、不阻断保存。复用 `EmbeddingService.storeDimensionConflict(int)`。

### Group 3 放宽强探测 <!-- comet-task-ref:d9a43eb5-c52d-4fad-bc17-558d5e45b996 --> <!-- comet-task-ref:14d2c38a-de96-4df6-9278-7d5f730eee85 --> <!-- comet-task-ref:40692b6f-3d52-4c1c-bd39-7c73be92b538 -->
实现 `connectionChanged(existing, body)` 比较 `api_domain/api_key/base_model/protocol/supplier/upstream_format`；仅当 `(isDefault||defaultModel) && connectionChanged` 时执行 `probeModel`，否则跳过。单测验证仅改名/无关参数不触发探测。

### Group 4 嵌入探测维度比对 <!-- comet-task-ref:e780a471-a6e3-48dc-b8bb-2e4c725c4b5a --> <!-- comet-task-ref:f9558131-f77a-4470-bed1-a0f6206ad50c -->
`probeModel` 增 `dimension` 入参，向量分支比对 `vectors.get(0).length` 与 `dimension`，不一致返回友好原因。单测验证维度不符时探测失败并给出原因。

### Group 5 重建可控性 <!-- comet-task-ref:2f37c01d-3eaa-424c-81df-dd0316352f9a --> <!-- comet-task-ref:5a9b0826-e0ff-454b-ad5e-332de45c2fc7 --> <!-- comet-task-ref:69d3fa6b-db0a-43c2-bf8a-b058ea7cc744 --> <!-- comet-task-ref:faf328e7-44a6-4232-b019-cf1eaf01d7ba --> <!-- comet-task-ref:a35f5a0d-a528-45e9-bd4b-db127d4c05df --> <!-- comet-task-ref:54e4dab9-6702-4ff6-bbf6-0b062f2a7bb7 --> <!-- comet-task-ref:1483b3ad-139c-4c2f-9338-0b5bbf04f826 --> <!-- comet-task-ref:69390f4b-1c1d-4076-8af3-667f585a84b2 --> <!-- comet-task-ref:34222d80-e157-4e0f-b5c5-3609b23b84f0 --> <!-- comet-task-ref:1399b644-e818-4832-89ee-1cf17ead76f3 --> <!-- comet-task-ref:20bf188a-7196-44f5-be71-fcbb39daf962 -->
- 5.1 `status()` 语义确认 + 回归（含 running 去孤儿误判）。
- 5.2 停止：`POST /embedding/rebuild/stop` + `stopped` 标志，循环每空间前检查→置 `interrupted`。
- 5.3 重新开始：`rebuild(mode)`，`full` 清集合全量，`resume` 从 `current_scope` 续做（维度未变），维度变强制 full。
- 5.4 仅重试失败：`mode=retry-failed`，解析 `failed_scopes` 仅重算失败空间、计数累加。
- 5.5 崩溃恢复：`selfHealOnStartup()`（ApplicationRunner）孤儿 `running`→`interrupted`；Redis 旧锁 owner 释放。
- 5.6 `running` 判定以内存 `running` 为主 + selfHeal 兜底；`interrupted` 计入 `pending`。
- 5.7/5.10 前端扩展 banner + 右下角进度悬浮（停止/重新开始/仅重试失败），`rebuildActive` 禁用向量默认操作。
- 5.8 集成测试：停止/维度变化全量/重试失败/重启孤儿自愈。
- 5.9 V7 迁移新增 `failed_scopes`/`stop_requested`/`mode` 列 + `interrupted`；仓储同步读写（mysql+postgresql）。
- 5.11 后端锁定：`setDefault`(向量)/`update`(默认向量) 在 `isRebuildActive()` 时 4xx 拒绝。

### Group 6 统一探测失败文案 <!-- comet-task-ref:24909cd8-0189-4853-a4f0-aff599b1c5d6 --> <!-- comet-task-ref:d7866968-76e7-40af-92d6-02d958888be9 -->
`probeModel` 异常分支改为统一友好文案（复用 `messageResolver` 现有 key），不再透传原始异常；单测验证返回可读文案。

### Group 7 规范漂移清理与回归 <!-- comet-task-ref:e58c0e2a-83d5-402f-ba46-8ea2d918f146 --> <!-- comet-task-ref:43b216d3-817e-4218-9316-b389cf5cdfaa -->
将 spec 中 `valid` 语义统一为 `enable`；全量回归保持 askdata-server 测试套件通过（基线 1124）。

## 执行顺序（依赖）

1. V7 迁移 + 仓储读写（5.9）→ 先于服务层。
2. Group 1 默认唯一（1.1,1.2）→ 可最先。
3. Group 2 维度冲突（2.1–2.3）→ 依赖 `EmbeddingService.storeDimensionConflict`。
4. Group 3 放宽探测（3.1–3.3）→ 依赖 `update`。
5. Group 4 维度比对（4.1–4.2）→ 依赖 `probeModel` 签名变更。
6. Group 5 重建可控性（5.1–5.11）→ 依赖 V7 迁移 + `isRebuildActive`。
7. Group 6 统一文案（6.1,6.2）→ 依赖 `probeModel`。
8. Group 7 规范清理 + 回归（7.1,7.2）→ 最后跑全量测试。

## 关键接口签名（跨任务约定）

- `EmbeddingIndexService.isRebuildActive()` → `boolean`
- `EmbeddingIndexService.rebuild(String mode)` → 兼容无参（默认 full）
- `EmbeddingIndexService.stop()` → 置标志 + `requestStop()`
- `EmbeddingIndexService.selfHealOnStartup()` → `ApplicationRunner.run()` 调用
- `AiEmbeddingIndexStateRepository.markInterrupted(int done, int failed, String failedScopes, String message)`
- `AiEmbeddingIndexStateRepository.requestStop()`
- `AiModelService.connectionChanged(Model existing, ModelUpdateBody body)` → `boolean`
- `AiModelService.probeModel(int modelType, Integer dimension, ...)` → 维度比对
