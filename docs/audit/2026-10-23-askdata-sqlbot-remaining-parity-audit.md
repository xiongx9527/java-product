# SQLBot → askdata 剩余保真度审计（2026-10-23）

- 审计日期：2026-10-23
- 基准（真值来源）：SQLBot（Python / FastAPI）`/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot/backend`
- 被审对象：askdata（Java / Spring Boot）`/Users/xiongxiao/Workspaces/Idea/codes/New/java-product/askdata/askdata-server`
- 审计性质：**只读**。本轮未修改任何源码文件；仅新增本报告工件。
- 判定词表：**等价** / **打折** / **缺失** / **更强**
- 引用约定：SQLBot 侧路径相对 `SQLBot/backend/`，askdata 侧路径相对 `askdata/askdata-server/src/main/`。

> 说明：本报告**不采信** `docs/comet/archive/**/coverage.md` 等既有自述材料，全部结论由源码逐行核对得出。
> 与 2026-10-22 两份审计的关系：本报告以那两份为起点，逐条在**当前 HEAD（8ac16ac）**上复验，并补上那两份**未覆盖**的能力面。

---

## 0. 复验结论：2026-10-22 报告中的打折/缺失项，当前状态

| 编号 | 2026-10-22 判定 | 2026-10-23 复验 | 证据 |
|---|---|---|---|
| F-01 应用密钥硬编码缺省 | 高 | **仍未修复** | `application.yaml:49` 仍为 `key: ${ASKDATA_SECRET_KEY:AskDataSecret001}`；`NoHardcodedSecretsTest` 仍只扫 `src/main/java` + `schema.sql`（`:39-46`），不扫 YAML |
| F-02 嵌入通道密钥键名写错 | 高 | **仍未修复** | `EmbeddedTokenAuthenticator.java:58` 与 `AssistantPublicController.java:45` 仍为 `@Value("${askdata.secret-key:}")`，配置键是 `askdata.secret.key` |
| F-03 强制改密逻辑缺失 | 高 | **部分修复，核心仍缺** | `TokenVO.need_change_pwd` 字段已加（`:14`），但 `LoginController.accessToken`（`:46-84`）**仍无默认口令判定、不发放 token 分支、不回写 need_change_pwd**；`changePwd` 仍无「新口令≠默认口令」拒绝。`login.initial_pwd_disabled` 仍无人读取 |
| F-04 默认口令不可预测 | 中 | **未修复** | `UserService.java:43-54` 仍回退 `UUID.randomUUID()`；`askdata.user.default-password` 全仓无定义 |
| F-05 系统管理面审计全缺 | 中 | **未修复** | `system/` 下 `@AuditLog` 仍为 **0**；全仓仍 29 处 |
| F-06 支线通道审计被跳过 | 中 | **未修复** | `AuditRecorder.currentUid()`（`:142-148`）仍只试 `StpUtil.getLoginIdAsLong()`，未读 `AuthContext` |
| F-07 审计资源名缺 CHAT / apikey 列名错 | 中 | **未修复** | `AuditResourceNameResolver.TABLE_MAP` 仍 13 项无 CHAT；`sys_apikey` 仍映射到不存在的 `name` 列（`:33`） |
| F-08 embedded 缺 oid 回写 | 中 | **未修复** | `EmbeddedTokenAuthenticator.java:166-170` 仍无 `assistant.oid = user.oid` |
| F-09 权限拒绝 500 vs 400 | 中 | **未修复（有意偏离）** | `CurrentUserService.requireAdmin/requireWsAdmin` 仍抛 `BadRequestException` → 400 |
| F-10 缓存层缺失 | 低 | **未修复** | 全仓 `Caffeine`/`@Cacheable`/`RedisTemplate` 仍 0 命中 |
| F-11 登出无 SSO 分支 | 低 | **未修复** | `LoginController.logout`（`:118-122`）仍无 origin 分支 |
| F-12 Host 缺失回退 | 低 | **未修复（有意偏离）** | `HostValidationFilter.java:66-78` 仍回退 `getServerName()` |
| F-13 尾斜杠归一化 | 低 | **未修复** | `AuthWhitelist.java:160` 仍只去一个斜杠 |
| F-14 IP 头链顺序 | 低 | **未修复** | `X-Forwarded` 仍按 RFC 7239 解析 |
| F-15 硬编码英文错误 | 低 | **未修复** | 15+ 处硬编码英文仍在（见 §3） |
| F-16 token 形态/有效期 | 低 | **未修复** | `application.yaml:82` 仍 `timeout: 2592000`（30 天） |
| F-17 Excel 导入列集 | 低 | **未修复** | `UserImportService.IMPORT_HEADERS` 仍 4 列 |
| F-18 schema 重复 DDL | 低 | **未修复** | `schema.sql:526` 与 `:637` 两处 `sys_logs_resource` |
| F-19 审计关联行写入条件 | 低 | **未修复** | `AuditRecorder.java:100-109` 仍只在 `resourceId != null` 时写 |
| F-20 注释漂移 33→35 | 低 | **未修复** | `AuthWhitelist.java:13,34` 仍写「33 条」 |

