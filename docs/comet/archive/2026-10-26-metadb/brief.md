# metadb

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A9: A9 元数据库迁移：元数据库由 Flyway 版本化迁移管理，H2/PostgreSQL/MySQL 均可从空库启动并写入种子数据；`schema.sql` 不再由 `spring.sql.init` 执行；PostgreSQL 用 embedded-postgres 真库测试，MySQL 有方言单测；切换元数据库只需改连接配置并完成迁移。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
