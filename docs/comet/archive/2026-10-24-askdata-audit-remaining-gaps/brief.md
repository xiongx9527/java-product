# 目标

修复 2026-10-24 全量审计（SQLBot 能力覆盖 + F-01~F-20 安全核查，只读核实完成）确认的真实缺口，使 askdata 在以下四组能力上达到或显式对齐 SQLBot：

1. **权限旁路修复（安全）**：MCP 问数链路（question_answer → AskdataNl2Sql）当前只做表白名单校验，不施行行/列权限；普通数据源必须执行与 Chat 主链路一致的 PermissionEnforce。动态数据源路径（无数据源身份）维持跳过行/列权限——这与 SQLBot 动态路径"用宿主表级 SQL 替代行权限"的机制一致，属确认后的对齐行为而非缺陷。
2. **动态数据源表级 SQL 改写闭环**：目前只有"宿主下发 dynamic_sql → 解析 → 提示词引导 LLM 以 WITH 复用"，缺少程序性保证。补齐占位符机制与确定性替换：一阶段提示词引导 LLM 引用占位符表，生成后把占位符确定性地替换为宿主下发的实际子查询再执行；替换失败时不执行未替换 SQL。
3. **模型层请求体兼容性**：config 的 extra_body / enable_thinking 当前被平铺为 payload 顶层 key（严格 OpenAI 兼容端点可能 400），改为归并进 extra_body 嵌套结构；Azure 的 api_version / deployment_name 只用于 URL 构造，从请求体剔除（对齐 SQLBot model_factory 的 pop 行为）。
4. **i18n 与协议细节批次**：导出物列头硬编码中文改走 messageResolver；20+ 处硬编码英文错误文案补 i18n 键（6 语言）；MCP workspace_list 对齐 SQLBot mcp_ws_list（任意登录用户可列自己所属工作空间）；chat.context_record_count 默认值 5 → 3（对齐 SQLBot GENERATE_SQL_QUERY_HISTORY_ROUND_COUNT）。

# 范围

需求来源：本轮会话完成的只读审计核实结论（7 个探查代理逐条核对当前 HEAD c6262b7 源码，含 file:line 证据）。用户粘贴的《SQLBot 能力完整覆盖审计清单》中其余条目经核实为已修复或有意偏离，不在本需求范围（见非目标）。

## 目录结构

### 新建

- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/mcp/McpPermissionEnforceTest.java — MCP 问数链路行/列权限生效测试
- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/chat/service/DynamicSubSqlRewriteTest.java — 占位符替换闭环测试（替换成功/失败/未替换不执行）
- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/chat/service/ChatModelClientPayloadTest.java — extra_body 嵌套与 Azure 参数剔除测试

### 修改

- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/AskdataNl2Sql.java — MCP 问数接入 PermissionEnforcer（行/列权限），表白名单校验保留
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/AskdataMcpTools.java — question_answer 链路权限上下文衔接（如需）
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatQuestionService.java — 动态数据源占位符替换与执行链路；priorContext 默认 5→3；硬编码英文文案改 i18n
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/embedded/DynamicDatasourceResolver.java — 表级 SQL 模板（dynamic_sql）解析保持/按需增强
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java — 动态数据源提示词段改为占位符引用引导（buildSqlMessagesForConf / buildSqlFixMessagesForConf）
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatModelClient.java — payload 构造：extra_body/enable_thinking 归并嵌套；Azure api_version/deployment_name 剔除
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/service/WorkspaceService.java — list 对非管理员返回其所属工作空间
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/service/CustomPromptService.java — 导出/模板列头 i18n（约 :216-230）
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/terminology 下的 TerminologyService.java — 导出/模板列头 i18n（约 :272-289）
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/training 下的 TrainingService.java — 导出/模板列头 i18n（约 :238-250）
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatService.java — 硬编码英文文案改 i18n（:107/109/245/247/266/273/286/289）
- askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system 下的 SettingsFileController.java、TableRelationService.java、ExternalIdpController.java、ParameterService.java — 硬编码英文文案改 i18n
- askdata/askdata-server/src/main/resources/messages*.properties（6 个文件）— 新增上述全部 i18n 键
- askdata/askdata-server/src/main/resources/schema.sql — chat.context_record_count 种子 '5'→'3'

