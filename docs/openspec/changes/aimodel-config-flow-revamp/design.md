---
comet_change: aimodel-config-flow-revamp
role: technical-design
canonical_spec: openspec
---

# Design

## Context

见 `proposal.md` 的 Why。当前实现有几个事实约束了方案：

- `ai_model` 表已有 `model_type`、`protocol`、`supplier`、`config`、`default_model`，但 `model_type` 实际只被 `ChatModelRouter.embeddingModel()` 一处消费，前端类型下拉恒为 `disabled`。
- `config` 列是 `[{key,name,val}]` 数组，`ChatModelRouter.paramsOf()` 会把它整包平铺进请求体，因此不能复用该列存放开关类元数据。
- `AiModelRepository.findDefault()`、`clearDefaults()`、`AiModelService.create()` 的 `count()==0` 均为全局语义，没有类型维度。
- `AiModelService.streamStatus()` 固定走对话补全端点，且支持从请求体读取 `api_domain` / `api_key`，可以不落库直接测试。
- `ChatModelClient.generate()` 目前不带格式参数，流式方法 `streamCompletion()` 已带 `protocol`。
- 平台对接模块 `sys_authentication` 已有 `enable` + `valid` 双状态先例，`valid` 只由独立校验接口回写，保存时沿用库值。

## Goals / Non-Goals

**Goals**

- 让 LLM 与向量模型在同一张表内共存，且各类型默认唯一、互不干扰。
- 让「配置是否可用」与「是否启用」成为两个独立、可观测的状态。
- 让保存前就能确认端点可用，而不是保存后靠问数失败发现。
- 让同一套 Base URL 能覆盖 OpenAI 兼容、Anthropic Messages、Responses、Azure 四种上游。

**Non-Goals**

- 不引入独立 provider 实体，不新增提供商管理页。
- 不做 Rerank 类型的运行时消费（本期不开放该类型）。
- 不改动向量存储实现，只增加触发既有回填的入口。
- 不做 token 级上下文裁剪。

## Decisions

### 1. 新增 `enable` / `valid` 两列，而非单一 `enabled`

`valid` 表示配置是否通过校验，`enable` 表示是否实际启用，与 `sys_authentication` 保持一致。

- `valid` 只由连通校验回写；保存配置时沿用库中值，不因保存动作重置。
- `enable` 仅当 `valid=1` 时可写；接口侧同样校验，不能只靠前端置灰。
- 删除 `valid` 通过校验后的隐式启用：测试通过不等于启用，仍需用户手动开启。

备选方案是单一 `enabled` + 前端内存态测试结果，但那样测试结论无法持久化，刷新页面后「是否测过」就丢失，也无法在列表上展示「不可用」。

### 2. 新增 `upstream_format` 列，不复用 `protocol`

现有 `protocol` 承载的是供应商协议线索（vLLM 写入 2），语义与「上游报文格式」不同。复用会让存量行被误判。

新列取值：`1` Chat Completions、`2` Anthropic Messages、`3` Responses、`4` Azure OpenAI；`0` 表示自动识别（默认）。

### 3. 类型维度在仓储层收口，而非逐个调用点过滤

`AiModelRepository` 改为只暴露带类型参数的查询：`findDefault(int)`、`clearDefaults(int)`、`countByType(int)`、`listByWorkspace(long, int)`。删除无类型版本，编译期强制调用方明确类型。

`ChatModelRouter` 只暴露 `chatModel()` 与 `embeddingModel()` 两个入口，内部按 `model_type + enable + valid` 过滤；调用方不得再直接读仓储列表。

设默认时只清除同类型标记，`default_model` 的唯一性由类型维度保证。

### 4. 上游格式判定收敛为单一解析器

新增 `UpstreamFormatResolver`，输入 `(baseUrl, upstreamFormat, fullUrl)`，输出统一格式枚举。三处消费者共用：模型列表获取、连通校验、`ChatModelClient` 运行时请求。