---

## 结论摘要（2026-10-23 全量复验 + 增量审计）

- 接口面：SQLBot 148 个唯一端点 vs askdata 199 个；**真正缺失的仅 2 个**（见 §1），其余差集为 askdata 的新增能力（审计查询、appearance、authentication、SSO、定时任务、动态数据源、高级应用知识作用域等 xpack/新需求面），或有意的偏离。
- 但**接口不缺 ≠ 能力不缺**：大量「参数种子在库、运行时无读取点」「UI 可配、链路失效」「实现只做一半」的打折项仍然存在（见 §2）。
- 2026-10-22 报告提出的 20 个问题（F-01～F-20）：**0 个已完全修复**，仅 F-03 完成「字段补齐」这一步（`TokenVO.need_change_pwd` 已加，但登录主链路仍不使用）。
- 2026-10-22 之后新增的提交（RAG 选表、方言分页、行权限递归、文件存储）属于**新增能力**，未触碰上述 20 项。

### 优先级排序（当前最需要处理）

| 级别 | 问题 | 影响 |
|---|---|---|
| 高 | F-01 + F-02 密钥双缺陷仍在 | 落库加密形同虚设；嵌入 token 重启全部失效 |
| 高 | G-01/G-02/G-03 三个 chat 参数空转 | 用户改配置完全无效（详见 §2.1） |
| 高 | C3/C4 分析与预测的自定义提示词 100% 静默失效 | UI 可配不可用（详见 §2.2） |
| 高 | E 组 AI 模型层：temperature/protocol/additional_params 全部不发 | 模型调优参数无效；Azure 等无法接入（详见 §2.3） |
| 高 | D-01 动态数据源缺「按表 SQL」子查询改写（详见 §2.4） | 高级应用宿主无法下发子查询数据集，动态数据源能力打折 |
| 中 | A-01 术语匹配未用抽取关键词、无逗号分词（详见 §2.5） | 召回精度低于 SQLBot |
| 中 | A-02 SQL 示例导入不写向量（详见 §2.6） | 导入的示例对向量召回不可见 |
| 中 | A-03 表名校验用正则分词而非 SQL 解析（详见 §2.7） | 绕过/误报风险 |
| 中 | A-04 /docs（API 文档站）整体缺失（详见 §1.3） | SQLBot 有多语言 Swagger UI + openapi |
| 中 | B 组 MCP 能力面缺口（详见 §1.2） | 对比 SQLBot MCP 少 4 个操作 |

---

## 1. 接口层对照与缺口

### 1.1 端点全量差分

程序化比对两侧全部注解端点：
- SQLBot：148 个唯一 `(method, path)`（Python `@router.*`，含 `include_in_schema=False` 与已注释掉的）；
- askdata：199 个唯一 `(method, path)`。

