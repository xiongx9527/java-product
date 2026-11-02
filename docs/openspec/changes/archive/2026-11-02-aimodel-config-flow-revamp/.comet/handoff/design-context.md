# Comet Design Handoff

- Change: aimodel-config-flow-revamp
- Phase: design
- Mode: compact
- Context hash: 9b9145afdc0707944b132d134c29a30ac04bda3bf8412c11c618be02b0b78197

Generated-by: comet-handoff.sh
Task hash policy: task-content-v1. Read tasks.md for live completion; excerpts are design-time context.

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## docs/openspec/changes/aimodel-config-flow-revamp/proposal.md

- Source: docs/openspec/changes/aimodel-config-flow-revamp/proposal.md
- Lines: 1-117
- SHA256: 5f10e79681d84cd4a91f680133872337bcdc4c77ca2ab30425921f16c92d3eb0

[TRUNCATED]

```md
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


```

Full source: docs/openspec/changes/aimodel-config-flow-revamp/proposal.md

## docs/openspec/changes/aimodel-config-flow-revamp/design.md

- Source: docs/openspec/changes/aimodel-config-flow-revamp/design.md
- Lines: 1-388
- SHA256: 6a5af9229a14a9df62038f905ff27d97f455bc67a765324409a840487f4f7bd7

[TRUNCATED]

```md
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


```

Full source: docs/openspec/changes/aimodel-config-flow-revamp/design.md

## docs/openspec/changes/aimodel-config-flow-revamp/tasks.md

- Source: docs/openspec/changes/aimodel-config-flow-revamp/tasks.md
- Lines: 1-168
- SHA256: 5c635dd1281d59cdf0f6c8228acaab4a456d3fbcc1938c74d2bc20326a00a590

[TRUNCATED]

