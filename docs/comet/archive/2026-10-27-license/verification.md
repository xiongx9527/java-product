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
- 完成时间: 2026-10-27T02:11:38.847Z
- 摘要: Independently reviewed candidate 5a71b29 for A3. scripts/issue-license.sh issues an HMAC-SHA256 signed license using the configured askdata.license.sign-key; LicenseService.upload verifies the signature and rejects tampered/expired/over-quota/version-mismatch licenses with reasons; an unconfigured sign-key now fails closed with a readable hint (random-key fallback removed); askdata.license.enforcement (bypass|enforce) gates enterpriseEnabled() and status() exposes enforcement+enterprise. LicenseServiceTest (14 tests incl. issuedByScriptIsAccepted) passed via the Runtime license-checks executor (exitCode 0).

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A3 | passed | brief.md | A3 License 校验与门控：用 `scripts/issue-license.sh` 与配置的 HMAC sign-key 签发 license；上传合法 license 返回 valid，篡改签名/过期/超量/版本不符返回 invalid/expired 并说明原因；`askdata.license.enforcement` 默认 bypass（企业能力可用），设为 enforce 后才拦截；前端企业入口读取真实状态与 enforcement，不再恒定隐藏。 | issue-license.sh + HMAC sign-key issuance verified (issuedByScriptIsAccepted); valid->valid and tampered/expired/over-quota/version-mismatch->invalid/expired with reasons covered by LicenseServiceTest; fail-closed on missing sign-key; enforcement bypass/enforce gating covered; GET /system/license returns real status+enforcement+enterprise for the frontend. |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-server license A3 tests | -pl askdata/askdata-server test -Dmaven.test.skip=false -Dtest=LicenseServiceTest | . | passed | 0 | 26524 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- askdata-server license A3 tests: passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

Independently reviewed candidate 5a71b29 for A3. scripts/issue-license.sh issues an HMAC-SHA256 signed license using the configured askdata.license.sign-key; LicenseService.upload verifies the signature and rejects tampered/expired/over-quota/version-mismatch licenses with reasons; an unconfigured sign-key now fails closed with a readable hint (random-key fallback removed); askdata.license.enforcement (bypass|enforce) gates enterpriseEnabled() and status() exposes enforcement+enterprise. LicenseServiceTest (14 tests incl. issuedByScriptIsAccepted) passed via the Runtime license-checks executor (exitCode 0).
