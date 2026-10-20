# 目标

用框架（fw-plus）替换 askdata 中手写的 MCP server 与手写聊天 LLM/RAG 后端：
- MCP 改用 `fw-plus-ai-mcp-server-spring-boot-starter`；
- 聊天/问答的 LLM 与 RAG 引擎改用 fw-plus 的 langchain4j `AssistantBuilder`/`SimpleAssistant`（用户已确认“全面迁移到框架 Assistant”）。
删除已无法编译的 `fw-plus-ai-model`（`AiChatModel`）依赖与手写 MCP 包，并以**真实端到端**（启动应用 + 真实 HTTP MCP 握手）验证，而非仅单元测试。

# 范围

## 目录结构

### 新建
- langchain4j `ChatLanguageModel` / `EmbeddingModel` Bean：复用现有 `ChatModelRouter` 的连接配置（base url / apiKey / modelName，OpenAI 或 Ollama 兼容端点）。
- askdata `Assistant` 装配（`@Configuration`）：经 `AssistantBuilder` 链式构建，注入 `ChatLanguageModel`、`StreamingChatLanguageModel`、`systemMessageProvider`（人设/系统提示）、`memoryStore`（DB 持久化，复用现有聊天表）、可选 `RetrievalAugmentor`（自研 `ContentRetriever` 包装 `SchemaContextBuilder` 的向量检索）。
- MCP 工具类（`@McpService`，无参构造，经 ApplicationContext 持有者调用业务服务）：`QuestionAnswerTool`、`DatasourceListTool`、`AssistantTool`，分别对应 `question_answer` / `datasource_list` / `assistant`。
- 真实端到端集成测试：启动 askdata（MCP 开启 + stub `ChatLanguageModel`），通过 HTTP 向 `/mcp` 发送 `initialize` → `tools/list` → `tools/call(question_answer)`，断言得到真实回复。

### 修改
- `askdata-server/pom.xml`：新增 `fw-plus-ai-mcp-server-spring-boot-starter:0.1.1-SNAPSHOT`、`fw-plus-ai-langchain4j-open-ai-spring-boot-starter`（或 adapter）+ langchain4j；移除 `fw-plus-ai-model`（已不存在）。
- `ChatQuestionService`：LLM 调用与 RAG 检索改走框架 Assistant（保留问数/Text-to-SQL 执行与结果格式化逻辑）。流程：检索上下文 → 组装 context/contents → `assistant.chat(memoryId, context, contents)` → 解析 SQL/答案 → 执行 → 格式化。
- `SchemaContextBuilder`：embedding 改走 langchain4j `EmbeddingModel`（同一端点/模型，保证现有向量兼容）；余弦检索逻辑保留。
- `ChatModelRouter`：保留（为 langchain4j 模型 Bean 提供连接配置）。
- 现有 REST 聊天端点（`ChatService`）改走框架 Assistant（同步 `chat` / 流式 `streamToSse`）。
- 应用配置：新增 `fw-plus.ai.mcp.enabled=true`、`fw-plus.ai.mcp.transport=http-sse`、`fw-plus.ai.mcp.endpoint=/mcp`、`fw-plus.ai.mcp.auth.provider=sa-token-login`（复用现有 Sa-Token 会话）。

### 删除
- `askdata/mcp` 整个手写包（McpServer、McpStdioLauncher、McpSupport、McpTool、QuestionAnswerer、ChatQuestionAnswerer、DatasourceLister、config/McpConfig、config/McpController 及 tools/*）。
- `McpServerTest`（手写）。
- `ChatModelClient`（实现已不存在的 `AiChatModel`）；其调用方适配到新 Assistant / EmbeddingModel。
- 旧 `askdata.mcp.*` 配置（改为 `fw-plus.ai.mcp.*`）。

### 明确不建
- 不保留 stdio 传输（框架仅 http-sse / streamable-http）。
- 不 fork 框架改前缀（沿用 `fw-plus.ai.mcp.*`）。

# 非目标
- 不重写 askdata 的问数/Text-to-SQL 业务编排（保留）。
- 不替换现有 DB 聊天记忆表结构（用框架 `ChatMemoryStore` 适配）。
- 不引入新的向量库（沿用现有 DB 向量）。

# 验收示例

- **A1 框架 Assistant 接入**：askdata 装配出框架 Assistant Bean；集成测试证明 `chat(memoryId, context, contents)` 在 stub 模型下返回非空，且 embedding 走 langchain4j `EmbeddingModel`。
- **A2 框架 MCP**：手写 MCP 包已删除，改用 `fw-plus-ai-mcp-server-spring-boot-starter`；`@McpService` 工具注册成功；`fw-plus.ai.mcp.enabled` 缺省即开（matchIfMissing），默认 http-sse；上下文加载测试通过。
- **A3 真实端到端（核心）**：启动 askdata（MCP 开启），用真实 HTTP 客户端向 `/mcp` 完成 MCP `initialize` → `tools/list` → `tools/call(question_answer)`，断言返回真实问答内容（证明 MCP 链路 + 聊天后端都通）。直接回应“不要忽悠”。
- **A4 RAG/embedding 迁移**：`ChatModelClient` 移除，embedding 与聊天 LLM 均走 langchain4j；现有向量仍可复用（同一端点/模型）。
- **A5 REST 聊天不回退**：现有 REST 聊天端点 smoke 测试在 Assistant 后端下仍可用（同步 + 流式）。

# 约束与不变量
- Java 8 / Spring Boot 2.7.18；fw-plus 0.1.1-SNAPSHOT 线（与框架 starter 一致）。
- 工具类为反射实例化（`@McpService`），不能依赖 Spring 注入，业务服务须经 ApplicationContext 持有者获取。
- 鉴权重用现有 Sa-Token 会话（`sa-token-login`）。

# 决策
- MCP 配置前缀用框架原生的 `fw-plus.ai.mcp.*`（不 fork）。
- MCP 仅 HTTP/SSE（http-sse / streamable-http），丢弃 stdio。
- 聊天 LLM/RAG 引擎迁移到框架 Assistant；askdata 的问数/SQL 编排与 DB 向量保留，RAG 检索经自研 `ContentRetriever` 接入框架 `RetrievalAugmentor`（保留现有向量）。

# 开放问题
- 无阻塞项。生产 LLM 后端（OpenAI / Ollama）由现有 `ChatModelRouter` 配置决定；测试用 stub。
