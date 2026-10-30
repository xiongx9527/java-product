# Spec: askdata-sqlbot-alignment

本规格描述 askdata 问数主链路与 MCP 问数路径在 SQL 生成、追问历史、表白名单、行权限环节对齐 SQLBot（llm.py）后的完整行为。

## 1. SQL 生成的提示词消息结构（多轮 ack）

问数主链路通过 SchemaContextBuilder.buildSqlMessages(question, dsId, terminologyText, customPromptText, sqlExamples, historyMessages, matchText) 构造发给模型的消息序列，结构须与 SQLBot init_messages 逐条对应：

- system：SQL 系统提示词（含输出 JSON 格式约束）。
- user(rules) + assistant("我已掌握所有规则…")：规则签收。
- user(schema) + assistant("我已确认您提供的数据库信息与表结构schema…")：schema 签收。
- 可选 user(custom_prompt) + assistant("我已确认您提供的额外信息…")；可选 user(terminologies) + assistant("我已确认您提供的术语信息…")；可选 user(data_training) + assistant("我已确认您提供的SQL示例…")。
- 历史轮次：来自 priorContextMessages 的 user/assistant 消息序列（见第 2 节）。
- user(question)：当前用户问题（含 JSON 输出字段约束）。

Scenario: 多轮 ack 消息序列
- Given 一个问题与已选数据源
- When 调用 buildSqlMessages
- Then 产出消息角色序列为 system, user(rules), assistant(ack), user(schema), assistant(ack), [可选 user+assistant]*, [历史 user/assistant]*, user(question)

Acceptance: A1

## 2. 追问历史来源（完整 model messages）

多轮会话中，第 N 问的 SQL 生成须注入第 N-1 问实际发给模型的完整消息（含模型上一轮完整 JSON 输出与思考），而非 DB 中的 "(Q, SQL)" 文本块。

- ChatQuestionService.priorContextMessages(chatId, excludeRecordId) 读取上一次 GENERATE_SQL 的 chat_log.message（结构化消息数组），剔除 system 消息，按 get_last_conversation_rounds 语义取最近 N 轮（N 由 chat.context_record_count 控制，≤0 表示不注入），返回 List<ModelMessage>。
- 历史消息作为独立 user/assistant 消息追加在 schema 签收与当前问题之间。

Scenario: 追问注入上一轮完整消息
- Given 一个已有一轮问答的会话
- When 发起第 N 问（N>1）
- Then 第 N 问的 messages 包含第 N-1 问模型完整输出作为 assistant 历史轮，且不出现 "Q:…A(SQL):…" 文本块

Acceptance: A2

## 3. 表白名单（retrieved-only）

SQL 生成后校验阶段，允许引用的表仅限于本轮 buildSchemaText 实际选入 schema 文本的表（retrieved-only），与 SQLBot table_name_list 一致。

- SchemaContextBuilder.selectedTableNames(dsId, question, matchText) 返回本轮 topTableIds 实际选中的表名。
- ChatQuestionService.validateSql 放行集由 checkedTableNames(dsId)（全部勾选表）改为 selectedTableNames(...)。
- 模型引用「勾选但未进入本轮 schema」的表 → 返回未授权错误；引用进入 schema 的表 → 通过。

Scenario: 引用未展示表被拒
- Given 模型生成引用了勾选但未进入本轮 schema 的表
- When 执行 validateSql
- Then 返回未授权错误

Acceptance: A3

## 4. 行权限混合方案（确定性优先 + LLM 兜底 + 复验）

PermissionEnforcer 对生成的 SQL 施加行/列权限：

- 主路径：JSQLParser 确定性改写（将每个基表包成带行权限过滤的子查询），fail-closed（解析失败抛 PermissionEnforceException）。
- 兜底：当 JSQLParser 解析/改写失败（SQLBot 能用 LLM 处理的 SQL 形态）时，调用 LLM 按行权限 filters 改写（对齐 build_table_filter），得到改写后 SQL。
- 复验：对 LLM 改写结果再次用 JSQLParser 解析，断言 filter 谓词存在且表集合不变；复验失败 → fail-closed 拒绝。

Scenario: 确定性改写覆盖普通 SQL
- Given 一条普通 SELECT（JSQLParser 可改写）
- When 施加行权限
- Then 走确定性子查询改写，结果与现状一致

Scenario: LLM 兜底并复验
- Given 一条 JSQLParser 无法改写的 SQL
- When 施加行权限
- Then 经 LLM 兜底改写且复验通过后方放行；复验失败则拒绝

Acceptance: A4

## 5. MCP 问数收敛到主链路

问数 MCP 的 question_answer 工具复用主链路 ChatQuestionService.ask 生成 SQL，与 mcp_question 走同一条实现，确保 Web 与 MCP 产出完全一致。

- AskdataMcpTools.question_answer 路由到 ChatQuestionService.ask(body)；chatId 为空时先按 resolveChatForQuestion 建会话。
- 原 AskdataNl2Sql 及其专属组件在确认无其它引用后删除。

Scenario: MCP 与主链路一致
- Given 同一问题分别经 mcp_question 与 reroute 后的 question_answer
- When 比较产出
- Then sql 与放行结果相同；AskdataNl2Sql 相关类已删除且无残留引用

Acceptance: A5

## 6. 回归

既有问数单测与集成测试保持通过。

Scenario: 回归通过
- Given 完成上述改动
- When 运行 ChatQuestionService / SchemaContextBuilder 单测与问数集成测试
- Then 全部通过

Acceptance: A6
