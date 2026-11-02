---
change: aimodel-config-flow-revamp
design-doc: docs/openspec/changes/aimodel-config-flow-revamp/design.md
base-ref: 2643c922f07fbbe1366f211ded6d1fea398b0462
status: draft
archived-with: 2026-11-02-aimodel-config-flow-revamp
---

# 实施计划 — aimodel-config-flow-revamp

<!-- comet-task-authority: docs/openspec/changes/aimodel-config-flow-revamp/tasks.md -->

## 目标

按已批准的技术设计（`docs/openspec/changes/aimodel-config-flow-revamp/design.md`）实施 AI 模型配置流程改造：类型化模型配置（LLM/向量）、enable/valid 双状态、四种上游报文格式、模型列表拉取、向量全空间重建与维度对齐、移除本地 ONNX。任务正文与完成状态以 tasks.md 为准，本计划只说明顺序、依赖、约束和验收方式。

## 执行配置

| 项 | 值 | 含义 |
| --- | --- | --- |
| build_mode | autonomous | Agent 自行规划与实施，按 tasks.md 推进 |
| tdd_mode | direct | 不强制 RED/GREEN，仍做相关检查与缺陷回归 |
| review_mode | standard | Build 做任务级/分段审查，Verify 做唯一最终集成审查 |

## 顺序与依赖

1. **0 参考对等基线** → 先确认 B/R/F 清单与既有差异，避免后续按错误基线实现。
2. **1 数据库迁移** → 后续所有新列的读写都依赖它；迁移必须在任何仓储改动前完成。
3. **2 仓储与类型维度 → 3 默认模型与启停保护 → 4 格式解析器 → 5 客户端格式分支 → 6 模型列表接口 → 7 连通校验** → 后端自底向上，编译期强制调用方改完才能继续。
4. **8 向量重建 → 9 相邻后端模块适配 → 10 消息与文档** → 依赖 2/3/7 完成。
5. **11 提供商与国际化 → 12 前端表单 → 13 列表与默认 → 14 关联页面** → 依赖后端接口形态稳定。
6. **15 Playwright 联调 → 16 集成回归** → 全部功能落地后执行。

同一阶段内任务无强依赖，可按序推进；跨阶段不得跳序（例如未完成迁移就改仓储会编译失败）。

## 约束

- 不自动 git commit / push，改动保留在工作区。
- 仅允许 `reference-parity.md` 第 4 节的 7 项偏离（D1–D7），新增偏离必须回到 design.md 登记并确认。
- Chat Completions 现有路径不改代码结构，只加格式分支。
- 新列（enable/valid/upstream_format/dimension）明文存储，不进入 CryptoUtil 加解密范围。
- 后端消息 6 份 properties、前端 5 份语言文件必须同步补齐，不得留 key。
- 前端提示文案走 i18n，不硬编码中文。

## 风险与回退

- 迁移回填错误会导致全站无模型 → 16.5 断言「迁移后可用模型数不减少」；回退只需代码回滚，新列/新表保留。
- default_model 语义变更 → 迁移不回算默认标记，其他类型走回退逻辑；回归覆盖默认弹层、工作空间授权、MCP 列表。
- ChatModelClient 触及问数主链路 → 只加分支；回归问数、图表、权限、定时任务。
- Anthropic / Responses 无真实网关 → stub 单测，并在交付说明标注未真实端点验证。

## 检查命令

- 后端编译：`mvn -pl askdata/askdata-server -am -Dmaven.test.skip=false test`（JDK 17）
- 前端构建：`cd askdata/askdata-server/web && npm run build`
- e2e：`cd askdata/askdata-server/e2e && npx playwright test tests/aimodel.spec.ts`

## 任务


### 0. 参考实现对等基线

<!-- comet-task-ref:fe4f34f5-db85-4442-a73f-ea7ab2525004 -->
- **fe4f34f5-db85-4442-a73f-ea7ab2525004** 0.1 按 `reference-parity.md` 第 1 节逐条核对后端接口面（B1–B14），记录当前实现状态与本轮处置；验证：14 条全部有明确结论，无「暂不实现」
<!-- comet-task-ref:c8f50c5f-62ea-4be2-8b05-501b72879305 -->
- **c8f50c5f-62ea-4be2-8b05-501b72879305** 0.2 核对第 2 节运行时行为（R1–R6），确认 Chat Completions、vLLM、Azure、参数平铺四条链路均保留；验证：单测或代码走查记录存在性
<!-- comet-task-ref:4d18d6f3-1bf5-48f3-8e4d-f2cb8453fe67 -->
- **4d18d6f3-1bf5-48f3-8e4d-f2cb8453fe67** 0.3 核对第 3 节前端表单行为（F1–F13），标记已有与缺失；验证：13 条全部有结论
<!-- comet-task-ref:190c398b-b3e0-4249-8d70-9dbce6659a75 -->
- **190c398b-b3e0-4249-8d70-9dbce6659a75** 0.4 修复已发现的存量差异：`ModelForm.vue` 的 `handleParamsDel` 删除参数后未回写 `tempConfigMap`（SQLBot 第 84 行有）；验证：删除参数→切模型→切回，参数仍为删除后的状态
<!-- comet-task-ref:792903db-3f9f-441c-8bd2-a873e9ab7f39 -->
- **792903db-3f9f-441c-8bd2-a873e9ab7f39** 0.5 把第 4 节 7 项有意偏离（D1–D7）逐条落到实现与测试，确保偏离是显式的而非遗漏；验证：每项偏离在单测或 e2e 中有对应用例

### 1. 数据库迁移

