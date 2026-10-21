# askdata-sqlbot-full-parity 完整目标规格

> 本规格描述归档后 askdata 相对 SQLBot 开源社区版（`fc378c37`）**完全覆盖**后的完整行为。
> 除本文明确新增/变更的部分外，其余行为沿用已归档的正式规格：
> `docs/comet/specs/askdata/spec.md`、`docs/comet/specs/askdata-sqlbot-gap/spec.md`、
> `docs/comet/specs/askdata-cn-table-fix/spec.md`、`docs/comet/specs/askdata-mcp-framework/spec.md`、
> `docs/comet/specs/askdata-sqlbot-parity/spec.md`。
>
> 覆盖口径：端点、运行时行为、数据模型、检索链路、安全控制、对外集成通道。
> 协议（端点路径、请求/响应字段、SSE 事件 type、错误码）必须与 SQLBot 逐字兼容。

## 1. 问数知识增强链路

问数主链路在生成 SQL 前，按 SQLBot 语义补齐四段知识编排；四段任一不可用时按对应降级路径处理，主链路仍须返回结果。

### 场景：关键词抽取与同义词扩展

系统用 LLM 从当前问题中抽取核心业务实体词（忽略时间词、动作词、修饰词），抽取时携带对话历史中最近若干轮的用户提问。抽取结果写入 `chat_record.extracted_keywords`；再用术语库的父术语+同义词扩展该关键词列表（上限 10 个），写入 `chat_record.expanded_keywords`。扩展后的关键词用于表/列匹配与术语匹配。

LLM 抽取失败时，`extracted_keywords` 落空并按原问题继续（降级），不阻断问数。

验收：A2

### 场景：术语库进入提示词

系统按 `expanded_keywords`（无则按原问题）匹配术语库：命中项包含**词法匹配**（子串/ILIKE）与**向量匹配**（`terminology.embedding` 余弦相似度高于阈值时取前 N 条）；匹配范围按当前工作空间与数据源/高级应用作用域过滤。

命中的术语（主词 + 同义词 + 释义）以 XML `<terminologies><terminology><words><word/>…</words><description/></terminology></terminologies>` 结构序列化，作为独立的一条 Human 消息追加到 SQL 提示词消息列表中（格式与 SQLBot `to_xml_string` + `generate_terminologies_info` 一致）。无命中时不追加该消息。

验收：A2

### 场景：SQL 示例进入提示词

系统按 `expanded_keywords` 匹配 `data_training`：命中项包含**双向词法匹配**（样例问题包含关键词，或关键词包含样例问题）与**向量匹配**（`data_training.embedding` 余弦相似度高于阈值时取前 5 条）；过滤条件为当前 `oid` + `enabled` + （`datasource` 命中或 `advanced_application` 命中）。

命中的样例以 XML `<sql-examples><sql-example><question/>…</question><suggestion-answer/></sql-example></sql-examples>` 结构序列化，作为独立的一条 Human 消息追加到提示词消息列表。图表生成提示词同样携带该上下文。

未选中数据源时，仍支持按 `advanced_application` 召回样例。

验收：A2

### 场景：自定义提示词进入提示词

系统按提示词类型（`GENERATE_SQL` / `ANALYSIS` / `PREDICT_DATA`）加载当前 `oid` 与作用域内的 `custom_prompt`，按 SQLBot 规则插入对应提示词消息段。作用域过滤（全部数据源 / 指定数据源 / 指定高级应用）语义与 SQLBot 一致。

验收：A2

### 场景：向量写入与启动回填

术语（父术语与同义词）、SQL 示例、表、数据源四类对象的向量均可写入其 `embedding` 列。写入时机：术语/SQL 示例/表在创建、更新、导入后异步触发；数据源在新增、勾选表、字段同步后异步触发。

应用启动时对上述四类对象中向量为空的记录执行回填；回填在集群内只由一个实例执行（单 worker 守卫），失败只记录日志，不影响问数可用性。

验收：A6

### 场景：本地 embedding 模型

应用内置 `shibing624_text2vec-base-chinese`（ONNX，768 维，归一化）作为默认 embedding 模型，模型文件随构建产物交付到可配置目录（默认 `/opt/askdata/models`）。当用户未配置远程 embedding 模型时，术语/样例/表/数据源的向量召回仍可用。

