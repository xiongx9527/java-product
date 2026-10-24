# Capability: sqlbot-parity-hardening（SQLBot 对齐残余缺口加固）

本规格描述修复完成后 askdata 在权限旁路、动态数据源表级 SQL、模型请求体兼容性与 i18n 协议细节方面的完整行为。

## 1. MCP 问数链路权限（对齐 Chat 主链路）

### Scenario: MCP 问数对普通数据源施行行权限
- **WHEN** 用户经 MCP question_answer 对已配置行权限的普通数据源提问
- **THEN** 生成的 SQL 经 PermissionEnforcer 递归改写（CTE/UNION/子查询/JOIN 覆盖，与 ChatQuestionService 主链路同一实现），执行结果行集与主链路一致
验收：A1

### Scenario: MCP 问数对普通数据源施行列权限
- **WHEN** 数据源配置了列权限（隐藏列）
- **THEN** MCP 问数的执行 SQL 不引用被隐藏列，返回结果不含该列
验收：A2

### Scenario: MCP 表白名单校验保留
- **WHEN** MCP 问数生成的 SQL 引用允许集之外的表
- **THEN** 拒绝执行（沿用现有表白名单行为）
验收：A1

### Scenario: 动态数据源路径的权限边界
- **WHEN** 问数对象为动态数据源（无持久化数据源身份，dsId 为空或 dynamic != null）
- **THEN** 跳过行/列权限注入（对齐 SQLBot 动态路径机制，见 brief D1），但表白名单校验仍以解析出的动态 schema 为允许集执行
验收：A4

## 2. 动态数据源表级 SQL 占位符闭环（对齐 SQLBot AssistantTableSchema.sql）

### Scenario: 占位符注入
- **WHEN** 动态数据源携带表级 dynamic_sql（顶层或 configuration 内嵌），每表一段 SQL
- **THEN** SchemaContextBuilder 在 SQL 生成 system 提示词中为每表声明占位符表名（对齐 SQLBot dynamic_subsql_prefix + sqlbot_dynamic_temp_table_<表名> 形态），并给出该表的真实子查询文本，引导 LLM 在 SQL 中引用占位符表而非直连物理表
验收：A3

### Scenario: 执行前确定性替换
- **WHEN** 一阶段生成的 SQL 含占位符表名
- **THEN** 执行前把占位符表名确定性替换为宿主下发的对应子查询（内联或 WITH 形式），替换后的 SQL 走统一校验（extractSql → validateTables 表白名单 → 执行）；宿主子查询文本原样使用，不做重写
验收：A3

### Scenario: 替换失败不执行
- **WHEN** 生成的 SQL 含占位符但宿主未下发对应表级 SQL，或替换过程失败
- **THEN** 不执行含未替换占位符的 SQL：进入错误回喂修复轮（regenerateSql）或返回失败帧
验收：A3

### Scenario: 宿主 URL 实时拉取路径一致
- **WHEN** 动态数据源经 resolveFromHost 实时 GET 宿主 API 获得，响应含 dynamic_sql
- **THEN** 走同一解析 → 占位符注入 → 替换闭环
验收：A3

## 3. 模型请求体兼容性（对齐 SQLBot model_factory / llm.py:194-201）

### Scenario: extra_body 嵌套归并
- **WHEN** 模型 config（config_list）中存在 key=extra_body 的对象，或单独出现 enable_thinking 键
- **THEN** 请求体中 extra_body 为嵌套对象（enable_thinking 归入其内），payload 顶层不出现 extra_body / enable_thinking 平铺键
验收：A5

### Scenario: Azure 参数剔除
- **WHEN** 端点为 Azure（域名匹配 *.openai.azure.com/.cn）
- **THEN** api_version、deployment_name 仅用于构造请求 URL（deployment 路径段与 api-version 查询参数），请求 JSON body 不含这两个键
验收：A5

### Scenario: 其他参数整包透传保持
- **WHEN** config 含 temperature 等其他参数
- **THEN** 维持现状：整包平铺进 payload 顶层（跳过 null），temperature 正常下发
验收：A5

## 4. i18n 与协议细节

### Scenario: 导出与模板列头走 i18n
- **WHEN** 导出或下载模板：自定义提示词、术语、SQL 示例三类
- **THEN** Excel 列头与模板示例文案按当前 locale 从 messageResolver 渲染，键在 6 个 messages*.properties 齐备
验收：A6

### Scenario: 后端错误文案 i18n 全覆盖
- **WHEN** ChatService、ChatQuestionService、SettingsFileController、TableRelationService、ExternalIdpController、ParameterService 抛出面向用户的错误
- **THEN** 文案来自 messageResolver（新键 + 6 语言文件），代码中不再残留该范围内的硬编码英文文案
验收：A7

### Scenario: workspace_list 非管理员语义
- **WHEN** 非管理员登录用户调用 workspace_list（MCP 或 API）
- **THEN** 返回其所属工作空间列表（不要求管理员）；管理员调用返回全部；身份校验与归属过滤沿用现有机制
验收：A8

### Scenario: 历史轮数默认 3
- **WHEN** chat.context_record_count 未显式配置（使用种子缺省）
- **THEN** priorContext 默认注入最近 3 条历史（对齐 SQLBot GENERATE_SQL_QUERY_HISTORY_ROUND_COUNT=3）；显式配置值优先
验收：A9

## 5. 回归约束

### Scenario: 既有行为不回归
- **WHEN** 全量测试执行（mvn -o -pl askdata/askdata-server clean test，JDK 17，-Dmaven.test.skip=false）
- **THEN** 全部通过，现有 626 用例无回归；新增测试（MCP 权限、占位符替换、payload 构造）覆盖上述场景
验收：A10