<!-- comet-task-ref:797dfdd3-c66a-4684-8f0b-f9ac5235d9db -->
- **797dfdd3-c66a-4684-8f0b-f9ac5235d9db** 1.1 在 `src/main/resources/db/migration/h2`、`mysql`、`postgresql` 各新增 `V2__ai_model_states.sql`，为 `ai_model` 增加 `enable`、`valid`、`upstream_format` 三列，均 `NOT NULL`，默认值分别为 `1`、`1`、`0`；验证：三份脚本语法一致，字段名与类型在各方言下均合法
<!-- comet-task-ref:be4e0532-d688-44c9-b301-18679d27b449 -->
- **be4e0532-d688-44c9-b301-18679d27b449** 1.2 迁移脚本对存量行显式回填 `enable=1`、`valid=1`、`upstream_format=1`；验证：在 H2 上用存量数据执行迁移后 `SELECT COUNT(*) FROM ai_model WHERE enable=1 AND valid=1` 等于迁移前的总行数
<!-- comet-task-ref:4e8b86a6-62b5-40cf-9973-953950fe1dd6 -->
- **4e8b86a6-62b5-40cf-9973-953950fe1dd6** 1.3 启动应用验证迁移幂等：二次启动不报错、不回滚已有 `valid=0` 状态；验证：手动置一条 `valid=0` 后重启，该行仍为 `0`

### 2. 仓储与类型维度

<!-- comet-task-ref:f13b20c2-15d9-437d-8a74-88c8ed836829 -->
- **f13b20c2-15d9-437d-8a74-88c8ed836829** 2.1 `AiModelRepository` 新增 `findDefault(int type)`、`listByType(int type)`、`countByType(int type)`，`clearDefaults(int type)` 改为按类型清除；验证：单测覆盖「清除 LLM 默认不影响向量默认」
<!-- comet-task-ref:4e8a730a-736c-4c37-92e8-e6f3df94d1dc -->
- **4e8a730a-736c-4c37-92e8-e6f3df94d1dc** 2.2 删除无类型版本的 `findDefault()`、`clearDefaults()`、`count()`，编译期暴露所有调用方；验证：`mvn -pl askdata/askdata-server compile` 通过且无遗留调用
<!-- comet-task-ref:20519acd-2926-4711-a41f-70884244a6c4 -->
- **20519acd-2926-4711-a41f-70884244a6c4** 2.3 `listByWorkspace(long, int type)` 增加类型过滤，工作空间授权与 MCP 模型列表只传 LLM 类型；验证：单测断言向量模型不出现在返回列表中
<!-- comet-task-ref:3ab9d74e-41e9-4024-aab4-e3b11fa6563d -->
- **3ab9d74e-41e9-4024-aab4-e3b11fa6563d** 2.4 仓储写入支持 `enable`、`valid`、`upstream_format` 三列，读取时映射到实体；验证：单测往返读写三次字段值不变
<!-- comet-task-ref:90ee6774-87e6-4b52-9d9e-c64db46a718c -->
- **90ee6774-87e6-4b52-9d9e-c64db46a718c** 2.5 `CryptoUtil` 加解密范围保持只覆盖 `api_domain`/`api_key`，新列明文存储；验证：单测断言新列读写不走加解密、`api_domain` 含多级路径时可原样往返

### 3. 默认模型与启停保护

<!-- comet-task-ref:85b954d8-38cc-4502-8d6d-236bb561a923 -->
- **85b954d8-38cc-4502-8d6d-236bb561a923** 3.1 移除 `AiModelService.create()` 中 `count()==0` 自动设默认的逻辑；验证：新建首个模型后 `default_model` 仍为 0
<!-- comet-task-ref:5b75ea90-25e8-42be-b237-7bc630255825 -->
- **5b75ea90-25e8-42be-b237-7bc630255825** 3.2 设默认改为按类型清除，并在设默认前校验目标模型 `valid=1 && enable=1`，否则返回明确错误；验证：单测覆盖四种组合（可用+启用通过，其余三种被拒）
<!-- comet-task-ref:d6674b9e-ad79-4da9-ad54-368373cd0b4e -->
- **d6674b9e-ad79-4da9-ad54-368373cd0b4e** 3.3 停用、删除默认模型时返回拒绝对应错误码，接口层不可绕过；验证：单测直接调用 service 与 controller 各一次，均被拒
<!-- comet-task-ref:1bb6d6ce-5130-46d6-8ea8-7fcf8de17595 -->
- **1bb6d6ce-5130-46d6-8ea8-7fcf8de17595** 3.4 `ChatModelRouter.chatModel()`、`embeddingModel()` 在无默认时回退到该类型首个 `valid=1 && enable=1` 模型；验证：单测构造「无默认 + 一个可用模型」断言选中该模型
<!-- comet-task-ref:7a4f901a-94ff-47f7-9700-881516057cd9 -->
- **7a4f901a-94ff-47f7-9700-881516057cd9** 3.5 `chatModelById()` 拒绝非 LLM、`enable=0` 或 `valid=0` 的模型；验证：单测覆盖三种拒绝场景，错误信息区分「类型不符」与「模型不可用」

### 4. 上游格式解析器

<!-- comet-task-ref:49bf86e1-738e-45e5-bd6c-376e2519b0e7 -->
- **49bf86e1-738e-45e5-bd6c-376e2519b0e7** 4.1 新增 `UpstreamFormatResolver`，输入 `(baseUrl, upstreamFormat, fullUrl)`，输出格式枚举；自动识别规则：`*.openai.azure.com|cn` → Azure，路径含 `/anthropic` 或 `api.anthropic.com` → Anthropic Messages，其余 → Chat Completions，Responses 仅手选；验证：参数化单测覆盖至少 8 组 URL
<!-- comet-task-ref:fb9ac113-e4c3-4ec8-b4d6-16fe6ce91666 -->
- **fb9ac113-e4c3-4ec8-b4d6-16fe6ce91666** 4.2 解析器同时提供 URL 规范化：去尾部 `/`、剥离尾部 `/chat/completions`、路径为空补 `/v1`；验证：单测断言 `https://api.deepseek.com` → `.../v1`，`https://api.openai.com/v1` 不重复追加
<!-- comet-task-ref:9106839c-5e5f-448a-a2a4-c71c31d7f68a -->
- **9106839c-5e5f-448a-a2a4-c71c31d7f68a** 4.3 完整 URL 开启时原样返回，不追加路径；验证：单测断言返回值与入参严格相等

