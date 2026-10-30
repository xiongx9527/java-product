# Outcome
把 askdata 问数主链路（ChatQuestionService / SchemaContextBuilder / PermissionEnforcer）在提示词结构与校验放行行为上严格对齐 SQLBot（llm.py），并把问数 MCP 的 SQL 生成路径收敛到同一条主链路，消除「同一问题产生不同 SQL / 不同放行范围」的实质偏差。

# Scope

## 目录结构

### 修改
- askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java — 重写 buildSqlMessages 为 SQLBot init_messages 的多轮 ack 结构；新增 selectedTableNames(dsId, question, matchText) 暴露本轮 buildSchemaText 实际选中的表名（retrieved-only）。
- askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatQuestionService.java — priorContext 改为 priorContextMessages(chatId, excludeRecordId)，读取上一次 GENERATE_SQL 的 chat_log.message（完整 model messages，剔除 system），取最近 N 轮（N 由 chat.context_record_count 控制，≤0 不注入），返回 List<ModelMessage>；validateSql 放行集由 checkedTableNames(dsId) 改为 selectedTableNames(...)。
- askdata-server/src/main/java/com/xiongx9527/askdata/permission/PermissionEnforcer.java — 以确定性 JSQLParser 改写为主（现状，fail-closed）；JSQLParser 解析/改写失败时用 LLM 按行权限 filters 改写（对齐 build_table_filter），随后 JSQLParser 复验（filter 谓词存在且表集合不变），复验失败则 fail-closed 拒绝；需注入 ChatModel。
- askdata-server/src/main/java/com/xiongx9527/askdata/mcp/AskdataMcpTools.java — question_answer 由 AskdataNl2Sql.ask 路由到 ChatQuestionService.ask（与 mcp_question 同一条主链路；chatId 为空时先建会话）。

### 删除
- askdata-server/src/main/java/com/xiongx9527/askdata/ai/AskdataNl2Sql.java 及其专属 AskdataSqlRetriever / AskdataAssistantConfig / SQL_PROMPT —— question_answer reroute 后若无其它引用则删除；删除前确认 mcp_assistant 未依赖。

# Non-goals
- 不改动 SQLBot（Python）仓库；SQLBot 仅作参考基线。
- 不把 ③ 行权限回退为纯 LLM 改写；保持 fail-closed。
- 不改动 4 项 askdata 主动增强：MAX_SQL_REPAIR_RETRIES 重试、SqlSemanticValidator 语义校验、执行期未知列自愈（isColumnError）、列权限双层。
- 选表（get_table_schema / buildSchemaText top-N）已用 embedding+词法兜底，与 SQLBot 同范式（已核查 topTableIds 调用 tableEmbeddingService.rankTables），本次不改动。
- 不改消息结构之外的其它帧协议、落库语义、出图、finish_step、标题、推荐问题。

# Acceptance examples
- A1：给定同一问题，buildSqlMessages 产出的 messages 角色序列为 system, user(rules), assistant(ack), user(schema), assistant(ack), [可选 user+assistant]*, [历史 user/assistant]*, user(question)，与 SQLBot init_messages 逐条对应。
- A2：多轮会话第 N 问的 messages 包含第 N-1 问模型完整输出作 assistant 历史轮；priorContextMessages 返回 List<ModelMessage>，剔除 system，取最近 N 轮。
- A3：模型生成引用「勾选但未进入本轮 schema」的表时 validateSql 返回未授权错误；引用进入 schema 的表则通过。
- A4：JSQLParser 无法改写的 SQL 行权限仍被施加（LLM 兜底），且兜底结果复验失败时被拒绝；普通 SELECT 仍走确定性改写且结果不变。
- A5：同一问题经 mcp_question 与 reroute 后的 question_answer 产出相同 sql 与放行结果；AskdataNl2Sql 相关类已删除且无残留引用。
- A6：现有 ChatQuestionService / SchemaContextBuilder 单测与问数集成测试通过。