若判定逻辑分散在三处，只要一处不同步，就会出现「校验通过、问数失败」且难以定位。解析器同时承担 URL 规范化，避免出现两套路径拼接规则。

### 5. 报文适配放在 `ChatModelClient` 内部，按格式分支

- Chat Completions：现有实现，`Authorization: Bearer`。
- Anthropic Messages：`{base}/v1/messages`，`x-api-key` + `anthropic-version`，`system` 消息提升为顶层字段，`messages` 仅保留 user/assistant，`max_tokens` 必填（默认 4096，可由模型参数覆盖），剔除 `stream_options`。流式解析增加 `message_start` / `content_block_delta` / `message_delta` / `message_stop` 分支，usage 取 `input_tokens` / `output_tokens`。
- Responses：`{base}/responses`，请求体使用 `input[]` 结构，流式解析 `response.output_text.delta` 与 `response.completed`。
- Azure OpenAI：`{base}/openai/deployments/{deployment}/chat/completions?api-version=`，`api-key` 头，报文沿用 Chat Completions。

`generate()` 增加格式参数，调用点共 5 处（`ChatKnowledgeService`、`ChatQuestionService` ×2、`PermissionEnforcer`、`ScheduledTaskExecutor`），统一从 `ModelRef` 取格式，不新增取参路径。

### 6. 连通校验按类型分支并回写 `valid`

`streamStatus()` 增加类型分支：LLM 走对话补全，向量走 `/embeddings`。响应保持 NDJSON 流式，末尾附带结果状态。

请求体携带 `id` 时校验成功回写 `valid=1`，失败回写 `valid=0`；不带 `id`（新建未保存）时只在响应中返回结论，不落库。

### 7. 模型列表接口独立于连通校验

新增 `POST /api/v1/system/aimodel/models`，入参与校验接口同形（Base URL、Key、格式、完整 URL 开关），返回 `{source, models[], error?}`。

- `source` 取 `remote` / `preset` / `none`；`none` 表示自定义提供商无兜底。
- 服务端限制返回条数上限并只做一次规范化请求，不做 404 路径探测。
- 失败统一降级为可读错误，不抛 5xx，不记录 Key。

### 8. 默认模型缺失时回退到首个可用模型

新建不自动设默认（保持用户选择），但 `chatModel()` 与 `embeddingModel()` 在无默认时回退到该类型首个 `valid=1 && enable=1` 的模型。这样既不违背「不自动设默认」，也不会让首次配置后无模型可用。

### 9. 向量重建：全空间、强制重算、可持久化的待重建状态

默认向量模型是**全局**配置，而向量是**按工作空间**存储的（`terminology.oid`、`data_training.oid`、`core_datasource.oid`、`core_table.ds_id → 数据源 oid`）。因此换模型不是「当前用户的事」，必须全空间重算。

**状态持久化**

新增 `ai_embedding_index_state` 单行状态表：

| 列 | 用途 |
| --- | --- |
| `id` | 固定为 1（单例） |
| `model_id` | 上次成功重建使用的向量模型 id |
| `dimension` | 上次成功重建的向量维度（由校验接口返回的维度写入） |
| `status` | `idle` / `running` / `failed` |
| `total` / `done` / `failed` | 全空间进度计数 |
| `current_scope` | 当前正在处理的工作空间 |
| `started_at` / `finished_at` | 时间戳 |
| `message` | 最近一次失败摘要 |

「待重建」判定：`ai_model` 中当前默认向量模型 id ≠ `state.model_id`，或 `state.status = failed`。判定结果由 `GET /system/aimodel/embedding/status` 返回，前端据此常驻展示提示，刷新与重启后仍然有效。

**强制重算**

`EmbeddingBackfillRunner` 的现有四类回填都带 `embedding IS NULL` 过滤，只能补空。重建必须新增 force 模式：忽略 null 判定，按 id 全量重算并覆盖，避免「新模型维度 + 旧向量」混存。