### 5. 对话客户端格式分支

<!-- comet-task-ref:76b40a75-0e48-419c-a107-ec6b429c8b6e -->
- **76b40a75-0e48-419c-a107-ec6b429c8b6e** 5.1 `ChatModelClient.generate()` 增加格式参数，5 处调用点统一从 `ModelRef` 取格式；验证：`mvn compile` 通过，且 `ChatKnowledgeService`、`ChatQuestionService`×2、`PermissionEnforcer`、`ScheduledTaskExecutor` 均无硬编码格式
<!-- comet-task-ref:70547d94-15f2-4a51-b336-f96a71127803 -->
- **70547d94-15f2-4a51-b336-f96a71127803** 5.2 实现 Anthropic Messages 分支：`{base}/v1/messages`、`x-api-key` + `anthropic-version`、system 提升为顶层、`max_tokens` 默认 4096 且可被模型参数覆盖、剔除 `stream_options`；验证：stub 单测断言请求头与请求体结构
<!-- comet-task-ref:42c35425-98cf-4c60-a8b5-d978440c45ee -->
- **42c35425-98cf-4c60-a8b5-d978440c45ee** 5.3 实现 Anthropic 流式解析：`message_start` / `content_block_delta` / `message_delta` / `message_stop`，usage 取 `input_tokens`/`output_tokens`；验证：stub 单测断言拼接文本与 usage 数值
<!-- comet-task-ref:11a9a099-50de-42d3-8ba8-af39350a0f46 -->
- **11a9a099-50de-42d3-8ba8-af39350a0f46** 5.4 实现 Responses 分支：`{base}/responses`、`input[]` 结构、解析 `response.output_text.delta` 与 `response.completed`；验证：stub 单测断言文本与完成事件
<!-- comet-task-ref:eeb1ffb6-e27c-4d48-a17a-21ffa064e3fe -->
- **eeb1ffb6-e27c-4d48-a17a-21ffa064e3fe** 5.5 实现 Azure OpenAI 分支：`{base}/openai/deployments/{deployment}/chat/completions?api-version=`、`api-key` 头、报文沿用 Chat Completions；验证：stub 单测断言路径与请求头
<!-- comet-task-ref:8d15d96b-a4ae-47a8-acbf-21f91260a603 -->
- **8d15d96b-a4ae-47a8-acbf-21f91260a603** 5.6 回归 Chat Completions 分支行为不变：现有单测全绿，且请求体字节级与改动前一致；验证：`mvn -pl askdata/askdata-server test -Dtest='ChatModelClient*'`

### 6. 模型列表接口

<!-- comet-task-ref:402bcfe8-becd-4bba-a060-fd4bf531c4ce -->
- **402bcfe8-becd-4bba-a060-fd4bf531c4ce** 6.1 新增 `POST /api/v1/system/aimodel/models`，入参含 Base URL、Key、上游格式、完整 URL；返回 `{source, models[], error?}`，`source` 取 `remote`/`preset`/`none`；验证：controller 单测覆盖三种 source
<!-- comet-task-ref:705c8784-0bfe-43a2-afbf-ef42dd98857f -->
- **705c8784-0bfe-43a2-afbf-ef42dd98857f** 6.2 服务端代理请求上游 `/models`，限制返回条数上限，只发一次规范化请求，不做 404 路径探测；验证：stub 单测断言仅调用一次且条数被截断
<!-- comet-task-ref:23560ff6-0901-4ae4-849d-8cdc7d18017d -->
- **23560ff6-0901-4ae4-849d-8cdc7d18017d** 6.3 上游失败统一降级为可读错误，不返回 5xx，日志不记录 Key；验证：stub 401/404/超时三种场景均返回 200 + error 字段，日志断言无 key 子串
<!-- comet-task-ref:a407a823-9945-4eca-8c67-5f017f4af705 -->
- **a407a823-9945-4eca-8c67-5f017f4af705** 6.4 完整 URL 无法剥离 `/chat/completions`、`/responses`、`/messages` 时，接口返回明确「不支持自动获取」错误且不发请求；验证：stub 断言零请求

### 7. 连通校验按类型

<!-- comet-task-ref:6b6c6a30-784f-403a-ae37-34fb438e5168 -->
- **6b6c6a30-784f-403a-ae37-34fb438e5168** 7.1 `streamStatus()` 按模型类型分流：LLM 走对话补全，向量走 `{base}/embeddings`；验证：stub 单测分别断言命中的路径
<!-- comet-task-ref:170e5924-3051-4bbe-a64b-b1be9f327769 -->
- **170e5924-3051-4bbe-a64b-b1be9f327769** 7.2 请求体携带 `id` 时校验结果回写 `valid`（成功 1 / 失败 0），不带 `id` 时只返回结论不落库；验证：单测断言幂等调用两次结果一致、未保存场景库中无新增行
<!-- comet-task-ref:e4510fd9-f810-47c6-95e9-ca109b9e5e17 -->
- **e4510fd9-f810-47c6-95e9-ca109b9e5e17** 7.3 向量校验请求体使用最小 `input`，解析 `data[0].embedding` 长度作为维度返回；验证：stub 单测断言返回维度等于 stub 向量长度
<!-- comet-task-ref:a4ec2200-918c-4b1b-a5cc-22fb0865f4d7 -->
- **a4ec2200-918c-4b1b-a5cc-22fb0865f4d7** 7.4 保存接口不改写 `valid`，沿用库中值；验证：单测「仅改名称保存后 valid 仍为 1」与「valid=0 保存后仍为 0」
<!-- comet-task-ref:35d3ea81-1f98-43fe-ba8a-c2af6aad9d71 -->
- **35d3ea81-1f98-43fe-ba8a-c2af6aad9d71** 7.5 校验结果回写走仓储专用方法（对齐 `sys_authentication` 的 `updateValid`），避免整行覆盖；验证：单测断言回写只更新 `valid` 一列

