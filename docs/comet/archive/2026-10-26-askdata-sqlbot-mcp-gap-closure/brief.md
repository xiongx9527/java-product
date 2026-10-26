# 目标

把 SQLBot → java-product 能力覆盖审计中剩余的 H/M/L 差距全部补齐，使 `/api/v1/mcp/*` 兼容层在**非流式响应与落库、流式默认值与参数、mcp_assistant 合成用户链路、数据源绑定优先级、mcp_start 会话语义、字段裁剪、错误码、数据源授权、工作空间/数据源列表形状**上与 SQLBot 的行为一致，同时不破坏 askdata 既有聊天主链路与前端契约。

本次修复的审计项：

- H1：非流式 `mcp_question` 返回 SQLBot JSON（`success/sql/data/chart/record_id/image_url?`），并按 `{fields,data,datasource,limit}` 落库，而不是只返回/落库裸行数组。
- H2：`mcp_question`、`mcp_assistant` 的 `stream` 缺省值必须为 `true`。
- H3：流式路径必须尊重 `return_img=false` 与 `custom_model`，不得因 `custom_model` 退回同步 JSON，也不得在 `return_img=false` 时出图。
- M1：`mcp_assistant` 必须要求 `url`/`authorization`，以合成用户创建会话与真实记录、落审计，并支持流式。
- M2：`datasource_id` 仅在会话尚未绑定数据源时生效；已绑定会话以已绑定数据源为准。
- M3：`mcp_start` 不创建 `first_chat` 空记录；`brief` 为问题前 20 字符或当前时间。
- M4：原生 `datasource_list` 也必须裁掉 `configuration`/`embedding`/`table_relation`/`recommended_config`。
- M5：无效 token → 403；非流式 `mcp_question` 运行期错误 → 500 `{message}`；其他工具错误保持 400 `{detail}`。
- M6：MCP 问数必须调用 `DsGrantService.checkUsable`，不能绕过数据源使用授权。
- L1：`mcp_ws_list` 只返回 `{id,name}` 并按名称排序。
- L2：`mcp_start` 响应只保留 `{access_token, chat_id}`。
- L3：非流式结果必须包含 `fields`（与 H1 同一数据源）。

# 范围

覆盖 SQLBot MCP 兼容层及其直接复用的问数主链路：

- `SqlbotMcpCompatController` 的流式/非流式分流、HTTP 状态码、SSE 响应与 `mcp_assistant` 端点。
- `AskdataMcpTools` 的 7 个 SQLBot operation_id、原生别名、`mcp_start`/`mcp_assistant` 语义、列表字段裁剪。
- `ChatQuestionService` 的流式问数参数（`custom_model`、`return_img`、`datasource_id`）、数据源授权、非流式结果组装与记录落库复用。
- `ChatService` 的 MCP 会话创建（无首条空记录）与合成助手会话创建。
- `ChatModelClient.FrameWriter` 的响应捕获能力（供非流式结果从同一管线提取帧）。
- `WorkspaceService` 的工作空间选项（`{id,name}`）与排序。
- 审计组件对合成用户（id=-1）的审计落库支持。
- 相关单元测试、控制器切片测试与 H2 集成测试；模块 README 的 MCP 行为说明。

## 目录结构

### 新建

- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/ForbiddenException.java` — 403 业务拒绝异常；MCP 兼容层将无效 token 映射为 HTTP 403 `{"detail"}`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/McpAssistantSupport.java` — `mcp_assistant` 合成身份上下文（id=-1/account=`sqlbot-mcp-assistant`/oid=1）、宿主数据源解析、会话创建的共用入口，避免控制器与工具层重复实现。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/mcp/McpSqlbotGapClosureTest.java` — 列表裁剪、工作空间选项、`mcp_start` 响应形状、错误映射、非流式结果形状等纯函数/切片测试。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/mcp/McpAssistantFlowTest.java` — 合成用户、会话/记录/审计、url/authorization 必填与 stream 行为测试。

### 修改

- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/SqlbotMcpCompatController.java` — `stream` 缺省 true；`custom_model` 不再触发同步回退；`return_img`/`datasource_id`/`custom_model` 传入流式管线；非流式 `mcp_question` 的 500 `{success:false,message}` 映射；无效 token 403；`mcp_assistant` 的 url/authorization 必填、合成会话、流式/非流式分流。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/AskdataMcpTools.java` — `mcp_question` 改走统一问数管线；`mcp_start` 只返回 `{access_token,chat_id}` 且不建空首记录；`datasource_list` 统一裁字段；`workspace_list`/`mcp_ws_list` 只返回 `{id,name}` 并按名称排序；`mcp_assistant` 必填校验、合成会话、无本地 assistant 回退。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatQuestionService.java` — 新增 `ask(...)` 非流式入口（捕获同一流式管线的帧并组装 SQLBot JSON）；支持 `custom_model`、`return_img`、`datasource_id` 仅未绑定生效；解析最终数据源时调用 `DsGrantService.checkUsable`；支持 `finish_step=QUERY_DATA`（`mcp_assistant` 不生成图表）；保留既有帧顺序。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatService.java` — 新增 `startForMcp`（不建 `first_chat` 空记录，`brief` 问题前 20 字符或时间戳）与 `startForMcpAssistant`（合成助手会话）；Web `/chat/start` 行为不变。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatModelClient.java` — `FrameWriter` 增加基于 `Writer` 的构造/帧观察能力，供非流式 `ask` 在不伪造 `HttpServletResponse` 的情况下复用流式输出。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/service/WorkspaceService.java` — 新增 `listOptions()`：返回翻译后名称的 `{id,name}`，按名称升序，供 MCP 工作空间列表使用。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/audit/AuditRecorder.java` — `currentUid()` 接受 `AuthContext` 中的合成负 id（-1），使 `mcp_assistant` 的 CHAT 创建审计可落库；普通登录/支线通道行为不变。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/mcp/SqlbotMcpCompatControllerTest.java` — 更新/新增 stream 默认、500/403、mcp_assistant、mcp_start 响应断言。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/mcp/McpSqlbotCompatTest.java` — 更新 `mcp_start` 响应只含两键；补充工作空间/数据源列表形状断言。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/chat/service/ChatPipelineTest.java` — 覆盖 `return_img=false` 无 `imageUrl`、`custom_model` 选模、已绑定会话忽略 `datasource_id`、绑定/选库均调用授权检查、`ask` 非流式 JSON 与落库形状。
- `askdata/askdata-server/README.md` — 补充 MCP 兼容层的新语义（stream 默认、非流式 JSON、错误码、mcp_assistant、字段裁剪）。

### 删除

无。

### 明确不建

- 不新建独立 MCP 传输/协议实现 — 继续复用 fw-plus MCP 框架与 `/api/v1/mcp/*` 控制器。
- 不新建认证机制 — 无效 token 的 403 仍由既有 Sa-Token token 反查产生，不引入 JWT/API Key 新体系。
- 不新建图表渲染器或 LLM 客户端 — 继续复用 `ChartImageService`、`ChatChartBuilder`、`ChatModelClient`。
- 不新建数据源 CRUD 或权限模型 — `DsGrantService` 已存在，只补调用点。
- 不新建前端页面或修改 Web 前端 — 本次只改后端 MCP 兼容层与问数链路。
- 不新建 `sys_user` 合成用户行 — SQLBot 的 `sqlbot-mcp-assistant` 同样不落用户表；仅以请求级 `AuthContext` 承载。
- 不批量回填历史 `chat_record.data` — 旧记录保持原样，新记录按新形状落库；回填需要独立的数据迁移决策。

# 非目标

