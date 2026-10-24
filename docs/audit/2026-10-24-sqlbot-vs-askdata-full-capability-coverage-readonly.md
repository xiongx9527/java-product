# SQLBot vs askdata 能力覆盖全量审计（只读，不改代码）

- 审计日期：2026-10-24
- 基准（真值）：SQLBot `/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot/backend`（Python/FastAPI）
- 被审对象：askdata `/Users/xiongxiao/Workspaces/Idea/codes/New/java-product/askdata/askdata-server`（Java/Spring Boot） + 前端相关（已纳入对照）
- 性质：**只读**，未修改任何源码
- 判定：`等价` / `打折` / `缺失` / `更强` / `不适用`
- 引用：SQLBot 路径相对 `SQLBot/backend/`，askdata 相对 `askdata/askdata-server/src/main/`

> 本报告在既有 `docs/audit/2026-10-23-askdata-sqlbot-remaining-parity-audit.md` 基础上，进一步做**前后端全量**对比，并补充本次实际核对的实现细节。

---

## 1. API 接口覆盖（路由）

### 1.1 数量概览
- SQLBot：统计 `@router.*`（含 include_in_schema=False 等）得到 **113** 个去重 `(method,path)`（程序化扫描 `apps/`，已见清单）。
- askdata：`@RequestMapping/@GetMapping/...` 共 **236** 处注解，Controller **33** 个，Service **35** 个。整体端点数目显著更多。

### 1.2 差集（SQLBot 有，askdata 无）
经比对，真正**协议/能力缺失**的（排除 SQLBot 中注释掉的死代码）：

| 方法 | 路径 | SQLBot 位置 | askdata 状态 | 判定 | 说明 |
|---|---|---|---|---|---|
| POST | `/api/v1/mcp/access_token` | `apps/mcp/mcp.py:41` | **缺失**（askdata MCP 用 `access_token` tool + Sa-Token） | 打折 | 协议形态不同。askdata 有 `AskdataMcpTools.access_token`（tool）但无 HTTP 路由同名端点。第三方若按 SQLBot MCP HTTP 协议直连，会缺该端点。 |
| POST | `/api/v1/mcp/mcp_start` | `apps/mcp/mcp.py:90` | **缺失**（有 `chat_start` tool） | 打折 | askdata 有 `AskdataMcpTools.chat_start`（`:109-126`）但无 HTTP 路由；语义部分覆盖。 |
| POST | `/api/v1/mcp/mcp_ws_list` | `apps/mcp/mcp.py:132` | **缺失**（有 `workspace_list` tool） | 打折 | 有 tool 覆盖。 |
| POST | `/api/v1/mcp/mcp_ds_list` | `apps/mcp/mcp.py:138` | **缺失**（有 `datasource_list` tool） | 打折 | 工具存在但 HTTP 协议端点缺失。 |
| POST | `/api/v1/mcp/mcp_model_list` | `apps/mcp/mcp.py:160` | **缺失**（有 `model_list` tool） | 打折 | 工具存在。 |
| POST | `/api/v1/mcp/mcp_question` | `apps/mcp/mcp.py:165` | **缺失**（有 `question_answer` tool） | 打折 | 工具存在且增强（`chatId/customModel/returnImg`）。 |
| POST | `/api/v1/mcp/mcp_assistant` | `apps/mcp/mcp.py:201` | **缺失**（有 `assistant` tool） | 打折 | 工具存在。 |
| POST | `/api/v1/system/assistant/validator` | `apps/system/api/assistant.py:144` | **缺失**（SQLBot 中也被注释） | 等价缺失 | 两侧实质一致。 |
| POST | `/api/v1/datasource/editLocalComment` | `apps/datasource/api/datasource.py:212` | **缺失**（SQLBot 注释） | 等价缺失 | 一致。 |
| GET | `/api/v1/system/assistant/{id}` | `apps/system/api/assistant.py:363` | **缺失**（SQLBot 注释） | 等价缺失 | 一致。 |