```md
# Tasks

## 0. 参考实现对等基线

- [x] 0.1 按 `reference-parity.md` 第 1 节逐条核对后端接口面（B1–B14），记录当前实现状态与本轮处置；验证：14 条全部有明确结论，无「暂不实现」 <!-- comet-task:fe4f34f5-db85-4442-a73f-ea7ab2525004 -->
- [x] 0.2 核对第 2 节运行时行为（R1–R6），确认 Chat Completions、vLLM、Azure、参数平铺四条链路均保留；验证：单测或代码走查记录存在性 <!-- comet-task:c8f50c5f-62ea-4be2-8b05-501b72879305 -->
- [x] 0.3 核对第 3 节前端表单行为（F1–F13），标记已有与缺失；验证：13 条全部有结论 <!-- comet-task:4d18d6f3-1bf5-48f3-8e4d-f2cb8453fe67 -->
- [x] 0.4 修复已发现的存量差异：`ModelForm.vue` 的 `handleParamsDel` 删除参数后未回写 `tempConfigMap`（SQLBot 第 84 行有）；验证：删除参数→切模型→切回，参数仍为删除后的状态 <!-- comet-task:190c398b-b3e0-4249-8d70-9dbce6659a75 -->
- [x] 0.5 把第 4 节 7 项有意偏离（D1–D7）逐条落到实现与测试，确保偏离是显式的而非遗漏；验证：每项偏离在单测或 e2e 中有对应用例 <!-- comet-task:792903db-3f9f-441c-8bd2-a873e9ab7f39 -->

## 1. 数据库迁移

- [x] 1.1 在 `src/main/resources/db/migration/h2`、`mysql`、`postgresql` 各新增 `V2__ai_model_states.sql`，为 `ai_model` 增加 `enable`、`valid`、`upstream_format` 三列，均 `NOT NULL`，默认值分别为 `1`、`1`、`0`；验证：三份脚本语法一致，字段名与类型在各方言下均合法 <!-- comet-task:797dfdd3-c66a-4684-8f0b-f9ac5235d9db -->
- [x] 1.2 迁移脚本对存量行显式回填 `enable=1`、`valid=1`、`upstream_format=1`；验证：在 H2 上用存量数据执行迁移后 `SELECT COUNT(*) FROM ai_model WHERE enable=1 AND valid=1` 等于迁移前的总行数 <!-- comet-task:be4e0532-d688-44c9-b301-18679d27b449 -->
- [x] 1.3 启动应用验证迁移幂等：二次启动不报错、不回滚已有 `valid=0` 状态；验证：手动置一条 `valid=0` 后重启，该行仍为 `0` <!-- comet-task:4e8b86a6-62b5-40cf-9973-953950fe1dd6 -->

## 2. 仓储与类型维度

- [x] 2.1 `AiModelRepository` 新增 `findDefault(int type)`、`listByType(int type)`、`countByType(int type)`，`clearDefaults(int type)` 改为按类型清除；验证：单测覆盖「清除 LLM 默认不影响向量默认」 <!-- comet-task:f13b20c2-15d9-437d-8a74-88c8ed836829 -->
- [x] 2.2 删除无类型版本的 `findDefault()`、`clearDefaults()`、`count()`，编译期暴露所有调用方；验证：`mvn -pl askdata/askdata-server compile` 通过且无遗留调用 <!-- comet-task:4e8a730a-736c-4c37-92e8-e6f3df94d1dc -->
- [x] 2.3 `listByWorkspace(long, int type)` 增加类型过滤，工作空间授权与 MCP 模型列表只传 LLM 类型；验证：单测断言向量模型不出现在返回列表中 <!-- comet-task:20519acd-2926-4711-a41f-70884244a6c4 -->
- [x] 2.4 仓储写入支持 `enable`、`valid`、`upstream_format` 三列，读取时映射到实体；验证：单测往返读写三次字段值不变 <!-- comet-task:3ab9d74e-41e9-4024-aab4-e3b11fa6563d -->
- [x] 2.5 `CryptoUtil` 加解密范围保持只覆盖 `api_domain`/`api_key`，新列明文存储；验证：单测断言新列读写不走加解密、`api_domain` 含多级路径时可原样往返 <!-- comet-task:90ee6774-87e6-4b52-9d9e-c64db46a718c -->

## 3. 默认模型与启停保护

- [x] 3.1 移除 `AiModelService.create()` 中 `count()==0` 自动设默认的逻辑；验证：新建首个模型后 `default_model` 仍为 0 <!-- comet-task:85b954d8-38cc-4502-8d6d-236bb561a923 -->
- [x] 3.2 设默认改为按类型清除，并在设默认前校验目标模型 `valid=1 && enable=1`，否则返回明确错误；验证：单测覆盖四种组合（可用+启用通过，其余三种被拒） <!-- comet-task:5b75ea90-25e8-42be-b237-7bc630255825 -->
- [x] 3.3 停用、删除默认模型时返回拒绝对应错误码，接口层不可绕过；验证：单测直接调用 service 与 controller 各一次，均被拒 <!-- comet-task:d6674b9e-ad79-4da9-ad54-368373cd0b4e -->
- [x] 3.4 `ChatModelRouter.chatModel()`、`embeddingModel()` 在无默认时回退到该类型首个 `valid=1 && enable=1` 模型；验证：单测构造「无默认 + 一个可用模型」断言选中该模型 <!-- comet-task:1bb6d6ce-5130-46d6-8ea8-7fcf8de17595 -->
- [x] 3.5 `chatModelById()` 拒绝非 LLM、`enable=0` 或 `valid=0` 的模型；验证：单测覆盖三种拒绝场景，错误信息区分「类型不符」与「模型不可用」 <!-- comet-task:7a4f901a-94ff-47f7-9700-881516057cd9 -->

## 4. 上游格式解析器

- [x] 4.1 新增 `UpstreamFormatResolver`，输入 `(baseUrl, upstreamFormat, fullUrl)`，输出格式枚举；自动识别规则：`*.openai.azure.com|cn` → Azure，路径含 `/anthropic` 或 `api.anthropic.com` → Anthropic Messages，其余 → Chat Completions，Responses 仅手选；验证：参数化单测覆盖至少 8 组 URL <!-- comet-task:49bf86e1-738e-45e5-bd6c-376e2519b0e7 -->
- [x] 4.2 解析器同时提供 URL 规范化：去尾部 `/`、剥离尾部 `/chat/completions`、路径为空补 `/v1`；验证：单测断言 `https://api.deepseek.com` → `.../v1`，`https://api.openai.com/v1` 不重复追加 <!-- comet-task:fb9ac113-e4c3-4ec8-b4d6-16fe6ce91666 -->
- [x] 4.3 完整 URL 开启时原样返回，不追加路径；验证：单测断言返回值与入参严格相等 <!-- comet-task:9106839c-5e5f-448a-a2a4-c71c31d7f68a -->

