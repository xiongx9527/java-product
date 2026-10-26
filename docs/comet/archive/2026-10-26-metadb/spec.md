# SQLBot 审计对齐目标规格

## 目的与边界

本规格描述 `askdata/askdata-server` 在完成 2026-10-26 SQLBot 对照审计修复后的完整目标行为。范围覆盖审计确认的 12 项 P0、全部中等项与轻微项；部署基建（Dockerfile/docker-compose/installer/start.sh、前端 Maven 构建、CONTEXT_PATH 子路径部署、logback 日志轮转、openapi 多语言文档）不在本规格内。

规格中的“必须”表示上线前必须具备的行为。所有行为沿用现有 Spring Boot、JdbcTemplate、Controller-Service-Repository 结构与 `{code,data,msg}` 响应包装；鉴权继续使用 `X-SQLBOT-TOKEN: Bearer`；SSE 帧名与既有白名单语义保持兼容。新增密钥、口令与连接信息只能来自配置或环境变量。

## Requirement: 会话与记录归属

会话列表、分页、计数、最近问题、会话详情与记录详情必须以当前用户和工作空间为边界。管理员身份不构成隐式的 owner 豁免；只有专门的系统管理端点可以在明确审计的前提下跨用户操作。

### Scenario: 同一工作空间内用户只能读取自己的会话、提问与记录

- **WHEN** 用户 A 与用户 B 属于同一工作空间 `oid`，分别创建会话与提问，管理员尝试读取用户 A 的会话详情或 `/chat/record/{rid}/data|log|usage|excel`
- **THEN** 会话列表、分页结果、总数与最近问题均按 `create_by + oid` 双条件过滤，A 只看到 A 的数据，B 只看到 B 的数据
- **AND** 最近问题最多返回 10 条，按既有时间倒序规则排序
- **AND** 管理员读取他人会话或记录详情时返回 403 或等价业务拒绝，不存在“管理员直接放行”的分支
- **AND** 会话详情只返回属于该会话且未删除的提问，记录导出/日志/用量/Excel 端点复用同一归属校验

验收：A1

## Requirement: 分析与预测记录持久化

分析（analysis）与预测（predict）必须作为基础记录的子记录持久化，外键方向为子记录指向基础记录。刷新页面后必须能重新加载子记录及其图表与预测数据。

### Scenario: 分析/预测子记录正确挂载并可刷新恢复

- **WHEN** 用户对一条已完成的基础提问发起分析或预测
- **THEN** 新建子记录的 `analysis_record_id` 或 `predict_record_id` 指向基础记录 ID，基础记录的这两个外键保持 `NULL`
- **AND** 子记录复制基础记录的 `chart` 与 `data` 字段，子记录自身保存分析/预测结果
- **AND** `GET /chat/{id}` 返回该会话下全部分析/预测子记录，而不是只返回基础记录
- **AND** 页面刷新后气泡、分析结论与预测数据仍可见，记录列表与导出不会跳过子记录

验收：A2

## Requirement: License 校验与门控

License 必须使用仓库可验证的 HMAC 签名链，支持本地签发与上传校验，并通过统一门控决定企业能力是否可用。默认不阻断企业能力，只有显式启用强制模式才拦截。

### Scenario: HMAC License 可签发、可校验并按 enforcement 门控

- **WHEN** 运维使用 `scripts/issue-license.sh` 和配置的 `askdata.license.sign-key` 签发许可证，并在系统中上传
- **THEN** 合法许可证返回 valid，包含签发对象、有效期、授权量与版本等可核对信息
- **AND** 篡改签名、过期、超出授权量或版本不匹配的许可证返回 invalid 或 expired，并给出具体失败原因
- **AND** `askdata.license.sign-key` 未配置时校验链 fail-closed 并输出可读配置提示，不再生成随机密钥掩盖问题
- **AND** `askdata.license.enforcement` 默认 `bypass`，企业能力保持可用；设为 `enforce` 后才按许可证状态拦截
- **AND** 前端企业入口读取真实 license 状态与 enforcement，不再恒定隐藏或只做本地假状态

验收：A3

## Requirement: 服务端图表语义

