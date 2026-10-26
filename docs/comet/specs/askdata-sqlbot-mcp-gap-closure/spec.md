# askdata-sqlbot-mcp-gap-closure 完整目标规格

> 本规格描述本次 change 完成后 SQLBot MCP 兼容层及其直接复用的问数主链路的目标行为。
> 除本文明确变更的部分外，其余行为沿用已归档的正式规格：
> `docs/comet/specs/askdata/spec.md`、`docs/comet/specs/askdata-mcp-framework/spec.md`、
> `docs/comet/specs/askdata-sqlbot-parity/spec.md`、`docs/comet/specs/askdata-sqlbot-full-parity/spec.md`、
> `docs/comet/specs/askdata-sqlbot-xpack-parity/spec.md`、`docs/comet/specs/askdata-sqlbot-deep-parity/spec.md`。
>
> 事实源：SQLBot → java-product MCP 能力覆盖审计的 H1–L3 差距清单（2026-10-26），以及
> SQLBot 开源版参考实现（`/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot` 的
> `apps/mcp/mcp.py`、`apps/chat/api/chat.py`、`apps/chat/task/llm.py`、`apps/chat/curd/chat.py`、
> `apps/system/crud/user.py`）。

## 1. 非流式 mcp_question 响应与落库（H1 / L3）

### 场景：非流式返回 SQLBot 结果对象

`POST /api/v1/mcp/mcp_question` 请求体 `stream=false` 时返回 HTTP 200 JSON 对象，包含：
`success=true`、`record_id`（本次问数的真实记录 id）、`sql`（实际执行的 SQL 文本）、
`data` 对象、`chart` 对象；`return_img` 未显式关闭且本次问数产出图片时还包含 `image_url`。

`data` 对象必须含 `fields` 数组与 `data` 数组：`fields` 按结果列顺序给出字段名，`data` 为行数组；
本次问数绑定了数据源时含 `datasource`（数据源 id）；SQL 执行发生 1000 行上限截断时含 `limit`。
不再返回裸行数组或只含 `question/sql/rows` 的简化结构。

`chart` 为图表配置对象；未生成图表时为 `null`。文件级错误不进入该成功结构，遵循 §8 的错误映射。

验收：A1

### 场景：结果对象与记录落库一致

非流式 `mcp_question` 成功时，`chat_record.data` 落库的 JSON 对象与响应 `data` 对象一致：
均含 `fields`、`data`，有数据源时含 `datasource`，发生截断时含 `limit`。
`chat_record.sql` 与响应 `sql` 一致，`chat_record.chart` 与响应 `chart` 一致，`chat_record.finish=true`。
后续 Web 端读取该记录的 `/chat/record/{id}/data` 与该记录在会话列表中的展示不受影响。

验收：A1

### 场景：无 chat_id 时创建会话后问数

非流式 `mcp_question` 未传 `chat_id` 时，先创建一个 MCP 会话（`origin=1`，
`brief` 为去空白后的问题前 20 字符），再在该会话中创建真实记录并执行问数，返回的 `record_id`
属于该新建会话。该扩展行为不改变 Web 端 `/chat/start` 与 `/chat/question` 的既有契约。

验收：A1

## 2. stream 缺省值（H2）

### 场景：stream 缺省按流式处理

`/api/v1/mcp/mcp_question` 与 `/api/v1/mcp/mcp_assistant` 的 `stream` 字段缺省或为 `null` 时，
按 `true` 处理：响应 `Content-Type` 为 `text/event-stream`，按既有 SSE 帧协议逐帧输出
`data:{json}`。显式 `stream=false` 时返回 JSON 对象；显式 `stream=true` 时返回 SSE。
缺省不再等价于 `false`。

验收：A2

## 3. return_img 与 custom_model（H3）

### 场景：return_img=false 不出图