## 5. 对话客户端格式分支

- [x] 5.1 `ChatModelClient.generate()` 增加格式参数，5 处调用点统一从 `ModelRef` 取格式；验证：`mvn compile` 通过，且 `ChatKnowledgeService`、`ChatQuestionService`×2、`PermissionEnforcer`、`ScheduledTaskExecutor` 均无硬编码格式 <!-- comet-task:76b40a75-0e48-419c-a107-ec6b429c8b6e -->
- [x] 5.2 实现 Anthropic Messages 分支：`{base}/v1/messages`、`x-api-key` + `anthropic-version`、system 提升为顶层、`max_tokens` 默认 4096 且可被模型参数覆盖、剔除 `stream_options`；验证：stub 单测断言请求头与请求体结构 <!-- comet-task:70547d94-15f2-4a51-b336-f96a71127803 -->
- [x] 5.3 实现 Anthropic 流式解析：`message_start` / `content_block_delta` / `message_delta` / `message_stop`，usage 取 `input_tokens`/`output_tokens`；验证：stub 单测断言拼接文本与 usage 数值 <!-- comet-task:42c35425-98cf-4c60-a8b5-d978440c45ee -->
- [x] 5.4 实现 Responses 分支：`{base}/responses`、`input[]` 结构、解析 `response.output_text.delta` 与 `response.completed`；验证：stub 单测断言文本与完成事件 <!-- comet-task:11a9a099-50de-42d3-8ba8-af39350a0f46 -->
- [x] 5.5 实现 Azure OpenAI 分支：`{base}/openai/deployments/{deployment}/chat/completions?api-version=`、`api-key` 头、报文沿用 Chat Completions；验证：stub 单测断言路径与请求头 <!-- comet-task:eeb1ffb6-e27c-4d48-a17a-21ffa064e3fe -->
- [x] 5.6 回归 Chat Completions 分支行为不变：现有单测全绿，且请求体字节级与改动前一致；验证：`mvn -pl askdata/askdata-server test -Dtest='ChatModelClient*'` <!-- comet-task:8d15d96b-a4ae-47a8-acbf-21f91260a603 -->

## 6. 模型列表接口

- [x] 6.1 新增 `POST /api/v1/system/aimodel/models`，入参含 Base URL、Key、上游格式、完整 URL；返回 `{source, models[], error?}`，`source` 取 `remote`/`preset`/`none`；验证：controller 单测覆盖三种 source <!-- comet-task:402bcfe8-becd-4bba-a060-fd4bf531c4ce -->
- [x] 6.2 服务端代理请求上游 `/models`，限制返回条数上限，只发一次规范化请求，不做 404 路径探测；验证：stub 单测断言仅调用一次且条数被截断 <!-- comet-task:705c8784-0bfe-43a2-afbf-ef42dd98857f -->
- [x] 6.3 上游失败统一降级为可读错误，不返回 5xx，日志不记录 Key；验证：stub 401/404/超时三种场景均返回 200 + error 字段，日志断言无 key 子串 <!-- comet-task:23560ff6-0901-4ae4-849d-8cdc7d18017d -->
- [x] 6.4 完整 URL 无法剥离 `/chat/completions`、`/responses`、`/messages` 时，接口返回明确「不支持自动获取」错误且不发请求；验证：stub 断言零请求 <!-- comet-task:a407a823-9945-4eca-8c67-5f017f4af705 -->

