# 归档材料来源

- Parent Run: askdata-sqlbot-p0-parity
- Archive Action: askdata-sqlbot-p0-parity:52
- Contract: 0fa101278005d5daed3fecf9085dd4588a03b7b86784926d64b9a4ce2b1b6003
- Candidate: 71ecff3ad9cbbc949323466ed1d952fdf06823eb
- Verifier: hy3-verifier-chat-pipeline-20261027-001
- Integration checks: askdata-sqlbot-p0-parity:51
- Shape confirmation: f4249ce1728c95a432700f2a66209c1dc10417c58c43d2e19437ae1c11928cb5

- brief.md: SHA256 5fddfdf683161e0cef0c843f4c6aef2bf356f29849c07855f20e62aa460f65b6
- specs/sqlbot-audit-parity/spec.md: SHA256 8e5d80506ad8f5f9063315c0b7c068b34b20d7949e92ff0d77296dfb9959c77c

## Parent 原始简报

# 目标

把 2026-10-26 SQLBot 对照审计中确认的 **12 项 P0、全部中等项与轻微项**（部署基建除外）从“接口存在但语义被替换/降级”修复为与 SQLBot 一致或明确更强的行为，覆盖会话归属、管理员豁免、分析/预测落库、license 校验与门控、服务端出图、出图 URL、表关系/推荐问题鉴权、`execSql` 权限面、Excel 元数据、元数据库迁移、平台同步口令、嵌入签名、问数输出协议、提示词、日志、MCP、权限、数据源类型、系统账号、工作空间、审计、i18n 与配置项。完成后，UI 上看起来正常的操作在刷新、跨用户、重启、非 H2 元数据库和第三方接入场景下仍保持正确与可授权。

# 范围

- 需求来源：用户于 2026-10-26 提供的只读审计结论（聊天消息），基准为 SQLBot `backend/` 与 `g2-ssr/`，被审对象为 `askdata/askdata-server`。
- 覆盖边界：审计列出的全部 12 项 P0、全部中等项、全部轻微项；**只排除部署基建**，排除项见下条。审计中“确认等价或更强（不必改）”的条目保持现状，不重复修改。
- 部署基建排除项（不做）：Dockerfile / docker-compose / installer / start.sh；前端构建接入 Maven；CONTEXT_PATH 子路径部署；logback-spring.xml 日志轮转；`openapi.json?lang=` 多语言文档。部署基建之外的“工程/交付类”条目全部纳入，包括出图文件回收、配置项缺口、SAML2 三端点、i18n 四语种、Hive LDAP、SQL Server lowVersion、ES `_meta.description`。
- P0（12）：1 会话跨用户、2 管理员 owner 豁免、3 分析/预测写错记录、4 license 校验与门控、5 服务端出图语义、6 出图 URL 鉴权与重启失效、7 表关系/推荐问题无鉴权、8 `execSql` 绕过权限、9 Excel 元数据在非 H2 下失效、10 元数据库锁死 H2 且无迁移、11 平台同步空口令、12 嵌入签名协议替换。
- 中等项：SQL 输出 JSON 协议、multi-quota、分析/预测/推荐提示词模板、执行日志步骤与 messages/pid、最近问题、助手 ds、快捷命令、MCP 非交互输出、表级向量召回、行权限变量校验、只读 AST 校验、列权限前置过滤、断连处理、用户导入模板、审计日志三列、仪表盘权限、术语/训练 ds 归属、多 sheet Excel、在线/离线通道、宿主数据源解密与凭证位、删工作空间 oid、license 生效、token 有效期、SSO 登出、用户搜索、导入返回、系统变量、工作空间 i18n、助手图片、模型连通性、出图文件回收、配置项缺口、SAML2 三端点、i18n 四语种、Hive LDAP、SQL Server lowVersion、ES `_meta.description`。
- 轻微项：Excel 导出类型、Excel 类型推断、结果列名大小写、MAX_TABLES、连接池 recycle、MySQL `allowPublicKeyRetrieval`、isAdmin 判定、Oracle thick、错误帧后 finish、空提问、token 逐帧累加、修复轮上下文、推荐问题重复调用。前端死调用 `/chat/question/{id}`、`/user/clearErrorRecord` 与 SQLBot 同样不存在，属非回归，不在本次修改。
- 为上述行为补充或更新单元/集成测试与必要文档（模块 README、审计修复说明、license/嵌入签名/元数据库使用说明）。
- 前端继续使用现有 SQLBot 拷贝，只做门控、契约、i18n 与调用所需的最小改动，不重写页面。