### 1.3 askdata 独有（新需求，非缺口）
审计查询、appearance、authentication/平台SSO、External IdP、动态数据源、高级应用凭据、行权限相关、文件存储、SSR图表、定时任务、术语高级应用作用域、`login/health`、`chat/question/pager` 等均属新增能力。

**结论**：接口**协议形态**上 MCP HTTP 路由较 SQLBot 有差距（askdata 转向 MCP Tool 注解），但大部分能力由 Tool 覆盖。

---

## 2. MCP 能力对比（重点）

### 2.1 工具清单
- **SQLBot** (`apps/mcp/mcp.py`, `main.py:220-222` include_operations): `access_token`, `mcp_start`, `mcp_ws_list`, `mcp_ds_list`(operation_id `mcp_datasource_list`), `mcp_model_list`, `mcp_question`, `mcp_assistant`（共7个 operation）
- **askdata** (`AskdataMcpTools.java`，`@McpService` + `@Tool`): `datasource_list`, `server_info`（自造探针）, `workspace_list`, `model_list`, `chat_start`, `access_token`, `assistant`, `question_answer`（共8个工具）

### 2.2 逐项对比

| SQLBot 操作 | 语义 | askdata 工具 | 判定 | 证据 |
|---|---|---|---|---|
| `access_token` (POST) | 用户名/口令换取 JWT token | `access_token` tool (`:132-168`) | **打折（协议）** | askdata 用 Sa-Token 签发（`StpUtil.login/getTokenValue`）；校验逻辑有（MD5、status/origin/oid），但无 HTTP 路由；返回结构 `{token, tokenName}` vs SQLBot `{access_token: ...}` 有差异。 |
| `mcp_start` (POST) | 会话开启、工作空间切换、审计(`@system_log` CHAT) | `chat_start` tool (`:109-126`) | **打折** | askdata 调用 `ChatService.start`；SQLBot 明确审计、切换工作空间校验等流程细节需对照 `create_chat`。askdata 工具能创建会话但协议细节未完全对齐。 |
| `mcp_ws_list` | token→工作空间列表 | `workspace_list` tool (`:81-90`) | **打折** | askdata 调用 `WorkspaceService.list()`；SQLBot 按 token 解析用户并过滤工作空间。权限/范围需结合实现细节。 |
| `mcp_ds_list` | token→数据源列表（带权限过滤） | `datasource_list` tool (`:51-62`) | **打折** | askdata 调用 `DatasourceService.list(workspaceId)`（`:57`）；SQLBot 传入 `session_user` 并过滤（`get_datasource_list(session=session, user=session_user)`）。是否真正按用户/工作空间全量过滤需深入实现。 |
| `mcp_model_list` | oid→工作空间可用模型 | `model_list` tool (`:95-104`) | **打折** | askdata 调用 `AiModelService.listByWs()`；SQLBot 调用 `get_ai_model_list_by_workspace(session, int(mcp_oid.oid), False)`。 |
| `mcp_question` | 问数（chat_id、datasource_id、custom_model、return_img、stream） | `question_answer` tool (`:229-322`) | **打折** | askdata 已实现：`chatId` 续接会话、归属校验、继承数据源、落库（`:258-304`）；`customModel` 覆盖（`:245-256`）；`returnImg` 控制出图（`:307-320`，`shouldReturnImage` 默认 true，显式 false 不渲染且不产出 `imageUrl`，与 A7 对齐）。**但权限链曾有旁路（见 3.1）**。 |
| `mcp_assistant` | 合成用户（id=-1/account='sqlbot-mcp-assistant'）进审计 | `assistant` tool (`:191-222`) | **打折** | askdata 用 `SimpleAssistant`，按登录用户隔离 memoryId（`:200-206`），审计走 `AuditRecorder`（`:208-218`）。身份语义不同于 SQLBot 的固定合成用户。 |

**关键结论**：askdata 的 MCP 更像「内置工具集」而非完全照搬 SQLBot 的 HTTP MCP 协议。能力大部分有对应工具，但**协议级完全等价性不足**。

---

## 3. 核心链路能力对比