服务端图表使用增强后的 Java2D 渲染器，保持与 SQLBot 图表语义一致的关键视觉与数据约束，不引入 Node/g2-ssr。图表构建器必须保留多指标信息，遇到未知图表类型必须显式失败。

### Scenario: Java2D 图表保留系列、多指标轴与类型校验

- **WHEN** 问数结果包含多系列、multi-quota 多指标、横向 bar、area 或 pie 图表
- **THEN** 不同 series 使用可区分的颜色，图例与系列名称一致
- **AND** `axis.multi-quota` 定义的多指标轴被保留并渲染，不合并为单一轴
- **AND** 横向 bar 的条长按数值比例计算，area 有填充，pie 显示标签并按配置格式化数值
- **AND** `ChatChartBuilder` 保留 `axis.multi-quota` 与 series 信息，未知 chart type 返回明确错误，不静默降级为 table

验收：A4

## Requirement: 出图 URL 生命周期

图表图片 URL 必须使用持久化、不可猜测的 token 映射到文件存储，进程重启后仍可访问；访问控制按 token 有效期执行，并定期回收文件与映射。

### Scenario: 出图 token 跨重启有效、过期与篡改被拒绝并被清理

- **WHEN** MCP、IM 或第三方集成获得包含 `image_url` 的响应，服务重启后再次访问该 URL
- **THEN** `image_url` 使用元数据库中的 token→文件映射，重启后仍能打开原图
- **AND** 未登录第三方在 token 有效期内可以访问 `/api/chart/image/*`，该路由位于鉴权白名单
- **AND** 篡改 token、使用已过期 token 或访问不存在文件时返回 404 或 403，不泄露文件路径
- **AND** 过期图片文件与 token 记录按配置 TTL 清理，长期运行不会无限增长

验收：A5

## Requirement: 数据源端点授权

表关系、推荐问题与 `execSql` 必须执行工作空间管理员与资源归属校验；`execSql` 保留公开路径但不得绕过行/列权限。

### Scenario: 表关系、推荐问题与 execSql 均受 ws_admin 和行列权限约束

- **WHEN** 非工作空间管理员调用表关系 save/get、推荐问题 list/base/save，或任意用户调用 `POST /datasource/execSql/{id}`
- **THEN** 表关系与推荐问题端点要求 ws_admin，并校验数据源 `type='ds'` 或资源授权关系，未授权请求被拒绝
- **AND** `POST /datasource/execSql/{id}` 保留原公开映射，但执行前要求 ws_admin
- **AND** `execSql` 与 `previewData` 使用同一权限服务，执行前应用 `hiddenColumnsFor` 与 `rowFilterWhereFor`
- **AND** 非 ws_admin 无法通过 `execSql` 读取隐藏列或越过行过滤，越权请求返回 403

验收：A6

## Requirement: 权限模型硬化

行权限、只读 SQL 校验与列权限必须在进入 SQL 构造和提示词之前生效，且基于结构化解析而不是易绕过的字符串匹配。

### Scenario: 行权限值域、AST 只读校验与列权限前置过滤

- **WHEN** 管理员配置行权限变量、提交包含函数调用的 SQL，或用户仅有部分列权限
- **THEN** 行权限按 `varType` 校验值域，并将 kv 变量做 key→value 映射，越界值被拒绝
- **AND** 只读 SQL 校验遍历 AST 函数节点，字符串字面量中的 `version(` 不被误拒，真实危险函数被拒绝
- **AND** 列权限在构造 schema、提示词与样例行之前剔除无权字段，LLM 不会看到被隐藏的列
- **AND** 上述校验失败时返回明确错误，不执行 SQL，也不把原始敏感列名写入日志或提示词

验收：A7

## Requirement: Excel 数据源与多 Sheet 解析

Excel 数据源在任意元数据库下都必须使用固定默认 schema 完成字段同步；多 sheet 文件必须按 sheet 独立识别表头。

### Scenario: 非 H2 元数据库下 Excel 字段完整且多 sheet 表头只出现一次

