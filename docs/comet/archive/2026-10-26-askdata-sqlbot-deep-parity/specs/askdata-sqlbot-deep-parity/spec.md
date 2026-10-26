# askdata-sqlbot-deep-parity 完整目标规格

> 本规格描述本次 change 完成后 askdata 在审计发现的 P0 失效/安全缺陷与主要 P1 能力打折上的目标行为。
> 除本文明确变更的部分外，其余行为沿用已归档的正式规格：`docs/comet/specs/askdata/spec.md`、
> `docs/comet/specs/askdata-sqlbot-gap/spec.md`、`docs/comet/specs/askdata-cn-table-fix/spec.md`、
> `docs/comet/specs/askdata-mcp-framework/spec.md`、`docs/comet/specs/askdata-sqlbot-parity/spec.md`、
> `docs/comet/specs/askdata-sqlbot-full-parity/spec.md`、`docs/comet/specs/askdata-sqlbot-xpack-parity/spec.md`、
> `docs/comet/specs/askdata-sqlbot-remaining-fidelity-r2/spec.md`、`docs/comet/specs/parity-hardening/spec.md`。
>
> 事实源：用户提供的 SQLBot 对照审计结论（2026-10-25），SQLBot 开源版位于 `/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot`。

## 1. 仪表板图表取数（P0-1）

### 场景：图表数据解密与落库形态一致

用户打开仪表板，前端用 SQLBot AES key 加密 SQL 后请求图表数据。后端从 core_datasource 读出 configuration（以应用 SECRET_KEY 加密落库），解密得到连接配置，执行 SQL 并返回 `{status:"success", data:[...]}`。

解密顺序为：先用应用密钥尝试，失败或结果不像 JSON 时回退到 SQLBot 前端密钥；两者都失败时返回 `status="failed"` 且 `message` 说明原因（密文无法解密/配置缺失），不再把密文当明文解析后抛难懂异常。

验收：A1

### 场景：解密失败的可见反馈

当数据源配置损坏或密钥不匹配时，仪表板该图表卡片展示失败原因而不是无限 loading 或空白。

验收：A1

## 2. 表数据预览的行列权限（P0-2 / P1-5 / P1-6）

### 场景：普通用户预览受列权限约束的表

普通用户（非 ws admin）请求 `POST /datasource/previewData/{id}`。系统按该数据源的列权限（隐藏字段）裁剪可选字段集合，只勾选允许列生成预览 SQL；结果集中不出现无权列。

验收：A2

### 场景：普通用户预览受行权限约束的表

同一数据源配置了行权限（字段 like/eq 条件）时，预览 SQL 追加对应 WHERE 条件，返回行集与权限语义一致；行权限 like 条件生成 `%value%` 而非 `value`。

验收：A2

### 场景：ws admin 预览不受权限裁剪

ws admin 预览同一张表时看到全部勾选字段与全部行，行为与本 change 之前一致。

验收：A2

### 场景：权限加固失败即拒绝执行

PermissionEnforcer 在解析或改写 SQL 过程中出现异常（无法解析、出现不支持的构造）时，问数与预览路径都拒绝执行并返回错误，不再以未加固的原始 SQL 放行（fail-open）。

验收：A3

### 场景：列权限进入提示词

SchemaContextBuilder 组装 schema 时直接从上下文里剔除当前用户无权的字段（而不是执行后删列），LLM 不会看到隐藏列名。

验收：A9

## 3. analysis/predict 记录外键方向（P0-3）

### 场景：分析后基础气泡仍可渲染

用户对一条问数气泡点"分析"。系统新增子记录，子记录的 analysis_record_id 指向基础记录；基础记录自身的 analysis_record_id 与 predict_record_id 保持 NULL。刷新页面后基础气泡的 SQL/数据/图表照常渲染，再次点"分析"仍以基础记录发起请求。

验收：A4

### 场景：预测方向一致

预测（predict）路径同样只写 child→base，基础记录 predict_record_id 保持 NULL。

验收：A4

## 4. 外观设置（P0-4 / 前端 P1）