## 7. 连通校验按类型

- [x] 7.1 `streamStatus()` 按模型类型分流：LLM 走对话补全，向量走 `{base}/embeddings`；验证：stub 单测分别断言命中的路径 <!-- comet-task:6b6c6a30-784f-403a-ae37-34fb438e5168 -->
- [x] 7.2 请求体携带 `id` 时校验结果回写 `valid`（成功 1 / 失败 0），不带 `id` 时只返回结论不落库；验证：单测断言幂等调用两次结果一致、未保存场景库中无新增行 <!-- comet-task:170e5924-3051-4bbe-a64b-b1be9f327769 -->
- [x] 7.3 向量校验请求体使用最小 `input`，解析 `data[0].embedding` 长度作为维度返回；验证：stub 单测断言返回维度等于 stub 向量长度 <!-- comet-task:e4510fd9-f810-47c6-95e9-ca109b9e5e17 -->
- [x] 7.4 保存接口不改写 `valid`，沿用库中值；验证：单测「仅改名称保存后 valid 仍为 1」与「valid=0 保存后仍为 0」 <!-- comet-task:a4ec2200-918c-4b1b-a5cc-22fb0865f4d7 -->
- [x] 7.5 校验结果回写走仓储专用方法（对齐 `sys_authentication` 的 `updateValid`），避免整行覆盖；验证：单测断言回写只更新 `valid` 一列 <!-- comet-task:35d3ea81-1f98-43fe-ba8a-c2af6aad9d71 -->

## 8. 向量索引重建（全空间）

- [x] 8.1 新增 `ai_embedding_index_state` 单行状态表（`model_id`、`dimension`、`status`、`total`、`done`、`failed`、`current_scope`、`started_at`、`finished_at`、`message`），三套方言各一份；验证：H2/MySQL/PostgreSQL 建表语句字段一致，初始化一行 `id=1, status=idle` <!-- comet-task:7fb5aa1f-2572-4aa9-8159-968b17df10e9 -->
- [x] 8.2 `EmbeddingBackfillRunner` 增加 force 模式：忽略 `embedding IS NULL` 过滤，按 id 全量重算并覆盖；验证：单测预置非空向量，断言重建后值被替换而非跳过 <!-- comet-task:d4c9879e-f8cc-47d8-a462-9fddd96a4bdb -->
- [x] 8.3 重建任务收集全部工作空间（`terminology`/`data_training`/`core_datasource` 的 distinct `oid` ∪ `sys_workspace`），逐空间执行四类重算；验证：单测构造 3 个空间，断言每个空间四类采样均被处理 <!-- comet-task:294a6021-1df9-43a3-9f37-9e74133b555f -->
- [x] 8.4 单空间异常不中断整体：记录失败原因与空间 id，继续下一个空间；验证：单测让第 2 个空间抛错，断言第 3 个空间仍执行且结果中列出第 2 个空间失败 <!-- comet-task:3426ed91-6170-4cd3-aa3d-a50a3f907959 -->
- [x] 8.5 进度写入状态表（`done`/`total`/`current_scope`），完成后写入 `model_id`、`dimension`、`finished_at` 并将 `status` 置 `idle`；验证：单测断言任务前后状态字段变化 <!-- comet-task:4a78fc13-59b6-4814-9948-3524724d1853 -->
- [x] 8.6 新增 `GET /api/v1/system/aimodel/embedding/status`：返回是否待重建（当前默认向量模型 id ≠ `state.model_id` 或 `status=failed`）、进度与上次结果；验证：单测覆盖「未切换」「已切换未重建」「失败待重试」三种返回 <!-- comet-task:0ceb8288-ae93-42a9-aa33-6af8f4c513b1 -->
- [x] 8.7 新增 `POST /api/v1/system/aimodel/embedding/rebuild`：运行中重复调用返回「任务进行中」，不启动第二个任务；验证：并发调用两次，断言仅一次实际执行 <!-- comet-task:b32f9ea3-ad6e-443f-9c3f-4c89f94e946d -->
- [x] 8.8 维度变化时先按新维度重建集合并清理旧维度数据，再写入向量；验证：单测先写 768 维再切 1024 维，断言集合维度为 1024 且旧数据不参与检索 <!-- comet-task:cc24ea4b-919f-4e99-82fc-448e34c7c977 -->
- [x] 8.9 两个接口纳入审计日志，且仅管理员可调用；验证：非管理员调用返回 403，审计记录存在 <!-- comet-task:827341ed-a9e9-4dab-85ef-21ee609b5853 -->