### 8. 向量索引重建（全空间）

<!-- comet-task-ref:7fb5aa1f-2572-4aa9-8159-968b17df10e9 -->
- **7fb5aa1f-2572-4aa9-8159-968b17df10e9** 8.1 新增 `ai_embedding_index_state` 单行状态表（`model_id`、`dimension`、`status`、`total`、`done`、`failed`、`current_scope`、`started_at`、`finished_at`、`message`），三套方言各一份；验证：H2/MySQL/PostgreSQL 建表语句字段一致，初始化一行 `id=1, status=idle`
<!-- comet-task-ref:d4c9879e-f8cc-47d8-a462-9fddd96a4bdb -->
- **d4c9879e-f8cc-47d8-a462-9fddd96a4bdb** 8.2 `EmbeddingBackfillRunner` 增加 force 模式：忽略 `embedding IS NULL` 过滤，按 id 全量重算并覆盖；验证：单测预置非空向量，断言重建后值被替换而非跳过
<!-- comet-task-ref:294a6021-1df9-43a3-9f37-9e74133b555f -->
- **294a6021-1df9-43a3-9f37-9e74133b555f** 8.3 重建任务收集全部工作空间（`terminology`/`data_training`/`core_datasource` 的 distinct `oid` ∪ `sys_workspace`），逐空间执行四类重算；验证：单测构造 3 个空间，断言每个空间四类采样均被处理
<!-- comet-task-ref:3426ed91-6170-4cd3-aa3d-a50a3f907959 -->
- **3426ed91-6170-4cd3-aa3d-a50a3f907959** 8.4 单空间异常不中断整体：记录失败原因与空间 id，继续下一个空间；验证：单测让第 2 个空间抛错，断言第 3 个空间仍执行且结果中列出第 2 个空间失败
<!-- comet-task-ref:4a78fc13-59b6-4814-9948-3524724d1853 -->
- **4a78fc13-59b6-4814-9948-3524724d1853** 8.5 进度写入状态表（`done`/`total`/`current_scope`），完成后写入 `model_id`、`dimension`、`finished_at` 并将 `status` 置 `idle`；验证：单测断言任务前后状态字段变化
<!-- comet-task-ref:0ceb8288-ae93-42a9-aa33-6af8f4c513b1 -->
- **0ceb8288-ae93-42a9-aa33-6af8f4c513b1** 8.6 新增 `GET /api/v1/system/aimodel/embedding/status`：返回是否待重建（当前默认向量模型 id ≠ `state.model_id` 或 `status=failed`）、进度与上次结果；验证：单测覆盖「未切换」「已切换未重建」「失败待重试」三种返回
<!-- comet-task-ref:b32f9ea3-ad6e-443f-9c3f-4c89f94e946d -->
- **b32f9ea3-ad6e-443f-9c3f-4c89f94e946d** 8.7 新增 `POST /api/v1/system/aimodel/embedding/rebuild`：运行中重复调用返回「任务进行中」，不启动第二个任务；验证：并发调用两次，断言仅一次实际执行
<!-- comet-task-ref:cc24ea4b-919f-4e99-82fc-448e34c7c977 -->
- **cc24ea4b-919f-4e99-82fc-448e34c7c977** 8.8 维度变化时先按新维度重建集合并清理旧维度数据，再写入向量；验证：单测先写 768 维再切 1024 维，断言集合维度为 1024 且旧数据不参与检索
<!-- comet-task-ref:827341ed-a9e9-4dab-85ef-21ee609b5853 -->
- **827341ed-a9e9-4dab-85ef-21ee609b5853** 8.9 两个接口纳入审计日志，且仅管理员可调用；验证：非管理员调用返回 403，审计记录存在

### 9. 相邻后端模块适配

<!-- comet-task-ref:e1a89e7f-de50-44d1-8515-ab1ac10121a8 -->
- **e1a89e7f-de50-44d1-8515-ab1ac10121a8** 9.1 `ChatModelRouter.defaultChatModel()` 改为「按类型默认 + 可用回退」，所有消费方零改动；验证：`ChatKnowledgeService`、`ChatQuestionService`×3、`PermissionEnforcer`、`ScheduledTaskExecutor` 单测全绿
<!-- comet-task-ref:6eafee1d-284b-4e5d-9154-fa901d37c1b9 -->
- **6eafee1d-284b-4e5d-9154-fa901d37c1b9** 9.2 上述 6 处消费方补齐「无可用模型」处理，返回可读错误（后端消息 key）而非空指针；验证：单测把所有 LLM 置为不可用，断言抛出带消息码的业务异常
<!-- comet-task-ref:186e2e90-266e-4277-8640-ec3233151d1d -->
- **186e2e90-266e-4277-8640-ec3233151d1d** 9.3 `EmbeddingService`、`TableEmbeddingService`、`SchemaContextBuilder` 改为强制依赖在线向量模型；无可用模型时返回统一错误码而非降级；验证：单测断言无模型时抛出带消息码的异常，且不回退词法
<!-- comet-task-ref:d1f8928e-efb9-4bb1-9665-bbc030ff833b -->
- **d1f8928e-efb9-4bb1-9665-bbc030ff833b** 9.4 `AskdataMcpTools.model_list` 与 `mcp_model_list`、`SqlbotMcpCompatController` 改用统一口径（LLM + 启用 + 可用），响应字段保持 SQLBot 兼容；验证：单测断言停用模型与向量模型均不返回，且原有字段集合不变
<!-- comet-task-ref:fa124487-3ccd-4158-a653-725a52da7fa4 -->
- **fa124487-3ccd-4158-a653-725a52da7fa4** 9.5 工作空间模型授权接口（`/system/aimodel/list/by_ws` 与 ws_mapping 相关）只返回 LLM 且已启用的模型；验证：单测断言向量与停用模型不出现
<!-- comet-task-ref:b3063606-7c30-4a19-b0fd-8057a31196f4 -->
- **b3063606-7c30-4a19-b0fd-8057a31196f4** 9.6 删除本地向量模型链路：移除 `LocalEmbeddingModel`、`BertWordPieceTokenizer`、`onnxruntime` 依赖、`askdata.embedding.model-path` 配置与相关文档，测试改用 stub 向量源；验证：`grep -r "LocalEmbeddingModel\|onnxruntime" src pom.xml` 无结果，全量测试通过
<!-- comet-task-ref:b041ef93-b94f-41da-9e38-bef0dd707bdc -->
- **b041ef93-b94f-41da-9e38-bef0dd707bdc** 9.7 问答入口前置校验：网页会话、MCP 对话、定时任务在无可用在线向量模型时统一阻断并返回同一错误码；验证：单测三个入口各一次，错误码一致
<!-- comet-task-ref:8b846ab9-2eef-40cf-96cc-9ac2bf042923 -->
- **8b846ab9-2eef-40cf-96cc-9ac2bf042923** 9.8 模型配置页在无可用在线向量模型时展示提醒条并可跳转配置；验证：e2e 断言提醒条存在、点击进入模型新增页