### 场景：外观保存与读回

ws admin 在外观页修改配置并保存，请求 `POST /system/appearance`，后端把键值写入 sys_arg（键前缀 `appearance.`）。随后 `GET /system/appearance/ui` 返回更新后的键值数组（键名去掉前缀），登录页与嵌入页的品牌外观随之变化。

验收：A5

### 场景：外观链路不依赖 license

前端 `getLicense()` 返回社区版（status !== 'valid'）时，外观页仍能读取配置并提交保存，不出现"改了没反应"。

验收：A6

### 场景：许可证上传入口可用或移除

关于页"上传许可证"按钮要么对应一个真实存在的后端端点，要么从界面移除，不留死链。

验收：A27

## 5. 嵌入式助手换取 token（P0-5）

### 场景：合法签名换取 token

嵌入页面按约定计算签名并携带签名头、assistant_id、时间戳请求 `GET /system/assistant/info/{id}`。校验通过后返回助手信息与工作空间 token。

验收：A7

### 场景：非法请求被拒绝

缺少签名头、签名不匹配、时间戳超出允许窗口、assistant_id 与路径不一致、或 UA 命中 webdriver 特征的请求一律拒绝，不返回 token；伪造 Origin 头不足以通过。

验收：A7

## 6. 数据源驱动与引擎细节（P0-6 / P1-15/19/20/21）

### 场景：六类驱动进入运行时

Oracle、SQLServer、ClickHouse、Hive、Kingbase、DM 的 JDBC 驱动以 runtime scope 随产物发布；前端选择这些类型执行测试连接时不再因 "No suitable driver" 失败。

验收：A8

### 场景：驱动按类型加载

JdbcEngine 按数据源类型构造 JDBC URL 并加载对应驱动（含必要的 URLClassLoader/DriverManager 处理），连接池键与现有静态/动态数据源路径一致。

验收：A8

### 场景：Hive 元数据解析

Hive 数据源同步元数据时按 Spark Thrift 结果集结构解析索引与字段（不按标准 JDBC `TABLE_NAME` 行取值）。

验收：A19

### 场景：SQLServer 编码回正

SQLServer 返回的乱码按 latin-1→GBK 回正，中文表名/字段名/数据正确显示。

验收：A19

### 场景：连接池参数生效

数据源配置中的 poolSize、max_overflow、pool_timeout 被 ConnectionPoolManager 消费，未配置时用默认值。

验收：A21

## 7. SQL 提示词体系（P1-1/2/9/13）

### 场景：提示词模板按方言加载

生成 SQL 的系统提示由 classpath 资源组装：`prompts/template.yaml` 提供通用规则，`prompts/sql_examples/<方言>.yaml` 提供方言样例。规则至少包含 process_check 自检步骤、标识符保持（原样引用表名/字段名）、多表 JOIN 必须限定列、时间格式约定、JSON 输出契约与 few-shot 示例。

验收：A9

### 场景：模板资源完整性

模板与方言样例资源随 jar 发布；资源缺失或为空时启动期或首次使用时给出明确错误，不静默退化成空规则。

验收：A9

### 场景：图表生成上下文

请求图表时 LLM 收到的消息包含 `<sql>`、`<m-schema>`、`<chart-type>` 三段；multi-quota 图表类型不被丢弃；value 字段统一小写归一。

验收：A10

### 场景：表关系进入提示词

提示词包含数据源已配置的表关系：补全关联表，并以 `【Foreign keys】a.x=b.y` 形式给出外键约束。

验收：A13

### 场景：默认行数限制

`chat.limit_rows` 未显式配置为 false 时按开启处理，问数结果行数超过 1000 被截断（SQLBot `GENERATE_SQL_QUERY_LIMIT_ENABLED` 默认开启语义）。

验收：A11

## 8. token 统计与推荐/抽取（P1-4）

### 场景：流式 usage 采集

问数请求体带 `stream_options.include_usage=true`；收到 usage 后分别记录 input、output、total tokens 到 chat_log。

验收：A12

### 场景：各步骤 token 落库

