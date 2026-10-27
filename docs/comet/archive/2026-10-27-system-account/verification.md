---
generated_from_state_version: 1
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-27T03:45:08.959Z
- 摘要: 独立复核 system-account 子任务：导入本地化表头/枚举与返回字段、token 默认 8 天可配置、SSO 登出联动 IdP、用户搜索相关性、内置变量可删除、模型连通性 protocol/Azure、isAdmin 角色判定、SAML2 三个配置端点均已实现；system-account-checks 全绿（exit 0，68s）。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A23 | passed | brief.md | A23 用户导入兼容：用户导入模板与解析使用本地化表头及“已启用/已禁用/本地创建”等枚举，兼容 SQLBot 模板；导入返回字段名与 SQLBot 一致，中文枚举值不再被判非法。 | UserImportService 使用 5 种语言本地化表头与已启用/已禁用/本地创建枚举，返回 successCount/errorCount/dataKey 与 SQLBot 一致；UserImportLocalizedTest/UserBatchImportTest 覆盖。 |
| A24 | passed | brief.md | A24 会话与系统细节：访问 token 默认 8 天且可配置；SSO 登出联动 IdP；用户搜索保留相关性排序；内置系统变量可删除；助手图片校验类型与大小；模型连通性测试使用 protocol/Azure 等配置；isAdmin 按角色而非 `id==1 && account=='admin'`。 | sa-token.timeout 默认 691200 且可用 ASKDATA_TOKEN_TIMEOUT 覆盖；SsoLogoutUrlResolver 让登出返回 IdP URL；UserRepository 相关性排序；内置系统变量可删；AiModelService 按 protocol/Azure 生成连通性 URL；isAdmin 按默认空间 sys_user_ws.weight>0 判定。 |
| A25 | passed | brief.md | A25 SAML2 配置端点：补齐前端调用的 `/setting/authentication/info/saml`、`/setting/authentication/save/saml`、`/setting/authentication/validate/saml2` 三个端点，读写 SAML2 配置并校验证书/元数据，配置缺失时返回可读错误。 | Saml2SettingsService/Controller 补齐 /setting/authentication/{info/saml,save/saml,validate/saml2}，校验证书与元数据，缺失时返回可读错误；Saml2SettingsTest 覆盖。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-server system account (A23/A24/A25) tests | -pl askdata/askdata-server test -Dmaven.test.skip=false -Dtest=UserImportLocalizedTest,SystemDetailsTest,Saml2SettingsTest,UserBatchImportTest,EmbeddedTokenAuthenticatorTest,RemainingFidelityTest,AuthenticationSsoLoginTest,AuthenticationServiceAdminGuardTest,PasswordStrengthAndSessionTest,LoginParameterRoundTripTest -DfailIfNoTests=false | . | passed | 0 | 68015 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- askdata-server system account (A23/A24/A25) tests: passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

独立复核 system-account 子任务：导入本地化表头/枚举与返回字段、token 默认 8 天可配置、SSO 登出联动 IdP、用户搜索相关性、内置变量可删除、模型连通性 protocol/Azure、isAdmin 角色判定、SAML2 三个配置端点均已实现；system-account-checks 全绿（exit 0，68s）。