<!-- comet-task-ref:38880e27-5265-4e90-b1c0-ca89051c6333 -->
- **38880e27-5265-4e90-b1c0-ca89051c6333** 9.9 向量存储只允许在线 Qdrant：删除 DbVectorStore 与 DB 向量列镜像、保留 `type` 作为可插拔入口（预留后端启动期显式失败）、未配置 url 时占位实现显式失败；验证：grep 无 DbVectorStore 引用，向量相关测试全绿，未配置 url 启动日志出现错误且不写本地

<!-- comet-task-ref:f5363b77-b96f-4015-8cbb-7faf7e57c3d8 -->
- **f5363b77-b96f-4015-8cbb-7faf7e57c3d8** 9.10 维度不一致不删集合：`ensureCollection` 改为抛错并保留既有向量，删集合收敛为 `dropCollection` 仅由显式重建调用，`EmbeddingService` 短路已知损坏集合；验证：真实 Qdrant 断言「拒写后原向量仍可召回」与「重建后可按新维度写入」

<!-- comet-task-ref:6e078be7-e5c9-48d8-9da1-1c7bd97be418 -->
- **6e078be7-e5c9-48d8-9da1-1c7bd97be418** 9.11 模型解析缓存：`ChatModelRouter.embeddingModel()` 缓存 60s，模型配置写路径调用 `invalidate()` 立即失效；验证：回填不再 2N 次查库，保存配置后下一次调用即生效

<!-- comet-task-ref:af36c1fb-d9a1-4f88-a188-df5157f8fbe3 -->
- **af36c1fb-d9a1-4f88-a188-df5157f8fbe3** 9.12 彻底移除 H2：删除 `db/migration/h2/`、`H2MetadbMigrationTest`、`com.h2database:h2` 依赖与 `DatasourceType.H2`（含 `JdbcEngine` 的 url/schema/方言兜底分支），`MetadataDatabaseGuard` 拒绝 H2 且去掉 `allow-non-mysql` 开关；验证：`grep -rn "h2" src/main` 无功能引用，全量测试通过

<!-- comet-task-ref:1d632721-44b7-4c97-b1bc-b3592b104050 -->
- **1d632721-44b7-4c97-b1bc-b3592b104050** 9.13 测试基建切到真实 MySQL：`MysqlTestSupport` 提供 `createSchema()`/`createEmptySchema()`/`bindSpringDatasource()`，Spring 上下文测试用 `@DynamicPropertySource` 注入独立 schema；验证：全量测试在 `ASKDATA_IT_MYSQL_*` 指向 MySQL 8 时全绿，不可达时整类 skip 且报告写明配置方式

<!-- comet-task-ref:b96fddc2-44f6-4677-b423-4e92dc8807ee -->
- **b96fddc2-44f6-4677-b423-4e92dc8807ee** 9.14 修掉 H2 掩盖的方言缺陷：excel 预览 SQL 按主库方言引用标识符、MySQL/PG `fieldSql` 补 `ORDER BY`、`convertValue` 支持 `java.time`；验证：`JdbcEngineSmokeTest`/`ExcelServiceTest` 在真实 MySQL 上全绿

<!-- comet-task-ref:44013ab9-e788-4682-8832-a3f887cd021e -->
- **44013ab9-e788-4682-8832-a3f887cd021e** 9.15 修掉作用域过滤与 LIKE 的方言缺陷：`'||'` 拼串改 `CONCAT(...) LIKE CONCAT('%,', ?, ',%')`（7 处，修复专属术语/提示词越权召回）、去掉 `LIKE ... ESCAPE '\'`（29 处，MySQL 下吞占位符）、`INSTR` 改标准 `POSITION(... IN ...)`；验证：`ChatKnowledgeServiceTest`/`CustomPrompt*`/`SystemDetailsTest` 在真实 MySQL 上全绿

<!-- comet-task-ref:ce7fc1c4-17c6-4bb0-aa06-6be327814bc2 -->
- **ce7fc1c4-17c6-4bb0-aa06-6be327814bc2** 9.16 全量测试在真实 MySQL + Qdrant + TEI 上跑通：清理测试里残留的 H2 基建（`EmbeddedDatabaseBuilder`）、空 `@TestPropertySource`、缺失 `@DynamicPropertySource` 的 `@SpringBootTest`、H2 专用 SQL（`VARCHAR(10000)`、`1672531200000L`、双引号标识符、保留字 `sql`）；验证：`mvn -o -pl askdata/askdata-server test` 零失败零错误

<!-- comet-task-ref:033bdf12-200c-484b-a904-67f511f55dbe -->
- **033bdf12-200c-484b-a904-67f511f55dbe** 9.17 清零 skip：Redis 跨实例防抖支持密码/端口配置并探测可达、ES 与 Ollama 缺省探测本机端口、SQLBot 白名单参考实现固化为测试夹具、企微/飞书换码支持 `api_base`（私有化/代理/测试可重定向）后启用原 `@Disabled` 用例；验证：全量测试 0 skipped

