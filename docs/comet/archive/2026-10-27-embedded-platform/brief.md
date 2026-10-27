# embedded-platform

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A10: A10 平台同步安全：平台同步请求缺少 origin 或 origin=0 时被拒绝并给出明确错误；同步新建用户不再写 `md5("")`，默认口令随机/不可用；origin≠0 的同步用户无法用空口令本地登录。
- A11: A11 嵌入签名协议：嵌入签名使用 SQLBot AES 信封（`assistant_id/target/sign_time/webdriver/user_agent/timezone`），校验 Origin 相等、±10s 窗口、UA 逐字、时区一致；AES key 可配置；旧 `id.timestamp.hmac` 仅由显式兼容开关启用，默认关闭。
- A17: A17 嵌入助手与动态数据源：嵌入页按 token 中的 `assistant_id` 取助手；type=1 高级应用实时请求宿主 API 获取 `ds_list`，失败时不回退到“当前用户第一个 type=0 助手”；`X-SQLBOT-ASSISTANT-ONLINE` 运行时在线/离线通道生效；宿主下发数据源支持 AES 解密与 cookie/param 凭证位。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