- **WHEN** 元数据库为 PostgreSQL 或 MySQL，用户上传 Excel 数据源；或上传包含多个 sheet 的 Excel
- **THEN** Excel 数据源字段同步使用固定默认 schema，不再写入空 `db_schema`
- **AND** `core_field` 包含完整列定义，字段列表、类型与预览可用
- **AND** 多 sheet Excel 的每个 sheet 表头只出现一次，第 2..N 个 sheet 的首行数据不会被当成表头重复写入
- **AND** 单 sheet 与多 sheet 的导入结果都保持列顺序、列名与数据类型一致

验收：A8

## Requirement: 元数据库与迁移

元数据库由 Flyway 版本化迁移统一管理，支持 H2、PostgreSQL 与 MySQL 从空库启动；运行时不得再通过 `spring.sql.init` 执行 `schema.sql`。

### Scenario: H2/PostgreSQL/MySQL 可从空库迁移启动并切换

- **WHEN** 使用 H2 默认配置、PostgreSQL 测试库或 MySQL 方言配置从空库启动服务
- **THEN** Flyway 按 `common` 种子与对应方言 baseline/迁移脚本完成建表与种子数据写入
- **AND** `schema.sql` 不再由 `spring.sql.init` 执行，运行期不重复建表或重复插入种子
- **AND** PostgreSQL 使用 embedded-postgres 真库测试完成迁移、登录与一次问数；MySQL 使用方言单测验证脚本与方言差异
- **AND** 切换元数据库只需修改连接配置并执行迁移，不需要手工改 SQL 或改代码

验收：A9

## Requirement: 平台同步安全

平台同步必须拒绝缺少来源或来源为零的请求，并且不能为同步用户写入空口令哈希。

### Scenario: 平台同步强制 origin 且不产生空口令账号

- **WHEN** 平台同步请求缺少 `origin`、`origin=0`，或同步创建一个本地不存在的用户
- **THEN** 缺少 origin 或 origin=0 的请求被拒绝，并返回可读的 origin 校验错误
- **AND** 新用户不再写入 `md5("")`，默认口令为随机值或明确不可用状态
- **AND** `origin≠0` 的同步用户无法使用空口令进行本地登录
- **AND** 合法 origin 的同步仍能创建/更新用户，并保持平台侧字段映射

验收：A10

## Requirement: 嵌入签名协议

嵌入签名必须兼容 SQLBot AES 信封，严格校验来源、时间、UA 与时区；旧 HMAC 仅作为显式兼容开关保留。

### Scenario: AES 信封签名严格校验且旧 HMAC 默认关闭

- **WHEN** 宿主生成嵌入 token 并打开嵌入页，或攻击者篡改 token、跨 Origin 重放、延迟超过 10 秒、修改 UA/时区
- **THEN** token 为 AES 加密的 JSON 信封，包含 `assistant_id`、`target`、`sign_time`、`webdriver`、`user_agent`、`timezone`
- **AND** 校验要求请求 Origin 与 token 中 `target` 相等、`sign_time` 在 ±10 秒窗口内、UA 逐字一致、时区一致
- **AND** AES key 通过配置提供且可轮换，未配置时给出可读错误
- **AND** 旧 `assistantId.timestamp.hmac` 格式仅在显式兼容开关开启时接受，默认关闭

验收：A11

## Requirement: SQL 输出协议

SQL 生成阶段必须使用并解析结构化 JSON 输出，禁止用正则从自然语言中抠取 SQL，也禁止用标题截断代替 brief。

### Scenario: SQL 生成返回 JSON 并被完整消费

- **WHEN** LLM 返回 SQL 生成结果
- **THEN** 提示词要求输出 `{success,sql,brief,chart-type,tables}` JSON，后端按该结构解析
- **AND** 成功时用 `brief` 重命名会话，将 `chart-type` 传入图表提示词，并用 `tables` 限制 FROM 表范围
- **AND** 非法 JSON、`success=false` 或字段缺失时返回明确错误，不执行 SQL
- **AND** 不再使用正则抠 SQL，也不再把问题标题截前 20 字作为会话名

验收：A12

## Requirement: 分析、预测与推荐提示词

三类问数请求必须加载移植后的模板资源，模板中包含术语、语言占位与预测约束。

### Scenario: 三类请求使用 template.yaml 对应模板

