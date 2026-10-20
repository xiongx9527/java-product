# askdata-mcp-framework 目标规格

> 本规格描述 Archive 后 askdata 的 MCP 集成与聊天后端的**完整目标行为**（替换手写实现，底座迁移到 fw-plus 框架）。

## 1. MCP 集成（框架封装）

askdata 经由 `fw-plus-ai-mcp-server-spring-boot-starter` 暴露 MCP server，取代手写 JSON-RPC 实现。

- **配置前缀**：`fw-plus.ai.mcp.*`（框架原生，不 fork）。
- **开启**：`fw-plus.ai.mcp.enabled` 缺省即开（matchIfMissing=true）。
- **传输**：仅 HTTP——`http-sse`（默认）或 `streamable-http`；无 stdio。端点 `fw-plus.ai.mcp.endpoint`（默认 `/mcp`），复用应用 `server.port`。
- **鉴权**：`fw-plus.ai.mcp.auth.provider=sa-token-login`，复用现有 Sa-Token 会话；部署可改为 `permit-all` 等。
- **工具**（经框架 `@McpService` 扫描注册，反射实例化，业务服务通过 ApplicationContext 持有者获取）：
  - `question_answer`：问数/问答，委托 askdata 的聊天后端（框架 Assistant + 问数编排）。
  - `datasource_list`：列出数据源。
  - `assistant`：助手类对话。
- **不变的能力**：MCP client 可发现并调用上述工具；默认开启；HTTP/SSE 可达。

## 2. 聊天/问答后端（框架 langchain4j Assistant）

askdata 的 LLM 与 RAG 引擎迁移到 fw-plus 的 `AssistantBuilder`/`SimpleAssistant`，取代手写 `ChatModelClient`（原 `AiChatModel`）。

- **模型**：langchain4j `ChatLanguageModel` 与 `StreamingChatLanguageModel` 由 `ChatModelRouter` 现有连接配置（base url / apiKey / modelName，OpenAI 或 Ollama 兼容端点）装配；`EmbeddingModel` 同样来自该配置（同一端点/模型，保证现有 DB 向量兼容）。
- **记忆**：框架 `ChatMemoryStore` 以 DB 持久化实现，复用 askdata 现有聊天表；多轮上下文按 memoryId 隔离。
- **RAG**：自研 `ContentRetriever` 包装 askdata 既有 `SchemaContextBuilder` 向量检索，接入框架 `RetrievalAugmentor`，保留现有 DB 向量与余弦检索逻辑。
- **系统提示**：由 askdata 人设/系统提示 provider 注入。
- **调用契约**：`Assistant.chat(@MemoryId memoryId, @V("context") context, @UserMessage List<Content> contents)` 返回回复文本；`stream(...)`/`streamToSse(...)` 提供流式。
- **复用**：askdata 的问数/Text-to-SQL 业务编排与结果格式化保留，仅将“调用 LLM 生成 SQL/答案”与“向量 embedding”两段替换为框架能力。
- **REST 与 MCP 统一后端**：现有 REST 聊天端点与 MCP `question_answer`/`assistant` 工具均走该框架 Assistant，外部问答能力不回退。
