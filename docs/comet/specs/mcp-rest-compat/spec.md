# Capability: mcp-rest-compat（SQLBot MCP HTTP REST 兼容层）

本规格描述兼容层完成后 askdata 对直接 HTTP 调用 SQLBot MCP 路由客户端的完整行为：POST /api/v1/mcp/* 七端点镜像 SQLBot，委托既有 MCP 工具实现。

## 1. 路由覆盖

### Scenario: 七端点全部可达
- **WHEN** 客户端 POST /api/v1/mcp/access_token、/mcp_start、/mcp_ws_list、/mcp_ds_list、/mcp_model_list、/mcp_question、/mcp_assistant（含 /mcp_datasource_list 别名路径）
- **THEN** 每个端点解析 SQLBot 形态 JSON body 并委托对应 MCP 工具实现，返回工具 JSON（application/json）
验收：A1

### Scenario: 成功响应键对齐
- **WHEN** access_token 登录成功 / mcp_start 建会话成功
- **THEN** 响应分别为 {access_token} 与 {access_token, chat_id}（mcp_start 另附会话详情）；列表类端点返回 JSON 数组
验收：A1

### Scenario: mcp_question 流式返回
- **WHEN** POST /mcp_question 携带 stream=true 且带 chat_id
- **THEN** 响应为 text/event-stream（data:{json} 帧，与 /chat/question 一致），身份绑定在打开流之前完成；未传 stream（默认同步，保持既有 MCP 客户端兼容）、缺少 chat_id 或指定 custom_model 时返回同步 JSON
验收：A1

## 2. 鉴权

### Scenario: 免会话头访问
- **WHEN** 客户端无 Sa-Token 会话头直接调用 /api/v1/mcp/** 端点
- **THEN** 请求不被登录拦截（AuthWhitelist 放行），身份以 body 内 token 为准
验收：A2

### Scenario: token 身份绑定
- **WHEN** body 携带有效 token（access_token 端点签发的 Sa-Token 令牌）
- **THEN** 该次调用以 token 对应用户身份执行（行/列权限、工作空间过滤均按该用户生效）

### Scenario: token 无效直接拒绝
- **WHEN** body 携带非空但无法反查登录身份的 token
- **THEN** 不执行任何业务逻辑，返回 400 {"detail": "无效或过期的 token"}
验收：A2

## 3. 错误形态

### Scenario: 业务错误镜像 SQLBot
- **WHEN** 委托的工具返回 {"error": ...}（如账号密码错误、chatId 不存在）
- **THEN** HTTP 状态 400，body 为 {"detail": "<可读原因>"}
验收：A3

## 4. 回归约束

### Scenario: 既有行为不回归
- **WHEN** 全量测试执行（mvn -o -pl askdata/askdata-server clean test，JDK 17，-Dmaven.test.skip=false）
- **THEN** 全部通过，既有用例无回退；新增测试覆盖端点映射、body 解析、委托与错误转换
验收：A4

