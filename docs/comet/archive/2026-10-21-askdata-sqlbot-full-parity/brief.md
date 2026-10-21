# 目标

以 SQLBot 开源社区版（`/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot`，DataEase 问数产品，基线 commit `fc378c37`）为唯一事实源，**完全覆盖** askdata（`java-product/askdata`，Java/Spring Boot 问数产品）的后端能力。

具体含义：

- **不能漏**：SQLBot 后端存在的每一条能力线（端点、运行时行为、数据模型、检索链路、安全控制、对外集成通道），askdata 都必须有等价实现。
- **不能降级**：askdata 现有实现与 SQLBot 语义不一致时，以 SQLBot 语义为准修正 askdata（不是把 askdata 现有实现反向定义为标准）。
- **后端能力不能减弱**：既包括"SQLBot 有而 askdata 没有"（缺失），也包括"askdata 有但语义/覆盖面小于 SQLBot"（降级），还包括"askdata 已实现但安全/正确性弱于 SQLBot"（质量回退）。

前端 `askdata/askdata-server/web/` 已是 SQLBot `frontend/` 的逐字拷贝（180 个 .vue 文件，目录结构一致，仅 `router/watch.ts` 与新增 `utils/xpackPatch.ts` 两处差异），前端零改动，仅作行为裁判。

# 范围

## 目录结构

### 新建

- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatKnowledgeService.java` — 问数知识增强编排：LLM 关键词抽取（`extracted_keywords`/`expanded_keywords` 落库）、术语库向量+词法召回、SQL 示例（data_training）向量召回、自定义提示词按类型注入、助手动态 SQL 生成。补齐 SQLBot `apps/chat/task/llm.py` 的 `extract_keywords` / `filter_terminology_template` / `filter_training_template` / `filter_custom_prompts` 四段缺失编排。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/KeywordExtractor.java` — LLM 关键词抽取（支持对话历史窗口），对齐 SQLBot `extract_keywords_sys_prompt` 语义。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/terminology/TerminologyRecallService.java` — 术语召回服务：`terminology.embedding` 向量写入 + 余弦召回 + 词法降级，对齐 SQLBot `apps/terminology/curd/terminology.py` 的 `save_embeddings` / `select_terminology_by_word` / `embedding_sql`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/embedding/DatasourceEmbeddingService.java` — 数据源级向量（`core_datasource.embedding`）写入与召回，对齐 SQLBot `apps/datasource/embedding/ds_embedding.py`（当前 askdata 只做表级）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/PermissionEnforcer.java` — 行/列权限在问数执行期的强制：行过滤条件拼装（SQLBot `build_table_filter`）、列隐藏字段应用（`hiddenColumns`）。当前 `DsPermissionQueryService` 无任何调用方，权限"存而不生效"。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/HostValidationFilter.java` — Host 头校验（最外层 Filter），对齐 SQLBot `common/core/host_validation.py`，防 `Host: evil.com/api/v1/mcp` 类鉴权绕过。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/AuthWhitelist.java` — 公共路径白名单（对齐 SQLBot `common/utils/whitelist.py` 33 条），按 `requestURI` 原始路径判定（非 URL 拼接路径）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/EmbeddedTokenAuthenticator.java` — 嵌入助手 token 通道（`X-SQLBOT-ASSISTANT-TOKEN` 的 `assistant` / `embedded` scheme + `app_secret` 验签 + 拒绝管理员账号 + 仅 type=4）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/ApiKeyTokenAuthenticator.java` — API Key 通道（`X-SQLBOT-ASK-TOKEN: sk <jwt>`，`access_key` + `secret_key` 校验），对齐 SQLBot `validateAskToken`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/CorsConfig.java` — CORS 配置（`BACKEND_CORS_ORIGINS` 可配 + 助手域名运行时动态加入 + 错误响应 `Access-Control-Allow-Origin`），对齐 SQLBot `main.py` 的 `CORSMiddleware` / `init_dynamic_cors`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/DataFormatUtil.java` — 大数转字符串（无科学计数法）+ 限定列名归一（`alias.column` → `column`）+ 列格式标记，对齐 SQLBot `common/utils/data_format.py`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/SnowflakeIdGenerator.java` — 41/5/5/12 位雪花 ID（机器号/数据中心位），对齐 SQLBot `common/utils/snowflake.py`，替换当前 `IdGenerator`（当前 `now*1000+seq` 跨进程不唯一）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/engine/ConnectionPoolManager.java` — 数据源连接池 LRU 管理（上限 500，淘汰即 `dispose`），对齐 SQLBot `apps/db/db.py` 的 `ConnectionPoolManager` / `DriverConnectionPoolManager`。当前 `JdbcEngine.openConnection` 每次 `DriverManager.getConnection`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/audit/AuditLogAspect.java` — 审计切面（`@AuditLog` 注解 + AOP），对齐 SQLBot `common/audit/schemas/logger_decorator.py` 的 `@system_log` 装饰器（当前 askdata 为 33 处手动调用，易漏）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/audit/AuditResourceNameResolver.java` — 审计资源名解析（跨 14 表 UNION），对齐 SQLBot `build_resource_union_query`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/audit/AuditClientIpResolver.java` — 客户端 IP 解析链（`x-forwarded-for` → `x-real-ip` → `cf-connecting-ip` → `x-client-ip` → `x-forwarded` → RFC 7239 `Forwarded` → socket peer）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/lock/SingleWorkerGuard.java` — 单worker 启动任务守卫（对齐 SQLBot `common/utils/distributed_lock.py` 的 `SingleWorkerGuard.once`），保证 3 个 embedding 回填任务在集群内只跑一次。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/startup/EmbeddingBackfillRunner.java` — 启动期 embedding 回填（术语/训练样例/表/数据源），对齐 SQLBot `main.py` 的 3 个 `init_*_embedding_data`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/AiModelFactory.java` — 多供应商模型工厂（`supplier`/`protocol` 分派：OpenAI / vLLM / Azure / 本地 Ollama / Spark / 钉钉 等），对齐 SQLBot `apps/ai_model/model_factory.py`。当前 `AiModelConfig` 只构建 OpenAI 兼容 bean。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/LocalEmbeddingModel.java` — 本地 embedding 模型（`shibing624/text2vec-base-chinese`，768 维归一化），对齐 SQLBot `apps/ai_model/embedding.py` 的 `EmbeddingModelCache`。当前 askdata 无 embedding 模型时整链降级为词法，SQLBot 默认可用。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/authentication/` — SSO 认证源包（`AuthenticationService`/`AuthenticationRepository`/`SsoAuthenticator`）：CAS / OAuth2 / LDAP / SAML 登录源与校验，配置存 `sys_authentication` 表；`/system/authentication/*` 端点（增删改查、状态启停、按 type 取配置）。对齐 askdata 前端已有 `views/system/authentication/`（CasEditor、Oauth2Editor）与 `views/system/platform/` 页面。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/platform/` — 企业平台用户同步包（`PlatformSyncService`/`PlatformRepository`）：`/system/platform/user/sync` 批量同步企业 IM 用户到 `sys_user`、平台用户绑定存 `sys_user_platform`、平台令牌存 `sys_platform_token`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/settings/AppearanceController.java` — 外观定制读取端点 `/system/appearance/ui` 与 `/system/appearance/picture/*`（返回前端 `stores/appearance.ts` 所需的键值与图片），并把 `appearance.*` 参数纳入 `sys_arg`（当前 `SysArgRepository` 显式排除 `appearance.*`）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/ConfigKeyController.java` — `/system/config/key` 公共端点（SQLBot 白名单内，askdata 缺失）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChartImageService.java` — 图表图片生成（调用 g2-ssr 图片服务），对齐 SQLBot `apps/chat/task/llm.py` 的 `request_picture`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/ResponseEnvelopeAdvice.java` — 响应信封全局兜底（`{code,data,msg}`），对齐 SQLBot `common/core/response_middleware.py` 的全局包装（当前 askdata 依赖每个 Controller 手写 `Result.ok`）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/auth/BcryptSupport.java` — bcrypt 口令校验兼容（SQLBot `pwd_context` 为 bcrypt，登录路径为 MD5；askdata 需能校验两者）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/ExcelReadSupport.java` — 旧版 `.xls`（BIFF8）解析支持 + CSV 数据源上传 + 文件名 basename 消毒 + 随机化存储文件名，对齐 SQLBot `pandas`+`calamine` 栈。
- `askdata/askdata-server/src/main/resources/messages_ko_KR.properties`、`messages_th_TH.properties`、`messages_zh_TW.properties` — 补齐 SQLBot 的 5 语言（现缺 ko-KR / th-TH / zh-TW）。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/...` — 新增单测：Host 校验、鉴权绕过回归、白名单匹配、权限强制、关键词抽取、术语/训练向量召回、大数格式化、雪花 ID 唯一性、连接池 LRU、审计切面。

### 修改

- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/config/SaTokenConfigure.java` — 接入三通道鉴权（Sa-Token 主通道 + 嵌入助手 token + API Key token）、白名单排除（对齐 SQLBot 33 条公共路径）、`scope["path"]` 原始路径判定。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatQuestionService.java` — 主链路接入知识增强（关键词/术语/训练/自定义提示词）、行权限过滤、助手动态 SQL、图表图片生成、错误帧结构化（`exec-sql-err` / `db-connection-err` + traceback）、`chat_log` 补齐 SQLBot 的操作级日志（`FILTER_TERMS`/`FILTER_SQL_EXAMPLE`/`EXTRACT_KEYWORDS` 等）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java` — 术语/训练样例按关键词与向量召回；数据源选择接入数据源级向量（现仅词法 bigram Jaccard）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatChartBuilder.java` — 图表配置归一补齐 SQLBot `check_save_chart` 规则：`multi-quota` 处理、`columns`/`axis` 值小写化。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatService.java` — `start()` 补齐 SQLBot `create_chat` 行为（首条 ChatRecord 预建、`recommended_config==2` 时预生成推荐问题）；`exportExcel` 按完整图表配置（columns/axis.x/axis.y 可为 list/axis.series）还原字段。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/repository/ChatRepository.java` — 记录级读写补 `extracted_keywords`/`expanded_keywords`/`ai_modal_id`/`engine_type`/`sql_exec_result`/`recommended_question_answer`/`datasource_select_answer`；`chat_log` 补 `type`/`pid`/`ai_modal_id`/`base_modal`/`reasoning_content`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/controller/ChatController.java` — 补 `/chat/question/{id}`、`/chat/question` 的编辑/删除/查询语义与 SQLBot 对齐。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/engine/JdbcEngine.java` — 接入连接池；`execSql` 改 `PreparedStatement` 绑定；`checkParams` 非法 JDBC 参数拦截；补 `SQLBOT_ALLOW_METADATA_QUERIES` 等价的元数据查询开关。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/engine/SqlSafety.java` — 由"首关键字 + 正则"启发式升级为 SQL 解析级校验（覆盖 `WITH ... AS (DELETE ...)` 这类 CTE 内写操作；解析失败即拒绝），对齐 SQLBot `check_sql_read`（sqlglot AST）语义。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/embedding/TableEmbeddingService.java` — 补数据源级向量协作与启动回填接线。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/terminology/TerminologyService.java` — 术语向量写入与召回接线（当前仅子串匹配，注释自述"术语表无 embedding 列"）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/training/TrainingService.java` — SQL 示例向量写入与召回接线（当前 `embedding` 列存在但从不写入）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/prompt/CustomPromptService.java` — 自定义提示词接入问数/分析/预测三处提示词组装。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/audit/AuditRecorder.java` — `execution_time` 实际计算（当前硬编码 0）、审计写入失败不阻断主流程保持、接入资源名解析与 `sys_logs_resource` 关联。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/controller/LoginController.java` — 登录审计接入（SQLBot 对登录有 `@system_log`）；bcrypt 口令兼容。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/auth/CryptoUtil.java` — 应用密钥改为配置项（当前硬编码常量 `AskDataSecret001`，SQLBot 走 `SECRET_KEY` 环境变量）；补 `simple_aes_*` 兼容。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/ApiExceptionHandler.java` — 错误响应补 `Access-Control-Allow-Origin`、补异常堆栈日志（当前无日志）、补 SQLBot 的 4 类业务异常区分（`SQLBotDBConnectionError` / `SQLBotDBError` / `ParseSQLResultError`）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/IdGenerator.java` — 改用雪花 ID 生成器。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/i18n/LocaleConfig.java` — 语言集合对齐 SQLBot 5 语言；`?lang=` 与 `Accept-Language` 解析顺序对齐 SQLBot。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/i18n/MessageResolver.java` — 键覆盖补齐到 SQLBot 177 键（当前默认 97 键、英文仅 48 键，缺 `terminology`/`data_training`/`custom_prompt`/`embedded`/`llm`/`variable`/`excel_*` 等组）；插值支持具名占位符 `{name}`。
- `askdata/askdata-server/src/main/resources/messages.properties` / `messages_zh_CN.properties` / `messages_en.properties` — 键集合对齐（英文包补齐 51 个缺失键）。
- `askdata/askdata-server/src/main/resources/schema.sql` — 补 SQLBot 缺失表 `sys_logs_resource`（审计资源关联）；补 `chat`/`chat_record`/`chat_log` 缺失列；`terminology`/`data_training` 的 `embedding` 长度足够存 768 维向量。
- `askdata/askdata-server/src/main/resources/application.yaml` — 补 CORS 配置项、连接池配置、连接超时配置、embedding 模型路径配置、g2-ssr 图片服务地址、SQLBot 兼容的元数据查询开关；`sa-token.timeout` 对齐 SQLBot 8 天。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/AskdataMcpTools.java` — 补 SQLBot MCP 的 `access_token`（账号口令换 token）、`mcp_start`（建会话 + 返 chat_id）、`mcp_ws_list`（工作空间列表）能力；现 4 个工具缺 3 个。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/AssistantController.java` — 嵌入助手公共端点放行 + `assistant`/`embedded` token 校验 + `X-SQLBOT-HOST-ORIGIN` 域名绑定（对齐 SQLBot `apps/system/api/assistant.py`）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/AiModelConfig.java` — 接入多供应商模型工厂。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/AskdataAssistantConfig.java` / `AskdataNl2Sql.java` — 去掉"仅演示"定位，接入主链路能力（多轮记忆、持久化、模板化提示词）。
- `askdata/askdata-server/pom.xml` — 补 SQL 解析器（JSqlParser）、bcrypt、CSV/旧版 xls 解析、g2-ssr 客户端所需依赖。

### 删除

无。

### 明确不建

- **SQLBot 闭源 `sqlbot_xpack` 商业能力本体**（行/列权限 UI、SSO/OIDC/LDAP/CAS、企业微信/钉钉/飞书同步、License 校验、外观定制 `/system/appearance/*` 的商业版实现）。`sqlbot_xpack` 包在本机**不存在**（`ModuleNotFoundError: No module named 'sqlbot_xpack'`），SQLBot 开源后端无法直接运行，其 xpack 部分不可作为事实源。但**其能力语义**（如 `ds_permission` 行/列规则模型）已在 askdata 有等价数据结构，本 change 补齐**运行时强制**而非重做 UI。
- **SQLBot 的 `/mcp/*` 7 个手写 REST 端点协议形态**——沿用既有决策（askdata-sqlbot-parity 的 D2）：能力等价，askdata 用框架 MCP（stdio 缺失由 HTTP/SSE 覆盖）。但本 change 补齐**缺失的 3 个工具能力**（`access_token`/`mcp_start`/`mcp_ws_list`），即"能力等价"而非"端点照抄"。
- **g2-ssr 服务本身的 Node 移植**——保持独立服务形态，askdata 侧只补客户端调用与图片产出链路。
- **前端改动**——前端已是 SQLBot 前端逐字拷贝，本 change 只改后端；仅当后端协议修复必须时，按 SQLBot 语义在前端做最小对齐。
- **数据库形态变更**——沿用 H2/`schema.sql` 幂等脚本，不引入 Flyway 迁移链（SQLBot 的 alembic 历史不移植）。
- **性能基准与部署形态对比**。

# 非目标

- 不做 SQLBot 商业版（xpack）功能的完整复刻（理由见"明确不建"）。
- 不重写 askdata 已与 SQLBot 等价且验证过的模块：数据源 14 种方言矩阵、ES 引擎、MCP 框架封装、仪表盘 9 端点、术语/训练/自定义提示词的 CRUD + Excel 导入导出、用户批量导入、工作空间、AI 模型 CRUD、SQL 安全黑名单与危险函数表。
- 不改前端交互与视觉。
- 不做影子运行/双写流量对比（不在本 change 的交付形态内）。

# 验收示例

- A1 端点覆盖：逐条比对 SQLBot `backend/apps/**` 全部 router 端点（约 140 条）与其对应实现，askdata 侧均有等价路由且返回信封 `{code,data,msg}` 一致；`docs/comet/changes/askdata-sqlbot-full-parity/coverage.md` 给出逐条覆盖表（SQLBot 端点 → askdata 端点 → 状态 → 证据）。
- A2 问数知识增强链路：提问命中术语库中的业务词时，生成的 SQL 上下文包含该术语的释义与同义词扩展；`chat_record.extracted_keywords` / `expanded_keywords` 有值；`chat_log` 可见 `EXTRACT_KEYWORDS` / `FILTER_TERMS` / `FILTER_SQL_EXAMPLE` / `FILTER_CUSTOM_PROMPT` 操作记录；未配置 embedding 模型时链路自动降级为词法召回且不报错。
- A3 权限强制生效：为普通用户配置行权限（`ds_permission`）与列权限后，问数返回的结果集已被过滤/隐藏对应行与列，且过滤条件出现在执行的 SQL 中；管理员与白名单用户不受影响。当前 askdata 中 `DsPermissionQueryService` 无调用方，本项要求其为**运行时生效**而非仅存储。
- A4 鉴权三通道与白名单：`X-SQLBOT-TOKEN` 主通道可用；`X-SQLBOT-ASK-TOKEN: sk <jwt>` API Key 通道可用（合法 key 通过、非法/停用 key 401）；`X-SQLBOT-ASSISTANT-TOKEN` 嵌入通道可用（`app_secret` 正确通过、管理员账号被拒、非 type=4 被拒）；SQLBot 33 条公共路径在 askdata 侧均免鉴权（尤其 `/system/assistant/info/*`、`/system/assistant/app/*`、`/system/assistant/picture/*`、`/system/assistant/validate/*`、`/system/appearance/*`、`/system/authentication/*`、`/mcp*`、`/images/*`）；伪造 `Host: evil.com/api/v1/mcp` 的请求被 400 拒绝。
- A5 SQL 安全与执行安全：`WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` 被拒绝；解析失败的 SQL 被拒绝（保守拒绝）；`execSql` 使用参数化执行；非法 JDBC 连接参数被拦截；SQLBot `test_cwe89_escape_fix.py` 钉住的转义语义（引号加倍、反斜杠加倍、`IN`/`LIKE`/`=`/`N'…'` 片段、逻辑运算符仅 `AND`/`OR`）在 askdata 侧有等价单测通过。
- A6 嵌入/向量链路完整：术语、SQL 示例、表、数据源四类对象的向量均能写入并被召回；未配置远程 embedding 模型时使用本地 embedding 模型（`shibing624/text2vec-base-chinese`）仍可完成向量召回；启动时自动回填空向量（三类 backfill 在集群内只执行一次）；向量不可用时全链路降级不报错。
- A7 数据格式化与图表：查询结果中的 `1.23e20` 以 `123000000000000000000` 形式返回（无科学计数法）；`alias.column` 形式的列在图表绑定中可按短列名解析；图表配置支持 `multi-quota`；Excel 导出按完整图表配置还原字段。
- A8 审计完整性：新增/修改/删除等写操作经审计切面自动记录（含 `execution_time` 实际值、资源名、资源关联行）；登录成功/失败均有审计记录；审计写入失败不阻断业务。
- A9 国际化覆盖：消息键集合与 SQLBot 177 键对齐（默认/中文/英文三包键集合一致，英文包无缺键）；支持 `zh-CN`/`zh-TW`/`en`/`ko-KR`/`th-TH` 五语言；`?lang=` 与 `Accept-Language` 切换生效；新增键有单测。
- A10 基础设施等价：数据源查询走连接池（可验证 LRU 淘汰与 `dispose`）；ID 生成跨进程唯一（雪花位段正确）；应用密钥由配置提供而非硬编码；CORS 生效（含错误响应与助手动态域名）；响应信封由全局兜底保证。
- A11 不回退：全模块既有测试（含 MCP E2E、问数主链路、RAG、datasource 引擎、ES、术语/训练/提示词、用户批量导入、i18n）保持通过；前端构建不破。

# 约束与不变量

- Java 17 / Spring Boot 2.7.18 / fw-plus 版本线；构建需 JDK 17（`export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jd/Contents/Home`）。
- 前端零改动是硬约束：任何后端协议调整必须与 SQLBot 前端解析逐字兼容（`views/chat/answer/*.vue` 的 switch 全集）。
- 鉴权继续复用 Sa-Token 主通道；新增的嵌入/API Key 通道以 Filter 形式在 Sa-Token 之前执行（对齐 SQLBot `TokenMiddleware` 的三通道顺序）。
- 嵌入/助手/权限相关端点必须与 SQLBot 公共路径白名单一致，嵌入场景不得 401。
- 既有 DB 结构只做增量列与增量表；不改 H2 形态，不引入 Flyway。
- 降级优先于报错：embedding / 权限 / 增强模型任一不可用时，问数主链路必须仍能返回结果（对齐 SQLBot 的降级语义）。
- SQL 安全一律保守拒绝（对齐 SQLBot `check_sql_read` 与设计文档"sqlglot 安全层不得被简化掉"的反目标）。
- 行权限改写后必须做代码级验证（过滤条件可被解析器在 WHERE 中识别），比 SQLBot 更强，不得弱化。
- 本 change 属大型需求，按 Supervisor 拆分与确认规则处理（见"决策"）。

# 已核实的缺口清单（Build 前的事实基线）

以下缺口均经直接读码或对运行实例核实，非推测。

## 致命级（能力缺失或安全回退）

| 缺口 | 证据 | 影响 |
| --- | --- | --- |
| **行/列权限"存而不生效"** | `permission/DsPermissionQueryService.java` 全仓无任何调用方（仅自身声明）；SQLBot 在 `apps/chat/task/llm.py:1069` 的 `generate_filter` 与 `apps/datasource/crud/datasource.py:343` 两处强制 | 普通用户的行权限/列隐藏完全失效，数据越权可见 |
| **术语库从不进入提示词** | `terminology/TerminologyService.buildTerminologyContext`（:444-487）无调用方；`chat/**` 无任何 terminology 引用 | 术语库功能对问数零影响；SQLBot 每次问数注入 XML `<terminologies>` 块 |
| **自定义提示词未接入问数** | `chat/**` 无任何 custom_prompt 引用；SQLBot 按 GENERATE_SQL/ANALYSIS/PREDICT_DATA 三类型注入 | 自定义提示词对问数零影响 |
| **无 LLM 关键词抽取与同义词扩展** | `chat/**` 无 keyword 相关代码；SQLBot 有 `extract_keywords` + `expand_with_terminology`（落 `extracted_keywords`/`expanded_keywords`） | 表/列匹配精度下降，术语无法参与匹配 |
| **无嵌入式 token 鉴权通道** | askdata 仅 `config/SaTokenConfigure.java` 一条 Sa-Token 通道；SQLBot `apps/system/middleware/auth.py` 有 `bearer`/`assistant`/`embedded`/`sk` 四种 | 嵌入助手应用无法鉴权接入 |
| **无 API Key（sk）鉴权通道** | `system/service/ApiKeyService.java` 只做 CRUD；无任何中间件消费 `X-SQLBOT-ASK-TOKEN` | API Key 存了但不能用于认证 |
| **白名单只放开 `/api/v1/login/**`** | `SaTokenConfigure.java:24`；SQLBot `common/utils/whitelist.py` 33 条 | 26 条公共路径在 askdata 需登录；嵌入助手图片/信息端点 401 |
| **无 Host 头校验** | askdata 无对应 Filter；SQLBot `common/core/host_validation.py` + `main.py:244` | `Host: evil.com/api/v1/mcp` 型鉴权绕过未缓解 |
| **MCP `datasource_list` 泄露凭据** | `mcp/AskdataMcpTools.java:29` 直接返回 `DatasourceService.list(null)`，含 `configuration`（AES 密文）、`embedding`、`table_relation`、`recommended_config`；SQLBot `apps/mcp/mcp.py:151-155` 显式剥离这四项 | MCP 客户端可取走数据源连接密文 |
| **无 CORS 配置** | 全仓 `CorsConfiguration`/`addCorsMappings`/`@CrossOrigin` 零命中 | 前端 dev server(5173) 与嵌入 iframe 跨域被拦；错误响应无 CORS 头 |
| **SQL 安全由 AST 降为启发式** | `datasource/engine/SqlSafety.java` 只看首关键字+正则；SQLBot `apps/db/db.py:1193` 用 sqlglot AST | `WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` 可绕过 |
| **`execSql` 用 `Statement` 非 `PreparedStatement`** | `datasource/engine/JdbcEngine.java:410` `stmt.executeQuery(statement)` | 执行安全全靠上面的弱校验 |

## 降级级（能力弱于 SQLBot）

| 缺口 | 证据 |
| --- | --- |
| 术语/训练样例的 `embedding` 列从不写入 | `schema.sql:427,443` 有列；全仓只有 `core_table.table_embedding` 被写（`datasource/repository/TableRepository.java:70`） |
| 训练样例召回不按 `oid` 隔离 | `chat/service/SchemaContextBuilder.java:143` `WHERE datasource = ? AND enabled = TRUE` 无 oid；SQLBot 额外按 `oid` + `advanced_application` 过滤 |
| 训练样例召回忽略已存向量、每次重算 | `SchemaContextBuilder.java:370` `embedAll(question, texts)` 实时 embedding 全部行 |
| 训练样例召回不按 `advanced_application` 生效 | `SchemaContextBuilder.java:185` `dsId == null` 即返回空；SQLBot 支持无数据源时按高级应用召回 |
| 无数据源级向量 | SQLBot `apps/datasource/embedding/ds_embedding.py` + `core_datasource.embedding`；askdata 只有表级 |
| 图表配置缺 `multi-quota` 与值小写化 | `chat/service/ChatChartBuilder.java` 无 multi-quota 处理；SQLBot `check_save_chart` 有 |
| 启动期无 embedding 回填 | askdata 无 `@PostConstruct`/`ApplicationRunner`；SQLBot `main.py:45-57` 有 3 个回填 + 单 worker 守卫 |
| 无分布式锁/单 worker 守卫 | askdata 无任何锁；SQLBot `common/utils/distributed_lock.py` |
| 数据源无连接池 | `datasource/engine/JdbcEngine.java:114` 每次 `DriverManager.getConnection`；SQLBot `apps/db/db.py:1274` LRU 池（上限 500） |
| ID 非雪花 | `common/util/IdGenerator.java` = `now*1000+seq`，跨进程不唯一；SQLBot `common/utils/snowflake.py` 41/5/5/12 位 |
| 大数被转科学计数法 | `datasource/engine/JdbcEngine.java:758` `BigDecimal→doubleValue()`；SQLBot `common/utils/data_format.py:51` 转字符串 |
| 限定列名不归一 | 无 `normalize_qualified_sql_column_keys`；Hive/MySQL 的 `alias.column` 图表绑定失效 |
| 审计为手动调用 | 33 处 `auditRecorder.*`；SQLBot 为 `@system_log` 装饰器（AOP） |
| 审计 `execution_time` 恒为 0 | `system/audit/AuditRecorder.java:82` 硬编码 `0L` |
| 登录无审计 | `system/controller/LoginController.java` 无审计调用；SQLBot `apps/system/api/login.py:28` 有 |
| 无 `sys_logs_resource` 审计资源关联表 | SQLBot `common/audit/models/log_model.py:51` |
| 审计资源名需手工传 | SQLBot `build_resource_union_query` 跨 14 表自动解析 |
| 客户端 IP 只读 `X-Forwarded-For` | SQLBot `common/audit/schemas/logger_decorator.py:124` 六级链 + RFC 7239 |
| 英文消息包缺 51 键 | `messages_en.properties` 48 键 vs `messages.properties` 97 键 |
| 缺 3 种语言 | SQLBot `locales/` 5 种（en/ko-KR/th-TH/zh-CN/zh-TW）；askdata 仅 zh-CN/en |
| 消息键缺组 | 缺 `terminology`(24)/`data_training`(23)/`custom_prompt`(20)/`embedded`(11)/`llm`(3)/`variable`(4)/`excel_*`/`audit`(46) 等组 |
| 插值占位符形态不同 | SQLBot 具名 `{msg}`；askdata `MessageResolver` 位置 `{0}` |
| 仪表盘无工作空间级权限 | 仅 `create_by` 归属校验；SQLBot `require_permissions(type='dashboard')` |
| 仪表盘图表执行无只读校验 | `dashboard/JdbcChartDataExecutor.java:58` 裸 `Statement.executeQuery` |
| 仪表盘 canvas 列 `VARCHAR(10000)` | SQLBot 为 `Text`；大画布会被截断 |
| MCP 缺 6/7 工具 | 缺 `access_token`/`mcp_start`/`mcp_ws_list`/`mcp_model_list`/`mcp_question`/`mcp_assistant`；`question_answer` 是对**元数据库**的裸 NL2SQL，非问数主链路 |
| Excel 只读 sheet0、无列数校验 | `common/util/ExcelUtils.java:92` `getSheetAt(0)`；SQLBot 遍历全部 sheet + `get_excel_column_count` 校验 |
| `.xls` (BIFF8) 不可读 | `ExcelUtils.java` 用 `XSSFWorkbook`；SQLBot 用 calamine |
| **`askdata.excel-path` 配置键拼写错误（活 bug）** | `settings/SettingsFileController.java:30`、`terminology/TerminologyService.java:46`、`training/TrainingService.java:54` 用 `${askdata.excel-path:...}`，而 `application.yaml:24` 定义的是 `askdata.excel.path` → 失败记录下载与写入目录会分叉 |
| 模板表头丢失"（必填）"等指引 | 术语/训练模板用普通表头；SQLBot 用 `_template` i18n 表头 |
| 术语/训练校验错误为硬编码英文 | 无对应 i18n 键 |
| 训练样例搜索大小写敏感 | `training/TrainingRepository.java` 用 `LIKE`；SQLBot 用 `ilike` |
| 应用密钥硬编码 | `system/auth/CryptoUtil.java` 常量 `AskDataSecret001`；SQLBot 走 `SECRET_KEY` 环境变量 |
| 缺 `simple_aes_*` 兼容 | SQLBot `common/utils/aes_crypto.py:12` |
| bcrypt 口令不可校验 | SQLBot `common/core/security.py:9` 有 bcrypt `pwd_context`；askdata 仅 MD5 |
| token TTL 30 天 | `application.yaml:47` 2592000；SQLBot 8 天 |
| 错误响应无 CORS 头、异常无日志 | `common/web/ApiExceptionHandler.java` |
| 业务异常类型塌缩 | SQLBot 4 类（`SQLBotDBConnectionError`/`SQLBotDBError`/`ParseSQLResultError`/`SingleMessageError`）；askdata 2 类 |
| 缺 `checkParams` 非法 JDBC 参数拦截 | SQLBot `apps/db/db.py:1262` |
| 缺元数据查询开关 | SQLBot `SQLBOT_ALLOW_METADATA_QUERIES`；askdata 恒拒 `SHOW`/`DESCRIBE`/`EXPLAIN` |
| 无分页排序能力 | `common/web/PagerResult.java`/`PageResult.java` 均不支持 `order_by`/`desc`；SQLBot `common/core/pagination.py` 支持 |
| 缺 5 张表 | `sys_authentication`/`sys_user_platform`/`sys_platform_token`/`sys_logs_resource`/`terms`（SQLBot 实际 DDL 已从运行实例取到） |
| `chat`/`chat_record`/`chat_log` 缺列 | `chat`: `brief_generate`/`recommended_question_answer`/`recommended_question`/`recommended_generate`；`chat_record`: `ai_modal_id`/`engine_type`/`sql_exec_result`/`recommended_question_answer`/`datasource_select_answer`/`extracted_keywords`/`expanded_keywords`；`chat_log`: `type`/`pid`/`ai_modal_id`/`base_modal`/`reasoning_content` |
| 缺 `move` 语义与状态码差异 | askdata 多了 `check_name` 的 `opt="move"`；SQLBot 抛 500，askdata 抛 400 |
| `download-fail-info` 状态码 400 vs 404 | SQLBot 404 |
| 审计缺 `result_id_expr` 表达式提取 | SQLBot `logger_decorator.py:167-342` |
| 5 张 SQLBot 源测试无等价用例 | `test_embedded_auth_bypass_fix.py`/`test_cwe89_escape_fix.py`/`test_data_format.py`/`test_distributed_lock.py`/`test_connection_pool_manager.py` |
| `ai/` 包定位为演示 | `ai/AskdataNl2Sql.java` 走元数据库 + `LIMIT 200`；`ai/AskdataAssistantConfig.java` 单句硬编码系统提示词 |
| AI 模型只支持 OpenAI 兼容 | `ai/AiModelConfig.java` 忽略 `protocol`/`supplier`；SQLBot `apps/ai_model/model_factory.py` 有多供应商 |

## 事实源补充（2026-10-21 核实）

`192.168.1.100` 上运行着 SQLBot 官方容器 `sqlbot`（端口 8011→8000 后端、8012→8001），SSH 免密可达。其 PG 库 `sqlbot` 有 31 张表（askdata 缺 `sys_authentication`/`sys_logs_resource`/`sys_platform_token`/`sys_user_platform`/`terms`/`license`/`rsa`），`/opt/sqlbot/models` 有 793MB 的 `shibing624_text2vec-base-chinese`（含 `onnx/` 子目录，可直接用于 D8 的 ONNX 交付）。同机另有 MySQL 8.0.40(3306/13306)、PostgreSQL 16(5432)/pgvector(8432)、ClickHouse(8123/9002)、Elasticsearch、Redis、MinIO、Ollama 与 g2-ssr 图片服务，可支撑数据源方言矩阵与图片链路的真实验收。

# 决策

- **D1 覆盖对象**：SQLBot 开源社区版 `fc378c37` 为唯一事实源；`sqlbot_xpack` 闭源部分不可作为事实源（本地不存在该包），其能力语义以 askdata 已有等价数据结构为准补齐运行时行为。
- **D2 "完全覆盖"的口径**：以**后端能力线**为界（端点 + 运行时行为 + 数据模型 + 检索链路 + 安全控制 + 对外通道），不逐行对齐 Python 代码结构；协议（端点路径、请求/响应字段、SSE 事件 type、错误码）必须逐字兼容。
- **D3 安全回退按"向上对齐"处理**：askdata 当前缺失的安全控制（Host 校验、嵌入鉴权、API Key 鉴权、白名单、权限强制、参数化执行）属"后端能力减弱"，须补齐至 SQLBot 语义，不视为"额外功能"而排除。
- **D4 保持 askdata 已有优势**：askdata 已有而 SQLBot 没有的（如更完整的错误分类、ES 单测覆盖、框架 MCP 封装）不降级回退。
- **D5 部署形态不变**：H2 + `schema.sql` 幂等脚本保持，不引入 Flyway/alembic 迁移链；g2-ssr 保持独立服务。
- **D6 前端不作为本 change 的改动面**：仅在后端协议修复确有必要时做最小对齐，且必须符合 SQLBot 前端既有解析。
- **D7 Supervisor 拆分（Q1 决策 A）**：拆为 5 个子任务——`chat-knowledge`（问数知识增强链）、`datasource-safety`（数据源与执行安全）、`auth-whitelist`（鉴权通道与公共路径）、`infra-toolchain`（基础设施工具链：连接池/雪花 ID/大数格式化/CORS/信封/审计切面/embedding 模型）、`i18n-audit`（i18n 与审计）。推进方式：**多会话协作**（Runtime 为每个子任务建独立 worktree，优先由独立会话处理，不可用时自动改用 subagent）。
- **D8 本地 embedding 模型随构建交付（Q2 决策 A）**：仿 SQLBot Dockerfile 的 `vector-model` 阶段，把 `shibing624_text2vec-base-chinese`（ONNX，768 维归一化）随构建产物交付到可配置目录（默认 `/opt/askdata/models`），保证未配置远程 embedding 模型时向量召回默认可用。
- **D9 一并实现 SSO 与企业 IM 同步（Q3 决策 A）**：新增 `sys_authentication` / `sys_user_platform` / `sys_platform_token` 三表，实现 CAS/OAuth2/LDAP/SAML 认证源与企业平台用户同步的**后端等价能力**；SSO 第三方授权统一使用 **JustAuth**（`me.zhyd:JustAuth` 1.16.6 + `com.xkcoding:justauth-spring-boot-starter` 1.4.0，两者均已在本机 `~/.m2` 仓库中，离线构建可用）。表结构以 `192.168.1.100` 上运行中的 SQLBot 实例（容器 `sqlbot`，PG 库 `sqlbot`）实际 DDL 为准。
- **D10 公共路径全量放开并补齐缺失端点（Q4 决策 C）**：按 SQLBot 33 条白名单全量放开，鉴权判定改用 `requestURI` 原始路径（非 URL 拼接路径），并新增 Host 头校验 Filter 防 `evil.com/api/v1/mcp` 型绕过；同时补齐 askdata 缺失的公共端点（`/system/appearance/ui`、`/system/appearance/picture/*`、`/system/config/key`、`/system/authentication/*`、`/system/platform/*`）。
- **D11 事实源以运行实例为准**：`192.168.1.100`（SSH key 免密可达）上运行着 SQLBot 官方镜像 `sqlbot`（含完整 PG 库 31 张表 + 真实 embedding 模型 + g2-ssr 图片服务），作为 schema/DDL/运行时行为的一手对照源；同机 MySQL/PostgreSQL/pgvector/ClickHouse/ES/Redis/MinIO/Ollama 可用于数据源方言矩阵的真实验收。

# 待解决问题

无（Q1–Q4 已由用户确认，结论落入 D7–D10）。
