# askdata-sqlbot-gap 完整目标规格

本文描述本次在 askdata（问数）中补齐的相对 SQLBot 缺失/偏弱的能力：Elasticsearch 数据源可用化（G2）、MCP 集成（G1）、i18n 多语言（G3），以及向量/RAG 检索核实（G4）。除本文明确新增/变更的部分外，其余行为沿用已归档的 askdata 正式规格（`docs/comet/specs/askdata/spec.md`）。

## 1. Elasticsearch 数据源可用化（补齐 EsEngine）

### 场景：ES 类型数据源连通检查

用户在数据源管理中选择类型 `ES`（Elasticsearch），填写地址（http/https + host:port）、认证（无/账号密码/API Key）与默认索引/库。系统对 ES 执行连通检查：

- 连通成功返回成功；认证失败/地址不可达返回明确错误（对齐 SQLBot `check_connection` 的 is_raise 分支）。
- `DatasourceType.ES` 由“占位/语义不保证”升级为可用引擎：新增 `EsEngine` 实现，或复用现有 `JdbcEngine` 之外的 ES 连接/查询路径。

验收：A2

### 场景：ES 元数据（索引与字段）

对 ES 数据源可获取元数据：索引列表（对应“表”）与字段映射（对应“列”，含字段名、类型、是否可查）。对齐现有 JDBC 数据源的 `getTables` / `getFields` 语义。

验收：A2

### 场景：ES 预览与执行

对 ES 索引可执行查询（如 `match_all` 或指定字段过滤），返回预览数据（默认 LIMIT 100）；`execSql` 路径对 ES 走 ES 查询方言而非 SQL。问数链路勾选 ES 数据源后，能对其实行索引生成查询并返回数据。

验收：A2

### 约束

- 不改变现有 JDBC/Excel 数据源行为。
- ES 驱动/客户端随父 POM `fw-plus-ai-build` 的版本管理引入（BOM 已含），不在子模块硬编码版本。
- 单测不可启动真实 ES 时，提供基于 Mock/内存 ES 或 HTTP mock 的测试，并以文档说明本地 e2e 验证方式。

验收：A2

## 2. MCP 集成（新增 MCP server）

### 场景：MCP server 启动与工具暴露

askdata 提供 MCP server，可配置为 stdio 或 SSE 传输，启动时注册问数相关工具。暴露工具至少包括：

- `question_answer`（问数）：输入自然语言问题与数据源标识，返回生成的 SQL 与查询结果（对齐 `POST /chat/question` 核心行为）。
- `datasource_list`（数据源列表）：返回当前用户/工作空间可见的数据源。
- `assistant`（助手/对话）：可选的连续对话入口。

验收：A3

### 场景：MCP client 调用问数工具

MCP client（如 n8n / Dify / MaxKB / DataEase）可连接本 MCP server 并调用 `question_answer`，获得与站内问数一致的结果。鉴权沿用 askdata 现有用户/工作空间/API Key 体系（不新建独立鉴权）。

验收：A3

### 约束

- 不新建问数内核，MCP 工具复用现有 `ChatQuestionService` / `ChatService` / `DatasourceService`。
- MCP server 不暴露给未授权来源；传输与端口受应用配置控制。
- 不实现与问数无关的工具集（保持最小可用）。

验收：A3

## 3. i18n 多语言（接入 MessageSource）

### 场景：核心文案迁到消息包

将面向用户/接口的核心文案、错误码文案迁到 Spring `MessageSource` + 消息包（`messages_zh_CN.properties` / `messages_en.properties` 等），替代散落的硬编码中文字符串。

验收：A4

### 场景：按 locale 切换

支持按请求 `Accept-Language` / 参数切换语言；缺省为中文。接口返回与错误文案随 locale 变化。

验收：A4

### 约束

- 不要求一次覆盖全部文案；优先覆盖核心接口与错误文案，其余逐批迁移（本 change 完成骨架与核心覆盖）。
- 现有 `Locale`/`i18n` 痕迹（6 处）与新增体系不冲突，统一收敛到 `MessageSource`。

验收：A4

## 4. 向量/RAG 检索核实（G4）

### 场景：核实 embedding/RAG 检索链路

核实 askdata 现有 embedding 用法（12 处，含 `SchemaContextBuilder` / `ChatModelClient`）的检索链路：是否对表/字段注释、术语库做向量检索并注入提示词。若与 SQLBot 的向量 RAG 检索存在明显差距（如缺少向量库持久化/检索），则补强；否则记录结论“与 SQLBot 持平”。

验收：A5

## 5. 约束与非目标

- 不重写 askdata 已有能力（问数、术语库、训练、仪表盘、用户/权限、提示词、数据权限、AI 模型、推荐问题、预测、embedding/RAG、API Key、审计）。
- 不做跨语言移植（SQLBot 为 Python）。
- 不实现与本次缺口无关的新产品能力。
- 不改流式协议帧格式与字段语义。

## 6. 回归基线

- 既有单测全部通过（基线 83/83）。
- 新增能力（ES/MCP/i18n）均有对应单测或集成测试；MCP 至少有启动/工具列表/一次问数调用的验证。
- ASCII/中文表名的既有问数链路行为不变。
