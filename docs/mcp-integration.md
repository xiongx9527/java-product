# askdata MCP 业务系统对接文档

askdata-server 提供两套面向业务系统/客户端的 MCP 接入方式，能力对齐 SQLBot 的 MCP 7 operations（数据源列表、工作空间列表、模型列表、开启会话、换取 token、问数、助手）：

1. SQLBot 兼容 REST 层（POST /api/v1/mcp/*）：镜像 SQLBot apps/mcp/mcp.py 的路由路径、请求形态与响应键，便于已对接 SQLBot 的业务系统零改造迁移。
2. 原生 MCP（fw-plus，传输 http-sse，端点 /mcp）：标准 MCP 协议，工具由 AskdataMcpTools 暴露，适合按 MCP 客户端规范接入。

完整且权威的接口定义以运行时 OpenAPI 为准：GET /v3/api-docs、/doc.html（Knife4j）。

## 1. 鉴权模型

- 兼容 REST 层 /api/v1/mcp/** 在 AuthWhitelist 中放行（与 SQLBot whitelist.py 逐字对齐项），免会话头访问；每次调用以请求体内的 token 标明身份。
- 换 token：POST /api/v1/mcp/access_token（账号口令 → {access_token, token, tokenName}），或 MCP 工具 access_token。
- 原生 MCP 走 Sa-Token 登录会话，调用需带请求头 X-SQLBOT-TOKEN: Bearer <token>（token 头与前缀与 SQLBot 前端逐字兼容）。

## 2. SQLBot 兼容 REST 层（/api/v1/mcp）

所有接口为 POST，请求体为 JSON（snake_case 字段），身份以 body 内 token 为准。工具返回含 error 键时，控制器转为 HTTP 400 {detail:...}（镜像 SQLBot HTTPException）；非流式问数的运行期错误返回 500 {success:false,message}。

| 端点 | 请求字段 | 说明 / 返回 |
|------|----------|------------|
| /access_token | username, password | {access_token, token, tokenName} |
| /mcp_start | username?, password?, token?, oid?, datasource_id?, question? | 开启会话，返回 {access_token, chat_id, ...} |
| /mcp_ws_list | token（也可 query） | 当前用户可管理的工作空间数组 |
| /mcp_ds_list（别名 /mcp_datasource_list） | token, oid? | 数据源数组（按用户过滤；管理员可指定 oid） |
| /mcp_model_list | token, oid | 当前工作空间可用模型数组 |
| /mcp_question | token, chat_id?, question, datasource_id?, custom_model?, lang?, stream?, return_img? | 问数。stream 缺省/true 返回 text/event-stream（SSE）；显式 false 返回 JSON。支持自定义模型与出图 |
| /mcp_assistant | question, url, authorization, stream?, datasourceId? | 助手对话。stream 缺省/true 返回 SSE；url/authorization 必填，以合成身份创建会话 |

问数 SSE 帧格式与 ChatQuestionService 一致（type=error / finish 等），前端按事件流消费即可。

## 3. 原生 MCP（/mcp，http-sse）

- GET /mcp/sse 建立 SSE 流；POST /mcp/messages/{uuid} 向该流发送消息（JSON-RPC 2.0）。
- 传输与端点由配置控制：fw-plus.ai.mcp.enabled=true（默认开）、transport=http-sse、endpoint=/mcp、auth.provider=sa-token-login。
- 客户端按 MCP 规范 initialize 后，可调用以下工具（@McpService 扫描注册）：

| 工具名 | 参数（required） | 说明 |
|--------|----------------|------|
| server_info | 无 | 连通性探针，返回 {app, mcp, transport} |
| access_token | username, password | 账号口令换 token |
| datasource_list | workspaceId? | 可用数据源（JSON 数组） |
| workspace_list | 无 | 当前用户可管理的工作空间 |
| model_list | 无 | 当前工作空间可用对话模型 |
| chat_start | datasourceId?, question? | 开启问数会话，返回 chat_id |
| question_answer | question, datasourceId?, chatId?, customModel?, returnImg? | 问数（NL2SQL → 只读执行 → 返回 SQL 与结果行） |
| assistant | message | 多轮记忆问数助手（基于默认对话模型） |

## 4. 业务系统对接示例（问数闭环）

以「业务系统把自然语言问题发给 askdata 取数」为例（兼容 REST 层）：

1. 换 token：POST /api/v1/mcp/access_token {username, password} → 取 access_token。
2. 列数据源：POST /api/v1/mcp/mcp_ds_list {token} → 选 datasource_id。
3. 问数（流式）：POST /api/v1/mcp/mcp_question {token, question, datasource_id, stream:true} → 消费 SSE，得到 NL2SQL 生成的 SQL 与结果行。
4. 如需多轮，带 chat_id 续接；需要图表可设 return_img:true。

若按标准 MCP 客户端接入，则用原生 /mcp：initialize → 调用 question_answer 工具（token 经 X-SQLBOT-TOKEN 头）。

## 5. 启用与配置

- 原生 MCP 默认即开（fw-plus.ai.mcp.enabled=true）；如需关闭在配置中置 false。
- 问数/助手（question_answer、assistant 工具与 /mcp_question）依赖大模型底座：需 ASKDATA_ASSISTANT_ENABLED=true 并在「系统 → AI 模型」配置默认对话模型（见部署文档 §2.5）。
- 兼容 REST 层的白名单放行项属于框架 AuthWhitelist，与 SQLBot 逐条等价（由 AuthWhitelistParityTest 校验）。

## 6. 真实环境测试

MCP 兼容层有专门测试覆盖：SqlbotMcpCompatControllerTest（21 个用例）验证端点路径、token 身份、响应键、错误映射与流式/非流式切换，均已在真实 Spring Boot + 内嵌 PostgreSQL 下通过；原生 MCP 工具层由 AskdataMcpTools 单测与全量套件覆盖。详见 docs/verification/sqlbot-parity-verification.md。
