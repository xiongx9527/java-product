# Outcome

让 askdata 在智能问数/ChatBI 领域完全覆盖 SQLBot 开源能力并补齐 xpack 商业能力（SSO 外部 IdP、动态数据源高级应用、商业级行/列权限建模、定时调度），同时消除已识别的实现打折项。本 change 为 Supervisor Change，统筹 10 个可独立验收的子任务。

# Scope

覆盖 SQLBot 能力面中与 askdata 问数/BI 相关的全部用户可见能力，含开源能力、xpack 商业能力与用户新增的定时调度能力。

## 目录结构

### 新建

- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chart/server/ — 服务端图表渲染（等价 g2-ssr），G2 配置渲染为 PNG，供 MCP/导出返回图片 URL。
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/security/idp/ — 外部 IdP 适配器：OIDC、CAS、SAML2、企业微信、钉钉、飞书、Lark。
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/schedule/ — 定时调度：周期性问数任务的增删改查/启停、cron 触发、执行历史与失败重试，复用现有 NL2SQL 与执行链路，结果按配置导出/推送。
- 对应单元测试与集成测试目录。

### 修改

- chat/service/SchemaContextBuilder.java — 主路径 NL2SQL 注入方言 dialect，按 DatasourceType 生成引号/分页/类型约定（A1）。
- datasource/engine/DatasourceType.java — 补全 13 引擎 dialect 名/引号/分页/类型映射（A1/A6）。
- ai/AskdataNl2Sql.java、ai/AskdataSqlRetriever.java、ai/AskdataAssistantConfig.java — 移除字面量 MySQL 与 DatabaseMetaData 全表扫描，复用主路径 top-N RAG schema+dialect+校验（A2）。
- chat/service/ChatQuestionService.java — 追问注入前序 Q&A 上下文；ChatKnowledgeService 记录校准降级为可观测（A3）。
- mcp/AskdataMcpTools.java — question_answer 复用重构后路径并在有图表时返回图片 URL（A2/A4）。
- chat/support/ChatExcel.java — 增加 CSV 导出（A5）。
- schema.sql、AskdataAssistantConfig.java、embedded/AssistantPublicController.java — 移除硬编码密钥/默认口令，改为可配置（A5）。
- pom.xml — 补齐非 MySQL 引擎驱动或可选依赖（A6）。
- security/AuthenticationService.java — 接入外部 IdP 登录与本地账号自动开通（A7）。
- embedded/AssistantPublicController.java 与动态数据源工厂 — 支持 assistant type 1/3 按请求从宿主拉取动态数据源（A8）。
- permission/DsPermissionService.java、DsPermissionQueryService.java、DsRules 模型 — 对齐商业级权限表达与作用域隔离，运行时强制（A9）。
- schedule/ 调度配置与 chat 执行入口 — 周期性问数任务接入现有问数链路并产出结果（A10）。

### 删除

无。

### 明确不建

- 独立前端图表渲染重写 — 仅补服务端出图。
- 超出问数/BI 的平台同步/运营中台能力 — 不在范围。
- 各 SSO IdP 私有云部署适配 — 仅标准协议级适配。

# Non-goals

- 不重写已等价实现的开源能力（workspace、i18n、dashboard、分析/预测/推荐、上传建数据源、越问越准）。
- 不替换 Sa-Token 登录主体。

# Acceptance examples

- A1：主聊天 NL2SQL 在生成 SQL 时把数据源方言（对应 DatasourceType 的 dialect 名，如 PostgreSQL/Oracle/ClickHouse）注入 system prompt，并使用该方言的引号/分页/类型约定；对任意非 MySQL 数据源生成的 SQL 在该方言下语法正确且可执行（由针对非 MySQL 数据源的集成/单测验证）。
- A2：MCP question_answer 与 SimpleAssistant 的 NL2SQL 不再硬编码 MySQL，不再每次问数全库 DatabaseMetaData 扫描；复用与主路径一致的 top-N RAG schema 选择 + dialect 注入 + 校验；移除 AskdataNl2Sql 中字面量 MySQL 与全表 dump 逻辑，且对非 MySQL 数据源产出正确 SQL（集成测试覆盖）。
- A3：追问将前序 Q&A 上下文注入 SQL 生成使 follow-up 能引用前文实体；术语库/SQL 示例在无 embedding 模型或对应表缺失时系统显式记录降级（日志/状态标志）而非静默失效（有对应测试或可观测日志断言）。
- A4：提供服务端图表渲染将 G2 配置渲染为 PNG（等价 SQLBot g2-ssr）；MCP/非交互与导出场景返回图片 URL；给定 chart config 服务端能产出 PNG 且 MCP 问数返回图片链接（有对应测试）。
- A5：问数结果支持 CSV 导出（XLSX 已有）；代码与 schema.sql 中不存在硬编码口令/密钥字面量；密钥/口令改为可配置，缺省值不写入版本库，且配置缺失时应用可正常启动（有测试/静态检查）。
- A6：对声明支持的 13 种数据源中 oracle/dm/kingbase/redshift/es/hive 等非 MySQL 引擎，在驱动就位时元数据（表/字段）可提取且样本查询可执行；补全缺失驱动/方言策略并验证（测试或容器验证覆盖）。
- A7：支持外部身份提供方登录与账号绑定：OIDC、CAS、SAML2、企业微信、钉钉、飞书、Lark（在已有 LDAP/SSO 基础上扩展）；每种协议可完成授权登录并自动开通本地账号，有对应配置与测试（可用 mock IdP 验证）。
- A8：高级应用（assistant type 1/3）支持按请求从宿主拉取数据源（动态数据源），不持久化该数据源；与静态数据源问数路径行为一致且有集成测试覆盖。
- A9：行/列权限支持 advanced-app 知识作用域隔离，权限模型（DsPermission/DsRules 风格）对齐 SQLBot 商业级表达（含 user_list/permission_list JSONB 匹配、多规则 AND/交集），运行时强制；多规则组合正确且有测试。
- A10：定时调度支持创建周期性问数任务（cron 或固定间隔），按调度自动执行已保存的问数（复用现有 NL2SQL 与执行链路），并将结果按配置导出（CSV/XLSX）或推送；调度任务可增删改查、启停，记录执行历史与失败重试；有对应配置与集成测试。

# Decisions

- 范围包含 xpack 商业能力：用户在范围选择中明确选择「含 xpack 商业能力」，故 SSO 外部 IdP、动态数据源高级应用、商业级行/列权限建模均纳入本 change。
- 定时调度：用户明确要求实现定时调度能力（超出 SQLBot 社区版范围），新增 scheduled-tasks 子任务（A10）。
- 不新建前端图表渲染：仅补服务端出图能力（A4）。
- 保持 Sa-Token 主体：SSO 仅在既有登录体系上叠加（A7）。

# Verification expectations

- A1/A2/A3/A4/A5/A8/A9/A10 以 askdata 本地单测/集成测试（Spring Boot Test + 内存/测试数据源）为主验证；非 MySQL 引擎（A6）以驱动就位后的元数据/执行测试或容器验证。
- A7 各 IdP 以 mock/测试 IdP 端点 + 授权码流程回调验证，不依赖真实企业租户。
- 校准降级（A3）以日志/状态断言验证，不要求真实 embedding 服务。
- 所有子任务集成后，父级需重跑既有全模块测试（410+）与前端构建，确保无回归。
