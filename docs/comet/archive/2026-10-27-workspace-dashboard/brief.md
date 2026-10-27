# workspace-dashboard

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A21: A21 仪表盘与工作空间：仪表盘列表/加载/保存/移动/删除按同工作空间可见即可操作，不再要求 `create_by=当前用户`；删除工作空间时用户 oid 重选或置 0，不再一律改成 1；术语/训练 `datasource_ids` 必须属于当前工作空间。
- A22: A22 审计日志三列：审计日志 `operation_type_name` 返回本地化标签而非 code，`operation_detail_info` 返回真实操作详情，`oid_name` 返回工作空间名称而非数字 id；前端审计列表三列正常显示。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
