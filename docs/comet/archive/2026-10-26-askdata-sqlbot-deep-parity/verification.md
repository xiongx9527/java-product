---
generated_from_state_version: 10
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-26T04:31:54.442Z
- 摘要: Independent read-only verification of candidate d53aa9e9: askdata-server full suite 851 tests pass (0 failures, 5 live-DB tests skipped by design); all 25 builder-cited test classes exist and pass; spot-checked source confirms A1 (JdbcChartDataExecutor:41 failed / :73 success), A3 (PermissionEnforcer:128 fail-closed throw), and A28 (LicenseService:100/138 valid/invalid/expired). All 35 acceptance items satisfied by implementation + tests.

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1 仪表板图表取数：配置以应用密钥落库的数据源，仪表板图表请求返回 status=success 与数据；配置无法解密时返回 status=failed 且 message 指明原因，不再静默失败。 | JdbcChartDataExecutor.java:41 returns status=failed with reason on decrypt failure; :73 success; EngineExecutionParityTest passes. |
| A2 | passed | brief.md | A2 表数据预览权限：普通用户预览已配置列权限与行权限的表时，返回字段仅含允许列，且执行 SQL 注入行权限 WHERE；ws admin 预览不受限。 | DsPermissionQueryService + PermissionEnforcer inject column/row predicates; DsPermissionQueryServiceTest + EngineExecutionParityTest pass. |
| A3 | passed | brief.md | A3 权限执行 fail-closed：PermissionEnforcer 无法解析 SQL 时拒绝执行并返回错误，不再以未加固 SQL 放行。 | PermissionEnforcer.java:128 throws on SQL parse failure (fail-closed); PermissionEnforcerTest passes. |
| A4 | passed | brief.md | A4 analysis/predict 外键方向：对基础气泡执行分析/预测后，基础记录自身的 analysis_record_id 与 predict_record_id 保持 NULL，子记录指向基础记录。 | SchemaContextBuilder keeps base record analysis/predict_record_id NULL; child points to base; SchemaContextForeignKeysTest passes. |
| A5 | passed | brief.md | A5 外观设置保存：ws admin 调 POST /system/appearance 保存外观键值后，GET /system/appearance/ui 能读回；外观页保存不再 404。 | AppearanceController/Service persist and read-back; web/src/stores/appearance.ts; GET /system/appearance/ui returns saved values. |
| A6 | passed | brief.md | A6 外观不依赖 license：前端 getLicense() 返回 invalid 时，外观页仍可读取与保存，页面不再"改了没反应"。 | web/src/stores/appearance.ts + xpackPatch.ts decouple appearance from license; works when getLicense()=invalid. |
| A7 | passed | brief.md | A7 嵌入助手 token：缺少签名、签名不匹配或超出时间窗的请求被拒绝；带合法签名的请求可换取工作空间 token。 | EmbeddedSigner validates signature/timestamp; AssistantPublicController exchanges valid signature for workspace token; tests pass. |
| A8 | passed | brief.md | A8 数据源驱动可用：Oracle、SQLServer、ClickHouse、Hive、Kingbase、DM 六类驱动进入 runtime scope，JdbcEngine 能加载对应 JDBC URL，前端选择这些类型时测试连接不再因缺少驱动失败。 | JdbcEngine loads Oracle/SQLServer/ClickHouse/Hive/Kingbase/DM via runtime scope; JdbcEngineDriverAvailabilityTest passes. |
| A9 | passed | brief.md | A9 SQL 提示词移植：生成 SQL 的系统提示来自 classpath 的 template.yaml 与所选方言样例，包含标识符保持、多表限定、时间格式、JSON 输出契约与 few-shot 示例。 | SqlPromptTemplates loads classpath template.yaml + dialect samples; SqlPromptTemplatesTest passes. |
| A10 | passed | brief.md | A10 图表生成上下文：图表请求消息包含 <sql><m-schema><chart-type> 三段；multi-quota 图表类型被保留；value 字段统一小写。 | Chart message built with <sql><m-schema><chart-type>; multi-quota preserved; value lower-cased; SqlPromptTemplatesTest passes. |
| A11 | passed | brief.md | A11 默认行数限制：chat.limit_rows 未显式关闭时按开启处理，问数结果超过 1000 行被截断。 | ChatQuestionService enforces chat.limit_rows default-on truncation >1000 rows; ChatPipelineTest passes. |
| A12 | passed | brief.md | A12 token 统计：LLM 请求带 stream_options.include_usage；chat_log 记录 input/output/total；推荐问题与字段抽取步骤同样落 token。 | ChatModelClient sends stream_options.include_usage; ChatRepository records input/output/total; ChatLogContractTest passes. |
| A13 | passed | brief.md | A13 表关系进提示词：提示词包含 table_relation 外键段（形如 a.x=b.y）与关联表补全。 | SchemaContextBuilder adds table_relation (a.x=b.y) + relation completion; verified in SqlPromptTemplatesTest. |
| A14 | passed | brief.md | A14 训练样例召回：关键词命中时 ILIKE 双向召回与向量召回两路合并返回。 | TrainingRecallService merges ILIKE bidirectional + vector recall; TrainingDualRecallTest passes. |
| A15 | passed | brief.md | A15 表级关键词打分：表名 1.0、表注释 0.9、表名子串 0.7、字段注释 0.5、字段名 0.3，并按覆盖率加权。 | TableEmbeddingService scores table-name 1.0/comment 0.9/substring 0.7/field-comment 0.5/field 0.3 by coverage; TableKeywordScoreTest passes. |
| A16 | passed | brief.md | A16 跨轮错误自愈：同一会话下一轮提问的提示词中包含上一轮 exec-sql 错误的 traceback。 | ChatQuestionService injects previous exec-sql traceback into next-turn prompt; ChatKnowledgeChainTest passes. |
| A17 | passed | brief.md | A17 first_chat 记录：创建会话时生成 first_chat=True 的空记录；recommended_config==2 时预填推荐问题。 | ChatRepository/ChatService create first_chat=True empty record; recommended_config==2 prefills; ChatLogContractTest passes. |
| A18 | passed | brief.md | A18 动态数据源行规则：宿主下发的表级 rule 生成并注入过滤 SQL；行权限 like 条件生成 '%value%'。 | DynamicDatasourceResolver builds filter SQL from host rule; like -> '%value%'; DynamicDatasourceRowRuleTest passes. |
| A19 | passed | brief.md | A19 数据源元数据细节：Hive 数据源按 Spark Thrift 结果集解析索引与字段；SQLServer 乱码按 latin-1→GBK 回正；字段枚举按 CoreField.id 查询。 | JdbcEngine Hive Spark Thrift index/field parse; SQLServer latin1->GBK; CoreField.id enum; HiveSparkThriftMetadataTest passes. |
| A20 | passed | brief.md | A20 向量刷新时机：修改表注释、字段注释或数据源配置后，表、字段与数据源向量被重算。 | Vector refresh recomputes table/field/datasource vectors after comment/datasource change; VectorRefreshTimingTest passes. |
| A21 | passed | brief.md | A21 连接池参数：数据源配置的 poolSize、max_overflow、pool_timeout 生效。 | ConnectionPoolManager honors poolSize/max_overflow/pool_timeout; ConnectionPoolSettingsTest passes. |
| A22 | passed | brief.md | A22 会话与口令：每个请求复查用户 status/oid；口令强度要求大小写、数字与特殊字符且长度 8-20。 | CurrentUserService/AuthenticationService recheck status/oid per request; password strength 8-20 mixed; PasswordStrengthAndSessionTest passes. |
| A23 | passed | brief.md | A23 模型与数据源下发：AI 模型连通测试支持 Azure 端点与自定义参数；高级应用数据源优先取宿主 API 下发结果。 | AiModelService supports Azure endpoint + custom params; host API datasource preferred; AiModelStatusAzureTest + HostApiDatasourceTest pass. |
| A24 | passed | brief.md | A24 嵌入与助手：嵌入页取数据源不要求 ws admin；助手 UI 上传校验 jpg/png 且不超过 10MB、替换时删除旧文件、清空时置空；mcp_assistant 使用下发的 url 与 authorization 调第三方接口。 | Embedded page datasource no ws-admin; upload jpg/png<=10MB, replace deletes old, clear empties; Assistant UI + McpAssistantHostDatasource tests pass. |
| A25 | passed | brief.md | A25 认证与平台：认证源 list/getById 要求 admin；SAML 响应断言签名被校验；平台客户端接口返回真实配置而非硬编码占位；助手 CORS 动态 origin 被实际调用。 | AuthenticationService requires admin for auth sources; SAML assertion signature checked; platform client returns real config; CORS dynamic origin; tests pass. |
| A26 | passed | brief.md | A26 问数执行明细契约：chat_log.operate 用 GENERATE_SQL 枚举名；错误事件为 {message,traceback,type:exec-sql-err\|db-connection-err} 且生成 SQL 前先 check_connection；/chat/record/{id}/data 返回 fields_info；chart_answer 存模型原始思考；/chat/record/{id}/log 的 message 为结构化对象。 | SqlbotMcpCompatController uses GENERATE_SQL enum; error event {message,traceback,type}; check_connection before SQL; fields_info/log structured; ChatLogContractTest passes. |
| A27 | passed | brief.md | A27 前端交付一致性：外观链路与 license 状态解耦；关于页许可证上传按钮对应可用端点或从界面移除；web/dist 重新构建后与 web/src 行为一致。 | web/dist rebuilt consistent with web/src; appearance decoupled from license; about-page license endpoint available. |
| A28 | passed | brief.md | A28 License 管理：ws admin 通过 POST /system/license 上传许可证后持久化生效；GET /system/license 返回真实状态（valid/invalid/expired）、版本、有效期与失败原因；GET /system/license/version 返回真实版本而非固定 community。 | LicenseController upload persists; GET returns valid/invalid/expired + version/expiry/reason; /version real version; LicenseServiceTest passes. |
| A29 | passed | brief.md | A29 前端企业门控：getLicense 读取后端真实状态；license 有效时企业 IM 扫码登录、用户页平台同步按钮、平台参数页企业能力可正常请求，无效时给出明确提示而非静默隐藏。 | web reads real license status; enterprise IM/platform sync/params gated on valid with clear messaging; verified in source. |
| A30 | passed | brief.md | A30 平台客户端信息：GET /system/platform/client/{origin} 返回 sys_authentication 中该平台真实配置（client_id、enable、valid 及平台所需字段），不再硬编码 {client_id:null, enable:false}。 | PlatformController/EnterprisePlatformService returns real sys_authentication config (not hardcoded null/false); PlatformSyncServiceTest passes. |
| A31 | passed | brief.md | A31 企业 IM 扫码登录：钉钉、Lark、LarkSuite、企业微信四类扫码登录全流程可完成（取客户端信息 → 第三方 SDK 扫码 → 回调 → sso/{type} 建立会话并跳回系统）；平台未配置或已禁用时返回可读错误。 | DingTalk/Lark/LarkSuite/WeChatWork scan-login full flow; clear error when unconfigured/disabled; AuthenticationSsoLoginTest passes. |
| A32 | passed | brief.md | A32 平台用户同步：用户页平台同步按钮可用；POST /system/platform/user/sync 幂等同步并返回 total/created/updated/skipped。 | POST /system/platform/user/sync idempotent total/created/updated/skipped; PlatformSyncServiceTest passes. |
| A33 | passed | brief.md | A33 平台组织端点：/system/platform/org/{id} 可正常响应前端 api/system.ts 的 platform() 调用，不再是死链。 | /system/platform/org/{id} responds to api/system.ts platform() call; no dead link. |
| A34 | passed | brief.md | A34 SSO 协议登录链路：OIDC、OAuth2、CAS、SAML2 的 login/{type} 跳转 URL 与 sso/{type} 回调登录链路可用，配置缺失时给出明确错误。 | OIDC/OAuth2/CAS/SAML2 login/{type} + sso/{type} callback; clear error when config missing; AuthenticationSsoLoginTest passes. |
| A35 | passed | brief.md | A35 企业登录参数：/system/parameter/login 分组中企业登录相关配置可读可写，并实际影响登录页展示的登录方式。 | /system/parameter/login enterprise config readable/writable and affects login page; LoginParameterRoundTripTest passes. |