推荐问题生成与字段抽取等辅助 LLM 调用同样记录 token，不只统计主问答一步。

验收：A12

## 9. 检索质量（P1-13/14）

### 场景：训练样例双路召回

TrainingRecallService 对关键词同时执行 ILIKE 双向（问题↔样例）匹配与向量召回，两路结果合并去重后返回。

验收：A14

### 场景：表级关键词打分

TableEmbeddingService 按表名 1.0、表注释 0.9、表名子串 0.7、字段注释 0.5、字段名 0.3 分档打分，并按覆盖率加权，档位与 SQLBot `table_embedding.py` 一致。

验收：A15

## 10. 多轮与记录契约（P1-3/10/11/12 + P2）

### 场景：跨轮错误自愈

同一会话中，下一轮提问的提示词包含上一轮 exec-sql 失败的 traceback，帮助 LLM 规避同类错误。

验收：A16

### 场景：first_chat 空记录

创建会话时插入一条 `first_chat=True` 的空记录；当 `recommended_config == 2` 时该记录预填推荐问题。

验收：A17

### 场景：operate 枚举名

chat_log.operate 使用 SQLBot 枚举名（`GENERATE_SQL` 等），不再使用小写 `generate_sql`。

验收：A26

### 场景：错误事件分型

执行 SQL 失败时 SSE 错误事件为 `{message, traceback, type: "exec-sql-err"}`，连接不可用为 `{type: "db-connection-err"}`；生成 SQL 之前先执行 check_connection，连接不可用直接报连接错误。

验收：A26

### 场景：记录数据与图表字段

`GET /chat/record/{id}/data` 返回 `fields_info`（含 `is_numeric`）；保存图表时 `chart_answer` 存模型原始思考而非图片 URL；`GET /chat/record/{id}/log` 的 message 为结构化对象。

验收：A26

## 11. 动态数据源与权限细节（P1-7/17）

### 场景：表级行规则生效

宿主下发的动态数据源表配置支持 `rule` 字段；问数时按规则生成并注入过滤 SQL（对齐 SQLBot `llm.py:1149-1160`）。

验收：A18

### 场景：行权限 LIKE 语义

行权限的 like 条件生成 `'%value%'`，与 SQLBot `row_permission.py:168` 一致。

验收：A18

## 12. 数据源元数据与向量刷新（P1-16/18）

### 场景：字段枚举查询

字段枚举按 CoreField.id 查询对应字段的枚举定义，不再把 fieldId 当 tableId 使用。

验收：A19

### 场景：向量随注释与配置重算

修改表注释、字段注释或数据源配置后，重新计算表、字段与数据源向量；删除数据源时清理连接池并移除相关向量。

验收：A20

## 13. 系统与安全（P1-22/23/24/25/26/27/28/29/30）

### 场景：会话状态复查

每个请求都重新加载用户并校验 status 与 oid，用户被禁用或移出工作空间后立即失效；SaToken timeout 与 SQLBot 口径一致。

验收：A22

### 场景：口令强度

修改口令时校验包含大小写字母、数字与特殊字符，长度 8-20。

验收：A22

### 场景：AI 模型连通测试

连通测试支持 Azure 端点与自定义参数（不止固定 `/chat/completions` + temperature），按模型协议选择调用方式。

验收：A23

### 场景：高级应用数据源下发

高级应用的数据源列表优先取宿主 API 下发结果，本地库仅作兜底。

验收：A23

### 场景：嵌入页取数据源

嵌入助手页获取数据源不要求 ws admin 权限（对齐 SQLBot `/ds` 无权限装饰器）。

验收：A24

### 场景：助手 UI 上传

上传限制为 jpg/png 且不超过 10MB；替换上传时删除旧文件；清空时把配置置空。

验收：A24

### 场景：MCP 第三方助手

`mcp_assistant` 使用下发的 `url` 与 `authorization` 调用第三方接口，不再忽略。

验收：A24

### 场景：认证源读取鉴权

认证源配置的 list/getById 要求 admin 权限。

验收：A25

### 场景：SAML 断言验签