`mcp_question` 显式传 `return_img=false` 时，流式路径不渲染图片、不发送 `imageUrl` 帧，
非流式路径响应不含 `image_url`；`chat_record` 中的图表配置仍按原逻辑生成。
`return_img` 缺省或为 `true` 时保持既有出图行为（有图片时流式发送 `imageUrl` 帧、
非流式返回 `image_url`）。

验收：A3

### 场景：custom_model 在流式与非流式均生效

`mcp_question` 传可用的 `custom_model` 时，SQL 生成与图表生成均使用该模型（请求的模型名与端点
与所选模型一致），流式路径仍返回 SSE，不再因存在 `custom_model` 退回同步 JSON。
`custom_model` 缺省时使用当前工作空间的默认对话模型。Web 端 `/chat/question` 的模型选择行为不变。

验收：A3

### 场景：custom_model 不可用时明确报错

`custom_model` 指向不存在或当前用户/工作空间不可用的模型时，不静默回退默认模型：
流式路径发送错误帧后结束，非流式路径返回 §8 定义的失败结构，消息说明模型不可用。

验收：A3

## 4. mcp_assistant 合成用户链路（M1）

### 场景：url 与 authorization 必填

`POST /api/v1/mcp/mcp_assistant` 缺少 `url` 或 `authorization`（空白视为缺失）时返回
HTTP 400 `{"detail":...}`，不创建会话、不写记录、不发起问数；不再回退到本地
`assistant()` 多轮问答。

验收：A4

### 场景：以合成用户创建会话、记录与审计

合法的 `mcp_assistant` 请求以 SQLBot 对齐的合成身份执行：当前用户 id 为 `-1`、
account 为 `sqlbot-mcp-assistant`、oid 为 `1`，不写入 `sys_user` 表，仅由请求级
`AuthContext` 承载。系统以该身份创建会话（不创建 `first_chat=TRUE` 的空记录），
按请求 `url` + `authorization` 拉取宿主数据源，创建一条真实 `chat_record`
（`first_chat=false`）并执行问数；同时落一条 CHAT 创建审计（`sys_logs.user_id=-1`）
与问数 `chat_log`。审计写入失败不阻断问数。

验收：A4

### 场景：mcp_assistant 的 stream 与 finish_step

`mcp_assistant` 的 `stream` 缺省或为 `true` 时返回 SSE，为 `false` 时返回 JSON。
无论流式与否均使用 `finish_step=QUERY_DATA` 语义：只生成 SQL 与数据，不生成图表配置、
不渲染图片、不出现 `chart`/`chart-result`/`imageUrl` 内容。返回结构包含
`success`、`record_id`、`sql`、`data`（含 `fields`/`data`）。

验收：A4

## 5. datasource_id 绑定优先级（M2）

### 场景：已绑定会话忽略显式 datasource_id

会话已绑定数据源 D1 时，`mcp_question` 传入 `datasource_id=D2` 仍在 D1 上生成并执行 SQL；
`chat.datasource` 与 `chat_record.datasource` 保持 D1，不因请求参数改绑。流式与非流式行为一致。

验收：A5

### 场景：未绑定会话采用显式数据源或 RAG 选库

会话未绑定数据源时，传 `datasource_id=D2` 则在 D2 上执行，并把 `chat.datasource` 等字段写为 D2；
未传 `datasource_id` 时沿用既有 RAG 选库逻辑并把选中数据源回写会话。流式与非流式行为一致。

验收：A5

## 6. mcp_start 会话语义（M3）

### 场景：mcp_start 不创建空的首条记录

`POST /api/v1/mcp/mcp_start` 与 MCP 工具 `chat_start` 只创建 `chat` 行，不创建任何
`first_chat=TRUE` 的空 `chat_record`。随后 `mcp_question` 创建的记录为 `first_chat=false`。
Web 端 `/chat/start` 仍保留创建首条空记录的行为。

验收：A6

### 场景：mcp_start 的 brief 语义

