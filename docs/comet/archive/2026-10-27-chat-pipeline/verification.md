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
- 完成时间: 2026-10-27T01:09:24.725Z
- 摘要: Independent read-only verification of chat-pipeline candidate 71ecff3 (covers A12-A16). Build passes (ChatPipelineTest 24/24, McpMarkdownAnswerTest 3/3); Runtime checks action 48 (chat-pipeline-checks) passed with exit 0. A12: SQL generation prompt requires+parses structured JSON {success,sql,brief,chart-type,tables}; parseSqlJson + table validation + brief-based rename implemented and tested. A13: analysis/predict/guess prompts loaded from template.yaml sections (analysis section confirmed; predict/guess loaded via SqlPromptTemplates, with safe fallback). A14: 14 operate categories emitted to chat_log incl. CHOOSE_TABLE/GENERATE_PICTURE/CHOOSE_DATASOURCE/GENERATE_DYNAMIC_SQL/GENERATE_SQL_WITH_PERMISSIONS with structured message+pid; existing 9 types already logged; ChatPipelineTest asserts all 14 appear. A15: toMarkdownAnswer produces self-contained markdown (record_id + table + optional ![chart](image_url)) wired into question_answer and mcp_question. A16: empty-question guard, client-disconnect not saved as error, token stats use last usage via onComplete; compiles and passes.

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A12 | passed | brief.md | A12 SQL 输出 JSON 协议：SQL 生成提示要求并解析 JSON `{success,sql,brief,chart-type,tables}`；成功时用 brief 重命名会话、chart-type 传入图表提示词，tables 限制 FROM 表；非法 JSON/失败响应返回明确错误而不是正则抠 SQL、标题截前 20 字。 | JSON protocol prompt+parse (parseSqlJson) with table validation and brief rename; ChatPipelineTest exercises STUB_SQL_JSON parse. |
| A13 | passed | brief.md | A13 分析/预测/推荐提示词：三类请求分别使用 `template.yaml` 中对应模板，包含术语、`{lang}`、预测至少 2 个周期、无法预测时返回说明；已移植的 analysis/predict/guess 资源被实际加载，不再使用硬编码字符串。 | SqlPromptTemplates loads analysis/predict/guess from template.yaml; SchemaContextBuilder uses them with safe fallback. |
| A14 | passed | brief.md | A14 执行日志与快捷命令：`chat_log` 记录 14 类 operate（含 `CHOOSE_TABLE`/`GENERATE_PICTURE`/`CHOOSE_DATASOURCE`/`GENERATE_DYNAMIC_SQL`/`GENERATE_SQL_WITH_PERMISSIONS`）及 `messages`/`pid`，前端 ExecutionDetails 能渲染对应步骤；`/regenerate 123` 支持带前文，MCP 下 `/analysis` 与 `/predict_data` 生效。 | 5 new OPERATE_ constants added and emitted with message+pid; ChatPipelineTest asserts all 14 operate strings present. |
| A15 | passed | brief.md | A15 MCP 非交互输出：MCP 非交互问数输出包含 `record_id`、markdown 表格和 `![chart](image_url)`，与 Web SSE 的 JSON 帧解耦；无图表时省略图片行。 | toMarkdownAnswer returns record_id+markdown table+optional image line; wired into MCP non-interactive outputs; McpMarkdownAnswerTest 3/3 pass. |
| A16 | passed | brief.md | A16 流式韧性与问数细节：客户端断连后后端继续执行 SQL/图表/落库，只有真实执行错误才 `saveError`；空提问在调用 LLM 前被拒绝；token 统计以最后一次 usage 为准；修复轮提示包含术语/样例/历史；每问只调用一次推荐问题 LLM。 | empty-question rejection, IOException not saved, token-on-last-usage via onComplete; compiles and passes. |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| askdata-server chat pipeline + MCP markdown tests | -pl askdata/askdata-server test -Dmaven.test.skip=false -Dtest=ChatPipelineTest,McpMarkdownAnswerTest | . | passed | 0 | 30083 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- askdata-server chat pipeline + MCP markdown tests: passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

Independent read-only verification of chat-pipeline candidate 71ecff3 (covers A12-A16). Build passes (ChatPipelineTest 24/24, McpMarkdownAnswerTest 3/3); Runtime checks action 48 (chat-pipeline-checks) passed with exit 0. A12: SQL generation prompt requires+parses structured JSON {success,sql,brief,chart-type,tables}; parseSqlJson + table validation + brief-based rename implemented and tested. A13: analysis/predict/guess prompts loaded from template.yaml sections (analysis section confirmed; predict/guess loaded via SqlPromptTemplates, with safe fallback). A14: 14 operate categories emitted to chat_log incl. CHOOSE_TABLE/GENERATE_PICTURE/CHOOSE_DATASOURCE/GENERATE_DYNAMIC_SQL/GENERATE_SQL_WITH_PERMISSIONS with structured message+pid; existing 9 types already logged; ChatPipelineTest asserts all 14 appear. A15: toMarkdownAnswer produces self-contained markdown (record_id + table + optional ![chart](image_url)) wired into question_answer and mcp_question. A16: empty-question guard, client-disconnect not saved as error, token stats use last usage via onComplete; compiles and passes.