## 目录结构

### 新建

- `askdata/askdata-server/src/main/resources/db/migration/common/`、`h2/`、`postgresql/`、`mysql/` — Flyway 公共种子与分方言 baseline/迁移脚本，替代 `schema.sql`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chart/server/ChartImageTokenRepository.java` — 出图 token 的持久化映射、过期查询与清理；配合文件存储回收。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/license/LicenseGate.java` — 统一 license 门控（bypass/enforce）与能力判定。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/support/SqlOutputProtocol.java` — 解析 `{success,sql,brief,chart-type,tables}` 并校验表限制。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/sso/Saml2SettingsController.java` — SAML2 三个配置/校验端点（或并入现有设置控制器）。
- `scripts/issue-license.sh` — 使用配置 HMAC sign-key 签发、校验许可证的本地工具（jq + openssl）。
- `askdata/askdata-server/src/test/java/.../parity/` 及各领域测试包 — 覆盖 A1–A27 的回归测试、embedded-postgres 真库测试、MySQL 方言单测、前端契约/构建检查。
- `docs/sqlbot-parity/` — 按领域记录审计修复说明、配置项、license/嵌入签名/元数据库切换与已知限制；最终由 Supervisor 汇总到模块 README。

### 修改

- `chat/repository/ChatRepository.java`、`chat/service/ChatService.java` — 会话列表/分页/计数/最近问题按 `create_by + oid` 过滤；去掉管理员 owner 豁免；返回全部分析/预测子记录。
- `chat/service/ChatQuestionService.java`、`chat/support/` — 分析/预测写子记录；SQL JSON 协议；断连后继续落库；空提问、token、修复轮、推荐问题调用次数；MCP 非交互输出。
- `chat/service/SchemaContextBuilder.java`、`prompt/`、`resources/prompts/` — 使用模板化 analysis/predict/guess 与 SQL 输出协议；列权限前置过滤；MAX_TABLES=10。
- `chart/service/ChatChartBuilder.java`、`chart/server/ChartImageRenderer.java`、`ChartImageService.java`、`ChartImageController.java` — 保留 multi-quota、未知类型报错；Java2D 增强；出图 token 持久化、白名单、重启可用、TTL 清理。
- `common/web/AuthWhitelist.java` — 放行签名出图路由，移除无效 `/images/*` 依赖或补齐等价路由。
- `datasource/controller/TableRelationController.java`、`RecommendedProblemController.java` — ws_admin + `type='ds'`/资源授权校验。
- `datasource/service/DatasourceService.java`、`DatasourceController.java`、`permission/DsPermissionQueryService.java`、`permission/SqlSafety.java` — `execSql` ws_admin + 行列权限；varType 校验；AST 只读校验。
- `datasource/engine/DatasourceType.java`、`JdbcEngine.java`、`ConnectionPoolManager.java`、`datasource/service/DatasourceService.java`、`common/util/ExcelUtils.java`、Excel 导入/导出类 — Excel 固定默认 schema、多 sheet、类型推断、数值导出、列名归一、Hive LDAP、SQL Server lowVersion、ES `_meta.description`、MySQL/Oracle/连接池参数。
- `datasource/embedding/TableEmbeddingService.java` — 远程 embedding 不可用时回退本地 embedding，再退化为词法。
- `system/license/LicenseService.java`、`LicenseController.java`、`system/platform/PlatformSyncService.java`、`system/controller/LoginController.java` — license 签发/校验/门控；同步 origin 必填非 0、空口令不可登录。
- `embedded/EmbeddedSigner.java`、`AssistantPublicController.java`、`AssistantService.java`、`DynamicDatasourceResolver.java` — AES 信封、Origin/时间窗/UA/时区校验、旧 HMAC 兼容开关；助手 ds、在线/离线通道、宿主数据源 AES 与 cookie/param。
- `dashboard/DashboardService.java`、`system/service/WorkspaceService.java`、`terminology/`、`training/` — 仪表盘同工作空间权限；删工作空间 oid 重选/置 0；术语/训练 ds 归属校验。
- `system/audit/AuditLogService.java`、`AuditLog.java`、`AuditRecorder.java` — typeLabel 本地化、operation_detail_info、oid_name 工作空间名。
- `system/service/UserImportService.java`、`UserService.java`、`SystemVariableService.java`、`ai/AiModelService.java`、`system/authentication/`、`system/sso/` — 本地化导入模板/枚举/返回字段、token 有效期、SSO 登出、用户搜索、系统变量、助手图片、模型 protocol、SAML2 三端点、isAdmin 判定。
- `src/main/resources/application.yaml`、`pom.xml` — 元数据库/Flyway、license、嵌入签名、图表图片、向量阈值、SAMPLE_DATA_ENABLED、ALLOW_METADATA_QUERIES、token 有效期等配置；Flyway/embedded-postgres/MySQL 测试依赖。
- `web/src/utils/xpackPatch.ts`、`web/src/views/login/xpack/Handler.vue`、`web/src/views/system/user/User.vue`、`web/src/views/system/parameter/xpack/PlatformParam.vue` — license 真实状态与 enforcement 门控。
- `web/src/views/chat/chat-block/ExecutionDetails.vue`、`web/src/views/system/authentication/SAML2Editor.vue`、`web/src/i18n/*.json` — 执行日志步骤、SAML2 配置、五语种键与工作空间文案。
- `askdata/askdata-server/README.md`、`docs/sqlbot-parity/` — 运行、迁移、license、嵌入签名、配置与验证说明。