`mcp_start` 请求带 `question` 时，`chat.brief` 为去空白后的前 20 个字符；未带 `question`
（或为空白）时为当前时间，格式 `yyyy-MM-dd HH:mm:ss`。传 `datasource_id` 时仍按既有
数据源使用授权校验后写入 `chat.datasource`，校验失败按 §8 返回错误。

验收：A6

## 7. datasource_list 字段裁剪（M4）

### 场景：原生与兼容数据源列表均裁剪敏感字段

MCP 工具 `datasource_list` 与 `mcp_datasource_list` 返回的数据源数组元素均不含
`configuration`、`embedding`、`table_relation`、`recommended_config`；其余既有字段保留，
数组按数据源名称升序。REST `/datasource/list` 仍返回前端所需的完整字段，不受本次裁剪影响。

验收：A7

## 8. 错误码与错误体（M5）

### 场景：无效 token 返回 403

`mcp_question`、`mcp_start`、`mcp_ws_list`、`mcp_ds_list`、`mcp_model_list`、
`mcp_assistant` 在请求 token 无效、过期或无法反查用户时返回 HTTP 403，
响应体为 `{"detail":...}`。有效 token 但业务校验失败不落入该分支。

验收：A8

### 场景：非流式问数的运行期错误返回 500 失败结构

非流式 `mcp_question` 在通过参数校验后发生的运行期错误——会话不存在、未绑定且无法选定数据源、
模型不可用、SQL 执行失败、数据源授权拒绝——返回 HTTP 500，
响应体为 `{"success":false,"message":...}`；若本次已创建记录，附带 `record_id`。
流式路径不改为 500，仍以 SSE 错误帧结束。

验收：A8

### 场景：其他工具错误与非法参数保持 400

其余 MCP 工具（`mcp_start`、`mcp_ws_list`、`mcp_ds_list`、`mcp_model_list`、
`mcp_assistant` 等）的业务错误返回 HTTP 400 `{"detail":...}`；
`mcp_question` 的 `datasource_id`、`chat_id`、`custom_model` 等字段格式非法时，
按 SQLBot 的字段校验口径返回 HTTP 400 `{"detail":...}`，不进入问数执行。

验收：A8

## 9. 数据源使用授权（M6）

### 场景：问数前对最终数据源校验授权

`mcp_question` 在流式与非流式路径上，对最终使用的数据源——会话已绑定、请求显式 `datasource_id`、
或 RAG 选中——调用 `DsGrantService.checkUsable`。授权被拒绝时不生成也不执行 SQL：
非流式返回 §8 的 500 失败结构（带 `record_id`，若记录已创建），流式发送错误帧后结束。
管理员与已获授权的用户行为不变，行/列权限（`PermissionEnforcer`）继续在 SQL 执行前生效。

验收：A9

## 10. 工作空间列表形状（L1）

### 场景：工作空间列表只含 id 与 name

MCP 工具 `mcp_ws_list` 与原生 `workspace_list` 返回的数组元素只含 `id` 与 `name` 两个键，
`name` 为按当前语言翻译后的名称，数组按 `name` 升序；不再包含 `create_time` 等字段。
管理员可见全部工作空间、非管理员仅见所属工作空间的既有可见性规则不变。
`mcp_ds_list` 继续按名称升序返回裁剪后的数据源。

验收：A10

## 11. mcp_start 响应形状（L2）

### 场景：mcp_start 只返回 access_token 与 chat_id

`POST /api/v1/mcp/mcp_start` 成功时只返回 `access_token` 与 `chat_id` 两个键，
不再包含 `id`、`brief`、`datasource`、`engine_type` 等会话详情。以用户名/密码换取的
新会话同时返回两者；使用既有会话 token 的场景可缺省 `access_token`，但响应中不得出现其他键。
`chat_id` 为字符串或数字均可被既有客户端解析。

验收：A11
