# 目标

修复 askdata（问数）归档后遗留、且会实际阻断使用或造成脏数据的缺陷，让**中文表名的数据源**（尤其是 Excel 导入建表）能在对话链路中被正常授权与查询，并提升 Excel 导入对长文本的容忍度与失败后的可恢复性。同时，将 askdata 根 POM 改为继承 `fw-plus-ai-build`，统一接管 fw-plus-ai 相关依赖与版本管理（此前 askdata 未继承该模块，ai 相关依赖版本散落在子模块）。

背景：askdata 已于 2026-10-18 归档（A1–A13 全部验收通过）。最终验收记录的风险中，以下三项会实际影响使用：

- **中文表名授权误判**：`ChatQuestionService.usedTables` 用 `replaceAll("[^a-z0-9_]","")` 提取 SQL 中的表名，非 ASCII（中文）字符被剥离，导致中文表名的数据源即使模型生成了合法 SQL，也被判为「SQL 使用了未授权的表」。实测复现：表名 `EXCEL_销售数据_319C13A236` 被提取成 `excel__319c13a236`，与授权名单不匹配；改用 ASCII 表名则全链路正常。
- **Excel 文本列长度上限**：导入建表时文本列固定为 `VARCHAR(1024)`，单元格文本超过 1024 字符即插入失败（`Value too long for column`）。
- **导入失败残留空表**：插入失败后，已 DDL 建立的表残留在应用库且不归属任何数据源，缺少回滚/清理入口。

# 范围

## 目录结构

### 新建

无（预计仅修改现有文件并补充测试）

### 修改

- `pom.xml`（根聚合 POM，artifactId 由 `askdata-root` 改为 `java-product`、`name` 改为 `Java Product Root`，groupId 维持 `com.xiongx9527.askdata`）— 作为通用聚合根容纳 askdata 及后续项目；新增 `<parent>` 继承 `fw-plus-ai-build`（`groupId=com.xiongx9527.fw.plus.ai`，`version=${revision}`），移除指向本地源码的 `<relativePath>`；使全模块继承 fw-plus-ai 的版本与依赖管理。
- `askdata/pom.xml` — 父引用 `<artifactId>` 由 `askdata-root` 改为 `java-product`。
- `askdata/askdata-server/pom.xml` — 因根 POM 已继承 `fw-plus-ai-build`，去掉由父管理的 `fw-plus-ai-model` 等显式 `<version>`，清理与父 POM 重复的 `dependencyManagement` 条目（保留 `fw-plus-boot-dependencies` BOM 等仍需要的项）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatQuestionService.java` — 修正 `usedTables`（:544-556，问题在 :550）的表名提取，保留 Unicode 字母/数字/下划线，使中文表名能与授权名单正确比对。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/excel/ExcelService.java` — 文本列长度：`STRING_COLUMN_LENGTH`(:63) 由 1024 改为 65535、`columnType`(:624-635) 相应生效；`createAndInsert`(:579) 插入失败分支回滚删除本次已建表。
- 相应单元测试 — 中文表名提取/授权用例，以及纳入范围内的长文本与失败清理用例。

### 删除

无

### 明确不建

- 不改对话授权的整体策略（仍按数据源已勾选的表名单授权），仅修正表名提取与比对。
- 不改流式协议帧格式与字段语义。
- 不新增独立模块或配置项。

# 非目标

- 不重构对话主链路或 Excel 导入流程。
- 不处理其它已记录但属独立需求的风险：PostgreSQL 端到端、约束文字 MyBatis-Plus 与实际 JdbcTemplate 的偏差、SPA 兜底、README 扩展目录说明、Ollama usage 上报、ai_model 种子。

# 验收示例

