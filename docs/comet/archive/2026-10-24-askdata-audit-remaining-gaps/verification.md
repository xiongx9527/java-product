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
- 完成时间: 2026-10-24T04:40:49.659Z
- 摘要: 独立只读验收 10/10 通过：MCP 行/列权限接入、动态占位符闭环、extra_body 嵌套与 Azure 参数剔除、i18n 批次（导出列+错误文案+workspace_list+默认3）均有代码与测试实证；全量 638/0/0/5 经 Runtime 检查记录与 surefire 报告双重核对一致，verdict=pass。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1 行权限经 MCP 生效：为某用户配置表级行权限后，经 MCP question_answer 问数返回行集与 Chat 主链路一致（过滤生效）；未配置行权限的数据源行为不变；表白名单校验保留。 | AskdataNl2Sql.ask() dsId!=null 分支先经 checkedTableNames 表白名单，再调用 enforceForMcp(permissionEnforcer, currentUserService, dsId, sql) 施加行权限并改写 SQL（AskdataNl2Sql.java:134-141,163-170）；McpPermissionEnforceTest.enforceForMcpPassesCurrentUserIdentityToEnforcer 验证 (oid=7,uid=42,dsId=9) 传递与行权限 SQL 改写，surefire 2/2 通过 |
| A2 | passed | brief.md | A2 列权限经 MCP 生效：被列权限隐藏的列不出现在 MCP 问数的执行 SQL 引用与返回结果中。 | AskdataNl2Sql.ask() 执行后调用 permissionEnforcer.applyHiddenColumns(result, enforced.getHiddenColumns()) 过滤结果行（AskdataNl2Sql.java:145-153）；McpPermissionEnforceTest.hiddenColumnsAreAppliedToResultRows 验证 secret_col 从结果行剔除；表白名单 checkedTableNames 分支保留 |
| A3 | passed | brief.md | A3 动态子查询占位符替换：宿主下发含表级 dynamic_sql 的动态数据源（含 resolveFromHost 实时拉取形态）时，一阶段 SQL 引用占位符表，执行前被确定性替换为宿主子查询；替换失败时按错误处理（回喂修复或失败帧），不执行含未替换占位符的 SQL。 | DynamicDatasource 提供确定性替换族：placeholderTable/replaceSubSqlPlaceholders/directTableReferenceError/leftoverPlaceholderError/templateInnerTables（DynamicDatasource.java:32-102）；ChatQuestionService 生成与修复轮均接入 directTableReferenceError→replaceSubSqlPlaceholders（ChatQuestionService.java:310-337）；validateSql 动态分支用 leftoverPlaceholderError 拦截残留占位符（ChatQuestionService.java:820-837）；SchemaContextBuilder 两轮提示词均 appendDynamicTemplateSection（SchemaContextBuilder.java:695,728）；DynamicSubSqlRewriteTest 5 用例 surefire 5/5 通过 |
| A4 | passed | brief.md | A4 动态路径权限边界：动态数据源问数仍执行表白名单校验；无数据源身份时跳过行/列权限（对齐 SQLBot，记录为既定行为）。 | ChatQuestionService.java:349-350 权限强制条件保持 (dynamic != null \|\| dsId == null \|\| permissionEnforcer == null) 跳过 enforce，与 brief 既定行为一致；validateSql 动态分支白名单 = 动态连接实时表 ∪ templateInnerTables 宿主模板内部表，validateTables 白名单校验保留（ChatQuestionService.java:828-837） |
| A5 | passed | brief.md | A5 模型请求体结构：config 含 extra_body 或 enable_thinking 时，请求体出现嵌套 extra_body 且无顶层平铺键；Azure 端点请求体不含 api_version 与 deployment_name，URL 仍含二者；temperature 等其他参数整包透传保持。 | ChatModelClient.buildPayload 将 extra_body（Map/JSON 字符串归并 mergeExtraBody）与 enable_thinking 归并进嵌套 extra_body 且不落顶层平铺键（ChatModelClient.java:180-227,228-251）；Azure 端点 api_version/deployment_name 只参与 URL 构造、从 body 剔除（ChatModelClient.java:200-204,472-498）；ChatModelClientPayloadTest 5 用例 5/5；ChatPipelineTest.modelConfigParamsForwardedToRequestBody 断言嵌套 extra_body.enable_thinking 且顶层无 enable_thinking（ChatPipelineTest.java:246-260） |
| A6 | passed | brief.md | A6 导出物 i18n：自定义提示词/术语/SQL 示例的导出与模板列头按当前 locale 渲染（不再固定中文）。 | CustomPromptService.promptExportColumns（:247-252）+模板示例行（:230-232）、TerminologyService.terminologyExportColumns（:300-307）+示例行、TrainingService.trainingExportColumns 全部走 messageResolver.get(export.*)；6 个 messages*.properties 每个含 19 个 export.* 键（含 .example 示例行键），6 文件键数一致（各 354 行） |
| A7 | passed | brief.md | A7 错误文案 i18n：ChatService、ChatQuestionService、SettingsFileController、TableRelationService、ExternalIdpController、ParameterService 中的硬编码英文错误文案全部改走 messageResolver，键在 6 个 messages*.properties 齐备。 | 6 类均接入 messageResolver（ChatService 13 处/ChatQuestionService 14 处/SettingsFileController 6 处/TableRelationService 4 处/ExternalIdpController 6 处/ParameterService 5 处）；grep 硬编码英文异常文案在 6 类中仅剩 TableRelationService.java:39,44 两处 DatasourceException（git show HEAD 确认为既有代码、本轮未触碰，属 56 处代码库级技术异常约定）；新键 chat.not.found/settings.file.required 等在 6 语言文件齐备 |
| A8 | passed | brief.md | A8 workspace_list 非管理员：非管理员登录用户经 MCP workspace_list 获得自己所属工作空间列表；管理员仍列全部。 | WorkspaceService.list（WorkspaceService.java:61-77）：isAdmin 走 workspaceRepository.list() 列全部，非管理员走 userWsRepository.oidsOf(uid)→findById 只列所属工作空间，注释标明 MCP workspace_list 与普通 API 共用同一实现 |
| A9 | passed | brief.md | A9 历史轮数默认 3：chat.context_record_count 未配置/种子缺省时 priorContext 默认取 3。 | ChatQuestionService.priorContext 使用 chatInt(chat.context_record_count, 3)（ChatQuestionService.java:672），未配置/种子缺省时默认 3；schema.sql:223 种子值已是 3（VALUES (11, chat.context_record_count, 3, ...)） |
| A10 | passed | brief.md | A10 全量回归：mvn -o -pl askdata/askdata-server clean test（zulu-17，-Dmaven.test.skip=false）全部通过，现有 626 用例无回归。 | Runtime verify.checks 记录 askdata-full-regression passed（exitCode=0，clean test，duration 102s，logRef+logSha256 在案）；surefire-reports 只读核对：97 个报告文件汇总 tests=638 failures=0 errors=0 skipped=5，与声明一致；新增用例 DynamicSubSqlRewriteTest 5、ChatModelClientPayloadTest 5、McpPermissionEnforceTest 2 全过 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-full-regression | -o -pl askdata/askdata-server clean test -Dmaven.test.skip=false -DskipTests=false | . | passed | 0 | 102156 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- main-compile: passed — mvn -o -pl askdata/askdata-server compile EXIT=0
- new-tests: passed — DynamicSubSqlRewriteTest 5/5、ChatModelClientPayloadTest 5/5、McpPermissionEnforceTest 2/2
- full-regression: passed — clean 全量 638/0/0/5（zulu-17）
- 已知限制: MCP question_answer 无 stream 同步形态（SQLBot 有），维持既有形态差异（非目标）
- 已知限制: 受限表直接引用检测基于 JSqlParser usedTables，方言解析失败退化为分词兜底时可能漏检（主链路同源限制）
- 已知限制: 动态占位符替换后若宿主子查询内部表不在动态连接实时元数据中，由 templateInnerTables 扩展允许集兜底

