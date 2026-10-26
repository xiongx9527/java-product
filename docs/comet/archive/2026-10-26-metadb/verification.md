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
- 完成时间: 2026-10-26T11:45:52.436Z
- 摘要: 独立复核候选 2d116f4（repair 后新检查 action 21 已重跑并通过）：Flyway 三方言 baseline + common 种子接管元数据库，schema.sql 删除且 sql.init=never，PG 用 embedded-postgres 真库、MySQL 有方言单测。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A9 | passed | brief.md | A9 元数据库迁移：元数据库由 Flyway 版本化迁移管理，H2/PostgreSQL/MySQL 均可从空库启动并写入种子数据；`schema.sql` 不再由 `spring.sql.init` 执行；PostgreSQL 用 embedded-postgres 真库测试，MySQL 有方言单测；切换元数据库只需改连接配置并完成迁移。 | pom.xml:370-379 引入 flyway-core/postgresql/mysql；application.yaml:42-52 配置 locations=common+{vendor}、baseline-on-migrate、baseline-version=2、sql.init.mode=never；schema.sql 已删除并重命名为 db/migration/h2/V1__baseline.sql，种子在 db/migration/common/V2__seed.sql；h2/postgresql/mysql 三份 baseline 各 35 张表且旧 ALTER 列已并入；H2MetadbMigrationTest.java:25-51、PostgresMetadbMigrationTest.java:59-159（EmbeddedPostgres 真库 + 真实登录问数）、MysqlMetadbDialectTest.java:38-58 覆盖三条路径；application-postgresql.yaml 与 application-mysql.yaml 仅覆盖连接配置即可切换。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| A9 元数据库迁移聚焦测试（H2/MySQL/PostgreSQL） | -c export JAVA_HOME=$(/usr/libexec/java_home -v 17); exec mvn -Dmaven.test.skip=false -Dtest=H2MetadbMigrationTest,MysqlMetadbDialectTest,PostgresMetadbMigrationTest,NoHardcodedSecretsTest,RemainingFidelityTest test | askdata/askdata-server | passed | 0 | 48099 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- A9 元数据库迁移聚焦测试（H2/MySQL/PostgreSQL）: passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

独立复核候选 2d116f4（repair 后新检查 action 21 已重跑并通过）：Flyway 三方言 baseline + common 种子接管元数据库，schema.sql 删除且 sql.init=never，PG 用 embedded-postgres 真库、MySQL 有方言单测。