### 删除

- `askdata/askdata-server/src/main/resources/schema.sql` — 由 Flyway 迁移取代；移除 `spring.sql.init.mode: always` 的运行期建表。
- `DatasourceController` 中 `POST /datasource/execSql/{id}` 的公开映射不删除（用户选择保留并补权限）。
- 不删除 SQLBot 前端页面或既有公开端点。

### 明确不建

- 不引入 g2-ssr / Node 运行时 — 用户已选择增强 Java2D。
- 不实现 SQLBot 闭源 xpack 的官方 license 格式与签名密钥 — 仓库不包含该实现的密钥与工具，采用本仓库可验证的 HMAC + 签发脚本。
- 不实现 Dockerfile/docker-compose/installer/start.sh、前端 Maven 构建、CONTEXT_PATH 子路径部署、logback 轮转、openapi 多语言文档。
- 不重写 SQLBot 前端页面 — 继续使用现有拷贝，只改门控/契约/i18n。
- 不修改审计“确认等价或更强”的条目，不处理前端死调用等非回归项。

# 非目标

- 不处理部署基建（见范围中的排除清单）。
- 不引入新的商业化能力，不改变 SQLBot 前端整体视觉与交互。
- 不承诺与闭源 `sqlbot-embedded-dynamic.umd.js` 逐字节互通；本次以自建 AES 签名样例 + 协议字段严格校验验收。
- 不改变 workspace / 用户 / 数据源等与本次审计无关的现有行为。
- 不在本次引入真实外部 IdP/IM/MySQL 实例的联调承诺；以单元/切片测试、embedded-postgres 与可重复命令验收。

# 验收示例