## 阻塞项

_无。_

## 风险与跳过的工作

- TableRelationService.java:39,44 残留两处既有 DatasourceException 英文文案（no datasource / Serialize table relation failed），为 HEAD 既有代码、属代码库 56 处 DatasourceException 技术异常约定，本轮未在 brief 审计清单内；后续如做全量 i18n 可一并处理
- ChatService.java:223 残留一处硬编码中文（数据为空，对齐 SQLBot export_excel 语义，非 A7 所指英文文案范围）
- 受限表直接引用检测基于 JSqlParser usedTables，方言解析失败退化分词兜底时可能漏检（builder known_limits 已声明，主链路同源限制）

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | pass | — | 独立只读验收 10/10 通过：MCP 行/列权限接入、动态占位符闭环、extra_body 嵌套与 Azure 参数剔除、i18n 批次（导出列+错误文案+workspace_list+默认3）均有代码与测试实证；全量 638/0/0/5 经 Runtime 检查记录与 surefire 报告双重核对一致，verdict=pass。 | 2026-10-24T04:40:49.659Z |



## 结论

独立只读验收 10/10 通过：MCP 行/列权限接入、动态占位符闭环、extra_body 嵌套与 Azure 参数剔除、i18n 批次（导出列+错误文案+workspace_list+默认3）均有代码与测试实证；全量 638/0/0/5 经 Runtime 检查记录与 surefire 报告双重核对一致，verdict=pass。