**全空间执行**

1. 从 `terminology`、`data_training`、`core_datasource` 取 distinct `oid`，与 `sys_workspace` 求并集得到空间清单。
2. 逐空间执行四类重算；单空间异常记录后继续下一个，不中断。
3. 每完成一条更新 `done`，供前端轮询进度。
4. 全部完成后写入 `model_id`、`dimension`、`finished_at`，`status` 置 `idle`；任一空间失败则 `status=failed` 并保留可重试入口。

**维度变化**

维度取自连通校验返回的 `data[0].embedding` 长度。与 `state.dimension` 不同时，先按新维度重建向量集合（`DbVectorStore.ensureCollection` + 清理旧维度数据），再写入向量。

**接口**

- `GET /api/v1/system/aimodel/embedding/status`：返回是否待重建、当前任务进度、上次结果。
- `POST /api/v1/system/aimodel/embedding/rebuild`：启动任务，已在运行则返回「任务进行中」。
- 两者均需管理员权限，并纳入审计。

**前端**

切换默认向量模型时弹确认框（说明影响所有空间），切换成功后在模型页顶部展示常驻提示条：`向量索引待重建` + 「一键重新向量」按钮 + 进度条；完成后提示条消失并展示汇总结果。

### 10. 前端状态集中在表单组件

`ModelForm.vue` 持有一份显式状态：拉取状态（idle/loading/success/failed）、来源、测试状态（untested/testing/success/failed）。拉取使用请求序号丢弃过期响应；配置类字段变更时清空拉取结果，`valid` 由后端返回后更新。

名称字段不参与任何联动，避免「换模型改名」的隐式行为。

### 11. 「自定义」提供商使用独立图标与独立 i18n key

现状是 `id=15` 复用了 `icon_common_openai.png`，视觉上与 OpenAI 无法区分，且语义已从「通用 OpenAI」变为「自定义」。

- 新增 `icon_custom_plug.svg`（插头造型，32px 画布，与现有彩色图标同一视觉重量），在 `supplier.ts` 中替换引用。选择 SVG 而非 PNG：可随主题缩放不糊，且新增资源不需要二进制文件评审。
- i18n 新增 `supplier.custom`，`supplier.generic_openai` 保留不删，避免历史引用断链。
- 五份语言必须同时补齐，`supplier.custom` 取值：`自定义` / `自訂` / `Custom` / `사용자 지정` / `กำหนดเอง`。

### 12. 相邻模块必须同步适配，不能只改模型页

本次改动会改变「模型可用性」的判定口径，所有消费模型配置的模块都会受影响，必须一并调整，否则会出现「页面能配、链路取不到」：

**后端**

- `ChatModelRouter.defaultChatModel()` / `chatModelById()`：改为按类型取默认 + 回退，并对 `enable`/`valid` 过滤；`chatModelById()` 拒绝非 LLM、未启用或未通过校验的模型。
- `ChatModelClient`：新增格式分派（见决策 5），是唯一发请求的位置。
- `ChatKnowledgeService`、`ChatQuestionService`×2、`PermissionEnforcer`、`ScheduledTaskExecutor`：只经 `ChatModelRouter` 取模型，且必须处理「无可用模型」，返回可读错误而非空指针。
- `EmbeddingService`、`TableEmbeddingService`、`SchemaContextBuilder`：改为强制依赖在线向量模型，无可用模型时阻断问答并返回统一错误（见决策 14），不再回落本地或词法。
- `EmbeddingBackfillRunner`：重建入口复用其四类回填与 `SingleWorkerGuard`；`DbVectorStore.ensureCollection(dimension)` 在维度变化时必须重建集合而不是追加。
- `AskdataMcpTools.model_list` / `mcp_model_list`、`SqlbotMcpCompatController`：列表口径变为「LLM + 启用 + 可用」，响应字段保持 SQLBot 兼容，不新增必填字段。
- `CryptoUtil`：`api_domain`/`api_key` 仍按既有规则加解密，新列（`enable`/`valid`/`upstream_format`）不得走加密。
- 后端消息 `messages*.properties`（6 份，含无后缀基线）：新增错误与提示文案必须全部补齐，缺失时前端只能显示 key。

