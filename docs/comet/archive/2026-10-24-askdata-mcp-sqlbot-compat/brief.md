# 目标

让使用 SQLBot 预设 MCP 配置的客户端零改造接入 askdata MCP 服务：补齐工具名兼容（SQLBot 7 个 operation_id）与 access_token 响应结构兼容，客户端无需修改任何工具名、参数名或响应解析逻辑即可完成登录、建会话、查数据源/模型、问数与助手对话全链路。

# 范围

仅限 MCP 工具层兼容，不改 Chat/datasource/model 业务实现。

## 目录结构

### 新建

（无）

### 修改

- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/AskdataMcpTools.java
  - 新增 7 个 SQLBot 兼容名 @Tool 别名方法，委托现有实现：
    - access_token（同名复用，仅响应结构调整）
    - mcp_start → chat_start（响应含 access_token/chat_id）
    - mcp_ws_list → workspace_list
    - mcp_datasource_list → datasource_list（注意 SQLBot 真实名为 mcp_datasource_list，路由路径才是 mcp_ds_list）
    - mcp_model_list → model_list
    - mcp_question → question_answer
    - mcp_assistant → assistant
  - 别名方法按 SQLBot 参数签名声明（username/password/token/oid/chat_id/datasource_id/question/custom_model/lang/stream/return_img 等 snake_case 形态），内部映射到现有参数；token 参数在当前会话未认证时经 Sa-Token 按 token 反查登录身份（复用 access_token 签发的令牌，不做新鉴权机制）
  - access_token 响应增加 access_token 字段（值为签发的 token），保留 token/tokenName 字段兼容现有调用方
- 新增测试覆盖别名行为等价与 access_token 响应结构

### 删除

（无）

### 明确不建

- 不实现 SQLBot 的 HTTP 裸 REST 路由（/mcp/* 直连端点），只做 MCP 协议层工具别名
- 不改 askdata 现有 8 个工具名与行为
- 不做 SQLBot 数据源/模型响应字段的逐字段对齐（结构差异由客户端 LLM 消化）

# 非目标

- 不变更身份/鉴权语义：不引入新 token 类型、不改 Sa-Token 签发逻辑
- 不实现 mcp_question 的 stream 流式返回差异（stream 参数接受并忽略）
- 不对齐 SQLBot 错误响应格式（沿用 askdata errorJson 约定）
- 不改 Chat 主链路与权限实现

# 验收示例

- 验收：A1 使用 SQLBot 工具名（mcp_start/mcp_ws_list/mcp_datasource_list/mcp_model_list/mcp_question/mcp_assistant）调用 MCP 服务，每个别名均可调用且行为与对应 askdata 原生工具等价（mcp_start 响应含 access_token 与 chat_id）
- 验收：A2 access_token 工具成功响应同时包含 access_token、token、tokenName 三个字段，access_token 值等于签发令牌
- 验收：A3 全量回归通过（mvn clean test，JDK 17），既有用例无回退，新增测试覆盖别名等价与响应结构

