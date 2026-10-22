---
generated_from_state_version: 16
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 3
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-22T08:59:28.920Z
- 摘要: 独立复核通过：集成工作区全量 496 测试通过（4 skipped）；独立扫描确认无硬编码口令/密钥（含 @Value 缺省值形式）；9 个能力点关键符号全部存在；A5 修复后相关测试复跑通过。10 项验收全部通过。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1：主聊天 NL2SQL 在生成 SQL 时把数据源方言（对应 DatasourceType 的 dialect 名，如 PostgreSQL/Oracle/ClickHouse）注入 system prompt，并使用该方言的引号/分页/类型约定；对任意非 MySQL 数据源生成的 SQL 在该方言下语法正确且可执行（由针对非 MySQL 数据源的集成/单测验证）。 | 方言注入主 NL2SQL system prompt；DatasourceTypeDialectHintTest 通过，sqlDialectHint 符号核对通过。 |
| A2 | passed | brief.md | A2：MCP question_answer 与 SimpleAssistant 的 NL2SQL 不再硬编码 MySQL，不再每次问数全库 DatabaseMetaData 扫描；复用与主路径一致的 top-N RAG schema 选择 + dialect 注入 + 校验；移除 AskdataNl2Sql 中字面量 MySQL 与全表 dump 逻辑，且对非 MySQL 数据源产出正确 SQL（集成测试覆盖）。 | AskdataNl2Sql/AskdataSqlRetriever 去除字面量 MySQL 与全表 dump，复用 top-N schema+方言+只读校验；全量套件通过。 |
| A3 | passed | brief.md | A3：追问将前序 Q&A 上下文注入 SQL 生成使 follow-up 能引用前文实体；术语库/SQL 示例在无 embedding 模型或对应表缺失时系统显式记录降级（日志/状态标志）而非静默失效（有对应测试或可观测日志断言）。 | 追问上下文注入与降级可观测实现存在，ChatKnowledgeServiceDegradationTest/ChatKnowledgeChainTest 通过。 |
| A4 | passed | brief.md | A4：提供服务端图表渲染将 G2 配置渲染为 PNG（等价 SQLBot g2-ssr）；MCP/非交互与导出场景返回图片 URL；给定 chart config 服务端能产出 PNG 且 MCP 问数返回图片链接（有对应测试）。 | 服务端 G2→PNG 渲染与图片 URL 实现存在，ChartImage*Test 通过。 |
| A5 | passed | brief.md | A5：问数结果支持 CSV 导出（XLSX 已有）；代码与 schema.sql 中不存在硬编码口令/密钥字面量；密钥/口令改为可配置，缺省值不写入版本库，且配置缺失时应用可正常启动（有测试/静态检查）。 | 修复后独立复检：主源码/schema.sql 无 Root57@123456、SQLBot@123456、AskDataSecret001，且无带非空缺省值的 secret 类 @Value；A5 相关测试全部通过。 |
| A6 | passed | brief.md | A6：对声明支持的 13 种数据源中 oracle/dm/kingbase/redshift/es/hive 等非 MySQL 引擎，在驱动就位时元数据（表/字段）可提取且样本查询可执行；补全缺失驱动/方言策略并验证（测试或容器验证覆盖）。 | 13 引擎方言/引号/分页策略与 EngineExecutionParityTest 存在且通过。 |
| A7 | passed | brief.md | A7：支持外部身份提供方登录与账号绑定：OIDC、CAS、SAML2、企业微信、钉钉、飞书、Lark（在已有 LDAP/SSO 基础上扩展）；每种协议可完成授权登录并自动开通本地账号，有对应配置与测试（可用 mock IdP 验证）。 | 7 种外部 IdP provider + LocalAccountLinker 存在，ExternalIdpServiceTest 通过。 |
| A8 | passed | brief.md | A8：高级应用（assistant type 1/3）支持按请求从宿主拉取数据源（动态数据源），不持久化该数据源；与静态数据源问数路径行为一致且有集成测试覆盖。 | DynamicDatasourceResolver 仅 type 1/3 放行且不落库；DynamicDatasourceParityTest 覆盖动态/静态一致性并通过。 |
| A9 | passed | brief.md | A9：行/列权限支持 advanced-app 知识作用域隔离，权限模型（DsPermission/DsRules 风格）对齐 SQLBot 商业级表达（含 user_list/permission_list JSONB 匹配、多规则 AND/交集），运行时强制；多规则组合正确且有测试。 | 多规则 AND 行过滤、白名单短路、隐藏列并集、子查询包裹与结果集剔列均已实现；相关测试通过。 |
| A10 | passed | brief.md | A10：定时调度支持创建周期性问数任务（cron 或固定间隔），按调度自动执行已保存的问数（复用现有 NL2SQL 与执行链路），并将结果按配置导出（CSV/XLSX）或推送；调度任务可增删改查、启停，记录执行历史与失败重试；有对应配置与集成测试。 | cron/interval 调度、复用 NL2SQL 执行、CSV/XLSX 导出与 webhook 推送、CRUD/启停/历史/重试均实现；相关测试通过。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-server 全量测试（集成工作区） | -lc export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn -o -pl askdata/askdata-server -Dmaven.test.skip=false test | . | passed | 0 | 222825 ms |
| 独立扫描硬编码口令/密钥（含 @Value 缺省值） | -lc if grep -rn 'Root57@123456\\|SQLBot@123456\\|AskDataSecret001' askdata/askdata-server/src/main/java askdata/askdata-server/src/main/resources/schema.sql; then echo forbidden-literal-found; exit 1; fi; if grep -rn '@Value("[^"}]*secret[^"}]*:[^"}]\+' askdata/askdata-server/src/main/java; then echo value-default-secret-found; exit 1; fi; echo clean | . | passed | 0 | 270 ms |
| 独立核对各验收点关键实现符号 | -lc set -e; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java; exit 1; fi; if ! grep -q "sqlDialectHint" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java"; then echo missing sqlDialectHint in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java; exit 1; fi; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/DynamicDatasourceResolver.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/DynamicDatasourceResolver.java; exit 1; fi; if ! grep -q "isAdvancedApplication" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/DynamicDatasourceResolver.java"; then echo missing isAdvancedApplication in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/DynamicDatasourceResolver.java; exit 1; fi; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/scheduled/ScheduledTaskExecutor.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/scheduled/ScheduledTaskExecutor.java; exit 1; fi; if ! grep -q "execSqlAsSystem" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/scheduled/ScheduledTaskExecutor.java"; then echo missing execSqlAsSystem in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/scheduled/ScheduledTaskExecutor.java; exit 1; fi; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/PermissionEnforcer.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/PermissionEnforcer.java; exit 1; fi; if ! grep -q "applyHiddenColumns" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/PermissionEnforcer.java"; then echo missing applyHiddenColumns in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/PermissionEnforcer.java; exit 1; fi; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/sso/LarkProvider.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/sso/LarkProvider.java; exit 1; fi; if ! grep -q "class" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/sso/LarkProvider.java"; then echo missing class in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/sso/LarkProvider.java; exit 1; fi; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chart/server/ChartImageRenderer.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chart/server/ChartImageRenderer.java; exit 1; fi; if ! grep -q "render" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chart/server/ChartImageRenderer.java"; then echo missing render in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chart/server/ChartImageRenderer.java; exit 1; fi; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/support/ChatExcel.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/support/ChatExcel.java; exit 1; fi; if ! grep -q "Format" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/support/ChatExcel.java"; then echo missing Format in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/support/ChatExcel.java; exit 1; fi; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/DsPermissionQueryService.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/DsPermissionQueryService.java; exit 1; fi; if ! grep -q "white_list_user" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/DsPermissionQueryService.java"; then echo missing white_list_user in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/permission/DsPermissionQueryService.java; exit 1; fi; if [ ! -f "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/AskdataNl2Sql.java" ]; then echo missing askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/AskdataNl2Sql.java; exit 1; fi; if ! grep -q "DatasourceType" "askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/AskdataNl2Sql.java"; then echo missing DatasourceType in askdata/askdata-server/src/main/java/com/xiongx9527/askdata/ai/AskdataNl2Sql.java; exit 1; fi; echo all-symbols-present | . | passed | 0 | 88 ms |
| A5 相关测试独立复跑 | -lc export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn -o -pl askdata/askdata-server -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=NoHardcodedSecretsTest,EmbeddedTokenAuthenticatorTest,ChatExcelCsvTest,DatasourceCryptoTest test | . | passed | 0 | 36572 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- askdata-server 全量测试（集成分支）: passed — Tests run: 496, Failures: 0, Errors: 0, Skipped: 4; BUILD SUCCESS
- 各子任务独立复核（Runtime 回执）: passed — 10 个子任务均通过 supervisor-checks 的 Runtime 检查回执，并在集成工作区完成集成检查
- 已知限制: 前端构建未执行：10 个子任务均未修改 askdata/askdata-server/web 下任何文件，且该环境无 node_modules，Maven 直接复用已提交的 web/dist 静态资源。
- 已知限制: A6 以 H2 与方言/元数据单测验证，未接入真实 Oracle/DM/Kingbase/Redshift/ES/Hive 容器。
- 已知限制: A7 以 mock IdP 验证授权码流程，未接入真实企业租户。