## 9. 相邻后端模块适配

- [x] 9.1 `ChatModelRouter.defaultChatModel()` 改为「按类型默认 + 可用回退」，所有消费方零改动；验证：`ChatKnowledgeService`、`ChatQuestionService`×3、`PermissionEnforcer`、`ScheduledTaskExecutor` 单测全绿 <!-- comet-task:e1a89e7f-de50-44d1-8515-ab1ac10121a8 -->
- [x] 9.2 上述 6 处消费方补齐「无可用模型」处理，返回可读错误（后端消息 key）而非空指针；验证：单测把所有 LLM 置为不可用，断言抛出带消息码的业务异常 <!-- comet-task:6eafee1d-284b-4e5d-9154-fa901d37c1b9 -->
- [x] 9.3 `EmbeddingService`、`TableEmbeddingService`、`SchemaContextBuilder` 改为强制依赖在线向量模型；无可用模型时返回统一错误码而非降级；验证：单测断言无模型时抛出带消息码的异常，且不回退词法 <!-- comet-task:186e2e90-266e-4277-8640-ec3233151d1d -->
- [x] 9.4 `AskdataMcpTools.model_list` 与 `mcp_model_list`、`SqlbotMcpCompatController` 改用统一口径（LLM + 启用 + 可用），响应字段保持 SQLBot 兼容；验证：单测断言停用模型与向量模型均不返回，且原有字段集合不变 <!-- comet-task:d1f8928e-efb9-4bb1-9665-bbc030ff833b -->

```

Full source: docs/openspec/changes/aimodel-config-flow-revamp/tasks.md

## docs/openspec/changes/aimodel-config-flow-revamp/.openspec.yaml

- Source: docs/openspec/changes/aimodel-config-flow-revamp/.openspec.yaml
- Lines: 1-2
- SHA256: 108f7b3dae33488206672f31b510275110c0194c93062eb1970e19ca60c08722

```md
schema: spec-driven
created: 2026-11-01

```

## docs/openspec/changes/aimodel-config-flow-revamp/specs/ai-model-configuration/spec.md

- Source: docs/openspec/changes/aimodel-config-flow-revamp/specs/ai-model-configuration/spec.md
- Lines: 1-296
- SHA256: 3d73bcf49882e7fc3b19da1fd16979458263a07b5fde9b30e9b53964a21ba72b

[TRUNCATED]

```md
# Spec Delta

## Purpose

定义 AI 模型配置的对外契约：字段与校验、模型类型能力、默认模型归属，以及「可用」与「启用」两类状态的生命周期。

## ADDED Requirements

### Requirement: 模型配置字段

系统 SHALL 在「配置模型」步骤提供以下字段，并按此顺序展示：模型名称、Base URL、API Key、模型类型、基础模型、是否生效、高级配置。

模型名称 MUST 必填且长度不超过 100 字符，并在同一实例内保持唯一；名称与基础模型完全独立，选择或更换基础模型 MUST NOT 自动改写名称。

Base URL MUST 必填，允许携带多级路径与查询参数，仅拒绝空值和非 `http`/`https` 协议。

API Key MUST 对无障碍标识的标准提供商必填，对标记为私有的提供商可留空。

高级配置 SHALL 收敛为单一折叠区，包含上游格式、完整 URL 开关与模型参数列表；模型参数非空时 SHALL 默认展开，参数区标题 SHALL 显示当前参数数量。

