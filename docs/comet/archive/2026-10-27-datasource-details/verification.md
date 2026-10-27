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
- 完成时间: 2026-10-27T03:16:29.619Z
- 摘要: 独立只读复核 69f1b1a 的 diff 与 Runtime 检查结果：A8 Excel 元数据查询使用固定默认 schema（PG public/H2 PUBLIC/SQL Server dbo/MySQL catalog），多 sheet 独立表头并有回归；A18 MySQL allowPublicKeyRetrieval 改为按配置、SQL Server lowVersion 兼容参数、Hive LDAP、Oracle oci thick、连接池 recycle、MAX_TABLES=10、ES _meta.description；A19 数值单元格/全量类型推断保留 float/列名大小写归一；A20 远程→本地→词法回退且结构一致。Runtime datasource-details-checks 全部通过。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A18 | passed | brief.md | A18 数据源类型细节：Hive 支持 LDAP 认证参数；SQL Server `lowVersion` 配置影响 URL/驱动参数；ES 表/字段同步保留 `_meta.description` 注释；MySQL `allowPublicKeyRetrieval` 仅在配置开启时追加；Oracle 可按配置启用 thick 模式；连接池按配置 recycle；`MAX_TABLES=10`。 | MySQL allowPublicKeyRetrieval 仅显式开启时追加；SQL Server lowVersion 追加旧版兼容参数；Hive 有口令时拼 auth=LDAP/user；Oracle 支持 oci thick URL；PoolSettings 增加 recycleMs 并在借出时回收超龄空闲连接；SchemaContextBuilder MAX_TABLES=10；ES 同步读取 _meta.description 作注释。 |
| A19 | passed | brief.md | A19 Excel 导出与类型推断：Excel 导出数字列写为数值单元格而非文本，可求和；类型推断不再只采样 100 行且保留 float 语义；结果列名按大小写不敏感归一；上述行为有导出/导入回归测试。 | ChatExcel 数字/布尔写为原生单元格，列名大小写不敏感归一且重复列名追加序号；ExcelService 类型推断全量扫描并保留 float；ExcelServiceTest/ChatExcelTest/ChatExportParityTest 覆盖。 |
| A20 | passed | brief.md | A20 表级向量召回：表级向量召回在远程 embedding 不可用时回退到本地 embedding（与 EmbeddingService 相同的本地模型路径），仍不可用才退化为词法；命中远程时优先远程向量。 | TableEmbeddingService 注入 EmbeddingService，embed 先远程、失败回退同一本地模型、再不可用才词法并记录降级原因；rankTables 三路径统一返回 List<Long>。 |
| A8 | passed | brief.md | A8 Excel 数据源与多 sheet：PostgreSQL/MySQL 元数据库下 Excel 数据源字段同步使用固定默认 schema（不再用空 `db_schema`），`core_field` 有完整列；多 sheet Excel 每个 sheet 的表头只出现一次，第 2..N 个 sheet 的数据行不被当成表头。 | JdbcEngine.metadataConf 对应用库类型补固定默认 schema（defaultAppSchema 按方言），Excel 字段/表同步使用该配置；ExcelService 按 sheet 独立读取表头，ExcelServiceTest 覆盖多 sheet 表头只一次与列序/类型一致。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-server datasource details (A8/A18/A19/A20) tests | -pl askdata/askdata-server test -Dmaven.test.skip=false -Dtest=DatasourceTypeStrategyTest,ConnectionPoolSettingsTest,ConnectionPoolManagerTest,EsEngineTest,ExcelServiceTest,ChatExcelTest,ChatExcelCsvTest,TableEmbeddingServiceTest,ChatRagTest,JdbcEngineParameterizedTest,JdbcEngineSmokeTest,JdbcEngineDriverAvailabilityTest,DatasourceServiceSmokeTest,ChatExportParityTest -DfailIfNoTests=false | . | passed | 0 | 45381 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- askdata-server datasource details (A8/A18/A19/A20) tests: passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

独立只读复核 69f1b1a 的 diff 与 Runtime 检查结果：A8 Excel 元数据查询使用固定默认 schema（PG public/H2 PUBLIC/SQL Server dbo/MySQL catalog），多 sheet 独立表头并有回归；A18 MySQL allowPublicKeyRetrieval 改为按配置、SQL Server lowVersion 兼容参数、Hive LDAP、Oracle oci thick、连接池 recycle、MAX_TABLES=10、ES _meta.description；A19 数值单元格/全量类型推断保留 float/列名大小写归一；A20 远程→本地→词法回退且结构一致。Runtime datasource-details-checks 全部通过。