**前端**

- `views/system/workspace/AuthorizedWorkspaceDialogForModel*.vue`、`AuthorizedWorkspaceDraw.vue`：模型授权列表按新口径展示，向量模型不可见。
- `views/system/embedded/iframe.vue`：`list_by_ws` 结果变化后，模型下拉仍可用，且默认模型缺失时不报错。
- `views/chat/index.vue`、`views/chat/ChatListContainer.vue`、`views/ds/Datasource.vue`：`checkLLMModel` 语义改为「存在可用 LLM」，错误文案需区分「未配置」与「已配置但不可用」。
- `entity/CommonEntity.ts`：`modelTypeOptions` 补齐 LLM/向量，并承载格式能力矩阵。
- `entity/supplier.ts`：`base_model_options()` 在自定义提供商下返回空数组（无静态兜底），调用方需容忍空值。

**文档与测试资产**

- `askdata/askdata-server/README.md`、`e2e/README.md` 同步新流程与新用例。
- `e2e/tests/aimodel.spec.ts` 重写；`e2e/tests/{routes,api,chat-real}.spec.ts` 需回归；`e2e/discover-aimodel.mjs` 中硬编码的「选择供应商」「API 域名」等选择器需更新。

### 13. 以 SQLBot 为参考实现，功能对等不可打折

参考实现位于 `/Users/xiongxiao/Workspace/Idea/codes/SQLBot`，其模型模块（`backend/apps/system/api/aimodel.py`、`apps/ai_model/model_factory.py`、`frontend/src/views/system/model/*`）是本项目的行为基线。

逐条对照清单见 `reference-parity.md`，分四类：后端接口面 14 条、运行时 6 条、前端表单 13 条、有意偏离 6 条。

执行约束：

- 仅允许 `reference-parity.md` 第 4 节列出的 7 项偏离（D1–D7）；新增偏离必须回到本文件补登记并经确认。
- 改造采用叠加方式：Chat Completions 路径不改代码结构，只加格式分派分支。
- 校验发现的其他行为差异一律按缺陷处理，不按「优化」处理。
- 已发现的存量差异需在本次一并修复：`ModelForm.vue` 的 `handleParamsDel` 缺少 `tempConfigMap` 回写（SQLBot 有、本项目无），会导致删除参数后再切换模型丢失缓存。

### 14. 移除本地向量模型，问答强制依赖在线向量模型

现状（改造前）：`EmbeddingService` 是「远程优先 → 失败回落本地 ONNX」。`embed`/`embedAll`/`available()` 三处都有本地分支，`pom.xml` 引入 `com.microsoft.onnxruntime:onnxruntime`，`application.yaml` 配 `askdata.embedding.model-path: /opt/askdata/models`，构建产物附带 793MB 权重。

改造后：

- `EmbeddingService` 只保留远程分支；远程不可用时返回 `null`，**不再回落本地**。
- 删除 `LocalEmbeddingModel`、`BertWordPieceTokenizer`、ONNX 依赖、`askdata.embedding.model-path` 配置与相关文档；测试改用 stub 向量源。
- `available()` 只反映「存在可用在线向量模型（`enable=1 && valid=1`）」。
- 问答入口统一前置校验：无可用在线向量模型时阻断，返回统一错误码，覆盖网页会话、MCP 对话、定时任务三个入口。
- 模型配置页展示「未配置可用的在线向量模型」提醒条，点击跳转新增向量模型。

影响与取舍：