**SQLBot 有、askdata 无**（排除注释掉的死代码 `/datasource/editLocalComment`、注释掉的 `/system/assistant/{id}` 与 `/system/assistant/validator` 后）：

| 方法 | 路径 | SQLBot 位置 | 状态 |
|---|---|---|---|
| POST | `/api/v1/mcp/access_token` | apps/mcp/mcp.py:41 | **缺失**（askdata MCP 用 Sa-Token 会话代替，见 §1.2） |
| POST | `/api/v1/mcp/mcp_start` | apps/mcp/mcp.py:90 | **缺失** |
| POST | `/api/v1/mcp/mcp_ws_list` | apps/mcp/mcp.py:132 | **缺失** |
| POST | `/api/v1/mcp/mcp_ds_list` | apps/mcp/mcp.py:138 | **缺失** |
| POST | `/api/v1/mcp/mcp_model_list` | apps/mcp/mcp.py:160 | **缺失** |
| POST | `/api/v1/mcp/mcp_question` | apps/mcp/mcp.py:165 | **缺失**（HTTP 端点形态；askdata 以 MCP tool `question_answer` 覆盖语义） |
| POST | `/api/v1/mcp/mcp_assistant` | apps/mcp/mcp.py:201 | **缺失** |
| POST | `/api/v1/system/assistant/validator` | apps/system/api/assistant.py:144 | **缺失**（SQLBot 中该端点也被注释为死代码，等价缺失） |
| POST | `/api/v1/datasource/editLocalComment` | apps/datasource/api/datasource.py:212 | 等价缺失（SQLBot 注释掉，两侧一致） |
| GET | `/api/v1/system/assistant/{id}` | apps/system/api/assistant.py:363 | 等价缺失（SQLBot 注释掉，两侧一致） |

其余 `chat / dashboard / datasource / login / recommended_problem / table_relation / sys_variable / system.aimodel / system.apikey / system.assistant / data-training / terminology / workspace / user / parameter / download-fail-info` 全部逐条对齐（含路径参数名差异，如 `{chart_id}` vs `{id}`，语义等价）。

**askdata 有、SQLBot 无**：均为新需求（审计查询、appearance、authentication/平台 SSO、External IdP、动态数据源、高级应用凭据、行权限、文件存储、SSR 图表、定时任务、术语高级应用作用域、login/health），不计为缺口。

### 1.2 MCP 能力面差距

askdata 的 MCP（`mcp/AskdataMcpTools.java`）暴露 4 个 tool：
- `datasource_list`（`:34`）、`server_info`（`:45`，自造探针）、`assistant`（`:59`）、`question_answer`（`:79`）。

SQLBot 的 MCP（`apps/mcp/mcp.py` + `main.py` FastApiMCP）暴露 7 个 operation：
`access_token`、`mcp_start`、`mcp_ws_list`、`mcp_datasource_list`、`mcp_model_list`、`mcp_question`、`mcp_assistant`（`main.py:220-222` include_operations）。

**逐个对照：**

| SQLBot MCP | 语义 | askdata 现状 | 判定 |
|---|---|---|---|
| `mcp_access_token` | 用户名/口令 → token | 无（用 sa-token-login 会话替代） | **打折**：MCP client 无「凭 access token 独立接入」路径；`server_info` 是自造探针不是等价物 |
| `mcp_start` | 发会话 + 工作空间切换 + 审计（`@system_log` CHAT，`mcp.py:90-135`） | 无 | **缺失**：MCP 形态的「开一次会话」语义不存在（MCP `assistant` 工具内部隐式建链，但无 chat_id 管理/无审计） |
| `mcp_ws_list` | token → 工作空间列表 | 无 | **缺失** |
| `mcp_ds_list` | token → 数据源列表（带权限过滤） | `datasource_list`（`:34`） | **打折**：无 token 维度，直接列全部数据源（`DatasourceService.list(null)`），无 per-user/工作空间作用域 |
| `mcp_model_list` | oid → ws 可用模型 | 无 | **缺失**：MCP client 无法问「这个工作空间可以调哪些模型」 |
| `mcp_question` | 流式问数（chat_id + ds_id + custom_model + return_img） | `question_answer`（`:79-81`） | **打折**：无 chat_id 会话管理、无 custom_model 选择、无 return_img 参数（虽有服务端图片 URL 但不可选） |
| `mcp_assistant` | 内部助手挂载（`sqlbot-mcp-assistant` 合成用户 `:201-205`） | `assistant`（框架 `SimpleAssistant` 多轮） | 打折：合成用户语义不同——SQLBot 创建 `oid=1` 的独立身份进审计体系；askdata 的 SimpleAssistant 是宿主进程内对象，无工作空间/权限语义 |

