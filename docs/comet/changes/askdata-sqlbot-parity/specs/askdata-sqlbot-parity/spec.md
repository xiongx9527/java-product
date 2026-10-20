# askdata-sqlbot-parity 完整目标规格

> 本规格描述归档后 askdata 相对 SQLBot 开源社区版补齐的能力的**完整目标行为**。
> 除本文明确新增/变更的部分外，其余行为沿用已归档的正式规格：
> `docs/comet/specs/askdata/spec.md`、`docs/comet/specs/askdata-sqlbot-gap/spec.md`、
> `docs/comet/specs/askdata-cn-table-fix/spec.md`、`docs/comet/specs/askdata-mcp-framework/spec.md`。

## 1. 表级向量召回（RAG）

问数主链路为模型组装 schema 上下文时，按“问题与表的相关度”选表，而不是固定取前 N 张勾选表。

### 场景：表文档与向量计算

对每个数据源下已勾选（checked）的表生成表文档（表名 + 表注释 + 字段名 + 字段注释，与现有 `tableDoc` 口径一致），经默认 embedding 模型（`ChatModelRouter.embeddingModel()`，`model_type=1`）计算向量并写入 `core_table` 的增量向量列。向量计算失败或模型不可用时该表向量为空，不阻断其它表。

验收：A1

### 场景：按相关度选表

用户在问数时，系统对当前数据源已勾选表做混合排序后取 TOP-N（沿用 `MAX_TABLES` 常量，默认 8）：

- **标识符边界感知匹配**：提问中显式提到的表名（含中文表名、`"引号"`/`` `反引号` ``/`[方括号]` 包裹的标识符）优先命中；同名出现在注释中同样计入提及；多个候选名称相互重叠时保留最长者。
- **混合排序**：向量余弦相似度与关键词命中分按权重合成（权重沿用可配置项），仅有其中一路可用时按该路排序。
- **完全不可用**：既没有 embedding 模型、也没有任何词法命中时，退回现有顺序（按现有 `listByDsIdChecked` 顺序）并正常生成上下文，不抛错、不返回空。

验收：A1

### 场景：异步回填

勾选表后（`chooseTables`、表/字段同步、数据源新增）触发表向量异步回填；回填失败只记录日志，不影响勾选结果与问数可用性；向量列为空时选表按“完全不可用”分支降级。

验收：A1

### 场景：数据源选择接入向量

`selectDatasource` 在多个数据源间选择时，同样先尝试向量相似度（问题 vs 数据源文档），失败或未配置 embedding 模型时保持现有词法（bigram Jaccard）降级路径，行为对调用方透明。

验收：A1

## 2. 用户批量导入

用户管理支持 Excel 批量导入，替代现有抛异常的占位实现。

### 场景：下载导入模板

`GET /user/template` 返回可下载的导入模板 Excel（含表头与示例行，字段与 SQLBot 用户模板一致：account、name、password/email 等）。

验收：A2

### 场景：批量导入与失败记录

`POST /user/batchImport` 解析上传的 Excel：合法行创建用户（账号唯一性校验，重复/格式错误行不计入）；存在错误行时生成失败记录文件并返回其 fileId；全部合法时正常返回。

验收：A2

### 场景：下载失败记录

`GET /user/errorRecord/{fileId}` 下载上一步生成的失败记录文件（含行号与失败原因）；fileId 不存在时返回明确错误。

验收：A2

## 3. 助手可用数据源

`GET /system/assistant/ds` 返回当前助手上下文可用的数据源列表，字段与 SQLBot 一致：`id`、`name`、`description`、`type`、`type_name`、`num`。

- type=0（普通助手）：按助手配置 JSON 中的 `oid` 过滤；助手未上线（online=false）时仅返回配置中 `public_list` 列出的数据源，`public_list` 为空则返回空数组。
- type=1（高级应用）：按配置中的 `oid` 返回该工作空间数据源。
- 未配置 embedding/LLM 不参与本端点；鉴权沿用现有会话。

验收：A3

## 4. i18n 补全

用户/权限/仪表盘/助手/审计等剩余模块的用户可见文案全部取自消息包（`messages.properties` 默认中文 + `messages_zh_CN` + `messages_en`），随 `?lang=` / Accept-Language 切换；新增消息键有单测覆盖。

验收：A4

## 5. 不回退

补齐上述缺口不得回退既有能力：全模块既有测试（含 MCP 端到端、问数主链路、RAG、datasource 引擎、术语、训练、仪表盘、用户/工作空间、审计、i18n）保持通过。

验收：A5