- 首次部署必须先配向量模型才能问数，这是**有意的可用性收紧**，需在部署文档写明。
- SQLBot 有本地 ONNX 回落，本项属有意偏离，登记为 `reference-parity.md` 的 D7。
- 与向量重建联动：本地模型产出的 768 维存量向量在新模型下失效，升级后 `state.model_id` 为空会提示重建，正好覆盖这次切换。

### 15. 维度是一等属性，驱动向量存储对齐

现状问题（已核实，不是推测）：

- `askdata.vector-store` 只有 `type` / `collection-prefix`，**没有任何维度配置**。
- `VectorStore.ensureCollection(collection, dimension)` 是唯一的维度入口，但 `DbVectorStore` 的实现是空方法。
- `DbVectorStore.query()` 第 104 行有 `stored.length != vector.length` 的跳过逻辑——不会崩，但会**静默漏召回**，且不计数、不告警。
- `ai_model` 没有维度列，`ai_embedding_index_state` 也不存在，因此「当前索引是什么维度」无处可查。
- 唯一硬编码维度是 `LocalEmbeddingModel.EMBEDDING_DIMENSION = 768`，随本地模型一起删除。

存储宽度也是硬约束：H2 的 `terminology.embedding` / `data_training.embedding` 是 `VARCHAR(10000)`，而 1024 维向量的 JSON 文本约 14k–20k 字符。`EmbeddingService.writeEmbedding` 因此设置了 `MIRROR_MAX_CHARS = 9000`，超限直接跳过写入。也就是说：切到 1024 维模型后，DB 后端根本存不下。

设计：

**维度落点**

- `ai_model` 新增 `dimension INT NULL`；连通校验成功时写入 `data[0].embedding` 长度，高级配置允许手动覆盖。
- `ai_embedding_index_state` 记录上次成功重建的 `model_id` 与 `dimension`。

**校验矩阵（三处硬校验）**

| 校验点 | 失败表现 | 实现位置 |
| --- | --- | --- |
| 启用 `enable` | 拒绝开启，提示「请先完成连通校验」 | `AiModelService.update` |
| 设为默认 | 拒绝，提示「向量索引未对齐，请先重建」 | `AiModelService.setDefault` |
| 问答入口 | 阻断问答，返回「向量索引待重建」 | 问答前置校验 |
| 运行期返回长度 | 判定调用失败，不写存储 | `EmbeddingService.embedRemote` |
| 存储写入长度 | 拒绝写入并记日志 | `DbVectorStore.upsert` |

**存储对齐**

- 迁移脚本把 H2 的两处 `VARCHAR(10000)` 改为 `CLOB`，PostgreSQL 保持 `TEXT`，MySQL 改 `MEDIUMTEXT`，消除维度上限。
- `DbVectorStore.query()` 保留长度跳过逻辑，但新增不匹配计数，由状态接口暴露。
- 重建开始前把四类目标记录的向量列置 `NULL`，避免旧维度残留。
- `EmbeddingService` 写入前显式校验 `vector.length == dimension`；`MIRROR_MAX_CHARS` 随之取消。

**前端**

- 表单在「模型类型 = 向量」时于高级配置展示「向量维度」，默认只读展示探测值，提供「手动指定」开关。
- 切换默认向量模型时，确认框展示「旧维度 → 新维度」。

## 端到端验证（Playwright）

复用 `askdata/askdata-server/e2e` 现有基线，不新建工程。

**运行方式**

1. 后端：`mvn -pl askdata/askdata-server spring-boot:run`，H2 文件库，端口 8000，种子账号 `admin / SQLBot@123456`。
2. e2e：`npx playwright test tests/aimodel.spec.ts`，配置沿用 `baseURL http://127.0.0.1:8000`、`channel: chrome`、`workers: 1`。

**上游 stub**

不依赖真实 Key。在 e2e 进程内起一个本地 stub provider，由用例通过 Base URL 指向它：