- **WHEN** 用户发起分析、预测或推荐问题
- **THEN** 分别使用 `template.yaml` 中 analysis/predict/guess 对应模板，并注入术语、`{lang}` 与当前上下文
- **AND** 预测模板要求至少 2 个周期，并在无法预测时返回解释而不是编造数值
- **AND** 已移植的 analysis/predict/guess 资源被实际加载，不再使用硬编码字符串
- **AND** 模板缺失或渲染失败时返回明确配置错误，不静默退回旧提示词

验收：A13

## Requirement: 执行日志与快捷命令

问数执行过程必须记录可读的步骤日志，前端能够渲染；快捷命令在 Web 与 MCP 下都按同一语义生效。

### Scenario: 执行日志覆盖 14 类操作且快捷命令可用

- **WHEN** 一次问数经过选表、选数据源、生成动态 SQL、生成带权限 SQL、出图等阶段，或用户输入 `/regenerate 123`、在 MCP 下使用 `/analysis` 与 `/predict_data`
- **THEN** `chat_log` 记录 14 类 operate，至少包含 `CHOOSE_TABLE`、`GENERATE_PICTURE`、`CHOOSE_DATASOURCE`、`GENERATE_DYNAMIC_SQL`、`GENERATE_SQL_WITH_PERMISSIONS`
- **AND** 日志包含 `messages` 与 `pid`，前端 `ExecutionDetails` 能按 operate 渲染对应步骤
- **AND** `/regenerate 123` 能带前文重新生成，不是忽略编号的空执行
- **AND** MCP 下 `/analysis` 与 `/predict_data` 与 Web 语义一致，均能产生对应子记录

验收：A14

## Requirement: MCP 非交互输出

MCP 非交互问数必须返回面向调用方的稳定文本协议，与 Web SSE 的 JSON 帧解耦。

### Scenario: MCP 输出 record_id、Markdown 表格与可选图片

- **WHEN** MCP 客户端以非交互方式发起问数并获得结果
- **THEN** 输出包含 `record_id`、Markdown 表格与 `![chart](image_url)` 图片行
- **AND** 没有图表时省略图片行，不输出空链接或占位图
- **AND** 输出不包含 Web SSE 的 JSON 帧结构，调用方可以直接渲染 Markdown
- **AND** `record_id` 可用于后续分析、预测或记录查询

验收：A15

## Requirement: 流式韧性与问数细节

流式执行必须在客户端断连时继续完成后端工作，只有真实执行错误才记录错误；问数细节必须与 SQLBot 对齐。

### Scenario: 断连继续执行且空提问、token、修复轮、推荐问题行为正确

- **WHEN** 客户端在流式过程中断开，或用户提交空提问、触发 token 统计、进入修复轮、触发推荐问题
- **THEN** 断连后后端继续执行 SQL、生成图表并落库，只有真实执行错误才调用 `saveError`
- **AND** 空提问在调用 LLM 前被拒绝，不产生记录与模型费用
- **AND** token 统计以最后一次 usage 为准，不逐帧累加
- **AND** 修复轮提示包含术语、样例与历史；每问只调用一次推荐问题 LLM

验收：A16

## Requirement: 嵌入助手与动态数据源

嵌入页必须按 token 中的助手身份加载配置，高级应用实时获取宿主数据源，并支持在线/离线通道与宿主凭证解密。

### Scenario: 嵌入助手按 assistant_id 加载且动态数据源不回退错误助手

- **WHEN** 嵌入 token 带有 `assistant_id`，助手为 type=1 高级应用，宿主 API 正常或失败，或请求携带在线/离线通道头
- **THEN** 嵌入页按 token 中的 `assistant_id` 加载助手，而不是当前用户第一个助手
- **AND** type=1 高级应用实时请求宿主 API 获取 `ds_list`；宿主 API 失败时返回明确错误，不回退到“当前用户第一个 type=0 助手”
- **AND** `X-SQLBOT-ASSISTANT-ONLINE` 在运行时切换在线/离线通道，行为与助手配置一致
- **AND** 宿主下发数据源支持 AES 解密，并支持 cookie/param 两类凭证位

验收：A17

## Requirement: 数据源类型细节

各数据源类型的连接参数、元数据同步与限制必须与 SQLBot 对齐，并由配置控制可选行为。

### Scenario: Hive/SQL Server/ES/MySQL/Oracle 与连接池细节生效