### 删除

无

### 明确不建

- 独立的缓存层 / Redis 接入（F-10）— 低优先级遗留，另行需求
- MCP stream 参数同步形态 — 与 SQLBot 的形态差异，不影响能力
- 第二次 LLM 改写调用（SQLBot generate_with_sub_sql 的 LLM 形态）— 采用确定性替换替代，见决定 D2

# 非目标

- F-10 缓存层、F-11 平台 SSO 登出差异化实现、F-16 token 有效期调整
- 推荐问题模板增强（主链路注入历史/图表类型、数据源级 20 条历史）
- 示例召回词法兜底条数 3→5、sqlbot_name 提示词覆盖面扩展到 8 处
- 已核实为"已修复"或"有意偏离"的审计条目：F-01~F-09、F-12、F-13、F-14、F-17~F-20、MCP 7 操作、/docs 文档站、术语召回、SQL 示例导入向量、表名 AST 校验、分析/预测自定义提示词、limit_rows 两套策略等

# 决定

- D1 动态数据源路径跳过行/列权限为**对齐行为**：SQLBot 动态路径（task/llm.py:1485-1495 → generate_assistant_dynamic_sql）同样不走 get_row_permission_filters，由宿主表级 SQL 子查询承担数据边界；本需求以目标 2 的替换闭环落实该机制。动态路径必须保留表白名单校验（以解析出的动态 schema 为允许集）。
- D2 表级 SQL 改写采用**确定性占位符替换**（占位符常量 + 生成后定位替换），不引入 SQLBot 的第二次 LLM 改写调用：消除幻觉风险且可测；宿主子查询文本原样替换，不做 SQL 重写。
- D3 extra_body 归并规则：config 中 key=extra_body 的对象合并进 payload.extra_body；单独出现 enable_thinking 键时归入 extra_body.enable_thinking；api_version/deployment_name 仅参与 URL 构造。
- D4 workspace_list 语义：管理员列全部工作空间，非管理员仅列自己所属（对齐 user_ws_options），MCP 与普通 API 共用同一实现。

# 验收示例

- A1 行权限经 MCP 生效：为某用户配置表级行权限后，经 MCP question_answer 问数返回行集与 Chat 主链路一致（过滤生效）；未配置行权限的数据源行为不变；表白名单校验保留。
- A2 列权限经 MCP 生效：被列权限隐藏的列不出现在 MCP 问数的执行 SQL 引用与返回结果中。
- A3 动态子查询占位符替换：宿主下发含表级 dynamic_sql 的动态数据源（含 resolveFromHost 实时拉取形态）时，一阶段 SQL 引用占位符表，执行前被确定性替换为宿主子查询；替换失败时按错误处理（回喂修复或失败帧），不执行含未替换占位符的 SQL。
- A4 动态路径权限边界：动态数据源问数仍执行表白名单校验；无数据源身份时跳过行/列权限（对齐 SQLBot，记录为既定行为）。
- A5 模型请求体结构：config 含 extra_body 或 enable_thinking 时，请求体出现嵌套 extra_body 且无顶层平铺键；Azure 端点请求体不含 api_version 与 deployment_name，URL 仍含二者；temperature 等其他参数整包透传保持。
- A6 导出物 i18n：自定义提示词/术语/SQL 示例的导出与模板列头按当前 locale 渲染（不再固定中文）。
- A7 错误文案 i18n：ChatService、ChatQuestionService、SettingsFileController、TableRelationService、ExternalIdpController、ParameterService 中的硬编码英文错误文案全部改走 messageResolver，键在 6 个 messages*.properties 齐备。
- A8 workspace_list 非管理员：非管理员登录用户经 MCP workspace_list 获得自己所属工作空间列表；管理员仍列全部。
- A9 历史轮数默认 3：chat.context_record_count 未配置/种子缺省时 priorContext 默认取 3。
- A10 全量回归：mvn -o -pl askdata/askdata-server clean test（zulu-17，-Dmaven.test.skip=false）全部通过，现有 626 用例无回归。