- `GET /v1/models`：可切换返回 200（固定模型清单）/ 401 / 404 / 空列表。
- `POST /v1/chat/completions`：可切换 200 / 401；SSE 模式返回固定的 `data:` 帧序列。
- `POST /v1/embeddings`：可切换 200（固定维度向量）/ 404（模拟非向量端点）。
- `POST /v1/messages`、`/v1/responses`：返回固定报文，用于验证格式分支与流式解析。

stub 只监听 `127.0.0.1` 随机端口，用例结束即关闭，避免端口冲突。

**用例清单**

| 用例 | 断言 |
| --- | --- |
| 新增 LLM 模型全流程 | 字段顺序正确；名称不随基础模型改写；保存后卡片显示「可用/已启用」 |
| 模型列表拉取 | stub 200 时出现「远程」标签并按类型过滤；404 时降级为「预设」 |
| 自定义提供商 | 无静态预设；拉取失败提示手动输入；图标与 OpenAI 可区分 |
| 连通校验失败 | stub 401 → 保存成功但状态为不可用，开关置灰 |
| 连通校验成功 | stub 200 → 状态可用，手动开启「是否生效」后卡片显示已启用 |
| 向量模型校验 | 断言请求打到 `/v1/embeddings`，而非 `/v1/chat/completions` |
| 上游格式切换 | 选 Anthropic Messages 后向量类型置灰；请求打到 `/v1/messages` |
| 默认模型保护 | 默认模型停用按钮禁用；设默认需先满足可用+已启用 |
| 编辑态类型只读 | 打开已有模型，类型控件不可编辑 |
| 五语言切换 | 「自定义」在五种语言下均有译文 |

**已知边界**：Anthropic 与 Responses 只用 stub 验证报文与流式解析，未在真实网关验证，需在交付说明中注明。

## Risks / Trade-offs

- **`default_model` 语义变更影响面大** → 迁移只回填新列，不重算默认标记；存量唯一默认自然成为 LLM 默认，其他类型无默认时走回退逻辑。回归覆盖默认模型弹层、工作空间授权、MCP 列表。
- **`enabled`/`valid` 回填错误会让全站无模型** → 迁移显式 `DEFAULT 1` 并回填存量行，同时补一条断言「迁移后可用模型数不减少」的检查。
- **`ChatModelClient` 改动触及问数主链路** → 格式分支按策略分发，Chat Completions 路径保持原有代码不动；对 Anthropic/Responses 只做新增分支。回归覆盖问数、图表、权限判定、定时任务。
- **Anthropic / Responses 无真实端点可验证** → 单测使用本地 stub server 固定 SSE 序列；同时把「无实际验证」写进验收说明，避免误判为已验证。
- **向量维度变化导致召回异常** → 切换默认时强制提示，回填入口幂等且可重复触发。
- **`config` 列不能承载新开关** → 通过新增独立列规避，`paramsOf()` 不做任何解析扩展。

### 16. 向量存储只允许在线 Qdrant（追加决定）

改造中确认：DbVectorStore（DB embedding JSON 列 + 应用内余弦）与「本地向量模型」是同一类问题——
同一份向量出现两个真相，且列宽放不下 1024 维（见决策 15），切换模型时会静默漏召回。

- 删除 DbVectorStore 及其测试；当前唯一实现是 QdrantVectorStore（REST）。
- 保留 askdata.vector-store.type 作为可插拔入口（缺省 qdrant）：milvus/weaviate/pgvector/es/redis 为预留值，
  配置后启动期显式失败（「尚未实现；当前仅支持 qdrant」），不静默降级。
- 未配置 askdata.vector-store.qdrant.url 时装配占位实现：应用可启动，但任何向量读写都抛带指引的异常，绝不静默写本地。
- 写入不再镜像 DB 向量列；回填不再用 embedding IS NULL 判断缺向量（该列恒空），启动回填与重建都按业务范围全量重算。
- 测试不再引入内存向量库/桩实现：向量用例直接打真实 Qdrant（+ 真实 MySQL 元数据库 + 真实 bge-m3），
  依赖不可达时整类 assume-skip，绝不伪造通过。