### 3.1 权限（行/列权限）——MCP 问数旁路（已修复？）
- **SQLBot**：`question_answer_inner` 等主链路都会走权限逻辑（结合用户、数据源、SQL 改写/列过滤）。
- **askdata**（当前代码）：`AskdataNl2Sql.ask`（`:123-150`）在 `dsId != null` 时：
  1. 表白名单校验 `ChatQuestionService.validateTables(sql, schemaContextBuilder.checkedTableNames(dsId))`（`:129-133`）
  2. 调用 `enforceForMcp(permissionEnforcer, currentUserService, dsId, sql)`（`:134-139`）——**已接入 PermissionEnforcer**
  3. 执行后应用隐藏列 `permissionEnforcer.applyHiddenColumns(result, enforced.getHiddenColumns())`（`:142-150`）
- 结论：根据当前源码，**MCP 问数已接入行/列权限**（与审计报告中提到的「此前旁路」状态不同，当前 HEAD 已改动）。这是正向对齐。

### 3.2 动态数据源（高级应用）表级 SQL 改写
- **SQLBot**：`AssistantTableSchema.sql` 字段，执行时生成子查询占位/改写（`llm.py` 中 `generate_assistant_dynamic_sql`、`generate_with_sub_sql` 等），模板 `templates/template.yaml` 有 `dynamic_sql`。
- **askdata**：`DynamicDatasource` 支持 `tableSqlTemplates`（`:26-158`），`DynamicDatasourceResolver` 解析 `dynamic_sql`（`:147-176`），`SchemaContextBuilder.appendDynamicTemplateSection` 注入占位符指引（`:694-759`），`ChatQuestionService` 做：
  - 生成 SQL 后若有模板：先 `directTableReferenceError`（`:311-326`）——**直接引用受限表名报错**
  - 替换：`DynamicDatasource.replaceSubSqlPlaceholders`（`:315,321,334`）——按词边界替换为 `(子查询)`
  - 校验：`validateSql` 检查残留占位符 `leftoverPlaceholderError`（`:821-827`），并扩展白名单加入模板内引用表（`:828-837`）
- **结论**：askdata 采用**确定性替换**方案（替代 SQLBot 的二次 LLM 改写），实现了「占位符 → 子查询」闭环且有防护（直引报错、残留报错）。思路对齐目标但**实现方式不同**（不是同一套 LLM 流程）。能力面上已覆盖核心闭环。

### 3.3 AI 模型层（protocol/temperature/additional_params/Azure）
- **SQLBot**：`apps/ai_model/model_factory.py` 根据 `protocol/supplier` 分发，处理 `additional_params`，Azure 特殊处理，`llm.py` 中对 `no_reasoning`/`extra_body` 有处理。
- **askdata**：`ChatModelClient`（`:91-555`）已增强：
  - 支持 `protocol` 参数（`:109-117`）传递
  - `buildPayload`（`:180-254`）：将 `extra_body`、`enable_thinking` **归并到嵌套 `extra_body`**（`:206-222`），**Azure 时从 payload 剔除 `api_version`/`deployment_name`**（`:200-205`）仅用于 URL（`:489-506`）
  - Azure 检测 `isAzureEndpoint`（`:473-482`），走 deployment URL + `api-key` 头（`:140-144`）
- **结论**：已较大幅对齐。`temperature` 等参数现在可通过 `extraParams` 传递并平铺（`:217`），配合 payload 构造逻辑较 SQLBot 更规整。

