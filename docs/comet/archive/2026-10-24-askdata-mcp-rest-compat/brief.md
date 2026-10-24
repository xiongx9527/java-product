# 目标

让直接以 HTTP REST 方式调用 SQLBot MCP 路由（POST /api/v1/mcp/*）的第三方客户端零改造接入 askdata：补齐 7 个 SQLBot HTTP 端点（薄适配层，委托既有 MCP 工具实现），响应键与错误形态对齐 SQLBot。MCP 协议客户端兼容已由 askdata-mcp-sqlbot-compat 完成，本 change 只补 REST 载体。

# 范围

仅限 HTTP REST 适配层，不改 MCP 工具实现与业务逻辑。

## 目录结构

### 新建

- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/SqlbotMcpCompatController.java
  - @RestController，前缀 /api/v1/mcp，7 个 POST 端点镜像 SQLBot 路由路径：access_token、mcp_start、mcp_ws_list、mcp_ds_list、mcp_model_list、mcp_question、mcp_assistant（另注册 mcp_datasource_list 作为 mcp_ds_list 的别名路径，与 operation_id 一致）
  - 解析 SQLBot pydantic 模型形态的 JSON body（ChatToken/ChatStart/token/McpDs/WsMcp/McpQuestion/McpAssistant），委托 AskdataMcpTools 对应方法（token 身份绑定、snake_case 参数映射、响应键组装复用既有实现）
  - 工具返回 {"error": ...} 时转 HTTP 400 {"detail": ...}（镜像 SQLBot HTTPException 形态）

### 修改

- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/AuthWhitelist.java
  - 放行 /api/v1/mcp/**（REST 客户端以 body 内 token 鉴权，无 Sa-Token 会话头）
- 新增测试覆盖端点映射、委托与错误转换

### 删除

（无）

### 明确不建

- 不改 AskdataMcpTools 既有工具与别名实现
- 不实现 SQLBot JWT 签发（沿用 askdata Sa-Token 令牌，token 反查复用既有 applyTokenIdentity 机制）
- 不做 stream 流式响应（stream 参数接受并忽略，与 MCP 层一致）

# 非目标

- 不变更 MCP 协议层（sse/messages 传输与工具面）
- 不对齐 SQLBot 审计中间件的每字段细节（委托实现已含审计）
- 不改错误文案 i18n 机制

# 验收示例

- 验收：A1 7 个 SQLBot HTTP 路由全部可达且委托既有 MCP 工具实现，成功响应键与 SQLBot 对齐（access_token 返回 {access_token}；mcp_start 返回 {access_token, chat_id}；列表类返回数组）
- 验收：A2 /api/v1/mcp/** 免登录拦截放行，body 携带 token 时按 Sa-Token 反查绑定身份
- 验收：A3 业务错误返回 HTTP 400 {"detail": "..."}，镜像 SQLBot HTTPException 形态
- 验收：A4 全量回归通过（mvn clean test，JDK 17），既有用例无回退，新增测试覆盖端点映射、委托与错误转换

