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
- 完成时间: 2026-10-27T02:47:40.282Z
- 摘要: 独立只读复核 a2a806e 源码与 Runtime 检查结果：A11 AES-GCM 信封校验 origin/target、±10s 窗口、UA/时区逐字、webdriver 拒绝，密钥缺失可读报错，旧 HMAC 默认关闭；A10 平台同步拒绝缺失/0 origin 且新建用户写随机不可用口令；A17 按 token assistant_id 解析助手、type=1 在线实时取宿主 ds_list 且失败不回退、X-SQLBOT-ASSISTANT-ONLINE 切换通道、宿主凭证 header/cookie/param 与 AES 解密。Runtime embedded-platform-checks 全部通过。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A10 | passed | brief.md | A10 平台同步安全：平台同步请求缺少 origin 或 origin=0 时被拒绝并给出明确错误；同步新建用户不再写 `md5("")`，默认口令随机/不可用；origin≠0 的同步用户无法用空口令本地登录。 | PlatformSyncService 对缺失/空白/0 origin 抛 BadRequestException 且不落库；新建用户写入随机 UUID 口令哈希（测试断言非 md5("")）；LoginController 对 origin!=0 用户拒绝空口令登录。 |
| A11 | passed | brief.md | A11 嵌入签名协议：嵌入签名使用 SQLBot AES 信封（`assistant_id/target/sign_time/webdriver/user_agent/timezone`），校验 Origin 相等、±10s 窗口、UA 逐字、时区一致；AES key 可配置；旧 `id.timestamp.hmac` 仅由显式兼容开关启用，默认关闭。 | EmbeddedSignatureVerifier 校验 AES 信封 target==origin、±10s、UA/时区逐字、webdriver 拒绝；askdata.embedded.aes-key 可配置，未配置时报可读错误；legacy-hmac-enabled 默认 false，旧 id.timestamp.hmac 仅显式开启时生效。 |
| A17 | passed | brief.md | A17 嵌入助手与动态数据源：嵌入页按 token 中的 `assistant_id` 取助手；type=1 高级应用实时请求宿主 API 获取 `ds_list`，失败时不回退到“当前用户第一个 type=0 助手”；`X-SQLBOT-ASSISTANT-ONLINE` 运行时在线/离线通道生效；宿主下发数据源支持 AES 解密与 cookie/param 凭证位。 | datasources 优先使用 AuthContext 中 token 的 assistant_id；type=1 在线实时拉取宿主 ds_list，宿主失败直接报错不回退；X-SQLBOT-ASSISTANT-ONLINE 覆盖 online 通道；宿主 certificate 支持 header/cookie/param，加密凭证与密码用 AES 解密。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-server embedded platform (A10/A11/A17) tests | -pl askdata/askdata-server test -Dmaven.test.skip=false -Dtest=EmbeddedSignatureVerifierTest,PlatformSyncServiceTest,HostApiDatasourceTest,EmbeddedAssistantDsChannelTest,AssistantDsEndpointTest,EmbeddedTokenAuthenticatorTest -DfailIfNoTests=false | . | passed | 0 | 42486 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- askdata-server embedded platform (A10/A11/A17) tests: passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

独立只读复核 a2a806e 源码与 Runtime 检查结果：A11 AES-GCM 信封校验 origin/target、±10s 窗口、UA/时区逐字、webdriver 拒绝，密钥缺失可读报错，旧 HMAC 默认关闭；A10 平台同步拒绝缺失/0 origin 且新建用户写随机不可用口令；A17 按 token assistant_id 解析助手、type=1 在线实时取宿主 ds_list 且失败不回退、X-SQLBOT-ASSISTANT-ONLINE 切换通道、宿主凭证 header/cookie/param 与 AES 解密。Runtime embedded-platform-checks 全部通过。