- **WHEN** 配置 Hive LDAP、SQL Server lowVersion、ES 数据源、MySQL `allowPublicKeyRetrieval`、Oracle thick 模式或连接池 recycle
- **THEN** Hive 连接支持 LDAP 认证参数并正确拼入连接属性
- **AND** SQL Server `lowVersion` 影响 URL 与驱动参数，旧版本可连接
- **AND** ES 表/字段同步保留 `_meta.description` 作为注释
- **AND** MySQL `allowPublicKeyRetrieval` 仅在配置开启时追加；Oracle 可按配置启用 thick 模式；连接池按配置 recycle
- **AND** 问数 schema 上下文最多包含 `MAX_TABLES=10` 张表

验收：A18

## Requirement: Excel 导出与类型推断

Excel 导出必须保留数值语义，类型推断必须覆盖足够样本并保留浮点，结果列名归一必须大小写不敏感。

### Scenario: 导出数值可求和且类型推断与列名归一正确

- **WHEN** 用户导出包含数字列的结果，或导入包含浮点、大小写混合列名的数据
- **THEN** 数字列写为数值单元格而非文本，Excel 中可直接求和
- **AND** 类型推断不再只采样前 100 行，并保留 float 语义，不把浮点强制转为整数或文本
- **AND** 结果列名按大小写不敏感归一，重复列名有稳定处理
- **AND** 上述行为有导出/导入回归测试覆盖

验收：A19

## Requirement: 表级向量召回

表级向量召回必须优先使用远程 embedding，远程不可用时回退到与问数一致的本地 embedding，最后才退化为词法召回。

### Scenario: 远程失败时回退本地 embedding 再退化为词法

- **WHEN** 表级向量召回请求远程 embedding 服务成功、失败或本地模型也不可用
- **THEN** 远程可用时优先使用远程向量结果
- **AND** 远程不可用时回退到 `EmbeddingService` 相同的本地模型路径，而不是直接退化为词法
- **AND** 本地 embedding 仍不可用时才使用词法召回，并在日志中记录降级原因
- **AND** 三种路径的返回结构一致，调用方无需区分实现

验收：A20

## Requirement: 仪表盘与工作空间

仪表盘按工作空间可见性授权；删除工作空间必须正确处理用户归属；术语与训练数据必须绑定当前工作空间的数据源。

### Scenario: 仪表盘同工作空间可操作且工作空间删除/数据源归属正确

- **WHEN** 同工作空间用户操作他人创建的仪表盘，管理员删除工作空间，或保存术语/训练配置
- **THEN** 仪表盘列表、加载、保存、移动、删除按同工作空间可见即可操作，不再要求 `create_by=当前用户`
- **AND** 删除工作空间时用户 `oid` 重新选择或置 0，不再一律改成 1
- **AND** 术语与训练的 `datasource_ids` 必须属于当前工作空间，跨空间数据源被拒绝
- **AND** 跨工作空间访问仪表盘返回拒绝，不泄露名称或配置

验收：A21

## Requirement: 审计日志可读字段

审计日志必须返回本地化操作类型、真实操作详情与工作空间名称，前端三列可直接展示。

### Scenario: 审计日志返回本地化标签、操作详情与工作空间名

- **WHEN** 用户查看审计日志列表
- **THEN** `operation_type_name` 返回本地化标签而非 code
- **AND** `operation_detail_info` 返回真实操作详情，不返回空值或占位符
- **AND** `oid_name` 返回工作空间名称而非数字 id
- **AND** 前端审计列表三列正常显示，切换语言后标签同步变化

验收：A22

## Requirement: 用户导入兼容

用户导入模板、解析与返回字段必须兼容 SQLBot，使用本地化表头与中文枚举值。

### Scenario: SQLBot 用户模板可直接导入并返回兼容字段

- **WHEN** 用户下载模板、填写“已启用/已禁用/本地创建”等中文枚举并导入
- **THEN** 模板与解析使用本地化表头及对应枚举，中文枚举值不再被判非法
- **AND** 导入返回字段名与 SQLBot 一致，前端可直接展示成功/失败明细
- **AND** 非法行返回行号与原因，合法行仍可导入
- **AND** 导入后的用户状态、来源与角色映射正确