SAML 响应校验断言签名，签名缺失或校验失败时拒绝登录。

验收：A25

### 场景：平台客户端配置

平台客户端接口返回数据库中真实配置（origin、client_id、enable），不再硬编码占位。

验收：A25

### 场景：助手 CORS 动态 origin

动态 origin 解析方法在嵌入助手响应链路上被实际调用，回退值不再是无条件 `*`。

验收：A25

## 14. 前端交付一致性（前端 P1/P2）

### 场景：xpack license 解耦

`xpackPatch.getLicense()` 返回社区版时，外观/品牌、用户页平台同步按钮、企业 IM 扫码登录、平台参数页四类功能仍向后端发请求（后端接口已实现），不再被前端提前拦截。

验收：A27

### 场景：构建产物与源码一致

`web/dist` 基于当前 `web/src` 重新构建，部署产物与源码行为一致。

验收：A27

## 15. 企业版能力（license / 企业 IM / 平台 / SSO）

> SQLBot 的 sqlbot_xpack 不在开源仓库中，无法逐字对照；本节以 askdata 已有数据结构
> （sys_authentication，type 1..9）与前端契约为准，保证企业能力真实可用。

### 场景：许可证上传与持久化

ws admin 在关于页上传许可证文件，请求 POST /system/license。后端校验许可证内容（签名、有效期、版本），通过后持久化到本地存储（不写入仓库），后续所有 license 查询以持久化状态为准。

验收：A28

### 场景：许可证状态查询

GET /system/license 返回 {status, license, version, expire_time, reason}：status ∈ valid/invalid/expired；无效或过期时 reason 说明原因。GET /system/license/version 返回真实版本号，不再固定 "community"。

验收：A28

### 场景：前端企业门控按真实状态工作

前端 getLicense() 从 GET /system/license 读取真实状态。license 有效时企业 IM 扫码登录入口、用户页平台同步按钮、平台参数页企业相关模块可正常发请求；无效时展示明确提示（如"许可证无效"），不再静默隐藏或恒走免费兜底分支。

验收：A29

### 场景：平台客户端信息真实化

GET /system/platform/client/{origin} 从 sys_authentication 读取该平台（type 6..9：企业微信/钉钉/飞书/LarkSuite）的配置，返回 origin、client_id、enable、valid 及平台所需字段（corpid/agentid 等）；未配置时返回 enable=false 与可读原因。

验收：A30

### 场景：企业 IM 扫码登录全流程

登录页选择钉钉/Lark/LarkSuite/企业微信扫码登录：前端凭平台客户端信息加载第三方 SDK 生成二维码，用户扫码授权后第三方回调 askdata，携带授权码请求 POST /system/authentication/sso/{type}，后端用授权码向第三方换取用户身份，本地建档或匹配已有用户，返回 access_token 与 platform_info，前端跳回系统。平台未启用或配置缺失时返回可读错误，不出现无限等待。

验收：A31

### 场景：平台用户同步

用户页平台同步按钮触发 POST /system/platform/user/sync，请求体 {user_list, origin, cover}。后端把企业平台用户幂等同步为本地 sys_user 并维护 sys_user_platform 绑定，返回 {total, created, updated, skipped}；同一 (origin, platform_uid) 重复同步不产生重复用户。

验收：A32

### 场景：平台组织查询

POST /system/platform/org/{id}（前端 api/system.ts 的 platform() 调用点）返回该平台组织/部门信息，供用户同步选择范围；不再是死链。

验收：A33

### 场景：SSO 协议登录链路

GET /system/authentication/login/{type} 对 OIDC(1)、OAuth2(2)、CAS(4)、SAML2 返回可用的第三方授权跳转地址（按已保存配置构造，缺失必要配置时返回明确错误）；POST /system/authentication/sso/{type} 完成回调登录并返回 access_token/exp/platform_info。

验收：A34

### 场景：企业登录参数生效

GET/POST /system/parameter 的 login 分组中企业登录相关配置（登录方式开关、各平台启用项）可读可写，并实际决定登录页展示的登录方式与扫码入口。

验收：A35