**结论**：askdata 的 MCP 是「工具集」而非完整问数平台协议。作为模型侧的通用 MCP 工具够用；作为 SQLBot MCP 的**协议级等价物**（第三方系统用 token 接入问数能力）缺 4 个操作和 token 生命周期管理。

### 1.3 /docs API 文档站整体缺失

SQLBot（`main.py:188-208`，`SQLBOT_DOC_ENABLED`）：
- `/openapi.json`：OpenAPI 3.1 全量 schema，按 `Accept-Language` 渲染（`apps/swagger/i18n.py`：en/zh 两包占位符翻译 `PLACEHOLDER_*` summary/description，含 `openapi.json?lang=` 缓存）；
- `/docs`：Swagger UI（bundle.js/css 自托管在 `frontend/public/`），前端「API KEY」页直接链接（`Apikey.vue:41`）。

askdata：
- `web/public/` 已**预置** `swagger-ui.css` + `swagger-ui-bundle.js`（与 SQLBot 同款静态资源）；
- 白名单里也放了 `/docs`（`AuthWhitelist.java:40`）；
- 但**后端无任何 `/docs` 路由、无 openapi 生成、无 springdoc/knife4j 依赖**（`pom.xml` grep 0 命中）。
- 结论：文档站是「资源与白名单就位，功能为零」的空壳。用户在 UI 上按 API KEY 文档入口会拿到 404。

---

## 2. 实现打折项（接口在、语义缺）

### 2.1 【缺失·严重】三个 chat 运行参数在运行时无人读取（G1/G2/G3，2026-10-22 报告 §结论摘要-2，未修复）

复验（2026-10-23）：

- 后端 Java 全量 grep `sqlbot_name|limit_rows|context_record_count|base_message_round_count_limit|enable_sql_row_limit` → **0 命中**（只有 `schema.sql:199/211/223` 的种子行）。
- `ChatQuestionService.priorContext` 仍硬编码取最后 5 条（`:592-594`）。
- `SchemaContextBuilder.sqlSystemPrompt` 仍恒定输出「结果行数控制在 1000 行以内」（`:883`），不受 `chat.limit_rows` 控制。
- 执行结果集**没有任何行数截断**：`ChatQuestionService` 全文无 `limit`/`subList(0, 1000)` 类逻辑；`ChatExcel.MAX_ROWS = 100000` 只管导出。
- `sqlbot_name`：SQLBot 在 8 处提示词模板插值（`templates/template.yaml:10,93,293,301,400,609,642,704`），askdata 四个 system prompt（`SchemaContextBuilder.java:878,912,921,925,930`）没有任何助手名插槽。
- 前端 `chatConfig.ts` 的 `getLimitRows`/`getSQLBotName` getter 仍零调用。

对照 SQLBot 真值（`apps/chat/task/llm.py:246-264, 1291-1293`）：

```python
for config in chat_params:
    if config.pkey == 'chat.sqlbot_name':     # → 8 处模板插值
        ...
    if config.pkey == 'chat.limit_rows':      # → query_limit / no_query_limit 模板二选一
        ...
    if config.pkey == 'chat.context_record_count':  # → get_last_conversation_rounds(count_limit)
        ...
# 1291-1293：执行结果 > 1000 且 enable_sql_row_limit 时硬截断并写 data_obj['limit']
if data_result and len(data_result) > limit and self.enable_sql_row_limit:
    data_obj['data'] = data_result[:limit]
    data_obj['limit'] = limit
```

