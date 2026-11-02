# Proposal

## Why

当前「AI 模型配置」实质上只支持一种模型类型：前端模型类型下拉始终禁用，`model_type` 只有对话模型被真正使用，向量模型只能靠预置数据存在。同时字段顺序不合理（先选模型、后填连接信息），模型名只能手输，连接信息是否可用要到保存后才知道，配置错误只能通过问数失败来发现。

随着向量召回链路已具备真实消费方（术语、训练、表、数据源四类召回），需要让管理员能够自行配置向量模型；也需要在保存前确认模型端点真的可用，而不是把错误配置直接落到生产。

## What Changes

**配置流程**

- 保持「选提供商 → 配置模型」两步制，重排字段顺序为：模型名称 → Base URL → API Key → 模型类型 → 基础模型 → 是否生效 → 高级配置。
- 模型名称与基础模型完全解耦，选中模型不再改写名称。
- 「API 域名」文案改为「Base URL」，允许携带路径，校验只拦截空值与非 http(s)。
- 高级配置收敛为单一折叠区，内含上游格式、完整 URL 开关、模型参数；已有参数时自动展开，参数区标题显示数量。

**模型类型**

- 开放 LLM(0) 与向量(1) 两类，Rerank(2) 本期不开放。
- 编辑态模型类型只读；类型按上游格式能力置灰。
- 每类型最多一个默认模型；默认模型必须同时 `enable=1` 且 `valid=1`，不可停用、不可删除，可切换到同类型另一个可用模型。
- 会话列表（工作空间授权、MCP 模型列表）只返回 LLM。

**向量模型**

- 向量能力 MUST 只使用在线向量模型；删除本地 ONNX 模型（`LocalEmbeddingModel`、`BertWordPieceTokenizer`）、`onnxruntime` 依赖、`askdata.embedding.model-path` 配置与 793MB 模型目录约定。
- 无可用在线向量模型时，网页会话、MCP 对话、定时任务统一阻断并返回同一错误码，不再降级为词法召回。
- 模型配置页展示「未配置可用的在线向量模型」提醒，并可跳转到新增向量模型。

**向量索引重建**

- 默认向量模型是全局配置，而向量按工作空间存储，切换默认向量模型后 MUST 对**全部工作空间**的术语、SQL 示例、表、数据源四类向量全量重算。
- 切换前弹确认框说明影响范围；切换后模型页常驻「向量索引待重建」提示，状态持久化，刷新、重登、重启后仍可见。
- 提供「一键重新向量」：全量强制重算（不只补空），按空间分批、单空间失败不中断、展示进度与按空间汇总结果。
- 新增 `ai_embedding_index_state` 状态表，记录上次重建的模型、维度、进度与结果，用于判定是否待重建。


**可用性（参照平台对接模块）**

- `ai_model` 新增 `enable` 与 `valid` 两列：`valid` 只由连通测试回写，`enable` 仅在 `valid=1` 时可手动开启。
- 保存不重置 `valid`，与 `sys_authentication` 的现有做法一致。
- 连通测试按模型类型走不同端点：LLM 走对话补全、向量走 embeddings。
- 测试失败允许保存为不可用模型。

**模型列表获取**

- 新增模型列表接口：服务端代理请求上游 `/models`，按类型打标签，前端按所选类型过滤。
- 请求地址由 Base URL 规范化得到；完整 URL 开启时先剥离 `/chat/completions`、`/responses`、`/messages` 再拼 `/models`，无法剥离时禁用拉取。
- 拉取失败回退供应商静态清单；自定义提供商无静态兜底，仅允许手输。

**上游格式**

- 新增上游格式：自动识别 / Chat Completions / Anthropic Messages / Responses / Azure OpenAI。
- 自动识别规则：`*.openai.azure.com|cn` → Azure；路径含 `/anthropic` 或 `api.anthropic.com` → Anthropic Messages；其余 → Chat Completions。
- 依据格式决定请求 URL、鉴权头、请求体与流式事件解析。

**提供商**

- `id=15` 改名为「自定义」，清空默认 Base URL，移除静态模型预设。
- `id=15` 换用独立插头图标，不再复用 OpenAI 图标；新增 `supplier.custom` i18n key 并补齐五份语言文件。
- 界面术语「供应商」统一为「提供商」，仅改展示文案，代码标识不变。

**BREAKING**

- `ai_model.default_model` 语义从「全局唯一」改为「按模型类型唯一」，默认模型查询、工作空间列表与 MCP 列表的行为随之变化。

## Capabilities

### New Capabilities

- `ai-model-configuration`：模型配置的字段契约、类型能力矩阵、默认模型归属、启用与可用状态的生命周期。
- `ai-model-connectivity`：连通校验、模型列表获取、上游格式识别与 URL 规范化。

### Modified Capabilities

无。仓库此前没有 OpenSpec 能力清单，本次为首批能力。

## Impact

**前端**

- `web/src/views/system/model/ModelForm.vue`、`Model.vue`
- `web/src/entity/supplier.ts`、`web/src/api/system.ts`
- `web/src/i18n/{zh-CN,zh-TW,en,ko-KR,th-TH}.json`

**后端**

- `system/controller/AiModelController`、`system/service/AiModelService`、`system/repository/AiModelRepository`
- `chat/service/ChatModelClient`、`ChatModelRouter`、`ChatQuestionService`、`ChatKnowledgeService`
- `permission/PermissionEnforcer`、`scheduled/ScheduledTaskExecutor`（`generate` 调用点）
- `ai/EmbeddingService`、`ai/vector/DbVectorStore`、`datasource/embedding/TableEmbeddingService`、`chat/service/SchemaContextBuilder`（在线向量与重建）
- **删除**：`ai/LocalEmbeddingModel`、`ai/BertWordPieceTokenizer`、`pom.xml` 的 `onnxruntime` 依赖、`application.yaml` 的 `askdata.embedding.model-path`
- `common/startup/EmbeddingBackfillRunner`（重建入口复用）
- `mcp/AskdataMcpTools`、`mcp/SqlbotMcpCompatController`（模型列表口径）
- `resources/messages*.properties` 六份后端消息文件（新增错误与提示文案）

**前端（模型页之外）**

- `web/src/views/system/workspace/*`（模型授权列表）
- `web/src/views/system/embedded/iframe.vue`（嵌入式模型下拉）
- `web/src/views/chat/index.vue`、`ChatListContainer.vue`、`web/src/views/ds/Datasource.vue`（`checkLLMModel` 语义）
- `web/src/entity/CommonEntity.ts`（类型选项与格式能力）

**数据库**

- `ai_model` 增加 `enable`、`valid`、`upstream_format` 列，三套方言各一个迁移；存量行回填 `enable=1`、`valid=1`、`upstream_format=1`。

**测试**

- 后端单测：URL 规范化、请求体构造、SSE 解析、类型默认与唯一性、过滤规则。
- e2e：`askdata/askdata-server/e2e/tests/aimodel.spec.ts` 需按新流程重写。

**约束**

- 不自动执行 git commit / push，改动保留在本地工作区。
- **功能对等不可打折**：以 `/Users/xiongxiao/Workspace/Idea/codes/SQLBot` 为参考实现，接口、表单交互、参数缓存、运行时行为逐条对齐，清单见 `reference-parity.md`；除其中第 4 节列明的 7 项有意偏离（D1–D7）外，任何差异按缺陷处理。