- 不逐字复刻 SQLBot 的 markdown 流式文本（`> record_id`、````sql`、markdown 表格）；askdata 继续使用 SSE `data:{json}` 帧，审计项只要求 stream 默认值与参数生效。
- 不实现 FastAPI/pydantic 的 422 字段校验细节；缺字段/非法 id 按既有 400 `{"detail"}` 口径处理。
- 不改变原生 `assistant` 工具（本地多轮 `SimpleAssistant`）的行为。
- 不改变 Web `/chat/start`、`/chat/question`、`/chat/record/*` 的既有响应契约。
- 不改变 REST `/datasource/list` 的返回（前端仍需要 `configuration` 等字段）。
- 不改变 `AskdataNl2Sql` 对原生 `question_answer`（无会话）的 `{question,sql,dialect,rows}` 返回。
- 不改变审计表结构、聊天表结构或 `chat_log` 的既有字段。
- 不处理审计清单之外的 MCP 差异（如 `lang` 白名单、`mcp_start` 无凭据时的 400 校验）。
- 不实现 SQLBot 的 `mcp_question` 必填 `chat_id` 校验；askdata 继续允许无 `chat_id` 的扩展调用，但统一按 A1 返回并创建会话。

# 验收示例

- A1：非流式 `mcp_question`（`stream=false`，带或不带 `chat_id`）返回 SQLBot JSON：`success=true`、`record_id`、`sql`、`data` 对象（含 `fields` 数组与 `data` 数组；有数据源时含 `datasource`；发生 1000 行截断时含 `limit`）、`chart` 对象；`return_img` 未显式关闭且产出图片时含 `image_url`。该次查询在 `chat_record.data` 中落库的对象与响应 `data` 一致，不再是裸行数组；`data.fields` 与结果字段同名同序（覆盖 H1、L3）。
- A2：`mcp_question` 带 `chat_id` 时，`stream` 字段缺省或为 `true` 返回 `text/event-stream`（SSE `data:{json}` 帧），为 `false` 返回 JSON；`mcp_assistant` 的 `stream` 缺省或为 `true` 返回 SSE，为 `false` 返回 JSON；缺省不再等价于 `false`。
- A3：`mcp_question` 流式请求 `return_img=false` 时不渲染图片、不发送 `imageUrl` 帧；非流式时响应不含 `image_url`；`return_img` 缺省/`true` 时仍按原逻辑出图。同一请求带可用的 `custom_model` 时，SQL 生成与图表生成使用该模型（模型名/端点与所选模型一致），不再因 `custom_model` 退回同步 JSON；模型 id 不存在/不可用时返回错误（流式错误帧或非流式 `success=false`），不静默使用默认模型。
- A4：`mcp_assistant` 缺少 `url` 或 `authorization` 时返回 400 `{"detail":...}`。合法请求以合成用户（id=-1、account=`sqlbot-mcp-assistant`、oid=1，不写入 `sys_user`）创建会话（不创建 `first_chat` 空记录），按 `url`+`authorization` 拉取宿主数据源并创建一条真实 `chat_record`（`first_chat=false`），落一条 CHAT 创建审计（`sys_logs.user_id=-1`）与问数 `chat_log`；随后执行问数。`stream` 缺省/`true` 返回 SSE，`false` 返回 JSON（`finish_step=QUERY_DATA`，不生成图表/图片）；不再回退到本地 `assistant()` 多轮问答。
- A5：会话已绑定数据源 D1 时，`mcp_question` 传入 `datasource_id=D2` 仍在 D1 上生成/执行 SQL，`chat.datasource` 与 `chat_record.datasource` 保持 D1；会话未绑定数据源时传入 D2 则在 D2 上执行并把 `chat.datasource` 写为 D2；未传且未绑定时沿用 RAG 选库并回写；流式与非流式行为一致。
- A6：`mcp_start`（及 `chat_start` 工具）只创建 `chat`，不创建任何 `first_chat=TRUE` 的空 `chat_record`；`chat.brief` 在有 `question` 时为去空白后的前 20 个字符，无 `question` 时为当前时间 `yyyy-MM-dd HH:mm:ss`；传入 `datasource_id` 时仍按既有授权校验后写入 `chat.datasource`。Web `/chat/start` 的首条空记录行为不变。
- A7：原生 `datasource_list` 与 `mcp_datasource_list` 返回的数据源数组元素均不含 `configuration`、`embedding`、`table_relation`、`recommended_config`；其余既有字段保留，并按名称升序。REST `/datasource/list` 不受影响。
- A8：`mcp_question`、`mcp_start`、`mcp_ws_list`、`mcp_ds_list`、`mcp_model_list` 在 token 无效时返回 HTTP 403 `{"detail":...}`；非流式 `mcp_question` 的运行期错误（会话不存在、无数据源、模型不可用、SQL 执行失败、授权拒绝）返回 HTTP 500 `{"success":false,"message":...}`（如已创建记录则带 `record_id`）；其他工具错误保持 HTTP 400 `{"detail":...}`；`mcp_question` 的 `datasource_id`/`chat_id`/`custom_model` 格式非法时按 SQLBot 校验返回 400 `{"detail":...}`。
- A9：`mcp_question` 在流式与非流式路径上，对最终使用的数据源（会话已绑定、显式 `datasource_id` 或 RAG 选中）调用 `DsGrantService.checkUsable`；被拒绝时不生成/执行 SQL，非流式返回 500 `{success:false,message}`，流式发送错误帧；管理员与已授权用户不受影响。
- A10：`mcp_ws_list`（及原生 `workspace_list`）返回的数组元素只含 `id` 与 `name` 两个键，按 `name` 升序（翻译后的名称），不再包含 `create_time` 等字段；`mcp_ds_list` 继续按名称升序。
- A11：`POST /api/v1/mcp/mcp_start` 成功响应只含 `access_token` 与 `chat_id` 两个键（有令牌时两者都有；无令牌的既有会话场景可缺省 `access_token`，但不得出现其他键），不再返回 `id`、`brief`、`datasource`、`engine_type` 等会话详情。

# 约束与不变量

- 既有 SSE 帧顺序保持：`id → question → info → sql-result → sql → sql-data → chart-result → chart → imageUrl → recommended_question → finish`；`return_img=false` 只移除 `imageUrl`，`finish_step=QUERY_DATA` 在 `sql-data` 后结束。
- Web 聊天主链路（`/chat/question`、`/chat/start`、分析/预测）行为与既有测试不变；`ChatPipelineTest` 既有断言继续通过。
- MCP 记录必须仍是真实问答记录（`first_chat=false`），不能把 `mcp_start` 的空记录语义扩散到 `mcp_question`/`mcp_assistant`。
- 数据源使用授权（`DsGrantService`）与行/列权限（`PermissionEnforcer`）不得被绕过；`mcp_assistant` 的动态数据源仍按宿主数据边界执行。
- 审计写入失败不得阻断问数；普通登录/API Key/嵌入助手通路的审计行为不变。
- 敏感字段不得进入任何 MCP 列表响应；REST 前端契约不受影响。
- 不新增硬编码密钥、token 或环境相关地址。

# 决定

- D1：非流式 `mcp_question` 复用 `ChatQuestionService` 的同一流式管线（通过捕获帧组装结果），不再走 `AskdataNl2Sql` 的简化路径。理由：H1/L3 要求 `fields`、`chart`、`datasource`、`limit` 与主链路一致；复用管线同时保证权限、知识增强、记录落库与流式行为不分叉。`AskdataNl2Sql` 保留给原生 `question_answer`（无会话）与既有测试。
- D2：`mcp_question` 无 `chat_id` 时创建一个 MCP 会话（`origin=1`，`brief=question[:20]`）再执行，保证非流式结果始终有 `record_id` 和落库数据；这是 askdata 对 SQLBot 必填 `chat_id` 的兼容扩展，不改变 Web `/chat/start`。
- D3：`mcp_assistant` 使用 SQLBot 的合成身份（id=-1、account=`sqlbot-mcp-assistant`、oid=1），不写入 `sys_user`；以请求级 `AuthContext` 承载，并在 `AuditRecorder` 中允许该合成 id 落审计。理由：与 SQLBot `apps/mcp/mcp.py:201-205` 对齐，同时避免伪造真实用户。
- D4：`mcp_assistant` 使用 `finish_step=QUERY_DATA`，只生成 SQL + 数据，不生成图表/图片；`mcp_question` 仍生成图表并按 `return_img` 决定是否出图。理由：SQLBot `mcp_assistant` 显式传 `ChatFinishStep.QUERY_DATA`。
- D5：`datasource_id` 优先级为「会话已绑定 > 请求显式 > RAG 选库」；对最终数据源统一调用 `DsGrantService.checkUsable`，包括已绑定会话。理由：M2 要求已绑定优先，M6 要求授权不可绕过；对已绑定会话复检可防止授权撤销后继续问数。
- D6：错误映射采用「无效 token → 403 detail；非流式问数运行期错误 → 500 `{success:false,message}`；其他工具错误 → 400 detail；非法 id 格式 → 400 detail」。理由：对齐 SQLBot `get_user` 的 403、`question_answer_inner` 非流式的 500、`HTTPException` 的 400。
- D7：`mcp_start` 与 `chat_start` 共用 `ChatService.startForMcp`（无首条空记录）；Web `/chat/start` 仍走 `start`（保留 `first_chat`）。理由：M3 只针对 MCP 会话语义，不能破坏前端首问引导。
- D8：流式格式继续使用 askdata SSE `data:{json}` 帧，不逐字复刻 SQLBot markdown 文本。理由：既有 MCP 流式客户端与测试已按该帧协议接入；本次审计项针对默认值与参数，不要求帧文本格式。
- D9：`return_img` 缺省 true；`custom_model` 缺省使用默认模型。`custom_model` 同时用于 SQL 生成与图表生成，并与流式/非流式无关。理由：SQLBot `McpQuestion` 的两个字段均参与同一次问数，模型选择不应因流式而失效。

# 源文档完整覆盖

覆盖边界：用户在 2026-10-26 会话中给出的 SQLBot → java-product MCP 能力覆盖审计 H1–L3 清单（全部 12 项），以及为理解这些差距所必需的 SQLBot 参考实现（`/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot` 的 `apps/mcp/mcp.py`、`apps/chat/api/chat.py`、`apps/chat/task/llm.py`、`apps/chat/curd/chat.py`、`apps/system/crud/user.py`）。范围外能力不纳入本次覆盖。

| 来源条目与位置 | 读取状态 | 需要保留的内容 | Spec 位置 | 验收 ID | 覆盖状态 | 理由或替代关系 |
| --- | --- | --- | --- | --- | --- | --- |
| S1：审计 H1（非流式 mcp_question 响应/落库） | complete | 返回 `{success,sql,data:{fields,data},chart,record_id,image_url?}`；`chat_record.data` 落 `{fields,data,datasource,limit}` | `specs/askdata-sqlbot-mcp-gap-closure/spec.md` §1 | A1 | covered | 本次修复 |
| S2：审计 H2（stream 缺省值） | complete | `stream` 缺省按 `true` 处理 | 同上 §2 | A2 | covered | 本次修复 |
| S3：审计 H3（流式 return_img/custom_model） | complete | `return_img=false` 不出图；`custom_model` 在流式生效且不退回同步 | 同上 §3 | A3 | covered | 本次修复 |
| S4：审计 M1（mcp_assistant） | complete | 必填 url/authorization；合成用户建会话/记录/审计；支持 stream | 同上 §4 | A4 | covered | 本次修复 |
| S5：审计 M2（datasource_id 优先级） | complete | 仅会话未绑定数据源时生效 | 同上 §5 | A5 | covered | 本次修复 |
| S6：审计 M3（mcp_start 空记录/brief） | complete | 不建 first_chat 空记录；brief 问题前 20 字符或时间戳 | 同上 §6 | A6 | covered | 本次修复 |
| S7：审计 M4（原生 datasource_list 裁剪） | complete | 裁掉 configuration/embedding/table_relation/recommended_config | 同上 §7 | A7 | covered | 本次修复 |
| S8：审计 M5（错误码） | complete | 无效 token 403；非流式问数 500 message；其他 400 detail | 同上 §8 | A8 | covered | 本次修复 |
| S9：审计 M6（数据源授权） | complete | MCP 问数调用 `DsGrantService.checkUsable` | 同上 §9 | A9 | covered | 本次修复 |
| S10：审计 L1（工作空间列表形状） | complete | 仅 `{id,name}`，按名称排序 | 同上 §10 | A10 | covered | 本次修复 |
| S11：审计 L2（mcp_start 响应形状） | complete | 仅 `{access_token,chat_id}` | 同上 §11 | A11 | covered | 本次修复 |
| S12：审计 L3（fields） | complete | 非流式结果包含 `fields` | 同上 §1 | A1 | covered | 与 H1 合并验收，避免重复项 |

# 待解决问题

无。

# 验收执行要求

- 相关测试随模块运行：`mvn -pl askdata/askdata-server -am test -Dmaven.test.skip=false`；至少运行 `-Dtest=SqlbotMcpCompatControllerTest,McpSqlbotCompatTest,McpSqlbotGapClosureTest,McpAssistantFlowTest,ChatPipelineTest`。
- 控制器切片测试覆盖：stream 缺省/true/false、500 `{success:false,message}`、403 `{"detail"}`、`mcp_start` 两键、`mcp_assistant` 必填与流式分流。
- 管线集成测试（H2 + 本地 stub LLM/宿主端点）覆盖：非流式 `data`/`chart`/`fields`/`record_id`、落库形状、`return_img=false`、`custom_model`、已绑定会话忽略 `datasource_id`、授权拒绝、`mcp_start` 无空首记录。
- 列表/响应形状测试覆盖：`datasource_list`/`mcp_datasource_list` 四字段裁剪、`mcp_ws_list` 仅两键并按名称排序、`mcp_ds_list` 名称排序。
- 合成助手测试覆盖：url/authorization 缺失 400、合成会话/记录/审计行、`finish_step=QUERY_DATA` 无图表帧。
- 独立只读 Verifier 运行全部验收项；不把 Builder 自测结论当作验收结论。