SQLBot 的 `query_limit` / `no_query_limit` 两套数据量策略模板（`templates/template.yaml:40-76`，含「限制只能加在最外层」「禁止进子查询/CTE」「『每组前 N』用窗口函数」的 enforcement 细则）在 askdata 只退化成一句话。

**影响**：
1. 管理员在 UI 上改这三个参数，后端行为零变化（静默失效，无任何日志）；
2. `limit_rows=true` 的用户仍然可能被模型生成无界查询——askdata 只靠提示词一句「1000 行以内」（模型可忽略），**没有 SQLAlchemyCouldn't 保底的落库前截断**；
3. 「不限制」语义（`no_query_limit`）在 askdata 不存在。

### 2.2 【缺失·严重】分析/预测链路的自定义提示词无注入点（C3/C4，未修复）

复验：`ChatKnowledgeService.customPrompts`（`:274-277`）仍写死 `type = 'GENERATE_SQL'`；`SchemaContextBuilder.buildAnalysisMessages`（`:327-334`）与 `buildPredictMessages`（`:337-346`）仍无任何自定义提示词参数；`ChatQuestionService.streamAnalysisOrPredict`（`:435-437`）直接调用二者。
SQLBot 真值不变：`llm.py:587`（ANALYSIS）与 `:638`（PREDICT_DATA）分别召回对应类型提示词注入模板（`template.yaml:606-638` / `:639-680` 的 `{custom_prompt}` 槽）。

**结论与 2026-10-22 相同**：UI 三个 tab 里 `ANALYSIS` / `PREDICT_DATA` 类型可配置、可保存、可在数据表里查到，但运行时**永远只是 GENERATE_SQL 在被消费**。这两类提示词在 askdata 是 100% 静默失效。

### 2.3 【缺失·高】AI 模型层：protocol 分发缺失，temperature/additional_params 主链路不发（E1-E4，未修复）

复验：
- `ChatModelRouter.ModelRef`（`chat/service/ChatModelRouter.java:44-66`）字段仍只有 `id/name/baseModel/apiDomain/apiKey/temperature`；
- `ChatModelClient.streamCompletion` 请求体（`:109-120`）仍只有 `model/messages/stream`——**没有 temperature**（`ModelRef.temperature` 甚至没被发送，只在连通性检查里用过）；
- URL 构造（`:350-365`）仍固定 `/v1/chat/completions` OpenAI 兼容路径；
- `ai_model.protocol` / `supplier` / `config`（additional_params）仍只存取不消费（`AiModelRepository.java:31,64-78`）。

SQLBot 真值（`apps/ai_model/model_factory.py:112-133,159-166,174-181`）：
- `LLMFactory` 工厂：openai / tongyi / vllm / azure 四类；
- `protocol==1 → openai else vllm`；
- `ai_model.config` JSON **整包**转 `additional_params` 传入模型；
- `llm.py:196-201`：`no_reasoning` 时按需删除 `extra_body.enable_thinking`。

**影响**：
1. 用户在模型参数里设置的 temperature 无效（主链路请求体根本不发）；
2. `max_tokens` / `top_p` / `extra_body.enable_thinking` 全部丢失；
3. Azure（`api-version` / `deployment_name`）与 vLLM 专有行为无法表达；
4. 「关闭思考模式」类开关无实现入口。

### 2.4 【打折·高】动态数据源缺「按表 SQL」子查询改写（D-01，本次审计新发现）

SQLBot 的动态数据源不只是「连接配置」：`AssistantTableSchema`（`apps/system/schemas/system_schema.py:178-184`）每张表可带

```python
class AssistantTableSchema(BaseModel):
    name: ...
    sql: Optional[str] = None      # ← 子查询
    fields: ...
```