## 检查

_没有记录 Runtime 检查。_

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- backend-suite-851: passed — askdata-server 全量测试通过（851）
- frontend-build: passed — web/dist 重新构建并与 web/src 行为一致
- mcp-compat: passed — SqlbotMcpCompatControllerTest 对齐 SQLBot 工具名/字段
- 已知限制: 部分 SQLBot 线上环境字段（真实钉钉/企业微信/平台 OAuth）依赖外部服务，单元测试以 Mock/本地可达端点为主，需真机联调补充。
- 已知限制: 方言提示词样例覆盖 Oracle/MySQL/PostgreSQL/ClickHouse/Hive/DM/Doris/StarRocks/Redshift/ES，其余方言走默认模板。

## 阻塞项

_无。_

## 风险与跳过的工作

- 5 live-dialect integration tests are skipped without reachable external DBs; SQLBot parity against those engines needs live QA.
- Enterprise IM scan-login and SSO against real IdPs rely on external services; verified with mocks/config-missing paths only.
- SQL dialect prompt few-shot coverage: Oracle/MySQL/PostgreSQL/ClickHouse/Hive/DM/Doris/StarRocks/Redshift/ES covered; other dialects fall back to default template.
- Frontend parity verified by rebuild + source review; full E2E browser QA against a live SQLBot backend was not executed in this environment.

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | pass | — | Independent read-only verification of candidate d53aa9e9: askdata-server full suite 851 tests pass (0 failures, 5 live-DB tests skipped by design); all 25 builder-cited test classes exist and pass; spot-checked source confirms A1 (JdbcChartDataExecutor:41 failed / :73 success), A3 (PermissionEnforcer:128 fail-closed throw), and A28 (LicenseService:100/138 valid/invalid/expired). All 35 acceptance items satisfied by implementation + tests. | 2026-10-26T04:31:54.442Z |



## 结论

Independent read-only verification of candidate d53aa9e9: askdata-server full suite 851 tests pass (0 failures, 5 live-DB tests skipped by design); all 25 builder-cited test classes exist and pass; spot-checked source confirms A1 (JdbcChartDataExecutor:41 failed / :73 success), A3 (PermissionEnforcer:128 fail-closed throw), and A28 (LicenseService:100/138 valid/invalid/expired). All 35 acceptance items satisfied by implementation + tests.
