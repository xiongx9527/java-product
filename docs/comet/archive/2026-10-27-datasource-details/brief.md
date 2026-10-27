# datasource-details

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A18: A18 数据源类型细节：Hive 支持 LDAP 认证参数；SQL Server `lowVersion` 配置影响 URL/驱动参数；ES 表/字段同步保留 `_meta.description` 注释；MySQL `allowPublicKeyRetrieval` 仅在配置开启时追加；Oracle 可按配置启用 thick 模式；连接池按配置 recycle；`MAX_TABLES=10`。
- A19: A19 Excel 导出与类型推断：Excel 导出数字列写为数值单元格而非文本，可求和；类型推断不再只采样 100 行且保留 float 语义；结果列名按大小写不敏感归一；上述行为有导出/导入回归测试。
- A20: A20 表级向量召回：表级向量召回在远程 embedding 不可用时回退到本地 embedding（与 EmbeddingService 相同的本地模型路径），仍不可用才退化为词法；命中远程时优先远程向量。
- A8: A8 Excel 数据源与多 sheet：PostgreSQL/MySQL 元数据库下 Excel 数据源字段同步使用固定默认 schema（不再用空 `db_schema`），`core_field` 有完整列；多 sheet Excel 每个 sheet 的表头只出现一次，第 2..N 个 sheet 的数据行不被当成表头。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