宿主下发的数据源中的表可以是**SQL 而不是真实表**。问数执行时（`llm.py:1000-1027, 1460-1466, 1523-1529`）：
1. `generate_assistant_dynamic_sql` 对生成 SQL 命中的且带 `sql` 的表构造 `sub_query=[{"table": t, "query": "select * from sqlbot_dynamic_temp_table_<t>"}]`；
2. 走第二次 LLM 调用 `generate_with_sub_sql`（`dynamic_sql` 模板 `template.yaml:769-800`）让模型把子查询占位符替换为宿主子 SQL；
3. 校验后把占位符 `replace()` 回真实子查询，作为 `real_execute_sql` 执行。

这是一整套「SQL 视图」能力：宿主把敏感/聚合层封装成子查询下发给小助手。

askdata 现状：`DynamicDatasource`（`embedded/DynamicDatasource.java`）只有 `type/conf/name` 三字段；`DynamicDatasourceResolver.resolve`（`:46-66`）只透传连接 `configuration`；`buildSchemaTextForConf`（`SchemaContextBuilder.java:419-442`）的表结构来自 `datasourceService.tablesTransient(type, conf)`（实时 JDBC 元数据）。**没有表级 `sql` 字段、没有子查询改写链路、没有 `dynamic_sql` 提示词模板**（全仓 grep `dynamic_sql|sub_query` 0 命中，只有行权限的独立实现）。

**影响**：宿主侧「每表一段 SQL」的动态数据集能力完全缺失。A8 验收时按「连接配置等价」通过，但 SQLBot 该模块的第三维度（表级 SQL 改写）没有覆盖。

### 2.5 【打折·高】术语召回不使用抽取关键词、无逗号分词逐词匹配（A1/A2）

复验（`ChatKnowledgeService.java:218-249`）：

```java
List<Map<String, Object>> groups = recallTerms(question, oid, dsId, advancedAppId);   // ← 仍用原始 question
List<String> llmEntities = extractEntitiesWithLlm(question, historyText);             // ← 抽取在其后，结果只进关键词落库
...
String extracted = String.join(",", extractedList);
```

- 术语召回仍以**原始整句**为查询文本，抽取出的实体**不回流**给 `recallTerms`；
- `TerminologyRecallService.lexicalRecall`（`:122-149`）整句 INSTR 子串匹配，无「`',' in question` → `parts.map(select_terminology_by_word)`」的逐词精确匹配（SQLBot `terminology.py:981-993`）；
- SQLBot 真值：`llm.py:384` `match_text = self.chat_question.extracted_keywords or self.chat_question.question`——SQLBot 用的是**抽取词拼接串**，不是小提示里那种原句。

**影响**：整句子串匹配短词噪声（如「额」「数」），召回精度低于 SQLBot 的「实体词精确命中」语义。2026-10-22 提交 30ccb48 只把抽取词用于**表匹配**（2.3），没有修术语召回本身。

### 2.6 【缺失·高】SQL 示例 Excel 导入仍不写向量（B8）

复验（`TrainingService.java:325-328`）：`uploadExcel` 循环内仍直接 `trainingRepository.insert(...)`，**收集 id 后批量 `saveEmbeddings` 的逻辑仍旧没有**（全仓 `saveEmbeddings` 命中仅 4 处：create `:176`、update `:191`、terminology 两处、backfill runner）。对比同义场景术语导入（`TerminologyService.java:388`→`create()`→`:205`）是即时补算的。

**影响**：导入的示例 `embedding IS NULL`，`TrainingRecallService.recallByDatasource` 只查 `embedding IS NOT NULL` 的行 → 导入的示例在**下一次应用启动跑完 backfill 之前对向量召回不可见**；期间只能走词法兜底（且兜底条数是 3 不是 5）。SQLBot 侧 `data_training.py:407-409` 在批量导入末尾统一补算。

### 2.7 【打折·中】表名安全检查：正则分词 vs SQL AST 解析（A-03，本次审计新发现）