### 10. 后端消息与文档

<!-- comet-task-ref:5dd66d70-931e-44a4-88b2-4920f12c51cb -->
- **5dd66d70-931e-44a4-88b2-4920f12c51cb** 10.1 `resources/messages.properties`、`messages_en.properties`、`messages_ko_KR.properties`、`messages_th_TH.properties`、`messages_zh_CN.properties`、`messages_zh_TW.properties` 六份文件同步新增本次全部错误与提示 key；验证：脚本比对六份文件 key 集合一致且无重复
<!-- comet-task-ref:ef837d10-e6b6-44a9-873f-19c11537b544 -->
- **ef837d10-e6b6-44a9-873f-19c11537b544** 10.2 更新 `askdata/askdata-server/README.md`：模型配置流程、类型与状态说明、新接口、迁移说明；验证：README 中给出的命令与路径实际存在
<!-- comet-task-ref:b34991a9-8183-41df-8897-a0502a5cccd6 -->
- **b34991a9-8183-41df-8897-a0502a5cccd6** 10.3 `AGENTS.md` 中模块说明与当前实现保持一致（若提及模型相关内容）；验证：文档描述与代码实际类名/接口一致

### 11. 提供商配置与国际化

<!-- comet-task-ref:9229352e-d2b1-41da-8fd7-a631ddf1cf75 -->
- **9229352e-d2b1-41da-8fd7-a631ddf1cf75** 11.1 新增 `web/src/assets/model/icon_custom_plug.svg`（32px 画布、插头造型、与现有彩色图标同一视觉重量）；验证：`ModelList.vue` 中渲染不变形、明暗背景下均可辨识
<!-- comet-task-ref:8c060ccb-4327-412c-a708-da39b84b3c91 -->
- **8c060ccb-4327-412c-a708-da39b84b3c91** 11.2 `supplier.ts` 中 `id=15` 改为 `name: '自定义'`、`i18nKey: 'supplier.custom'`、`icon: icon_custom_plug`，清空 `api_domain`，移除全部静态 `model_options`；验证：页面选择该提供商后 Base URL 为空、基础模型下拉无预设
<!-- comet-task-ref:deb92465-8340-4473-a534-8b59c80dcf0f -->
- **deb92465-8340-4473-a534-8b59c80dcf0f** 11.3 `base_model_options()` 在自定义提供商下返回空数组而非抛错，调用方容忍空值；验证：单测断言返回 `[]` 且表单不报错
<!-- comet-task-ref:e3a06eeb-d861-4be7-a58f-e634ef4c8f76 -->
- **e3a06eeb-d861-4be7-a58f-e634ef4c8f76** 11.4 五份语言文件新增 `supplier.custom`：`自定义`/`自訂`/`Custom`/`사용자 지정`/`กำหนดเอง`，并保留 `supplier.generic_openai` 不删；验证：脚本比对五份文件 key 集合一致
<!-- comet-task-ref:6c483949-f380-40e3-8b7a-09bfe69acd11 -->
- **6c483949-f380-40e3-8b7a-09bfe69acd11** 11.5 界面术语「供应商」统一改为「提供商」（仅文案，代码标识不变），覆盖 `model.select_supplier` 及同类 key，简繁两体分别取「提供商」/「提供商」；验证：全局搜索五份语言文件中 `供应商`、`供應商` 展示位无残留旧称
<!-- comet-task-ref:af5a3bbc-28bd-4a08-b0f1-a5cc3280aa68 -->
- **af5a3bbc-28bd-4a08-b0f1-a5cc3280aa68** 11.6 `entity/CommonEntity.ts` 的 `modelTypeOptions` 补齐 LLM 与向量，并承载格式能力矩阵；验证：单测断言各格式下可用类型集合正确

### 12. 前端表单改造

