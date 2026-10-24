# Capability: mcp-sqlbot-compat（SQLBot 预设 MCP 客户端兼容层）

本规格描述兼容层完成后 askdata MCP 服务的对外工具面：SQLBot 预设客户端无需改造即可完成全部问数链路调用。

## 1. 工具名兼容

### Scenario: SQLBot 工具名全部可用
- **WHEN** 客户端以 SQLBot 工具名 mcp_start、mcp_ws_list、mcp_datasource_list、mcp_model_list、mcp_question、mcp_assistant 调用 MCP 服务
- **THEN** 每个名称均解析为 askdata 对应实现（chat_start/workspace_list/datasource_list/model_list/question_answer/assistant），行为与原生工具等价，返回相同业务结果
验收：A1

### Scenario: mcp_start 响应结构
- **WHEN** 客户端调用 mcp_start
- **THEN** 响应同时包含 access_token（当前令牌）与 chat_id（新建会话标识），与 SQLBot mcp_start 响应键一致
验收：A1

### Scenario: 参数名兼容
- **WHEN** 别名工具收到 SQLBot 风格 snake_case 参数（datasource_id、chat_id、custom_model、return_img、oid 等）
- **THEN** 内部映射到对应实现参数后正常执行；token 参数在会话未认证时按 Sa-Token 反查登录身份，查不到时返回可读错误
验收：A1

## 2. access_token 响应兼容

### Scenario: access_token 字段并存
- **WHEN** 用户以正确账号口令调用 access_token 工具
- **THEN** 响应同时包含 access_token、token、tokenName 三个字段，access_token 与 token 值一致
验收：A2

### Scenario: 现有调用方不受影响
- **WHEN** 既有 askdata 客户端按原字段（token/tokenName）解析响应
- **THEN** 行为不变
验收：A2

## 3. 回归约束

### Scenario: 既有行为不回归
- **WHEN** 全量测试执行（mvn -o -pl askdata/askdata-server clean test，JDK 17，-Dmaven.test.skip=false）
- **THEN** 全部通过，现有用例无回退；新增测试覆盖别名等价、参数映射与 access_token 响应结构
验收：A3