远程 embedding 模型（`ai_model` 中 `model_type=1`）配置时优先使用远程模型。

任一 embedding 路径不可用（模型缺失、加载失败、端点不可达）时，相关召回降级为词法匹配，问数主链路不报错。

验收：A6

### 场景：数据源选择接入向量

多数据源场景下选择数据源时，先尝试向量相似度（问题 vs 数据源文档），失败或未配置 embedding 模型时保持词法（bigram Jaccard）降级路径，对调用方透明。

验收：A2

## 2. 行/列权限强制

行权限与列权限在问数执行期强制生效，而非仅存储。

### 场景：行权限过滤

普通用户（非管理员、非工作空间管理员、非规则白名单用户）在问数执行 SQL 前，系统按 `ds_permission` / `ds_rules` 计算该数据源下涉及表的行过滤条件，生成改写后的 SQL 并执行。

过滤条件的值转义语义与 SQLBot 一致：单引号加倍、反斜杠加倍、逻辑运算符仅允许 `AND` / `OR`；`IN (...)`、`LIKE '%…%'`、`= '…'`、SQL Server `N'…'` 四种片段形态下引号数保持偶数、含逗号的值不拆分。

改写后的 SQL 必须做代码级验证：过滤条件可被 SQL 解析器在 `WHERE` 中识别，否则拒绝改写结果并按未授权处理（该验证强于 SQLBot）。

管理员、工作空间管理员与白名单用户不受行权限影响。

验收：A3

### 场景：列权限隐藏

列权限配置中标记为隐藏的列，在问数结果集中不出现该列（`SELECT` 投影与结果集均剔除）。语义与 SQLBot 的 `get_column_permission_fields` 一致。

验收：A3

## 3. 鉴权通道与公共路径

### 场景：四类 token 通道

系统按 SQLBot 语义支持四类请求凭证：

- **主通道**：`X-SQLBOT-TOKEN: Bearer <token>`，由 Sa-Token 校验（继续沿用现有实现）。
- **API Key 通道**：`X-SQLBOT-ASK-TOKEN: sk <jwt>`，JWT 载荷含 `access_key`，与 `sys_apikey` 的 `access_key` + `secret_key` 校验；`status=false` 的 key 拒绝；校验失败返回 401。
- **嵌入助手通道**：`X-SQLBOT-ASSISTANT-TOKEN: assistant <jwt>` 或 `embedded <jwt>`，按 `app_secret` 验签；管理员账号拒绝；仅 `sys_assistant.type == 4` 允许；`embedded` 形态支持 `embeddedId` 解密回退。
- **辅助通道**：请求头 `X-SQLBOT-HOST-ORIGIN` 或 Referer 推导的 origin 与助手配置的 `domain` 匹配，匹配失败拒绝。

三通道的校验发生在业务逻辑之前，失败返回 401 且响应体带 `Access-Control-Allow-Origin: *`。

验收：A4

### 场景：公共路径白名单

公共路径判定按 SQLBot `common/utils/whitelist.py` 的 33 条规则执行：使用请求行真实路径（`request.getRequestURI()`，去 context path 与 `/api/v1` 前缀）做匹配，不使用由 Host 头拼接的 URL 路径。

放行路径含：`/`、`/docs`、`/login/*`、静态资源后缀（ico/html/js/css/png/jpg/jpeg/gif/svg/woff/woff2/ttf/eot/otf/css.map）、`/mcp*`、`/system/license`、`/system/config/key`、`/images/*`、`/sse`、`/system/appearance/ui`、`/system/appearance/picture/*`、`/system/assistant/info/*`、`/system/assistant/app/*`、`/system/assistant/picture/*`、`/system/assistant/validate/*`、`/system/authentication/platform/status`、`/system/authentication/login/*`、`/system/authentication/sso/*`、`/system/platform/sso/*`、`/system/platform/client/*`、`/system/parameter/login`。

未在白名单内的路径需登录。

验收：A4

### 场景：Host 头校验

