# license

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A3: A3 License 校验与门控：用 `scripts/issue-license.sh` 与配置的 HMAC sign-key 签发 license；上传合法 license 返回 valid，篡改签名/过期/超量/版本不符返回 invalid/expired 并说明原因；`askdata.license.enforcement` 默认 bypass（企业能力可用），设为 enforce 后才拦截；前端企业入口读取真实状态与 enforcement，不再恒定隐藏。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