#### Scenario: 新增模型时名称与模型解耦
- **WHEN** 用户将基础模型从 `deepseek-chat` 改为 `deepseek-reasoner`
- **THEN** 模型名称保持用户已填写的内容不变

#### Scenario: Base URL 携带路径
- **WHEN** 用户填写 `https://dashscope.aliyuncs.com/compatible-mode/v1`
- **THEN** 校验通过，且该值按原样保存

#### Scenario: 标准提供商缺少 API Key
- **WHEN** 选定非私有提供商并留空 API Key 提交
- **THEN** 表单校验失败并提示 API Key 必填

#### Scenario: 模型参数自动展开
- **WHEN** 编辑一个已配置 3 条模型参数的模型
- **THEN** 高级配置默认展开，参数区标题显示「模型参数（3）」

### Requirement: 模型类型能力

系统 SHALL 支持 LLM(0) 与 向量(1) 两类模型。

模型类型在编辑态 MUST 只读；新建时 SHALL 默认为 LLM。

模型类型下拉 SHALL 依据当前上游格式的能力矩阵置灰不支持的选项：Anthropic Messages 与 Responses 仅支持 LLM，Azure OpenAI 不支持 Rerank，Chat Completions 支持全部类型。

#### Scenario: 编辑态不可改类型
- **WHEN** 用户打开一个已有模型的编辑表单
- **THEN** 模型类型控件为只读

#### Scenario: 选择 Anthropic 上游格式
- **WHEN** 用户将上游格式切换为 Anthropic Messages
- **THEN** 向量类型选项置灰，且当前类型不为 LLM 时回退为 LLM

### Requirement: 默认模型按类型唯一

每个模型类型 MUST 至多存在一个默认模型；设置某个模型为默认时，系统 SHALL 仅清除同类型的默认标记，不影响其他类型。

默认模型 MUST 同时满足 `valid=1` 与 `enable=1`，否则 MUST 拒绝设默认。

默认模型 MUST NOT 被停用，也 MUST NOT 被删除；用户若要调整，MUST 先将同类型另一个可用模型设为默认。

新建模型 MUST NOT 自动成为默认。

当某类型尚无默认模型时，模型路由 SHALL 回退到该类型下第一个 `valid=1` 且 `enable=1` 的模型，以保证首次配置后即可使用。

#### Scenario: 跨类型互不影响
- **WHEN** 用户将某向量模型设为向量默认
- **THEN** 当前 LLM 默认模型保持不变

#### Scenario: 默认模型不可停用
- **WHEN** 用户尝试关闭当前默认模型的「是否生效」开关
- **THEN** 系统拒绝操作并提示先切换默认模型

#### Scenario: 无默认时回退
- **WHEN** 某类型没有任何默认模型，但存在一个已启用且校验通过的模型
- **THEN** 该类型请求使用该模型

### Requirement: 可用与启用状态

模型 MUST 维护两个独立状态：`valid` 表示最近一次连通校验是否通过，`enable` 表示是否启用。


```

Full source: docs/openspec/changes/aimodel-config-flow-revamp/specs/ai-model-configuration/spec.md

## docs/openspec/changes/aimodel-config-flow-revamp/specs/ai-model-connectivity/spec.md

- Source: docs/openspec/changes/aimodel-config-flow-revamp/specs/ai-model-connectivity/spec.md
- Lines: 1-107
- SHA256: f5e91505693f323c7476f51626614498d1e0c7405f2b7720380d17c6733b76a9

[TRUNCATED]

```md
# Spec Delta

## Purpose

定义模型连接相关的对外契约：Base URL 规范化、上游格式识别、模型列表获取与连通校验的行为与降级规则。

## ADDED Requirements

### Requirement: Base URL 规范化

系统 SHALL 根据 Base URL 与上游格式推导实际请求地址。

规范化规则 MUST 为：去除尾部 `/`；若以 `/chat/completions` 结尾则先剥离；随后解析路径，路径为空时补 `/v1`，非空时直接作为前缀。