最外层 Filter 校验 `Host` 头：必须匹配 `^[A-Za-z0-9.\-:\[\]]{1,255}$`。不匹配时返回 400，响应体为 `{"code":400,"data":null,"msg":"invalid host header"}`，且在任何业务逻辑之前拒绝。

该校验使 `Host: evil.com/api/v1/mcp` 这类"路径伪装成白名单前缀"的请求无法绕过鉴权。

验收：A4

### 场景：嵌入助手公共端点

补齐 askdata 缺失的公共端点，行为与 SQLBot 一致：

- `GET /system/assistant/info/{id}`：返回助手信息（`app_id`/`app_secret`/`domain`/`type`/`configuration` 等），并按 origin 匹配结果动态设置 `Access-Control-Allow-Origin`；`OPTIONS` 预检对匹配的 origin 直接 200 返回 CORS 头。
- `GET /system/assistant/app/{appId}`：返回嵌入式应用信息。
- `GET /system/assistant/validate/{id}`：返回助手的访问令牌与过期时间。
- `GET /system/assistant/picture/{file_id}`：返回助手图片。
- `GET /system/appearance/ui`：返回外观键值配置数组（`sys_arg` 中 `appearance.*` 项）。
- `GET /system/appearance/picture/*`：返回外观图片。
- `GET /system/config/key`：返回前端所需的公开配置键。

验收：A4

## 4. SSO 认证源与企业平台同步

### 场景：SSO 认证源管理

系统提供认证源（CAS / OAuth2 / LDAP / SAML）的增删改查、启停与按类型读取配置，配置存 `sys_authentication` 表（字段：`id`、`name`、`type`、`config`、`enable`、`valid`、`create_time`），作用域限定当前 `oid`（`sys_authentication` 不含 `oid` 列，沿用 SQLBot 实际结构）。

端点：`GET /system/authentication`、`GET /system/authentication/{id}`、`POST /system/authentication`、`PUT /system/authentication`、`PATCH /system/authentication/status`、`PATCH /system/authentication/enable`、`GET /system/authentication/status`。

第三方授权流程统一使用 **JustAuth**（`me.zhyd:JustAuth` 1.16.6 + `justauth-spring-boot-starter` 1.4.0）：授权跳转、回调换取用户信息、本地用户绑定，均走 JustAuth 的 provider 抽象。

验收：A4

### 场景：SSO 登录

用户通过已启用的认证源登录时，回调携带的平台用户标识经 `sys_user_platform`（字段 `id`、`uid`、`origin`、`platform_uid`）解析为本地 `sys_user`；未绑定则按配置自动创建或拒绝。令牌签发沿用主通道（Sa-Token）。

平台临时令牌存 `sys_platform_token`（字段 `id`、`token`、`create_time`、`exp_time`）。

验收：A4

### 场景：企业平台用户同步

`POST /system/platform/user/sync` 按平台配置批量拉取企业 IM 用户，同步到 `sys_user`（`origin` 标识来源），并维护 `sys_user_platform` 绑定。同步为幂等操作，重复同步不产生重复用户。

验收：A4

## 5. 数据源与执行安全

### 场景：只读 SQL 校验（解析级）

`execSql` 与仪表盘图表执行前的 SQL 校验改为解析级：解析 SQL 语法树后判定

- 首关键字仅允许 `SELECT` / `WITH`（元数据命令 `SHOW` / `DESCRIBE` / `DESC` / `EXPLAIN` 由配置开关控制，默认拒绝）；
- 语法树中任意位置不得出现 `INSERT` / `UPDATE` / `DELETE` / `CREATE` / `DROP` / `ALTER` / `MERGE` / `COPY`（含 CTE 内嵌，如 `WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x`）；
- 危险模式（`INTO OUTFILE` / `INTO DUMPFILE` / `EXEC(` / `COPY ... TO PROGRAM`）与按数据源类型的危险函数表逐条命中即拒绝；
- **解析失败即拒绝执行**（保守拒绝），不因解析器不支持某方言而放行。

验收：A5

### 场景：参数化执行

`execSql` 使用 `PreparedStatement` 绑定参数执行，不以 `Statement` 拼接执行用户 SQL。

仪表盘图表数据执行同样经过只读校验。

验收：A5

### 场景：连接参数与元数据查询开关