- 向量模型保持全局唯一（findDefault(model_type=1) → 首个可用），不参与工作空间授权：向量集合与 ai_embedding_index_state 都是全局的，按空间用不同向量模型会把不同维度/语义的向量写进同一集合。工作空间授权只作用于 LLM。

### 17. 维度不一致不删集合；模型解析加缓存（追加决定）

**维度保护**：`ensureCollection` 原先在维度不同时直接 DELETE 集合，只有一行 `log.info`。一次误配
（改错 embedding 模型维度）就会在首次写入时清空整个集合，而 ensuredDimensions 只是进程内缓存，
重启后第一次写入即触发。

- `ensureCollection` 维度不同时改为抛错（log.error + 明确指引），集合与既有向量保持不动。
- 删除集合收敛为独立方法 `dropCollection`，只由显式重建（`EmbeddingIndexService.rebuild` →
  `EmbeddingService.resetCollections`）调用；重建本就全量重算，先删集合再回填既保证新维度生效，
  也顺带清理孤儿点。
- `EmbeddingService` 记录「集合 + 维度」已知损坏并短路后续写入，避免回填上千行时重复远程探测与刷屏；
  损坏状态只由重建清除。

**缓存**：`ChatModelRouter.embeddingModel()` 每次调用都查库（findDefault 未命中再 listByType），
回填 N 张表 = 2N 次查询且结果恒定。

- 解析结果缓存 60s（兜住绕过服务层直接改库的场景）。
- 失效以 `AiModelRepository.writeVersion()` 为准：任何经仓储的写操作（create / update / delete / setDefault /
  连通校验回写 valid、enable、dimension）都自增版本，缓存键失配即重新解析。这比「由服务层记得调 invalidate()」
  更可靠——绕过服务层直接经仓储写入（测试夹具、数据修复脚本）同样立即生效；60s TTL 只兜住绕过仓储直改库的极端场景。

**HTTP 客户端**：Qdrant 客户端由手写 `HttpURLConnection` 改为 Spring `RestTemplate`
（超时、错误流、JSON 编解码交给框架），4xx/5xx 仍按状态码返回给调用方以区分「集合不存在(404)」。

### 18. 移除 H2：元数据库与数据源类型都只留 MySQL / PostgreSQL（追加决定）

**问题**：H2 同时承担三个角色——测试元数据库、默认单实例元数据库、一种数据源类型。三者互相掩盖问题：

- 测试跑在 H2 上，MySQL 方言差异（反引号 vs 双引号、`ORDINAL_POSITION` 缺失导致字段顺序不稳定、
  `DATETIME` 经 Connector/J 返回 `LocalDateTime`）在 CI 里永远暴露不出来；
- 生产元数据库已锁定 MySQL 8+/PostgreSQL，H2 分支属于不可达代码，却仍在维护一套 baseline 脚本；
- `h2` 数据源类型让「开发期随手连本地文件库」成为可能，与产品只支持 MySQL/PG 的定位冲突。

**决定**：彻底移除 H2，不留「仅测试可用」的例外。

- 删除 `db/migration/h2/`、`H2MetadbMigrationTest`、`com.h2database:h2` 依赖。
- `MetadataDatabaseGuard` 明确拒绝 H2（内存与文件形态都拒绝），只放行 MySQL 8.0+/MariaDB 10.2+/PostgreSQL 10+；
  去掉 `askdata.metadata.allow-non-mysql` 开关——PostgreSQL 是正式支持项，不需要例外开关。
- 移除 `DatasourceType.H2` 及 `JdbcEngine` 的 H2 分支（url 拼接、PUBLIC 默认 schema、产品名兜底、标识符折叠）。
- 测试基建改为真实 MySQL：`MysqlTestSupport` 每个测试类建独立临时 schema（`askdata_it_*`），
  Spring 上下文测试用 `createEmptySchema()` + `@DynamicPropertySource` 注入连接、由应用自己的 Flyway 迁移
  （顺带真实覆盖 `MetadataDatabaseGuard`）；不可达时整类 assume-skip 并在报告里给出配置方式。

