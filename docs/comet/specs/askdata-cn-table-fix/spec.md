# askdata-cn-table-fix 完整目标规格

本文描述本次修复后 askdata（问数）在「中文表名数据源授权」与「Excel 导入」两处的行为。除本文明确变更的部分外，其余行为沿用已归档的 askdata 正式规格（`docs/comet/specs/askdata/spec.md`）。

## 1. 对话授权中的表名提取与比对（修复中文表名误判）

### 场景：模型生成的 SQL 引用中文表名

`POST /chat/question` 主链路生成 SQL 后，需校验 SQL 只使用数据源已勾选的表（`ChatQuestionService` 的授权校验）。

- 表名提取（`usedTables`）在 `FROM` / `JOIN` 之后取表名记号，并做**字符白名单清洗**：保留 Unicode 字母、Unicode 数字与下划线，即规则为 `[^\p{L}\p{N}_]`（原实现为 `[^a-z0-9_]`，会把中文等所有非 ASCII 字符删除）。
- 提取结果与授权名单的比对**大小写不敏感**（SQL 与名单均归一化后比较）。
- 清洗规则对中文表名与 ASCII 表名行为一致：
  - 输入 `... FROM EXCEL_销售数据_319C13A236 ...` → 提取 `excel_销售数据_319c13a236`（中文字符保留），可命中授权名单，校验通过。
  - 输入 `... FROM excel_sales_23c13ee62a ...` → 提取 `excel_sales_23c13ee62a`，与修复前行为一致。
- **授权范围不变**：仍仅允许该数据源已勾选的表。不属于已勾选名单的表（无论中文或 ASCII）仍被拒绝，并流式返回 `error` 帧，文案为 `SQL 使用了未授权的表: <表名>`；`<表名>` 应为清洗后的真实表名，不得出现字符被剥离的畸形名（修复前会退化成 `excel__319c13a236` 这种缺失中间字符的形态）。

验收：A1、A2

## 2. Excel 导入的文本列（修复长度上限）

### 场景：导入含长文本的 Excel

`POST /datasource/importToDb` 建表时，文本列（`columnType("string")`）的类型由 `VARCHAR(1024)` 改为 **`VARCHAR(65535)`**（常量 `ExcelService.STRING_COLUMN_LENGTH = 65535`）。

- 单元格文本长度在 65535 以内时可正常导入；`previewData` 与 `execSql` 能读出完整文本（含中文）。
- 元数据（`getFields` / `fieldList`）中该列类型显示为 VARCHAR 系（H2 下为 `CHARACTER VARYING(65535)`），不改变元数据展示形态。
- 其余列类型映射（`int`→BIGINT、`float`→numeric、`datetime`→timestamp 等）保持不变。
- 文本列不使用 CLOB：避免列类型在元数据中变为 CLOB，从而无需在导出、图表等其它查询路径上额外处理大对象。

验收：A3

## 3. 导入失败时的表清理（修复残留空表）

### 场景：建表后插入数据失败

`importToDb` 的 `createAndInsert` 在**插入阶段**抛错时，回滚删除本次已创建的表，再向上抛出 `DatasourceException`（文案沿用 `Insert data failed for <table>: <原因>`）。

- 失败后应用库中不残留该次导入建立的空表。
- 回滚删除沿用现有 `executeAppDdl("DROP TABLE IF EXISTS " + engine.appQuote(storedTable))` 写法，使用与建表一致的标识符规范化（`JdbcEngine.appIdentifier`：H2→大写、PG→小写），确保删到同一张表。
- 建表前的 `DROP TABLE IF EXISTS`（重名覆盖场景）语义不变。
- 多 sheet 导入时，某个 sheet 失败即回滚该 sheet 对应的表，不影响已成功导入的其它 sheet 数据。

验收：A4

## 4. 约束与非目标

- 不改 `POST /chat/question` 的流式帧格式与事件语义（`data:{json}\n\n` 及 `type` 取值保持不变）。
- 不放宽对话授权范围；不得引入越权查询。
- 不重构对话主链路、Excel 导入流程或流式协议。
- 不处理其它已记录风险（PostgreSQL 端到端、MyBatis-Plus 约束文字偏差、SPA 兜底、README 扩展目录说明、Ollama usage 上报、ai_model 种子）。

## 5. 回归基线

- 既有单测全部通过（基线 78/78）。
- Excel 与 H2 数据源的「建表 → getTables/getFields → previewData → execSql」链路保持可用。
- ASCII 表名的对话链路行为与修复前完全一致。

验收：A5
