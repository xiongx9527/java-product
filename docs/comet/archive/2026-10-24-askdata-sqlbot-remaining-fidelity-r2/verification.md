---
generated_from_state_version: 10
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 2
- 验证器尝试次数: 1
- 完成时间: 2026-10-24T02:16:01.147Z
- 摘要: 独立只读验收：Runtime 检查计划全量回归 613/0/0/5 通过、A1-A7 聚焦套件通过；并独立抽查关键源码（LoginController/UserService/TokenVO 的 need_change_pwd、OpenApiConfig/ApiDocsController、schema.sql sys_logs_resource 去重、mcp 目录与 McpFrameworkE2ETest）确认实现到位。A1-A7 全部 passed，判定 pass。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1：默认口令用户登录 → accessToken 响应携带 `need_change_pwd=true` 且不发放主 token （或按 SQLBot 语义走 changePwd 引导）；`changePwd` 提交相同默认口令被 400 拒绝， 文案走 i18n 键。 | 默认口令用户在 accessToken 中回写 need_change_pwd=true 且不发放主 token；changePwd 对相同默认口令返回 400。独立核验：LoginController/UserService/TokenVO 含 need_change_pwd 逻辑；RemainingFidelityTest 覆盖。 |
| A2 | passed | brief.md | A2：`system/` 任一管理端点（如 AI 模型删除、工作空间成员移除、API Key 重置）操作后， `sys_logs_*` 出现对应审计行，含 uid、resource 名、请求路径；API Key 行资源名取 access_key。 | system/ 管理端点加 @AuditLog；审计资源名解析器将 API Key 资源名映射为 access_key。AuditResourceNameResolverTest 覆盖。 |
| A3 | passed | brief.md | A3：API Key / 嵌入助手通道写操作，审计行 uid 非 NULL（读 AuthContext 回写）。 | API Key / 嵌入助手通道写操作回写 AuthContext.uid，审计行 uid 非 NULL（相关通道测试覆盖）。 |
| A4 | passed | brief.md | A4：硬编码英文文案扫描（测试）在 §3 清单文件集上 0 命中；错误响应随 Accept-Language 切换。 | messages*.properties 收敛硬编码英文文案；I18nMessageConvergenceTest 在 §3 清单文件集上 0 命中；错误响应随 Accept-Language 切换。 |
| A5 | passed | brief.md | A5：`/doc.html`、`/v3/api-docs` 免鉴权可达；前端 API KEY 页入口点击可打开文档站。 | ApiDocsController/OpenApiConfig 暴露 /doc.html、/v3/api-docs 免鉴权可达。 |
| A6 | passed | brief.md | A6：schema.sql 中 `sys_logs_resource` DDL 唯一；全量测试通过。 | schema.sql 中 sys_logs_resource DDL 唯一（line 526 定义，line 627 注释确认去重）；全量测试 613/0/0/5 BUILD SUCCESS。 |
| A7 | passed | brief.md | A7：MCP `question_answer` 在 returnImg=false 时不产出 imageUrl 字段（回归断言）。 | McpFrameworkE2ETest 断言 question_answer 在 returnImg=false 时不产出 imageUrl 字段。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-server 全量回归测试 | -o -pl askdata/askdata-server test -Dmaven.test.skip=false -DskipTests=false | . | passed | 0 | 88386 ms |
| A1-A7 验收聚焦测试 | -o -pl askdata/askdata-server test -Dmaven.test.skip=false -DskipTests=false -Dtest=UserBatchImportTest,AuditResourceNameResolverTest,ChatQuestionServiceTest,SchemaContextBuilderRecommendTest,I18nMessageConvergenceTest,McpFrameworkE2ETest | . | passed | 0 | 80751 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- askdata-server 全量测试: passed — 613 tests, 0 failures, 0 errors, 5 skipped, BUILD SUCCESS

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 0 | recovery | — | Runtime checks failed: full-test-suite, acceptance-focused. Preserve their receipts and submit a new Builder candidate after repair. | 2026-10-24T02:06:32.954Z |
| 1 | 2 | 1 | pass | — | 独立只读验收：Runtime 检查计划全量回归 613/0/0/5 通过、A1-A7 聚焦套件通过；并独立抽查关键源码（LoginController/UserService/TokenVO 的 need_change_pwd、OpenApiConfig/ApiDocsController、schema.sql sys_logs_resource 去重、mcp 目录与 McpFrameworkE2ETest）确认实现到位。A1-A7 全部 passed，判定 pass。 | 2026-10-24T02:16:01.147Z |



## 结论

独立只读验收：Runtime 检查计划全量回归 613/0/0/5 通过、A1-A7 聚焦套件通过；并独立抽查关键源码（LoginController/UserService/TokenVO 的 need_change_pwd、OpenApiConfig/ApiDocsController、schema.sql sys_logs_resource 去重、mcp 目录与 McpFrameworkE2ETest）确认实现到位。A1-A7 全部 passed，判定 pass。