- A1 会话归属与最近问题：同一工作空间内用户 A/B 的会话列表、分页、计数和最近问题只返回各自的会话/提问（`create_by + oid` 双过滤，最近问题限 10 条）；管理员读取他人会话或 `/chat/record/{rid}/data|log|usage|excel` 时同样被拒绝，不再有管理员 owner 豁免。
- A2 分析/预测落库：对基础记录发起分析/预测后，新子记录的 `analysis_record_id`/`predict_record_id` 指向基础记录，基础记录两个外键保持 NULL；子记录复制基础记录的 chart/data，`/chat/{id}` 返回全部分析/预测子记录，刷新后气泡与预测数据仍可见。
- A3 License 校验与门控：用 `scripts/issue-license.sh` 与配置的 HMAC sign-key 签发 license；上传合法 license 返回 valid，篡改签名/过期/超量/版本不符返回 invalid/expired 并说明原因；`askdata.license.enforcement` 默认 bypass（企业能力可用），设为 enforce 后才拦截；前端企业入口读取真实状态与 enforcement，不再恒定隐藏。
- A4 服务端图表语义：服务端出图对 series 使用不同颜色、保留 multi-quota 多指标轴、横向 bar 条长按数值比例、area 填充、pie 标签与数值格式；`ChatChartBuilder` 保留 `axis.multi-quota`，未知 chart type 返回错误而不是静默改 table。
- A5 出图 URL 生命周期：MCP/IM/第三方拿到的 `image_url` 使用持久化 token→文件映射，进程重启后仍可打开；未登录第三方在有效期内可访问，篡改/过期 token 返回 404/403；`/api/chart/image/*` 在鉴权白名单内；过期图片文件与 token 按 TTL 清理，不无限增长。
- A6 数据源端点授权：表关系 save/get 与推荐问题 list/base/save 要求 ws_admin 且数据源 `type='ds'`/资源授权，未授权用户被拒绝；`POST /datasource/execSql/{id}` 保留但要求 ws_admin，并在执行前应用 `hiddenColumnsFor` 与 `rowFilterWhereFor`，非 ws_admin 无法绕过行列权限。
- A7 权限硬化：行权限按 varType 校验值域并对 kv 做 key→value 映射，越界值被拒绝；只读 SQL 校验基于 AST 函数节点而不是原文正则，字符串字面量中的 `version(` 不误拒、真实危险函数被拒；列权限在构造 schema/提示词/样例行之前剔除无权字段。
- A8 Excel 数据源与多 sheet：PostgreSQL/MySQL 元数据库下 Excel 数据源字段同步使用固定默认 schema（不再用空 `db_schema`），`core_field` 有完整列；多 sheet Excel 每个 sheet 的表头只出现一次，第 2..N 个 sheet 的数据行不被当成表头。
- A9 元数据库迁移：元数据库由 Flyway 版本化迁移管理，H2/PostgreSQL/MySQL 均可从空库启动并写入种子数据；`schema.sql` 不再由 `spring.sql.init` 执行；PostgreSQL 用 embedded-postgres 真库测试，MySQL 有方言单测；切换元数据库只需改连接配置并完成迁移。
- A10 平台同步安全：平台同步请求缺少 origin 或 origin=0 时被拒绝并给出明确错误；同步新建用户不再写 `md5("")`，默认口令随机/不可用；origin≠0 的同步用户无法用空口令本地登录。
- A11 嵌入签名协议：嵌入签名使用 SQLBot AES 信封（`assistant_id/target/sign_time/webdriver/user_agent/timezone`），校验 Origin 相等、±10s 窗口、UA 逐字、时区一致；AES key 可配置；旧 `id.timestamp.hmac` 仅由显式兼容开关启用，默认关闭。
- A12 SQL 输出 JSON 协议：SQL 生成提示要求并解析 JSON `{success,sql,brief,chart-type,tables}`；成功时用 brief 重命名会话、chart-type 传入图表提示词，tables 限制 FROM 表；非法 JSON/失败响应返回明确错误而不是正则抠 SQL、标题截前 20 字。
- A13 分析/预测/推荐提示词：三类请求分别使用 `template.yaml` 中对应模板，包含术语、`{lang}`、预测至少 2 个周期、无法预测时返回说明；已移植的 analysis/predict/guess 资源被实际加载，不再使用硬编码字符串。
- A14 执行日志与快捷命令：`chat_log` 记录 14 类 operate（含 `CHOOSE_TABLE`/`GENERATE_PICTURE`/`CHOOSE_DATASOURCE`/`GENERATE_DYNAMIC_SQL`/`GENERATE_SQL_WITH_PERMISSIONS`）及 `messages`/`pid`，前端 ExecutionDetails 能渲染对应步骤；`/regenerate 123` 支持带前文，MCP 下 `/analysis` 与 `/predict_data` 生效。
- A15 MCP 非交互输出：MCP 非交互问数输出包含 `record_id`、markdown 表格和 `![chart](image_url)`，与 Web SSE 的 JSON 帧解耦；无图表时省略图片行。
- A16 流式韧性与问数细节：客户端断连后后端继续执行 SQL/图表/落库，只有真实执行错误才 `saveError`；空提问在调用 LLM 前被拒绝；token 统计以最后一次 usage 为准；修复轮提示包含术语/样例/历史；每问只调用一次推荐问题 LLM。
- A17 嵌入助手与动态数据源：嵌入页按 token 中的 `assistant_id` 取助手；type=1 高级应用实时请求宿主 API 获取 `ds_list`，失败时不回退到“当前用户第一个 type=0 助手”；`X-SQLBOT-ASSISTANT-ONLINE` 运行时在线/离线通道生效；宿主下发数据源支持 AES 解密与 cookie/param 凭证位。
- A18 数据源类型细节：Hive 支持 LDAP 认证参数；SQL Server `lowVersion` 配置影响 URL/驱动参数；ES 表/字段同步保留 `_meta.description` 注释；MySQL `allowPublicKeyRetrieval` 仅在配置开启时追加；Oracle 可按配置启用 thick 模式；连接池按配置 recycle；`MAX_TABLES=10`。
- A19 Excel 导出与类型推断：Excel 导出数字列写为数值单元格而非文本，可求和；类型推断不再只采样 100 行且保留 float 语义；结果列名按大小写不敏感归一；上述行为有导出/导入回归测试。
- A20 表级向量召回：表级向量召回在远程 embedding 不可用时回退到本地 embedding（与 EmbeddingService 相同的本地模型路径），仍不可用才退化为词法；命中远程时优先远程向量。
- A21 仪表盘与工作空间：仪表盘列表/加载/保存/移动/删除按同工作空间可见即可操作，不再要求 `create_by=当前用户`；删除工作空间时用户 oid 重选或置 0，不再一律改成 1；术语/训练 `datasource_ids` 必须属于当前工作空间。
- A22 审计日志三列：审计日志 `operation_type_name` 返回本地化标签而非 code，`operation_detail_info` 返回真实操作详情，`oid_name` 返回工作空间名称而非数字 id；前端审计列表三列正常显示。
- A23 用户导入兼容：用户导入模板与解析使用本地化表头及“已启用/已禁用/本地创建”等枚举，兼容 SQLBot 模板；导入返回字段名与 SQLBot 一致，中文枚举值不再被判非法。
- A24 会话与系统细节：访问 token 默认 8 天且可配置；SSO 登出联动 IdP；用户搜索保留相关性排序；内置系统变量可删除；助手图片校验类型与大小；模型连通性测试使用 protocol/Azure 等配置；isAdmin 按角色而非 `id==1 && account=='admin'`。
- A25 SAML2 配置端点：补齐前端调用的 `/setting/authentication/info/saml`、`/setting/authentication/save/saml`、`/setting/authentication/validate/saml2` 三个端点，读写 SAML2 配置并校验证书/元数据，配置缺失时返回可读错误。
- A26 i18n 五语种：`zh-CN`/`en`/`zh-TW`/`ko-KR`/`th-TH` 五个 locale 的键集合与使用点一致，四语种不再回退中文/英文；工作空间名等 Java 新增文案全部有对应翻译，缺失键有自动检查。
- A27 配置项补齐：`application.yaml` 补齐向量召回阈值、`SAMPLE_DATA_ENABLED`、`ALLOW_METADATA_QUERIES`、token 有效期、图表图片 TTL/清理、许可证/嵌入签名开关等配置项，均有默认值与文档；未配置时行为与默认值一致。