askdata `ChatQuestionService.validateTables/usedTables`（`:729-750`）：

```java
String text = sql.toLowerCase().replaceAll("\\s+", " ");
String[] tokens = text.split(" ");
if ("from".equals(tokens[i]) || "join".equals(tokens[i])) { ... }
```

SQLBot 真值（`llm.py:74-88`）用 **sqlglot 按方言 parse**，收集 CTE 别名并排除，再取 `exp.Table`：

```python
statements = sqlglot.parse(sql, dialect=dialect)
cte_names = {cte.alias for cte in stmt.find_all(exp.CTE)}
for table in stmt.find_all(exp.Table):
    if table.name and table.name not in cte_names: tables.add(table.name)
```

**askdata 的分词法的问题**：
1. `WITH cte AS (...) SELECT * FROM cte` —— `cte` 会被当成表名进入校验（配合白名单表集校验时会**误报**未授权表，因为 CTE 名不在 core_table 列表里），或若 CTE 名碰巧与真实表同名则**漏检**；
2. `FROM (SELECT ...)` 的 `(` 之后的子查询别名情形，`tokens[i+1].replaceAll("[^\p{L}\p{N}_]", "")` 对 `(select` 剥不出正确语义；
3. `FROM "user"`（引号标识符）会被剥成 `user`，若 schema 用大小写敏感 dialect 会失真。

（描述：这不是安全防火墙的主力——行权限改写走 `PermissionEnforcer` 的 JSqlParser；但作为「允许表集校验」这一独立防线，语义与 SQLBot 不等价。）

### 2.8 【打折·中】MCP `question_answer` 返回的是 SQL+行，不含图表图片（A-04 残余）

2026-10-22 commit a7a83b8 已在主链路接入 SSR 出图，但 MCP 工具 `question_answer` 的返回仍是 SQL+结果行；`return_img` 语义（SQLBot `McpQuestion.return_img`，命中图表时返回图片 URL）在 askdata MCP 上不存在。作为「MCP 问数返回图片链接」这一验收点它在**主链路**是有的（`ChartImageService` 落 `chart_answer`），但 MCP 调用方拿不到。

### 2.9 【打折·低】自定义提示词导出表头硬编码中文（C 组 i18n，2026-10-22 报告 §8 同类）

`CustomPromptService.java:210-231` 导出/模板列仍硬编码中文（「提示词名称」「提示词内容」「生效数据源」「高级应用」）。同病：`TerminologyService.java:268-285`、`TrainingService.java:230-233`。askdata 自身 5 语言消息包就位（`messages_*.properties`），导出物不经过它。

### 2.10 【打折·低】推荐问题（猜你想问）模板退化

SQLBot `templates/template.yaml:574-600` 的 `guess` 模板显式声明：图表类型枚举（table/column/bar/line/pie）、「不能与当前问题重复」「参考以往提问」「最多 N 个」「推测问题例子」。askdata `recommendSystemPrompt`（`SchemaContextBuilder.java:929-932`）：

```java
"你是问答助手。基于用户原问题，生成若干相关的、可继续追问的自然语言问题，"
+ "只输出一个 JSON 数组，不要编号、不要解释。"
```

- 没有图表类型引导（SQLBot 靠它让推荐题偏向「用柱状图展示…」以便点开直接出图）；
- 没有注入「以往提问」（SQLBot `get_old_questions`：同数据源近 20 条无 error 的历史问题，`chat.py curd:1208-1219`；askdata 的 `recommendQuestions(ModelRef, question, n)` 只传当前问题，`ChatQuestionService.java:838-843`）；
- 没有全量 sys 列表式约束（重复抑制、空结果 `[]` 约定、`{articles_number}` 只以文字级别包含）。

### 2.11 【打折·低】`chat.question/pager` 是新增端点（不算缺口），但 `data` 与 `data_live` 参数化