**连带修出的真实缺陷**（H2 掩盖，MySQL 才暴露）：

- `DatasourceType.EXCEL.previewSql` 固定用双引号：MySQL 把 `"x"` 当字符串字面量，excel 预览直接语法错误。
  改为 `JdbcEngine.previewSql(...)`：`usesAppDatabase()` 的类型按主库方言生成，其余类型保持自身方言。
- MySQL/PostgreSQL 的 `fieldSql` 没有 `ORDER BY`：字段顺序由数据库返回顺序决定（UI 字段顺序不稳定，
  测试按索引断言会随机失败）。补 `ORDER BY ORDINAL_POSITION` / `a.attnum`。
- `JdbcEngine.convertValue` 未处理 `java.time` 类型：Connector/J 8 默认把 `DATETIME` 映射为 `LocalDateTime`，
  输出退化成 `2026-01-15T10:30`，与 SQLBot 的 `yyyy-MM-dd HH:mm:ss` 契约不一致。补 `LocalDateTime`/`LocalDate`/`LocalTime`。
- 作用域过滤用 `',' || COALESCE(...) || ','` 拼串：`||` 在 MySQL 里是逻辑或，不是字符串拼接，
  整段退化成 `0`，`INSTR(0,0) > 0` 恒真——**专属术语与专属提示词因此对所有数据源可见**（越权召回）。
  7 处统一改为 `CONCAT(...)` + `LIKE CONCAT('%,', ?, ',%')`（MySQL/PG 通用）。
- `LIKE ? ESCAPE '\\'`：Java 里的 `'\\'` 落到 SQL 是 `ESCAPE '\'`，MySQL 把反斜杠当转义符，
  字符串字面量到行尾都没闭合，后续占位符被吞掉（`Parameter index out of range (9 > 8)`）。
  29 处去掉 `ESCAPE` 子句——MySQL 与 PostgreSQL 的 LIKE 默认转义符都是反斜杠，绑定参数也不需要再翻倍。
- 术语词法命中用 `INSTR(LOWER(?), LOWER(word))`：MySQL 有、PostgreSQL 没有。改为标准
  `POSITION(LOWER(word) IN LOWER(?))`，两种元数据库都能跑。
- 测试 SQL 里 `sql` 未加引号：`SQL` 是 MySQL 8 保留字（`INFORMATION_SCHEMA.KEYWORDS` 中 `RESERVED=1`），
  `SELECT error, sql FROM chat_record` 直接语法错误；测试侧统一加反引号（生产仓储本就用 `sqlColumn` 映射）。

## Migration Plan

1. 三套方言各新增 `V2__ai_model_states.sql`：为 `ai_model` 增加 `enable`、`valid`、`upstream_format`，全部 `NOT NULL DEFAULT`，存量行自动获得 `1/1/1`；同时创建 `ai_embedding_index_state` 并初始化 `id=1, status='idle'`，`model_id`/`dimension` 留空（表示待重建）。
2. 后端先发布仓储与路由改动，保证新列参与过滤；此阶段前端仍可正常保存，`valid` 沿用默认值。
3. 前端随后发布新表单、卡片状态与重建提示条。
4. 首次升级后因 `state.model_id` 为空，模型页会提示「向量索引待重建」一次，属预期行为；执行一次重建即消除。
5. 回滚策略：新列与新表保留、代码回退到旧版本即可，旧代码不读取新列/新表；不需要删除。
6. 全程不自动提交代码，改动保留在本地工作区，由用户决定提交时机。

## Open Questions

- Anthropic Messages 与 Responses 在真实网关上的字段差异（如 `max_tokens` 上限、usage 字段命名）需要在有可用端点时补充验证；当前以 stub 单测为准。