<!-- comet-task-ref:56b8ec8e-fd08-4493-beb6-4f6ea2102861 -->
- **56b8ec8e-fd08-4493-beb6-4f6ea2102861** 12.1 `ModelForm.vue` 重排字段顺序为：模型名称 → Base URL → API Key → 模型类型 → 基础模型 → 是否生效 → 高级配置；验证：新增与编辑两种状态下的 DOM 顺序快照一致
<!-- comet-task-ref:434ea73a-fcde-47f6-8c78-d7dc5234468b -->
- **434ea73a-fcde-47f6-8c78-d7dc5234468b** 12.2 模型名称与基础模型完全解耦，选中模型不改写名称；验证：单测或 e2e 断言改模型后名称保持
<!-- comet-task-ref:b445f67b-3fa4-4b94-97c3-32c533df0ad7 -->
- **b445f67b-3fa4-4b94-97c3-32c533df0ad7** 12.3 Base URL 校验只拦截空值与非 `http(s)`，允许路径与查询参数；验证：单测覆盖合法/非法各 4 组
<!-- comet-task-ref:316e66b9-66b2-406a-b76a-6d417b2c1430 -->
- **316e66b9-66b2-406a-b76a-6d417b2c1430** 12.4 API Key 对标准提供商必填、私有提供商可空，编辑态明文回显；验证：单测两种提供商类型下的校验结果
<!-- comet-task-ref:540e5909-ea57-41c2-a6ac-8a4dd4be06fc -->
- **540e5909-ea57-41c2-a6ac-8a4dd4be06fc** 12.5 模型类型：新建默认 LLM，编辑态只读，按上游格式能力置灰；验证：切到 Anthropic Messages 后向量选项禁用
<!-- comet-task-ref:bf1d02c7-0295-4b90-9494-64e88bbedcb7 -->
- **bf1d02c7-0295-4b90-9494-64e88bbedcb7** 12.6 高级配置收敛为单一折叠区，含上游格式、完整 URL 开关、模型参数；有参数时自动展开，参数区标题显示数量；验证：3 条参数时默认展开且标题为「模型参数（3）」
<!-- comet-task-ref:7ff8ca88-361f-4121-b53c-833f0965dae9 -->
- **7ff8ca88-361f-4121-b53c-833f0965dae9** 12.7 基础模型支持远程拉取与手输，标注「远程/预设」来源，唯一结果自动选中，拉取用请求序号丢弃过期响应；验证：单测模拟两次乱序响应，最终采用最后一次
<!-- comet-task-ref:7cf33316-757e-4301-9336-fff50430b709 -->
- **7cf33316-757e-4301-9336-fff50430b709** 12.8 「是否生效」开关仅在 `valid=1` 时可操作，`valid=0` 时置灰并提示需先通过校验；验证：两种状态下开关的 disabled 属性
<!-- comet-task-ref:ec4d3298-551a-4887-9ae3-6399ac59d74b -->
- **ec4d3298-551a-4887-9ae3-6399ac59d74b** 12.9 校验进行中禁止重复提交并显示加载态；验证：连续点击只发一次请求
<!-- comet-task-ref:80fe95c6-8ff9-4df0-9cb6-0cb6186df8e1 -->
- **80fe95c6-8ff9-4df0-9cb6-0cb6186df8e1** 12.10 保留 SQLBot 的地址联动：选中基础模型时自动填充自带地址（模型地址优先，其次供应商默认地址），名称不动；验证：e2e 断言地址被填充且名称未变
<!-- comet-task-ref:43ad8b06-eedb-48e7-969e-740f00d7a80d -->
- **43ad8b06-eedb-48e7-969e-740f00d7a80d** 12.11 保留参数按 `supplier-base_model` 缓存与恢复（含删除参数后的回写）；验证：e2e 切换模型往返后参数一致
<!-- comet-task-ref:f8bb4884-bdda-4b2b-9b05-410bd79789d4 -->
- **f8bb4884-bdda-4b2b-9b05-410bd79789d4** 12.12 保留默认参数合并（`common_args` + 模型 `args`，按 key 去重）与参数表分页（每页 5 条）；验证：选中带默认参数的模型后参数条数与内容正确，超过 5 条出现分页
<!-- comet-task-ref:a449e510-908a-4a05-8bf3-12bfee01ff1d -->
- **a449e510-908a-4a05-8bf3-12bfee01ff1d** 12.13 保留参数显示名与 key 的重复校验，以及提交时 `config_list` 映射为 `{key,name,val}`；验证：重复 key 被拒，请求体字段结构符合后端契约

### 13. 前端列表与默认模型

<!-- comet-task-ref:4adb4830-1ba0-41d2-84df-ad636f5205dd -->
- **4adb4830-1ba0-41d2-84df-ad636f5205dd** 13.1 `Model.vue` 卡片显示「可用/不可用」与「已启用/未启用」双状态；验证：四种状态组合渲染文案正确
<!-- comet-task-ref:0d2271e0-4861-4ac2-86e5-fb4819e80b6f -->
- **0d2271e0-4861-4ac2-86e5-fb4819e80b6f** 13.2 默认模型停用开关禁用、删除按钮禁用并给出切换指引；验证：e2e 断言按钮 disabled
<!-- comet-task-ref:3944b839-fe67-41cb-9dff-9be9a42bff40 -->
- **3944b839-fe67-41cb-9dff-9be9a42bff40** 13.3 设默认只对 `valid=1 && enable=1` 的模型可用，且只影响同类型；验证：e2e 断言设向量默认后 LLM 默认不变
<!-- comet-task-ref:b35b6b5e-c36b-42f7-afd6-370ae6b5ba47 -->
- **b35b6b5e-c36b-42f7-afd6-370ae6b5ba47** 13.4 切换默认向量模型时弹出确认框，说明「需重新向量化且影响所有工作空间」，确认后才执行切换；验证：e2e 断言确认框文案与取消行为
<!-- comet-task-ref:fba48b49-aa0e-4e3a-b6e9-a02993bbea51 -->
- **fba48b49-aa0e-4e3a-b6e9-a02993bbea51** 13.5 模型页常驻「向量索引待重建」提示条：「一键重新向量」按钮 + 进度条，可展示当前空间与已完成/总数；刷新页面后提示仍在；验证：e2e 切换后刷新，断言提示条存在
<!-- comet-task-ref:38532b9c-a353-494a-b16f-cc408c7eb49b -->
- **38532b9c-a353-494a-b16f-cc408c7eb49b** 13.6 重建完成后提示条消失并展示按空间汇总的成功/失败/耗时，失败空间可单独重试；验证：e2e 用 stub 制造单空间失败，断言汇总内容与重试入口

### 14. 关联前端页面适配

<!-- comet-task-ref:1cf15d65-6104-4e94-9e6c-8d6df8a6e57c -->
- **1cf15d65-6104-4e94-9e6c-8d6df8a6e57c** 14.1 `views/system/workspace/AuthorizedWorkspaceDialogForModel*.vue`、`AuthorizedWorkspaceDraw.vue` 按新口径展示（仅 LLM 且已启用），不出现向量模型；验证：e2e 或组件单测断言列表内容
<!-- comet-task-ref:f6b6e03c-685b-44c7-9dac-025913ef5fe2 -->
- **f6b6e03c-685b-44c7-9dac-025913ef5fe2** 14.2 `views/system/embedded/iframe.vue` 的模型下拉在 `list_by_ws` 结果变化后仍可用，无默认模型时不报错；验证：stub 空列表场景页面无 JS 异常
<!-- comet-task-ref:a2efc9cc-6dc3-4cc7-a361-ba7ccf0792d9 -->
- **a2efc9cc-6dc3-4cc7-a361-ba7ccf0792d9** 14.3 `views/chat/index.vue`、`ChatListContainer.vue`、`views/ds/Datasource.vue` 的 `checkLLMModel` 提示区分「未配置」与「已配置但不可用」；验证：两种后端状态下的提示文案不同且不出现 key 原文

### 15. Playwright 前后端联调

