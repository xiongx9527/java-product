---
generated_from_state_version: 16
---

# 验证

## 当前结果

- 结果: **验收通过，需要你确认**
- 验证情况: **已完成检查，但需要你确认验证结果**
- 目标周期: 1
- 迭代: 5
- 验证器尝试次数: 1
- 完成时间: 2026-10-23T15:39:37.485Z
- 摘要: A1-A7 全部通过：A4 的 401/i18n 硬编码收敛经独立字面量审计与规则探针确认清零，错误响应随 Accept-Language 切换；Runtime 刷新检查 full-test-suite 与 acceptance-focused 均 exit 0，clean 全量 608 用例 0 失败 0 错误（5 跳过）BUILD SUCCESS。F-17 Excel 列集按 spec §4 在 verify 声明豁免并记入 risks。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1：默认口令用户登录 → accessToken 响应携带 `need_change_pwd=true` 且不发放主 token （或按 SQLBot 语义走 changePwd 引导）；`changePwd` 提交相同默认口令被 400 拒绝， 文案走 i18n 键。 | LoginController.java:90-100 默认口令+initial_pwd_disabled 命中时返回 TokenVO("") 且 need_change_pwd=true（不发主 token，种子管理员 uid=1 豁免），:135-138 changePwd 以 i18n_login.new_pwd_is_initial 400 拒绝；UserService.java:46-72 默认口令基准与 null 语义，schema.sql:181 种入 login.initial_pwd_disabled。Runtime 刷新检查 :17 full-test-suite 通过（exit 0，116.4s），RemainingFidelityTest 11/11（引导/豁免/拒绝三用例）全绿。 |
| A2 | passed | brief.md | A2：`system/` 任一管理端点（如 AI 模型删除、工作空间成员移除、API Key 重置）操作后， `sys_logs_*` 出现对应审计行，含 uid、resource 名、请求路径；API Key 行资源名取 access_key。 | system/ 写端点 @AuditLog 覆盖：UserController 10/10、WorkspaceController 6/6、AiModelController 7 个写端点（status 为连通检查非写）、ApiKeyController 3/3、ParameterController 1/1、VariableController 2 个写端点；AuditRecorder.java:113-130 落 sys_logs 含 user_id/module/resource_id/resource_name/request_method/request_path，AuditResourceNameResolver.java:34 API_KEY→sys_apikey.access_key；RemainingFidelityTest.memberRemovalWritesAuditRowWithResourceName 实测 uid+资源名+request_path 断言通过。 |
| A3 | passed | brief.md | A3：API Key / 嵌入助手通道写操作，审计行 uid 非 NULL（读 AuthContext 回写）。 | AuditRecorder.java:185-198 currentUid() Sa-Token 失败后回退 AuthContext.currentUser()，uid 为空时不落行（uid NULL 行从根消除）；AuthChannelFilter.java:104-122 为 ASK_TOKEN/ASSISTANT_TOKEN 两条支线写 AuthContext；EmbeddedTokenAuthenticator.java:177-183 回写 assistant.oid=user.oid（F-08）。RemainingFidelityTest.sideChannelAuditUidFallsBackToAuthContext 实测 user_id=42 通过。 |
| A4 | passed | brief.md | A4：硬编码英文文案扫描（测试）在 §3 清单文件集上 0 命中；错误响应随 Accept-Language 切换。 | ApiExceptionHandler.java:51-57 notLogin 401 正文走 i18n_permission.authenticate_invalid（随 LocaleContextHolder/Accept-Language），AuthChannelFilter.java:186-190 同键并按请求 locale 渲染；我独立对 §3 的 15 个文件做全字符串字面量审计：0 处用户可见硬编码错误文案（仅日志/SQL/Excel 表头/资源标签）；用测试同款三条正则对全模块跑探针：审计文件集 0 命中、非审计文件 47 个命中（规则非空转）；messageResolver 引用的 116 个键在 6 个 messages 包中全部存在；I18nMessageConvergenceTest 3/3（含 notLoginMessageFollowsAcceptLanguage 中英正文不同）。F-17 Excel 列集按 spec §4 允许的 verify 阶段声明豁免，理由见 risks。 |
| A5 | passed | brief.md | A5：`/doc.html`、`/v3/api-docs` 免鉴权可达；前端 API KEY 页入口点击可打开文档站。 | AuthWhitelist.java:92-97 API_DOCS_PATHS 含 /doc.html、/v3/api-docs*、/swagger-ui/*、/swagger-resources/*，:175-183 归一化剥除全部尾斜杠；ApiDocsController 提供 /openapi.json（forward 到 /v3/api-docs）与 /docs（跳 /doc.html）；OpenApiDocsEndpointTest 7/7 用 MockMvc 实测规范公开、Knife4j 页面 text/html、静态资源公开、受保护接口仍 401。 |
| A6 | passed | brief.md | A6：schema.sql 中 `sys_logs_resource` DDL 唯一；全量测试通过。 | schema.sql:526 仅 1 处 CREATE TABLE IF NOT EXISTS sys_logs_resource（grep 计数 1，原 :637 重复块已删）；AuthWhitelist 注释『35 条』与 DEFAULT_PATHS 实际 35 条一致（F-20）。Runtime 刷新检查 :17 full-test-suite 通过（exit 0，116.4s，608 用例 0 失败 0 错误 5 跳过）；我另做 clean 全量复跑：90 个测试类 608 用例，BUILD SUCCESS，H2 上下文全部正常启动无重复表冲突。 |
| A7 | passed | brief.md | A7：MCP `question_answer` 在 returnImg=false 时不产出 imageUrl 字段（回归断言）。 | AskdataMcpTools.java:282-291 仅在 shouldReturnImage(returnImg) 为真时 result.put("imageUrl")，:304-305 未传默认 true；datasource_list(:48-55) 透传 workspaceId 到 DatasourceService.java:138-149——非管理员恒取自身 oid、管理员才可指定、无全量回退。McpQuestionReturnImgTest 3/3 通过。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-server 全量测试 | -c JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home mvn -pl askdata/askdata-server -Dmaven.test.skip=false test | . | passed | 0 | 116398 ms |
| A1-A7 定向回归 | -c JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home mvn -pl askdata/askdata-server -Dmaven.test.skip=false -Dtest=RemainingFidelityTest,UserBatchImportTest,EmbeddedTokenAuthenticatorTest,ApiKeyTokenAuthenticatorTest,DynamicDatasourceResolverTest,I18nMessageConvergenceTest,McpQuestionReturnImgTest,OpenApiDocsEndpointTest,DatasourceCryptoTest -Dsurefire.failIfNoSpecifiedTests=false test | . | passed | 0 | 26685 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- askdata-server 全量测试（仓库根 -pl）: passed — Tests run: 608, Failures: 0, Errors: 0, Skipped: 5 (BUILD SUCCESS)
- i18n 结构扫描（审计文件集）: passed — I18nMessageConvergenceTest 3/3，规则含 new DatasourceException / emitError / errors.add
- Accept-Language 切换: passed — notLogin 401 正文中英不同且各自正确
- datasource 配置解码回退: passed — DatasourceCryptoTest 5/5，含 frontendCipherStillDecodesWithDifferentAppSecret
- A1-A3 定向回归: passed — RemainingFidelityTest 11/11
- A5 文档站: passed — OpenApiDocsEndpointTest 7/7
- 已知限制: F-11 SSO 登出分支仅做本地会话清理（平台侧会话管理不在社区版范围）。
- 已知限制: A4 的迁移范围是审计 §3 清单及其同族服务；datasource 引擎（JdbcEngine/ExcelService/SqlSafety/TableRelationService）的 DatasourceException 文案沿用 SQLBot 既有 ds.* 键域，未纳入本轮清单。
- 已知限制: A7 的回归断言覆盖决策函数与调用点门控，未做 MCP 响应 JSON 级断言。

## 阻塞项

- **user**: The generic Skill bridge cannot prove an independent Verifier execution; user confirmation is required before Archive. — next: `await-user`

## 风险与跳过的工作

- F-17 用户 Excel 导入列集仍为 4 列（account/name/password/email），SQLBot 为 8 列（account/name/email/workspace/role/status/origin/platform_user_id），SQLBot 模板无法直接导入。按 spec §4『原 4 列扩展项在 brief/verify 复核对齐口径后纳入或声明豁免理由』，本轮 verify 声明豁免，理由：askdata 导入显式要求 password 并强制 PWD_REGEX 强度（SQLBot 依赖默认口令，而 F-04 已刻意去掉可预测默认口令），workspace/role/origin 由调用管理员身份推导；若按 SQLBot 列集直导等于开放跨工作空间/角色指派面。属低优先级，需用户确认；若不接受该豁免需回 Build 扩列。
- F-07 的 CHAT 资源名映射仍缺（TABLE_MAP 13 项无 CHAT，AuditResourceNameResolverTest.unknownModuleReturnsNull 固化此行为）；当前全仓无 AuditModule.CHAT 写入点，暂无可见影响。
- A5 的『前端 API KEY 页文档入口可打开』无法在本仓库验证——仓库仅含 askdata-server，无前端模块；仅后端 /doc.html、/v3/api-docs、/docs、/openapi.json 可达性已实测。
- A7 回归只断言 shouldReturnImage 决策函数，未做 MCP 响应 JSON 级『无 imageUrl 字段』断言；字段缺失由 AskdataMcpTools.java:282-291 门控保证。
- §3 清单外仍有面向用户的硬编码文案（JdbcEngine/ExcelService/SqlSafety/TableRelationService 的 DatasourceException、ChatQuestionService.emitError 中文、ChatService 的 BadRequestException），不在本轮 A4 审计文件集范围。
- Builder 自述 DatasourceCryptoTest 5/5，实际该类 4 个用例；另首次非 clean 运行因 target/test-classes 残留只报 354 用例，clean 重跑与 Runtime 刷新检查均为 608 用例，验收以 clean/Runtime 结果为准。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | fail | A2, A4 | 独立验收 7 项：A1、A3、A5、A6、A7 通过；A2 因「工作空间成员移除」审计行 resource_id/resource_name 为 NULL 未达标；A4 因 UserService.java:362 仍保留 §3 清单内的硬编码英文文案（扫描测试字面量清单漏项导致假绿）未达标。全量测试 605 例全绿但不足以覆盖上述两点，故 verdict=fail。 | 2026-10-23T14:49:49.977Z |
| 1 | 2 | 0 | recovery | — | Runtime checks failed: full-test-suite. Preserve their receipts and submit a new Builder candidate after repair. | 2026-10-23T14:59:38.209Z |
| 1 | 3 | 1 | fail | A4 | 独立复跑全量测试 606/0/0/5 通过；A1、A2、A3、A5、A6、A7 均有实现与测试证据，A2 的资源名与 A4 的 §3 表内文案确已修复。但 A4 未真正清零：ApiExceptionHandler.java:48 的 401 正文仍为硬编码英文且不随 Accept-Language 切换，§3 文件集内还有多处面向用户的硬编码英文被扫描规则漏检，判定 A4 failed，本轮 verdict=fail。 | 2026-10-23T15:07:59.503Z |
| 1 | 4 | 0 | recovery | — | Runtime checks failed: full-test-suite. Preserve their receipts and submit a new Builder candidate after repair. | 2026-10-23T15:17:51.180Z |
| 1 | 5 | 1 | pass | — | A1-A7 全部通过：A4 的 401/i18n 硬编码收敛经独立字面量审计与规则探针确认清零，错误响应随 Accept-Language 切换；Runtime 刷新检查 full-test-suite 与 acceptance-focused 均 exit 0，clean 全量 608 用例 0 失败 0 错误（5 跳过）BUILD SUCCESS。F-17 Excel 列集按 spec §4 在 verify 声明豁免并记入 risks。 | 2026-10-23T15:39:37.485Z |



## 结论

A1-A7 全部通过：A4 的 401/i18n 硬编码收敛经独立字面量审计与规则探针确认清零，错误响应随 Accept-Language 切换；Runtime 刷新检查 full-test-suite 与 acceptance-focused 均 exit 0，clean 全量 608 用例 0 失败 0 错误（5 跳过）BUILD SUCCESS。F-17 Excel 列集按 spec §4 在 verify 声明豁免并记入 risks。
