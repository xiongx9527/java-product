# 数据源域补充缺口（datasource 审计，Shape 确认后追加）

> 来源：datasource 域并行审计的最终结果。Shape 已确认（shape_confirmation_hash 已绑定 brief+spec+children），
> 因此以下条目**不并入本次 change 的正式验收项**，留作 Build 阶段实现范围内的补强项，
> 或在需要时以 `--revise-requirements` 正式扩展。

## 致命级追加

| 缺口 | 证据 | 影响 |
| --- | --- | --- |
| `previewData` 行权限过滤恒为 null | `datasource/service/DatasourceService.java:377` `type.previewSql(fieldNames, tableName, conf, null)` | where 参数硬编码 null，行权限永不注入 |
| `fieldEnum` 按表 id 查字段（查找 bug） | `DatasourceService.java:392` `fieldRepository.listByTableId(fieldId)` | 把字段 id 当表 id 用，真实字段 id 恒返回空 |
| Hive 结果集解析整体缺失 | `datasource/engine/DatasourceType.java:477` tableSql=`SHOW TABLES`、fieldSql=`DESCRIBE`，无后处理 | SQLBot `apps/db/db.py:135,174` 的 `parse_hive_table_rows`/`parse_hive_field_rows`（含 Spark 三列 in-band header、`# Partition Information` 伪行过滤、分区列只保留一次）无对应物；SQLBot `tests/test_hive_fields.py` 7 组用例全不通过 |
| Spark Thrift 检测缺失 | `datasource/engine/DatasourceConf.java:158` `getEngine()` 全仓无消费者 | `conf.engine == 'spark'` 未被读取 |
| Hive 标识符反引号改写缺失 | `JdbcEngine.execSql` 无 `"x"` → `` `x` `` 改写 | LLM 生成的双引号 Hive SQL 执行失败 |
| SQL Server GBK 重解码缺失 | 全仓无 gbk 相关代码 | varchar 中的 GBK 中文返回乱码（SQLBot `apps/db/db.py:851`） |
| ES `_meta.description` 丢弃 | `JdbcEngine.java:538,558` 索引/字段描述硬编码 `""` | SQLBot `apps/db/es_engine.py` 会把 ES 索引/字段描述作为表/字段注释返回 |

## 降级级追加

| 缺口 | 证据 |
| --- | --- |
| `core_datasource.embedding` 无写入路径 | `DatasourceRepository` 无 `updateEmbedding`；`SchemaContextBuilder.selectDatasource` 每次问题现场重算 embedding，不用已存列 |
| 表级 embedding 缺 quoted-identifier 处理 | `TableEmbeddingService.java:255` 为裸子串搜索；SQLBot `table_embedding.py:39` 的 `_select_tables` 处理 `` `x` ``/`"x"`/`[x]` 并做最长重叠消解 |
| 表级向量文档格式与 SQLBot 不同 | `TableEmbeddingService.java:81` 为扁平文本；SQLBot 为 `# Table: x\n[\n(f:type, c)\n]`。语义等价但向量不可互换 |
| 混合排序第二项语义不同 | `TableEmbeddingService.java:45` 用 0.4·vector + 0.6·mention；SQLBot `table_embedding.py:119` 的 `calc_keyword_score` 是关键词分（表名全匹配 1.0/表注释 0.9/注释子串 0.7/字段注释 0.5/字段名 0.3），非提及命中 |
| 启动回填用裸 Thread 无上限 | `TableEmbeddingService.java:182` 每次调用新建 Thread；SQLBot `common/utils/embedding_threads.py:6` 为 200 线程池 |
| 数据源 CRUD 无审计 | `datasource/` 全模块零 `AuditRecorder` 调用，但 `AuditModule.DATASOURCE` 已定义 |
| `DatasourceConf` 三个死配置 | `isLowVersion()`(:135)、`getEngine()`(:158)、`getPoolSize()`(:150) 解析后从不消费 |
| Doris/StarRocks SSL 与超时语义不同 | 目标用 `useSSL=<bool>`，SQLBot 用 `ssl_mode=REQUIRE`；`connect_timeout`/`read_timeout` 缺失 |
| Kingbase `statement_timeout` 缺失 | SQLBot `apps/db/db.py:332` 设 `-c statement_timeout={timeout*1000}` |
| Redshift 复用 pg 驱动 | SQLBot 用 `redshift_connector`（`apps/db/db.py:315`），目标用 `org.postgresql.Driver` + 多余 `currentSchema` |
| SQL Server `lowVersion`→`tds_version=7.0` 缺失 | `DatasourceConf.isLowVersion()` 无消费者；SQLBot `apps/db/db.py:111` |
| 元数据查询开关缺失 | SQLBot `SQLBOT_ALLOW_METADATA_QUERIES`（默认 False，`common/core/config.py:118`）可放开 SHOW/DESCRIBE/DESC/EXPLAIN；目标恒拒 |
| 危险函数表缺 SQL Server `EXEC` | SQLBot `DS_SPECIFIC_DANGEROUS_FUNCTIONS` 含 `EXEC`；目标只有 `xp_cmdshell`/`sp_executesql` |
| Hive `ADD FILE`/`ADD JAR` 未拦截 | SQLBot `check_sql_read` 覆盖 |
| Oracle/CK/DM/Hive 数值类型识别退化 | `JdbcEngine.java:845` `isNumeric(int)` 只看 `java.sql.Types`；SQLBot 按类型名识别（`apps/db/db.py:747`） |
| schema 文本缺 `【Foreign keys】` 块 | SQLBot `apps/datasource/crud/datasource.py:531` 依据 `ds.table_relation` 拼接外键块；目标 `SchemaContextBuilder.buildSchemaText` 无此段 |
| schema 文本缺 `no_schema_types` 规则 | mysql/es/sqlite/hive/doris/starrocks 不带 schema 前缀 |
| 样例行无 100 字符截断与换行清理 | `SchemaContextBuilder.sampleRowsText`(:494)；SQLBot `crud/datasource.py:458` |
| `getSchemaByConf` 对 excel 返回空 | 目标返回 `[]`；SQLBot 返回应用库 `pg_namespace` |
| 无缓存层 | SQLBot `clear_ws_ds_cache` / `@cache(DS_ID_LIST)`（`crud/datasource.py:654`） |
| 审计装饰器未覆盖数据源域 | 见上 |

## 已核验为 FULL 的数据源能力（无需补齐）

- `parseExcel` / `importToDb`（含 int HALF_UP、Int64 nullable、63 字节标识符检查、CJK 表名过滤、`na_rep='\N'`）/ `exportDsSchema` / `uploadDsSchema` / `uploadExcel` / `table_relation` / `recommended_problem` 全部语义等价。
- 14 种方言类型全部在 `DatasourceType` 注册，无整类缺失（11 种为 PARTIAL，pg/excel 为 FULL）。
- AES 加解密（前端 `SQLBot1234567890` key）与 SQLBot 一致。