<!-- comet-task-ref:460936a1-4782-4418-ba60-cf4242d7710c -->
- **460936a1-4782-4418-ba60-cf4242d7710c** 15.1 在 `askdata/askdata-server/e2e` 下新增本地 stub provider 工具（`/v1/models`、`/v1/chat/completions`、`/v1/embeddings`、`/v1/messages`、`/v1/responses`，可切换 200/401/404/空列表，随机端口，用例结束关闭）；验证：单独脚本启动后可被 curl 命中并正确返回各状态
<!-- comet-task-ref:4d7317ca-3c78-42e5-acf2-0ceacb905494 -->
- **4d7317ca-3c78-42e5-acf2-0ceacb905494** 15.2 重写 `tests/aimodel.spec.ts` 的新增 LLM 模型全流程用例：字段顺序、名称不随模型改写、保存后卡片状态正确；验证：`npx playwright test tests/aimodel.spec.ts -g '新增 LLM'` 通过
<!-- comet-task-ref:ea65f1c7-2a31-4f2f-901e-5e82518fa0b8 -->
- **ea65f1c7-2a31-4f2f-901e-5e82518fa0b8** 15.3 新增模型列表拉取用例：stub 200 显示「远程」并按类型过滤，404 降级「预设」，自定义提供商无预设且提示手输；验证：对应用例全绿
<!-- comet-task-ref:4f85c94d-0250-46fb-8611-74f44d505743 -->
- **4f85c94d-0250-46fb-8611-74f44d505743** 15.4 新增连通校验用例：401 保存为不可用且开关置灰，200 后手动启用成功；向量模型断言请求命中 `/v1/embeddings`；验证：对应用例全绿且断言了请求路径
<!-- comet-task-ref:7d3ffcc1-6754-43b4-a7a4-dfd186f946b4 -->
- **7d3ffcc1-6754-43b4-a7a4-dfd186f946b4** 15.5 新增上游格式用例：选 Anthropic Messages 后向量置灰、请求命中 `/v1/messages`；验证：对应用例全绿
<!-- comet-task-ref:d1f8fcdb-f36e-4cb2-93ed-9a30345f5105 -->
- **d1f8fcdb-f36e-4cb2-93ed-9a30345f5105** 15.6 新增默认模型保护与编辑态只读用例；验证：对应用例全绿
<!-- comet-task-ref:3aa02786-6285-452d-b482-cb5c3a3e02e1 -->
- **3aa02786-6285-452d-b482-cb5c3a3e02e1** 15.7 新增五语言切换用例，断言「自定义」在五种语言下均有译文；验证：对应用例全绿
<!-- comet-task-ref:361b39d8-d8a7-4c2c-a10d-c6e0d0cfb99d -->
- **361b39d8-d8a7-4c2c-a10d-c6e0d0cfb99d** 15.8 更新 `e2e/discover-aimodel.mjs` 中硬编码的「选择供应商」「API 域名」等选择器，使其匹配新文案与图标；验证：脚本可跑通并输出模型页元素树
<!-- comet-task-ref:14de656f-9144-4ef2-a9d9-50e67f8cf95e -->
- **14de656f-9144-4ef2-a9d9-50e67f8cf95e** 15.9 更新 `e2e/README.md`：补充 stub provider 说明、运行前置与新用例覆盖表；验证：README 中的命令可原样执行

### 16. 集成回归

<!-- comet-task-ref:7638bcf1-c844-4d12-8e99-0f7649007a2c -->
- **7638bcf1-c844-4d12-8e99-0f7649007a2c** 16.1 后端全量单测通过：`mvn -pl askdata/askdata-server test`；验证：退出码 0，无 skipped 的新增用例
<!-- comet-task-ref:6f664745-b87d-4e7e-837f-b4cdf58626cd -->
- **6f664745-b87d-4e7e-837f-b4cdf58626cd** 16.2 问数主链路回归：使用真实可用模型完成一次提问→SQL→图表闭环，确认 Chat Completions 分支无行为变化；验证：`npx playwright test tests/chat-real.spec.ts` 通过
<!-- comet-task-ref:5328a96b-283f-40f0-aff5-66d646356581 -->
- **5328a96b-283f-40f0-aff5-66d646356581** 16.3 路由与鉴权冒烟：`npx playwright test tests/routes.spec.ts tests/api.spec.ts` 通过
<!-- comet-task-ref:47747b4c-e997-45e4-84d2-1a5892da814b -->
- **47747b4c-e997-45e4-84d2-1a5892da814b** 16.4 MCP 兼容回归：`mcp_model_list` 在两个工作空间下返回一致结构，`SqlbotMcpCompatController` 字段无缺省；验证：`mvn -pl askdata/askdata-server test -Dtest='*Mcp*'`
<!-- comet-task-ref:a58346a6-2310-4b86-af2e-f1f040cda139 -->
- **a58346a6-2310-4b86-af2e-f1f040cda139** 16.5 迁移后可用模型数校验：迁移前后 `SELECT COUNT(*) FROM ai_model WHERE enable=1 AND valid=1` 不减少；验证：在 H2 存量库上比对输出
<!-- comet-task-ref:fcaf3d4b-ee2a-43ab-887c-50972110f648 -->
- **fcaf3d4b-ee2a-43ab-887c-50972110f648** 16.6 前端构建通过：`npm run build` 无类型错误；验证：退出码 0
<!-- comet-task-ref:03035554-dfce-42ac-a04c-6b716c7ffc09 -->
- **03035554-dfce-42ac-a04c-6b716c7ffc09** 16.7 确认工作区无自动提交：`git status` 显示改动均未提交，`git log -1` 未新增提交；验证：与开始前 HEAD 一致
<!-- comet-task-ref:cd99602f-3968-4dc6-ab34-22a2f5cbd554 -->
- **cd99602f-3968-4dc6-ab34-22a2f5cbd554** 16.8 逐条复核 `reference-parity.md`：B/R/F 三类共 33 条全部覆盖，7 项有意偏离（D1–D7）全部有测试佐证；验证：清单每条均标注覆盖用例或代码位置，无遗留空白
