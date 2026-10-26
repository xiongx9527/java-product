# 目标

AskData（askdata-server）与 SQLBot 开源版逐行对照后，修复审计确认的 6 项 P0 失效/安全缺陷与主要 P1 能力打折：仪表板图表因密钥不匹配整体不可用、普通用户可越权预览全表、analysis 气泡刷新后丢失、外观设置保存 404、嵌入助手 token 可被伪造、六类数据源因缺驱动不可用；同时把被压缩到 10 行的 SQL 提示词工程还原为 SQLBot 的模板体系，并修掉前端逐字拷贝暴露出的契约性打折（operate 枚举、错误分型、fields_info、first_chat、limit_rows 默认值、license 对外观页的拦截）；同时把企业版能力（license 管理、企业 IM 扫码登录、平台客户端与用户同步、SSO 协议登录、企业登录参数）从 stub/占位补齐为真实可用实现，前端不再因 license 恒定 invalid 而静默拦截。

# 范围

## 目录结构

### 新建

- askdata/askdata-server/src/main/resources/prompts/ — 从 SQLBot backend/templates 移植的提示词资源
  - template.yaml — 主规则（process_check 自检、标识符保持、多表限定、时间格式、JSON 输出契约、few-shot）
  - sql_examples/{MySQL,PostgreSQL,Oracle,Microsoft_SQL_Server,ClickHouse,Hive,DM,Kingbase,Doris,StarRocks,AWS_Redshift,Elasticsearch}.yaml — 13 份方言样例
- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/chat/PromptTemplateLoadTest.java — 模板资源可加载、规则段齐全
- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/dashboard/JdbcChartDataExecutorCryptoTest.java — 应用密钥密文可解密
- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/datasource/PreviewPermissionTest.java — 预览行列权限与 fail-closed
- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/embedded/AssistantSignTest.java — 签名/时间窗校验
- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/system/license/LicenseServiceTest.java — license 上传/校验/过期/版本
- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/system/platform/EnterpriseLoginTest.java — 平台客户端信息、扫码登录链路、用户同步、org 端点
- askdata/askdata-server/src/main/resources/license/ — license 公钥/校验配置（无敏感信息）

### 修改