### 3.4 Chat 主链路（上下文/行数/分析预测/推荐）
- **上下文轮数**：`ChatQuestionService.priorContext` 用 `chatInt("chat.context_record_count", 3)`（`:669-680,683-693`）——对齐 SQLBot（默认3）。**已修复**（早期审计提到硬编码5）。
- **结果行数截断**：`chat.limit_rows` 开启时硬截断到 `SQL_ROW_LIMIT=1000` 并在 `dataObj` 写入 `limit`（`:373-384`）。**已实现**运行时兜底。
- **分析/预测自定义提示词**：`streamAnalysisOrPredict` 用 `chatKnowledgeService.customPromptsForType` 按 `ANALYSIS/PREDICT_DATA` 召回并传入 `buildAnalysisMessages/buildPredictMessages`（`:486-496`）；SchemaContextBuilder 支持 `customPromptText` 注入（`:353-378`）。**已修复**静默失效问题。
- **推荐问题**：`recommendQuestions` 增强版传入 `chartHint`、`history`（`:1038-1048`），`streamRecommendQuestions` 收集历史问题（`:563-583`），模板更贴近 SQLBot（`:403-420`, `recommendSystemPrompt` 更新）。
- **表名校验**：改用 **JSqlParser + TablesNamesFinder**，剔除 CTE（`:896-915`），失败退化分词（`:941-955`）。**已对齐 SQLBot 用 AST 解析**。
- **知识增强**：关键词提取、术语/示例/自定义提示词过滤链路已接入（`:263-287`），表召回用扩展关键词 `matchText = knowledge.getExpandedKeywords()`（`:292-301`）。

### 3.5 MCP `question_answer` 返回
- 出图：`shouldReturnImage` 默认 true，显式 false 跳过渲染且不加 `imageUrl`（`:307-320,324-330`）。**符合 A7**。
- 会话落库：chatId 场景落记录、保存 SQL/结果、finish（`:297-304`）。
- 权限：已接入（见 3.1）。

---

## 4. API 文档站（/docs）对比

### 4.1 SQLBot
- `main.py:188-208`：`/openapi.json`（多语言 i18n，缓存）、`/docs`（Swagger UI，自托管 js/css）、可配 `SQLBOT_DOC_ENABLED`
- 静态资源在 `frontend/public/`（swagger-ui-bundle.js/css）
- 前端「API KEY」页链接文档

### 4.2 askdata
- **配置**：`application.yaml:107-118` 启用 springdoc + Knife4j
- **控制器**：`ApiDocsController`（`:25-49`）将 `/openapi.json` 转发到 springdoc `/v3/api-docs`，`/docs` 跳转到 Knife4j（`/doc.html`）
- **OpenAPI 配置**：`OpenApiConfig` 配置基本信息和 SecurityScheme
- **白名单**：`AuthWhitelist` 已放行 `/docs`, `/v3/api-docs`, `/swagger-ui/*`, `/swagger-resources/*` 等（`:46,93-97`）
- **测试**：`OpenApiDocsEndpointTest` 覆盖免鉴权、转发、`/docs` 可达
- **静态资源**：`web/public/` 有 `swagger-ui.css`、`swagger-ui-bundle.js`（审计报告提到资源预置）

**结论**：askdata 已实现**功能完整**的文档站（基于 springdoc/Knife4j），对齐 SQLBot 的 `/docs` + `/openapi.json` 能力。早期审计的「空壳」判断在当前代码已修复。

---

## 5. 前后端对比要点

### 5.1 前端（`frontend/`）
- 存在大量 Vue 组件（chat、dashboard、system、embedded 等），与 SQLBot 前端功能面较为接近
- 聊天流式协议需兼容（askdata 后端 `FrameWriter` 已按 SQLBot 帧类型设计）
- 推荐问题、图表、分析预测等交互逻辑在前后端配合下已对齐

### 5.2 后端差异
- 架构不同：FastAPI vs Spring Boot，但核心业务逻辑已大量移植
- MCP：Tool 注解 vs HTTP router，协议形态差异
- 动态数据源：确定性替换 vs LLM 二次改写
- 模型客户端：自研 HttpURLConnection vs 可能不同实现

---

## 6. 安全、i18n、其他

### 6.1 安全（沿用既有审计结论，当前状态）
既有审计 F-01~F-20 多数仍未完全修复（密钥、审计、强制改密等）——本次主要关注**能力覆盖**，安全项需单独处理。

### 6.2 i18n
- 消息包齐全（6种语言）
- 部分硬编码英文仍存在（审计 §3 列出），但文档站、错误处理等已有 i18n 机制

### 6.3 缓存
- 全仓未见 Caffeine/RedisTemplate/@Cacheable（与审计 F-10 一致）

