# system-account

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A23: A23 用户导入兼容：用户导入模板与解析使用本地化表头及“已启用/已禁用/本地创建”等枚举，兼容 SQLBot 模板；导入返回字段名与 SQLBot 一致，中文枚举值不再被判非法。
- A24: A24 会话与系统细节：访问 token 默认 8 天且可配置；SSO 登出联动 IdP；用户搜索保留相关性排序；内置系统变量可删除；助手图片校验类型与大小；模型连通性测试使用 protocol/Azure 等配置；isAdmin 按角色而非 `id==1 && account=='admin'`。
- A25: A25 SAML2 配置端点：补齐前端调用的 `/setting/authentication/info/saml`、`/setting/authentication/save/saml`、`/setting/authentication/validate/saml2` 三个端点，读写 SAML2 配置并校验证书/元数据，配置缺失时返回可读错误。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