# 约束与不变量

- 所有改动遵循现有 Spring Boot / JdbcTemplate / Controller-Service-Repository 结构，不新起并行框架。
- `{code,data,msg}` 响应包装、`X-SQLBOT-TOKEN: Bearer` 鉴权头、SSE 帧名与现有白名单语义保持不变。
- 不引入硬编码密钥；license 密钥、嵌入签名密钥、元数据库凭据均通过配置/环境变量提供。
- 不破坏现有 `web/dist` 构建产物与 Maven 打包链路；前端改动只在 `web/src` 并同步构建。
- 所有新增/修改测试必须在 JDK 17 + Maven 下可运行；不依赖本机 Docker；MySQL 只做方言单测，PostgreSQL 用 embedded-postgres。
- 既有安全控制（SQL 只读、JDBC 参数拦截、行列权限、表白名单、API Key、嵌入 token）不得削弱。

# 决策

- D1 拆分：本 change 为 Supervisor Change，按领域拆为 11 个子任务（metadb、chat-ownership、chat-pipeline、chart、authz、license、embedded-platform、datasource-details、system-account、workspace-dashboard、i18n-config），子任务依赖与验收映射见 `children.yaml`；推进方式（多会话/单会话）由用户另行选择。
- D2 元数据库：引入 Flyway，公共种子 + H2/PostgreSQL/MySQL 分方言 baseline；删除 `schema.sql` 与运行期 `spring.sql.init`；PostgreSQL 用 embedded-postgres 真库测试，MySQL 做方言单测（本机无 Docker，不承诺真 MySQL 实例验收）。
- D3 License：打通本仓库可验证的 HMAC 校验链并提供 `scripts/issue-license.sh`；`askdata.license.sign-key` 显式配置，未配置时 fail-closed 并提示；`askdata.license.enforcement=bypass` 为默认，`enforce` 才拦截过期/超量/版本不符；前端企业入口由真实状态与 enforcement 共同驱动。
- D4 `execSql`：保留公开端点，执行前要求 ws_admin 并强制 `hiddenColumnsFor + rowFilterWhereFor`，与 `previewData` 使用同一权限服务；非 ws_admin 返回 403。
- D5 嵌入签名：实现 SQLBot AES 加密 JSON 信封，严格校验 Origin 相等、±10s、UA 逐字、时区一致；AES key 配置化；旧 HMAC 仅保留为显式兼容开关，默认关闭。
- D6 图表：增强 Java2D，不引入 Node/g2-ssr；保留 multi-quota，未知 chart type 报错。
- D7 出图 URL：token→文件映射持久化到元数据库，文件仍走 x-file-storage；未登录可读、过期/篡改拒绝；按 TTL 清理文件与 token。
- D8 会话：管理员对会话/记录不再豁免 owner 校验；分析/预测只写 child→base 外键，基础记录两个外键保持 NULL，记录列表返回全部子记录。
- D9 问数协议：SQL 输出 JSON、analysis/predict/guess 模板、日志步骤与 MCP 非交互输出按 SQLBot 语义实现；前端只做契约所需最小改动。
- D10 i18n：五语种键集合与使用点对齐，工作空间名等新增文案全部补齐；后端 `messages_*.properties` 同步。
- D11 部署排除：只排除 Dockerfile/docker-compose/installer/start.sh、前端 Maven 构建、CONTEXT_PATH、logback 轮转、openapi 多语言；其余工程/交付类条目纳入。
- D12 验收：共 27 个验收项 A1–A27，由 11 个子任务分工覆盖；每项有自动化测试或可重复命令，独立 Verifier 逐项核对。

# 待解决问题

无。

# 验证预期

- 每项验收至少有 1 个自动化回归测试；无法自动化的（重启后 URL、真实元数据库启动、外部 IdP/IM）需给出可重复命令与手动步骤。
- 元数据库：H2 默认启动、PostgreSQL embedded-postgres 真库迁移/登录/问数、MySQL 方言单测；`mvn -pl askdata/askdata-server -am test` 全量通过。
- 前端：`web` 构建/类型检查通过；license 门控、执行日志、SAML2 配置与 i18n 关键分支有单测或手动验证记录。
- 独立只读 Verifier 需能按 A1–A27 逐条核对源码、测试输出与配置默认值。