数据源连接的额外 JDBC 参数按 `DatasourceType` 的非法参数表拦截（如 MySQL 的 `local_infile`）。元数据查询命令是否放行由配置项控制，与 SQLBot `SQLBOT_ALLOW_METADATA_QUERIES` 语义一致（默认拒绝）。

验收：A5

### 场景：连接池

外部数据源查询走连接池：按数据源 id 缓存连接池，LRU 淘汰（上限可配，默认 500），淘汰时安全关闭底层连接；数据源更新或删除时移除对应池；应用关闭时关闭全部池。

连接超时、查询超时按数据源配置生效（含 PostgreSQL `statement_timeout` 等价语义）。

验收：A10

## 6. 基础设施工具链

### 场景：雪花 ID

实体 ID 采用雪花算法生成：41 位毫秒时间戳 + 5 位数据中心 + 5 位工作节点 + 12 位序列，保证跨进程/跨实例唯一。序列溢出时等待下一毫秒；检测到时钟回拨时按 SQLBot 语义处理。

生成的 ID 保持在 JS 安全整数范围内（不超过 2^53−1），与前端 json-bigint 兼容。

验收：A10

### 场景：数据格式化

查询结果在返回前做格式化：

- 大整数（≥1e15）与大浮点（≥1e10 或 <1e-6）转为十进制字符串，不使用科学计数法（`1.23e20` → `123000000000000000000`）；阈值以下保持原类型；递归处理嵌套的字典与数组。
- 限定列名归一：`alias.column` 形式的行额外补充短列名 `column`（仅在短名不存在时），使图表按列名绑定在 Hive/MySQL 下可用。
- 逐列标记格式（`text` / `number`），供图表渲染使用。

验收：A7

### 场景：CORS

系统按配置的 `BACKEND_CORS_ORIGINS` 放行跨域请求（`allow-credentials`、方法与请求头全放开）；助手应用的 `domain` 在运行时动态加入允许来源；**所有错误响应**（401/400/404/500）均带 `Access-Control-Allow-Origin` 头。

验收：A10

### 场景：响应信封

所有 JSON 响应体由全局机制兜底包装为 `{"code":0,"data":<原始>,"msg":null}`；已是该结构的响应不被二次包装；流式与文件下载响应不包装。

验收：A10

### 场景：审计切面

写操作通过注解 + AOP 自动记录审计，取代逐处手动调用：

- 注解声明操作类型、模块、资源 ID 表达式、结果 ID 表达式、备注表达式；切面自动从请求参数与返回值中按表达式提取资源 ID，并解析资源名（跨表查询）。
- `execution_time` 记录真实耗时（毫秒），非 0。
- 异常时记录失败状态与错误信息；审计写入失败不阻断业务。
- 登录成功与失败均记录审计。
- 审计与资源的多对多关联写入 `sys_logs_resource` 表。

验收：A8

### 场景：客户端 IP 解析

审计记录的客户端 IP 按链式顺序解析：`X-Forwarded-For` → `X-Real-IP` → `CF-Connecting-IP` → `X-Client-IP` → `X-Forwarded` → RFC 7239 `Forwarded` → socket 对端地址。

验收：A8

### 场景：单 worker 守卫

启动期的向量回填等一次性任务在集群内只由一个实例执行；未获得守卫的实例跳过且不重试；应用关闭时释放守卫。

验收：A6

### 场景：Excel 读取健壮性

- 读取工作簿的**全部 sheet**，而非仅第一个；
- 列数不足时明确报错（不静默补空列）；
- 支持旧版 `.xls`（BIFF8）解析；
- 数据源上传接受 `.csv`；
- 上传文件名做 basename 消毒（防路径穿越），落盘文件名随机化（防覆盖）。

验收：A10

## 7. 数据模型补齐

### 场景：新增表

新增 `sys_authentication`、`sys_user_platform`、`sys_platform_token`、`sys_logs_resource` 四张表，字段与 SQLBot 实际 DDL 一致（见第 4 节与第 6 节）。

验收：A4、A8

### 场景：新增列