askdata 的 `/chat/record/{rid}/data` 不带权限说明注释（SQLBot `chat.py:75` 之后各自独立数据源换行），行为上等价（都走 record + ownership）——**已确认等价，非缺口**。此处列出仅为完整性。

---

## 3. i18n 缺口清单（F-15 的完整清单，2026-10-23 状态）

仍硬编码英文的面向用户错误（部分）：

| askdata 位置 | 文案 |
|---|---|
| `DatasourceService.java:196` | `Missing required argument: [name]` |
| `TerminologyService.java:214,250` | `Terminology does not exist` |
| `TerminologyService.java:577`、`TrainingService.java:396`、`UserService.java:398`、`WorkspaceService.java:153,169,190,208`、`AiModelService.java:467` | `Missing required argument: [...]` |
| `TrainingService.java:184,212`、`TrainingService.java:333` | `Data training does not exist` / `Data training already exists in db` |
| `UserController.java:197,211` | `Login user does not exist` |
| `VariableService.java:62` | `Missing required argument: [name]` |
| `WorkspaceService.java:211` | `uws not exist` |
| `UserImportService.java:229` / `UserService.java:180` | `account is required` / `account is required for external identity` |
| `UserService.java:331,333`、`UserService.java:411` | `Workspace [...] is not available...` / `does not exist` / `Invalid system_variables` |
| `ApiKeyTokenAuthenticator.java:90,92,95`、`EmbeddedTokenAuthenticator.java:137,156,161,164` | `Account does not exist` / `User is disabled` / `No associated workspace` |
| `AuthChannelFilter.java:183-184`、`ApiExceptionHandler.java:48` | `Authentication invalid【...】` |
| `EmbeddedTokenAuthenticator.java:129-131` | `Assistant application not exist`、`Invalid embedded app type!`、`Miss account payload error!` |
| `DynamicDatasourceResolver.java` | `Dynamic datasource is only supported for advanced applications (type 1/3)...` 等 |

同时 `EmbeddedTokenAuthenticator` 中的鉴权失败文案与 SQLBot 的 i18n 键（`i18n_permission.authenticate_invalid` 等，messages 包**键已存在**）不一致。

---

## 4. 2026-10-22 报告之外新增的偏离（低风险，记录在案）

| 编号 | 项 | 说明 |
|---|---|---|
| N-01 | `login/health` 端点（`LoginController.java:124`） | 新增探针，无 SQLBot 对应物，不算缺口 |
| N-02 | `/system/terminology/{id}`（详情） | SQLBot 无该端点；askdata 为前端需要新增，无行为差异 |
| N-03 | `/dashboard/move_resource/{id}` | SQLBot 无；askdata 侧控制台功能 |
| N-04 | `/chat/question/pager/{page}/{size}` | SQLBot 无（SQLBot 聊天列表天然分页由前端承担）；askdata 新增 |
| N-05 | `db_schema` 值在 `/user/defaultPwd` | askdata 已实现 `login.initial_pwd_hidden` 空串化（`UserService.java:131-136`），SQLBot 侧等价。已核对 |
| N-06 | `SkeletonStubController`：`/system/license` 恒 community | xpack 许可体系整体缺席的结果侧面； 社区版语义内等价 |

---

## 5. 总结

以「能力完整覆盖」为目标，当前主链路的**结构性缺口**（按优先级）：

1. §2.1 三个 chat 参数空转 + 无运行时行数截断（高）
2. §2.2 分析/预测自定义提示词静默失效（高）
3. §2.3 AI 模型层 protocol/additional_params/temperature 全链路缺位（高）
4. §2.4 动态数据源缺表级 SQL 子查询改写（高）
5. §1.2 MCP 4 个协议操作缺失（中）
6. §1.3 /docs 站点空壳（中）
7. §2.5 术语召回不用抽取词（中）
8. §2.6 示例导入不写向量（中）
9. §2.7 表名校验不用 SQL AST（中）
10. F-01/F-02/F-04/F-05/F-06/F-07/F-08（安全与审计，2026-10-22 已列，全部未动）