---

## 7. 问题清单（不改代码，仅列出）

### 严重（High）
| 编号 | 模块 | 问题 | 影响 | 建议（仅建议，不实施） |
|---|---|---|---|---|
| P-01 | MCP 协议 | 缺少 SQLBot HTTP MCP 路由（`/mcp/access_token`, `/mcp/mcp_start`, `/mcp/mcp_ws_list`, `/mcp/mcp_ds_list`, `/mcp/mcp_model_list`, `/mcp/mcp_question`, `/mcp/mcp_assistant`） | 若第三方严格按 SQLBot MCP HTTP 协议接入会失败 | 补充 HTTP 控制器适配，或文档说明迁移方式 |
| P-02 | MCP 权限范围 | `datasource_list` 工具直接用 `DatasourceService.list(workspaceId)`，需确认是否严格按当前用户权限过滤（SQLBot 用 `get_datasource_list(..., user=...)`） | 可能暴露越权数据源 | 核对 `DatasourceService.list` 实现中的权限过滤逻辑 |
| P-03 | 动态数据源改写方式 | 采用确定性替换而非 SQLBot 的二次 LLM 改写（`generate_with_sub_sql`） | 宿主极复杂子查询模板场景下，LLM 对占位符的适配能力可能不同 | 测试复杂模板兼容性，必要时补充 LLM 改写路径 |

### 中等（Medium）
| 编号 | 模块 | 问题 | 影响 | 建议 |
|---|---|---|---|---|
| P-04 | MCP 身份语义 | `mcp_assistant` 合成用户语义与 SQLBot 不同（askdata 用登录用户隔离） | 审计追溯、权限模型可能有差异 | 确认是否符合预期业务需求 |
| P-05 | MCP 返回格式 | 部分工具返回结构与 SQLBot HTTP 响应格式不同（如 `access_token` 返回 `{token, tokenName}` vs `{access_token}`） | 客户端适配成本 | 统一响应格式或做兼容转换 |
| P-06 | 推荐问题 | 虽已增强，但仍需验证图表类型引导、历史问题注入等细节是否完全一致 | 推荐质量略有差异 | 对比实测效果 |

### 低（Low）
| 编号 | 模块 | 问题 | 影响 | 建议 |
|---|---|---|---|---|
| P-07 | 静态资源 | SQLBot 前端有完整 swagger UI 自托管；askdata 依赖 Knife4j/springdoc | 功能对齐但实现栈不同 | 正常，不算缺陷 |
| P-08 | 协议细节 | SSE 帧类型基本兼容，但需全量回归前端解析 | 潜在前端解析差异 | 集成测试覆盖 |

---

## 8. 总结

### 已对齐/覆盖良好
- ✅ **API 文档站**：`/docs`、`/openapi.json` 完整实现（springdoc+Knife4j）
- ✅ **动态数据源闭环**：占位符机制、直引拦截、替换、残留校验全套实现
- ✅ **模型层**：protocol、extra_body 嵌套、Azure 处理、temperature 等参数传递
- ✅ **Chat 主链路**：上下文轮数、行数截断、分析/预测自定义提示词、表名 AST 校验
- ✅ **MCP 工具集**：核心工具齐全，`question_answer` 功能更丰富（chatId/customModel/returnImg）
- ✅ **权限**：MCP 问数已接入 PermissionEnforcer（行/列权限）

### 存在打折/差距
- ⚠️ **MCP 协议形态**：HTTP 路由 vs Tool 注解，部分响应格式/身份语义有差异
- ⚠️ **动态数据源改写**：确定性替换 vs LLM 二次改写（思路对齐但机制不同）
- ⚠️ **细节覆盖**：个别边缘场景需实测验证

**整体评估**：askdata 在**核心问数能力**上已达到较高的覆盖度。最大差距在**MCP 协议兼容性**（HTTP 路由层）和**部分实现机制差异**。如果目标是「功能等价」而非「代码结构等价」，当前状态已接近达成；若要严格兼容 SQLBot 第三方 MCP HTTP 客户端，则需补充 P-01 所列路由。