- `chat`：`brief_generate`（boolean）、`recommended_question_answer`、`recommended_question`（text）、`recommended_generate`（boolean）。
- `chat_record`：`ai_modal_id`、`engine_type`、`sql_exec_result`、`recommended_question_answer`、`datasource_select_answer`、`extracted_keywords`、`expanded_keywords`。
- `chat_log`：`type`、`pid`、`ai_modal_id`、`base_modal`、`reasoning_content`。
- `terminology` / `data_training` 的 `embedding` 列长度足以容纳 768 维向量。

新增列以幂等方式追加（`ADD COLUMN IF NOT EXISTS`），既有数据不迁移、不破坏。

验收：A2、A4、A8

## 8. MCP 工具补齐

### 场景：MCP 工具集

MCP server 暴露的工具集与 SQLBot 对齐：

- `access_token`：以账号口令换取访问令牌（口令错误或用户无工作空间时拒绝）。
- `mcp_start`：以令牌或账号口令启动会话，校验目标工作空间归属，创建 `origin=1` 的会话，返回 `{access_token, chat_id}`。
- `mcp_ws_list`：返回调用者的工作空间列表及权重。
- `datasource_list`：返回工作空间内的数据源，**剥离 `configuration`、`embedding`、`table_relation`、`recommended_config`**（连接密文与向量不得出库）。
- `model_list`：返回工作空间可用的 AI 模型（`id`/`name`/`default_model`/`supplier`）。
- `question_answer`：走**完整问数主链路**（数据源选择 → 关键词抽取 → 术语/样例/自定义提示词注入 → SQL 生成 → 校验 → 执行 → 图表），支持指定会话、指定数据源、指定语言、指定模型、指定是否返回图片；流式时返回 SSE，非流式时返回 JSON。
- `assistant`：以第三方端点（URL + 证书）为数据源，代理其问数请求并止步于取数阶段。

工具调用出错时返回明确的错误信息（不静默返回空结果）。

验收：A1、A2

## 9. 国际化

### 场景：语言集合与消息键

系统支持 5 种语言：`zh-CN`（默认）、`zh-TW`、`en`、`ko-KR`、`th-TH`。语言解析顺序：显式 `lang` 参数 > `Accept-Language` 首段 > 默认 `zh-CN`。

消息键集合与 SQLBot 的 177 键对齐（含 `terminology`、`data_training`、`custom_prompt`、`embedded`、`llm`、`variable`、`excel_export`、`excel_import`、`authentication`、`audit`、`table_not_exist` 等组），默认包与各语言包键集合一致（无缺键）。

验收：A9

### 场景：插值与错误文案

消息插值支持具名占位符（`{name}` 形态）。术语/训练/自定义提示词等模块的校验错误文案取自消息包，不再使用硬编码英文。

验收：A9

## 10. 数据格式与图表

### 场景：图表配置

图表配置归一化补齐 SQLBot `check_save_chart` 的规则：支持 `multi-quota`（多指标）配置；`columns` 与 `axis` 的值按 SQLBot 规则小写化。

验收：A7

### 场景：图表图片

问数生成非表格类型图表时，通过 g2-ssr 图片服务渲染并产出图片（路径按 SQLBot 的 `c_{chat_id}_r_{record_id}` 约定），失败时按 SQLBot 语义降级（图表数据仍返回）。

验收：A7

### 场景：Excel 导出

问数记录导出 Excel 时，字段按**完整图表配置**还原（`columns`、`axis.x`、`axis.y`（可为单值或列表）、`axis.series`），预测数据一并导出；大数值按第 6 节的格式化规则输出（无科学计数法）。

验收：A7

## 11. 仪表盘

### 场景：工作空间级资源权限

仪表盘资源（`load_resource` / `update_resource` / `delete_resource` / `update_canvas`）按 SQLBot 语义校验资源属于调用者所在**工作空间**（而非仅创建者本人），同工作空间成员可读改删。

验收：A3

## 12. 不回退

补齐上述缺口不得回退既有能力：全模块既有测试（包含 MCP 端到端、问数主链路、RAG、datasource 引擎、ES、术语/训练/提示词、用户批量导入、i18n）保持通过；前端构建不破；前端对问数流式协议的解析（`views/chat/answer/*.vue` 的 switch 全集）行为不变。

验收：A11