当「完整 URL」开启时，系统 MUST 原样使用填写值，不得追加任何路径段。

#### Scenario: 仅填写主机
- **WHEN** Base URL 为 `https://api.deepseek.com`
- **THEN** 对话请求地址为 `https://api.deepseek.com/v1/chat/completions`

#### Scenario: 已含版本路径
- **WHEN** Base URL 为 `https://api.openai.com/v1`
- **THEN** 对话请求地址为 `https://api.openai.com/v1/chat/completions`

#### Scenario: 误粘贴完整路径
- **WHEN** Base URL 为 `https://api.deepseek.com/v1/chat/completions`
- **THEN** 规范化后请求地址仍为 `https://api.deepseek.com/v1/chat/completions`，不出现重复路径

#### Scenario: 完整 URL 开启
- **WHEN** 完整 URL 开启且填写 `https://gw.example.com/proxy/chat/completions`
- **THEN** 请求地址为填写值本身

### Requirement: 上游格式识别与覆盖

系统 SHALL 提供上游格式字段，取值包括：自动识别、Chat Completions、Anthropic Messages、Responses、Azure OpenAI。

自动识别 MUST 遵循：主机匹配 `*.openai.azure.com` 或 `*.openai.azure.cn` 时判为 Azure OpenAI；路径含 `/anthropic` 或主机为 `api.anthropic.com` 时判为 Anthropic Messages；其余判为 Chat Completions。

用户手动指定格式时 MUST 覆盖自动识别结果；切换格式 MUST NOT 自动改写 Base URL。

同一份格式判定 MUST 被模型列表获取、连通校验与运行时请求三处共用，不得各自实现。

#### Scenario: DeepSeek 双协议
- **WHEN** Base URL 为 `https://api.deepseek.com` 且格式为自动识别
- **THEN** 判定为 Chat Completions

#### Scenario: DeepSeek Anthropic 端点
- **WHEN** Base URL 为 `https://api.deepseek.com/anthropic` 且格式为自动识别
- **THEN** 判定为 Anthropic Messages

#### Scenario: 手动覆盖
- **WHEN** 自动识别为非标准结果，用户手动选择 Chat Completions
- **THEN** 后续请求按 Chat Completions 处理，Base URL 保持不变

### Requirement: 模型列表获取

系统 SHALL 提供模型列表获取能力，由服务端代理请求上游模型接口，避免浏览器跨域限制。

获取结果 MUST 携带来源标识：远程获取或供应商预设；前端 MUST 在选项中标注来源。

拉取 SHALL 在 Base URL 与凭据齐备后自动触发，并提供手动刷新入口；请求 MUST 防抖，且 MUST 以请求序号丢弃过期响应。

上游返回的模型 MUST 按名称启发式标注类型，前端 SHALL 依据当前模型类型过滤选项。

拉取失败、超时或返回空列表时，系统 SHALL 降级为供应商静态预设；自定义提供商没有静态预设，MUST 提示只能手动输入。

「完整 URL」开启时，系统 SHALL 剥离末尾的 `/chat/completions`、`/responses` 或 `/messages` 后再拼 `/models`；无法剥离时 MUST 禁用拉取并提示手动输入。

获取过程中 MUST NOT 输出或回显 API Key，MUST NOT 因上游错误返回 5xx。

#### Scenario: 正常拉取
- **WHEN** 用户填写 Base URL 与 API Key，且上游返回 30 个模型
- **THEN** 选项显示 30 个带「远程」标签的模型，并按当前类型过滤

#### Scenario: 拉取失败降级
- **WHEN** 上游返回 404
- **THEN** 选项回退为供应商静态预设并标注「预设」，用户仍可手动输入

#### Scenario: 自定义提供商无兜底
- **WHEN** 自定义提供商的拉取请求失败

```

Full source: docs/openspec/changes/aimodel-config-flow-revamp/specs/ai-model-connectivity/spec.md
