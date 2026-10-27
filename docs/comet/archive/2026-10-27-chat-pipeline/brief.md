# chat-pipeline

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A12: A12 SQL 输出 JSON 协议：SQL 生成提示要求并解析 JSON `{success,sql,brief,chart-type,tables}`；成功时用 brief 重命名会话、chart-type 传入图表提示词，tables 限制 FROM 表；非法 JSON/失败响应返回明确错误而不是正则抠 SQL、标题截前 20 字。
- A13: A13 分析/预测/推荐提示词：三类请求分别使用 `template.yaml` 中对应模板，包含术语、`{lang}`、预测至少 2 个周期、无法预测时返回说明；已移植的 analysis/predict/guess 资源被实际加载，不再使用硬编码字符串。
- A14: A14 执行日志与快捷命令：`chat_log` 记录 14 类 operate（含 `CHOOSE_TABLE`/`GENERATE_PICTURE`/`CHOOSE_DATASOURCE`/`GENERATE_DYNAMIC_SQL`/`GENERATE_SQL_WITH_PERMISSIONS`）及 `messages`/`pid`，前端 ExecutionDetails 能渲染对应步骤；`/regenerate 123` 支持带前文，MCP 下 `/analysis` 与 `/predict_data` 生效。
- A15: A15 MCP 非交互输出：MCP 非交互问数输出包含 `record_id`、markdown 表格和 `![chart](image_url)`，与 Web SSE 的 JSON 帧解耦；无图表时省略图片行。
- A16: A16 流式韧性与问数细节：客户端断连后后端继续执行 SQL/图表/落库，只有真实执行错误才 `saveError`；空提问在调用 LLM 前被拒绝；token 统计以最后一次 usage 为准；修复轮提示包含术语/样例/历史；每问只调用一次推荐问题 LLM。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