验收：A23

## Requirement: 会话与系统细节

访问 token、SSO 登出、用户搜索、系统变量、助手图片、模型连通性与管理员判定必须与 SQLBot 对齐。

### Scenario: token、SSO、搜索、系统变量、图片、模型与 isAdmin 细节正确

- **WHEN** 用户登录、登出、搜索用户、删除内置系统变量、上传助手图片、测试模型连接，或系统判断管理员
- **THEN** 访问 token 默认 8 天且可通过配置调整，过期后要求重新登录
- **AND** SSO 登出联动 IdP 登出端点，不只是清理本地会话
- **AND** 用户搜索保留相关性排序；内置系统变量可删除
- **AND** 助手图片校验类型与大小；模型连通性测试使用 protocol/Azure 等配置
- **AND** `isAdmin` 按角色判定，不再使用 `id==1 && account=='admin'`

验收：A24

## Requirement: SAML2 配置端点

前端调用的 SAML2 配置读写与校验端点必须存在，并对证书/元数据错误返回可读信息。

### Scenario: SAML2 三端点读写配置并校验证书

- **WHEN** 管理员打开 SAML2 配置页、保存配置或点击校验
- **THEN** `/setting/authentication/info/saml` 返回当前 SAML2 配置
- **AND** `/setting/authentication/save/saml` 持久化配置并校验必填项
- **AND** `/setting/authentication/validate/saml2` 校验证书与元数据，返回明确成功/失败原因
- **AND** 配置缺失或格式错误时返回可读错误，前端不再出现 404 或无响应

验收：A25

## Requirement: 五语种国际化

`zh-CN`、`en`、`zh-TW`、`ko-KR`、`th-TH` 五个 locale 的键集合必须与使用点一致，任何新增 Java 文案都必须有对应翻译。

### Scenario: 五语种键集合完整且不回退中文/英文

- **WHEN** 用户切换任意受支持语言，或代码新增工作空间名等后端文案
- **THEN** 五个 locale 的键集合与前端使用点一致，缺失键有自动检查
- **AND** 四语种不再回退中文/英文，页面展示对应语言
- **AND** 工作空间名等 Java 新增文案全部有对应翻译
- **AND** 缺失键检查可重复执行，并在构建或测试中报告具体键名

验收：A26

## Requirement: 配置项补齐

运行所需配置必须在 `application.yaml` 中有明确默认值、说明与文档，未配置时行为与默认值一致。

### Scenario: 配置项齐全且有默认值与文档

- **WHEN** 使用默认配置启动，或运维只修改部分配置项
- **THEN** `application.yaml` 包含向量召回阈值、`SAMPLE_DATA_ENABLED`、`ALLOW_METADATA_QUERIES`、token 有效期、图表图片 TTL/清理、license 与嵌入签名开关等配置项
- **AND** 每项都有默认值与用途说明，敏感值只引用环境变量或外部配置
- **AND** 未配置时行为与默认值一致，不因缺项启动失败
- **AND** 文档列出配置项、默认值与影响范围，切换元数据库、license、嵌入签名时可照文档操作

验收：A27

## 全局约束

- 所有新增与修改的测试必须在 JDK 17 + Maven 下可运行，不依赖本机 Docker；MySQL 只做方言单测，PostgreSQL 使用 embedded-postgres。
- 既有安全控制（SQL 只读、JDBC 参数拦截、行列权限、表白名单、API Key、嵌入 token）不得削弱。
- 不引入硬编码密钥；license 密钥、嵌入签名密钥、元数据库凭据均通过配置/环境变量提供。
- 不破坏现有 `web/dist` 构建产物与 Maven 打包链路；前端改动只在 `web/src` 并同步构建。
- 本规格不承诺与闭源 `sqlbot-embedded-dynamic.umd.js` 逐字节互通，以自建 AES 签名样例与协议字段严格校验验收。

## 非目标

- 不实现部署基建与部署编排。
- 不引入 g2-ssr/Node 运行时，不重写 SQLBot 前端页面。
- 不实现 SQLBot 闭源 xpack 的官方 license 格式与密钥。
- 不修改审计中“确认等价或更强”的条目，不处理前端死调用等非回归项。
