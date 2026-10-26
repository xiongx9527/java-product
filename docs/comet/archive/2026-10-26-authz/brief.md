# authz

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A6: A6 数据源端点授权：表关系 save/get 与推荐问题 list/base/save 要求 ws_admin 且数据源 `type='ds'`/资源授权，未授权用户被拒绝；`POST /datasource/execSql/{id}` 保留但要求 ws_admin，并在执行前应用 `hiddenColumnsFor` 与 `rowFilterWhereFor`，非 ws_admin 无法绕过行列权限。
- A7: A7 权限硬化：行权限按 varType 校验值域并对 kv 做 key→value 映射，越界值被拒绝；只读 SQL 校验基于 AST 函数节点而不是原文正则，字符串字面量中的 `version(` 不误拒、真实危险函数被拒；列权限在构造 schema/提示词/样例行之前剔除无权字段。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