- askdata/askdata-server/pom.xml — ojdbc11、mssql-jdbc、clickhouse-jdbc、hive-jdbc、kingbase、dm 驱动改 runtime scope
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/dashboard/JdbcChartDataExecutor.java — 解密改走 DatasourceCrypto.decodeConfiguration（应用密钥优先、SQLBot 密钥回退），失败返回 status=failed 与明确 message
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/repository/ChatRepository.java — insertSubRecord 去掉对基础记录外键列的反向 UPDATE
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java — 从 classpath 加载提示词模板与方言样例；注入 table_relation 外键段；列权限字段从 schema 剔除
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatQuestionService.java — limit_rows 默认 true + 1000 行截断、跨轮错误自愈注入、operate 用枚举名、错误分型与生成前 check_connection、动态数据源行规则接线
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatChartBuilder.java — 图表上下文补 <sql><m-schema><chart-type>、保留 multi-quota、value 小写归一
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatModelClient.java — 请求体带 stream_options.include_usage，记录 input/output/total，推荐与抽取步骤同样采集
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatService.java — createChat 生成 first_chat 空记录（recommended_config==2 预填推荐问题）；record data 带 fields_info；chart_answer 存模型思考；log message 结构化
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/service/DatasourceService.java — previewData 行列权限；fieldEnum 按 CoreField.id 查；改注释/配置后重算向量
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/engine/JdbcEngine.java — Hive/Spark Thrift 元数据解析、SQLServer GBK 回正、六类驱动 URLClassLoader 加载
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/engine/ConnectionPoolManager.java — 消费 conf.poolSize/max_overflow/pool_timeout
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/PermissionEnforcer.java — 解析失败 fail-closed
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/DsPermissionQueryService.java — like 条件生成 '%value%'
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/DynamicDatasource.java — 新增 table.rule 并生成过滤 SQL
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/AssistantPublicController.java — 增加签名、时间窗与 UA/webdriver 校验
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/settings/AppearanceController.java — 新增 POST /system/appearance（ws admin）
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/controller/LoginController.java — 口令强度（大小写+数字+特殊，8-20）
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/config/SaTokenConfigure.java — 每请求用户状态复查、timeout 对齐
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/authentication/Saml2SsoClient.java — 断言签名校验
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/platform/PlatformController.java — 返回真实平台客户端配置
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/CorsConfig.java — 动态 origin 方法接入实际调用点
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/training/TrainingRecallService.java — ILIKE 双向 + 向量合并
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/embedding/TableEmbeddingService.java — 五档打分 × 覆盖率
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/AiModelService.java — 连通测试支持 Azure/自定义参数
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/AssistantService.java — 取数据源免 ws_admin、上传校验与旧文件清理
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/AskdataMcpTools.java — mcp_assistant 使用 url+authorization
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/service/AuthenticationService.java — list/getById 加 requireAdmin
- askdata/askdata-server/web/src/utils/xpackPatch.ts、web/src/stores/appearance.ts、web/src/views/system/appearance/index.vue — 外观链路与 license 解耦；license 上传接后端或下掉按钮
- askdata/askdata-server/web/dist — 重新构建与 src 一致
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/controller/SkeletonStubController.java — /system/license 由真实 LicenseController 接管（POST 上传、GET 状态与版本），stub 仅保留必要兜底
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/license/LicenseService.java — 新建：license 持久化、签名/有效期/版本校验
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/platform/PlatformController.java — client/{origin} 返回真实配置；新增 /system/platform/org/{id}
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/platform/PlatformSyncService.java — 同步结果口径与组织信息查询
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/authentication/AuthenticationService.java — OIDC/OAuth2/CAS/SAML2 与企业 IM 的 login/sso 链路补齐与配置校验
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/service/ParameterService.java — login 分组企业登录参数读写与生效
- askdata/askdata-server/web/src/utils/xpackPatch.ts — getLicense 改读后端 /system/license 真实状态
- askdata/askdata-server/web/src/views/login/xpack/* — 扫码/OIDC/OAuth2/CAS 组件的门控与错误提示按真实 license 状态工作

### 删除

无

### 明确不建

- 不引入新的 LLM 客户端或向量库 — 沿用现有 ChatModelClient 与可插拔向量后端。
- 不处理 P2 精选清单中未列入的项目（Excel 采样行数、CSV BOM/分隔符推断、术语分页、审计 typeLabel、内联 thinking 解析、线程模型改造、按次选模型、MAX_TABLES 可配、ES 索引注释、MCP 流式）— 各自独立，留待后续 change，避免本次范围失控。

# 非目标

- 不改变已归档 parity 规格中已验证等价的部分（只读 SQL 校验、JDBC 参数安全、API Key 鉴权、用户/工作空间/术语/训练 CRUD、MCP 工具别名、登录加密、LDAP bind）。
- 不新增超出 SQLBot 的能力（定时问数、服务端图表渲染、审计日志页、SSO 多协议、可插拔向量后端保持不变）。
- 不重写前端（前端保持 SQLBot 逐字拷贝，只改 xpackPatch/appearance 相关与外溢契约所需处）。
- 不处理审计 P2 精选清单中除 A27 明确列出之外的条目。
- 不运行真实 LLM/数据库服务完成端到端验证；验收以单元/切片测试与代码对照为准。

# 验收示例

- A1 仪表板图表取数：配置以应用密钥落库的数据源，仪表板图表请求返回 status=success 与数据；配置无法解密时返回 status=failed 且 message 指明原因，不再静默失败。
- A2 表数据预览权限：普通用户预览已配置列权限与行权限的表时，返回字段仅含允许列，且执行 SQL 注入行权限 WHERE；ws admin 预览不受限。
- A3 权限执行 fail-closed：PermissionEnforcer 无法解析 SQL 时拒绝执行并返回错误，不再以未加固 SQL 放行。
- A4 analysis/predict 外键方向：对基础气泡执行分析/预测后，基础记录自身的 analysis_record_id 与 predict_record_id 保持 NULL，子记录指向基础记录。
- A5 外观设置保存：ws admin 调 POST /system/appearance 保存外观键值后，GET /system/appearance/ui 能读回；外观页保存不再 404。
- A6 外观不依赖 license：前端 getLicense() 返回 invalid 时，外观页仍可读取与保存，页面不再"改了没反应"。
- A7 嵌入助手 token：缺少签名、签名不匹配或超出时间窗的请求被拒绝；带合法签名的请求可换取工作空间 token。
- A8 数据源驱动可用：Oracle、SQLServer、ClickHouse、Hive、Kingbase、DM 六类驱动进入 runtime scope，JdbcEngine 能加载对应 JDBC URL，前端选择这些类型时测试连接不再因缺少驱动失败。
- A9 SQL 提示词移植：生成 SQL 的系统提示来自 classpath 的 template.yaml 与所选方言样例，包含标识符保持、多表限定、时间格式、JSON 输出契约与 few-shot 示例。
- A10 图表生成上下文：图表请求消息包含 <sql><m-schema><chart-type> 三段；multi-quota 图表类型被保留；value 字段统一小写。
- A11 默认行数限制：chat.limit_rows 未显式关闭时按开启处理，问数结果超过 1000 行被截断。
- A12 token 统计：LLM 请求带 stream_options.include_usage；chat_log 记录 input/output/total；推荐问题与字段抽取步骤同样落 token。
- A13 表关系进提示词：提示词包含 table_relation 外键段（形如 a.x=b.y）与关联表补全。
- A14 训练样例召回：关键词命中时 ILIKE 双向召回与向量召回两路合并返回。
- A15 表级关键词打分：表名 1.0、表注释 0.9、表名子串 0.7、字段注释 0.5、字段名 0.3，并按覆盖率加权。
- A16 跨轮错误自愈：同一会话下一轮提问的提示词中包含上一轮 exec-sql 错误的 traceback。
- A17 first_chat 记录：创建会话时生成 first_chat=True 的空记录；recommended_config==2 时预填推荐问题。
- A18 动态数据源行规则：宿主下发的表级 rule 生成并注入过滤 SQL；行权限 like 条件生成 '%value%'。
- A19 数据源元数据细节：Hive 数据源按 Spark Thrift 结果集解析索引与字段；SQLServer 乱码按 latin-1→GBK 回正；字段枚举按 CoreField.id 查询。
- A20 向量刷新时机：修改表注释、字段注释或数据源配置后，表、字段与数据源向量被重算。
- A21 连接池参数：数据源配置的 poolSize、max_overflow、pool_timeout 生效。
- A22 会话与口令：每个请求复查用户 status/oid；口令强度要求大小写、数字与特殊字符且长度 8-20。
- A23 模型与数据源下发：AI 模型连通测试支持 Azure 端点与自定义参数；高级应用数据源优先取宿主 API 下发结果。
- A24 嵌入与助手：嵌入页取数据源不要求 ws admin；助手 UI 上传校验 jpg/png 且不超过 10MB、替换时删除旧文件、清空时置空；mcp_assistant 使用下发的 url 与 authorization 调第三方接口。
- A25 认证与平台：认证源 list/getById 要求 admin；SAML 响应断言签名被校验；平台客户端接口返回真实配置而非硬编码占位；助手 CORS 动态 origin 被实际调用。
- A26 问数执行明细契约：chat_log.operate 用 GENERATE_SQL 枚举名；错误事件为 {message,traceback,type:exec-sql-err|db-connection-err} 且生成 SQL 前先 check_connection；/chat/record/{id}/data 返回 fields_info；chart_answer 存模型原始思考；/chat/record/{id}/log 的 message 为结构化对象。
- A27 前端交付一致性：外观链路与 license 状态解耦；关于页许可证上传按钮对应可用端点或从界面移除；web/dist 重新构建后与 web/src 行为一致。

- A28 License 管理：ws admin 通过 POST /system/license 上传许可证后持久化生效；GET /system/license 返回真实状态（valid/invalid/expired）、版本、有效期与失败原因；GET /system/license/version 返回真实版本而非固定 community。
- A29 前端企业门控：getLicense 读取后端真实状态；license 有效时企业 IM 扫码登录、用户页平台同步按钮、平台参数页企业能力可正常请求，无效时给出明确提示而非静默隐藏。
- A30 平台客户端信息：GET /system/platform/client/{origin} 返回 sys_authentication 中该平台真实配置（client_id、enable、valid 及平台所需字段），不再硬编码 {client_id:null, enable:false}。
- A31 企业 IM 扫码登录：钉钉、Lark、LarkSuite、企业微信四类扫码登录全流程可完成（取客户端信息 → 第三方 SDK 扫码 → 回调 → sso/{type} 建立会话并跳回系统）；平台未配置或已禁用时返回可读错误。
- A32 平台用户同步：用户页平台同步按钮可用；POST /system/platform/user/sync 幂等同步并返回 total/created/updated/skipped。
- A33 平台组织端点：/system/platform/org/{id} 可正常响应前端 api/system.ts 的 platform() 调用，不再是死链。
- A34 SSO 协议登录链路：OIDC、OAuth2、CAS、SAML2 的 login/{type} 跳转 URL 与 sso/{type} 回调登录链路可用，配置缺失时给出明确错误。
- A35 企业登录参数：/system/parameter/login 分组中企业登录相关配置可读可写，并实际影响登录页展示的登录方式。

# 约束与不变量

- 只读 SQL 校验、JDBC 参数拦截、表白名单等既有安全控制不被削弱。
- 前端保持 SQLBot 逐字拷贝，除 xpackPatch/appearance 与外溢契约外不改前端行为。

# 决定

D1 驱动补齐（A8）：把六类驱动改为 runtime 依赖，而不是从 UI 下掉类型。理由：SQLBot 支持这些类型，前端可选 14 种，"能选不能用"属可见缺陷；六类 artifact 均在 Maven Central 可获得。
D2 提示词移植（A9）：直接移植 SQLBot backend/templates/template.yaml 与 sql_examples/*.yaml 为 classpath 资源，由 SchemaContextBuilder 按方言选择加载，而不是继续手写规则字符串。
D3 外观保存落点（A5）：在 AppearanceController 增加 POST /system/appearance（ws admin），写入 sys_arg 的 appearance. 前缀键，与既有 AppearanceService 读取路径共用；不接入 xpack。
D4 嵌入 token 校验（A7）：按 SQLBot 语义实现签名头、assistant_id 一致性、时间窗与 UA/webdriver 校验，签名密钥沿用 askdata.secret.key，origin 匹配保留为额外条件。
D5 外键方向（A4）：按 SQLBot curd/chat.py:841-865 只写 child→base，删除对基础记录的反向 UPDATE。
D6 验收方式：不启动真实服务，用新增/扩展的 JUnit 测试与现有测试套件验证；mvn -pl askdata/askdata-server test。
D7 企业功能范围（A28-A35）：按"真实可用"标准实现，不做占位。license 采用自包含的签发/校验实现（密钥配置化，不写入仓库）；企业 IM 扫码登录按各平台公开 OAuth/扫码流程实现，回调统一走既有 sso/{type} 端点；前端门控从"恒定 invalid"改为读后端真实状态。SQLBot 的 sqlbot_xpack 不在开源仓库中，无法逐字对照，因此以 askdata 已有数据结构（sys_authentication type 6..9）与前端契约为准保证行为一致。

# 待解决问题

无

# 验收执行要求

- 所有新增与改动的测试随模块测试运行：mvn -pl askdata/askdata-server -am test -Dmaven.test.skip=false。
- 提示词模板资源加载测试必须校验规则段与方言文件存在且非空。
- 权限相关测试须覆盖普通用户与 ws admin 两条路径。