## 阻塞项

_无。_

## 风险与跳过的工作

- A6 以 H2 与方言/元数据单测验证，未接入真实 Oracle/DM/Kingbase/Redshift/ES/Hive 容器。
- A7 以 mock IdP 验证授权码流程，未接入真实企业租户。
- 未配置 askdata.secret-key 时嵌入/访问 token 使用进程内随机密钥，重启后旧 token 失效。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 0 | 0 | recovery | — | Native confirmed acceptance criteria changed | 2026-10-21T23:37:05.521Z |
| 2 | 1 | 1 | fail | A5 | 独立复核：集成工作区全量测试 496 通过（4 skipped），能力符号核对通过；但独立扫描发现 AskDataSecret001 仍作为硬编码缺省密钥出现在两处 @Value 注解中，A5 不通过，其余 9 项通过。 | 2026-10-22T07:25:36.082Z |
| 2 | 2 | 0 | recovery | — | Native Shape artifacts changed | 2026-10-22T08:45:54.231Z |
| 3 | 1 | 1 | pass | — | 独立复核通过：集成工作区全量 496 测试通过（4 skipped）；独立扫描确认无硬编码口令/密钥（含 @Value 缺省值形式）；9 个能力点关键符号全部存在；A5 修复后相关测试复跑通过。10 项验收全部通过。 | 2026-10-22T08:59:28.920Z |


## Supervisor 分层证据

- Child 验证: complete
- 父级集成: complete
- 父级检查: askdata-server 全量测试（集成工作区）, 独立扫描硬编码口令/密钥（含 @Value 缺省值）, 独立核对各验收点关键实现符号, A5 相关测试独立复跑
- 未重跑: A1 dialect injection tests: passed, A2 MCP/assistant NL2SQL parity tests: passed, A3 multiturn + degradation tests: passed, a4 acceptance tests: passed, a5 acceptance tests: passed, a6 acceptance tests: passed, a7b acceptance tests: passed, a8 acceptance tests: passed, a9b acceptance tests: passed, a10 acceptance tests: passed, A5 repair acceptance tests: passed
- 未完成: —


## 结论

独立复核通过：集成工作区全量 496 测试通过（4 skipped）；独立扫描确认无硬编码口令/密钥（含 @Value 缺省值形式）；9 个能力点关键符号全部存在；A5 修复后相关测试复跑通过。10 项验收全部通过。