- A1 中文表名可授权并查询：数据源中存在中文表名（如 `EXCEL_销售数据_xxx`）时，`POST /chat/question` 不再返回「SQL 使用了未授权的表」，能正常产出 SQL 并返回表格数据（`sql`/`sql-data`/`finish` 帧）；ASCII 表名行为不变。
- A2 不越权：不属于该数据源/未勾选的表名（含中文）仍被拒绝，且拒绝信息中的表名与实际表名一致，不出现字符被剥离的畸形名。
- A3 长文本可导入：Excel 单元格文本超过 1024 字符（如 1500 字符）时，`importToDb` 成功且 `previewData`/`execSql` 能读出完整文本；元数据列类型显示为 VARCHAR(65535)。
- A4 失败不残留：构造导入失败场景（如超长文本之外的失败路径或强制失败）后，应用库中不残留该次已建的空表。
- A5 回归：既有单测全通过（基线 78/78），Excel 与 H2 数据源的建表→查询→预览→执行链路仍可用。
- A6 父 POM 继承与重命名：根 `pom.xml` 以 `fw-plus-ai-build` 为父（无本地 `<relativePath>`），artifactId 已为 `java-product`、`askdata/pom.xml` 父引用同步更新；`mvn -q -pl askdata/askdata-server dependency:tree` 显示其继承自 `com.xiongx9527.fw.plus.ai:fw-plus-ai-build`；全量 `mvn compile`/`mvn test` 通过（`fw-plus-ai-build` 须已 `mvn install` 至本地仓库或由 reactor 聚合）。

# Constraints and invariants

- Java 8、Spring Boot 2.7.18；沿用现有分层与命名风格。
- 根 POM 改继承 `fw-plus-ai-build` 后，仍须满足 Java 8 / Spring Boot 2.7.18 约束；不得因父 POM 的版本管理引入不兼容升级，且 `fw-plus-ai-model` 等 ai 依赖行为不得改变。
- 表名提取与授权比对必须大小写不敏感，且对中文表名与 ASCII 表名行为一致。
- **不得放宽授权**：仅允许数据源已勾选的表，修复不得引入越权。
- 不改流式协议帧格式与字段语义。

# Decisions

- D1 修复范围（用户确认，Q1）：三项全部纳入——① 中文表名授权正则；② Excel 文本列长度；③ 导入失败残留空表清理。
- D2 Excel 文本列方案（用户确认，Q2）：**提高 VARCHAR 长度**而非改用 CLOB。理由：改动最小、元数据列类型仍为 VARCHAR、与现有 `convertValue` 无耦合；CLOB 方案会让元数据列类型变为 CLOB 并需逐条复核导出/图表等查询路径。
- D3 VARCHAR 具体长度：由 1024 提高到 **65535**（`ExcelService.STRING_COLUMN_LENGTH`）。H2 VARCHAR 支持到非常大，65535 为常规上限，足以覆盖 Excel 单元格；仍保留固定列类型，不改变元数据展示形态。
- D4 残留空表处理（实现选择）：在 `createAndInsert` 的插入失败分支中**回滚删除本次已建表**（沿用现有 `executeAppDdl("DROP TABLE IF EXISTS ...")` 写法）。理由：该表本就是本次导入创建的，失败即无意义；回滚比新增清理端点改动更小、且不留脏数据。
- D5 授权语义不变：`usedTables` 的提取规则修正后，仍严格按数据源已勾选的表名单授权，且大小写不敏感；修复只解决「中文表名被剥离导致误判」，不得放宽越权。
- D6 父 POM 继承与根重命名（用户确认）：最外层根聚合 POM 由 `askdata-root` 重命名为 `java-product`（通用聚合根，后续容纳多项目，groupId 维持 `com.xiongx9527.askdata`），并改为继承 `fw-plus-ai-build` 以统一接管 fw-plus-ai 相关依赖/版本管理；不保留指向 `../../fw-plus-ai/pom.xml` 的源码 `<relativePath>`，父 POM 来自本地仓库（需先 install）或 reactor；`askdata/pom.xml` 父引用同步改名。具体继承哪一个 fw-plus-ai 源仓库、以及 `revision` 属性取值，实现时按与现有 `0.1.1-SNAPSHOT` 版本线对齐确认。

# Verification expectations

- 需实际启动应用做端到端验证：至少覆盖「中文表名数据源走 /chat/question 全链路」与「Excel 导入→查询→预览→执行」两条路径。
- 本机 HTTP 验证须走 IPv6 回环 `http://[::1]:<port>`（沙箱代理只占 IPv4 127.0.0.1，会拦截 POST）；运行 jar 从仓库外临时目录启动以免 H2 `./data` 污染工作区。
