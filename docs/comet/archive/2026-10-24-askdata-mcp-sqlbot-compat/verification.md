---
generated_from_state_version: 8
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-24T06:02:43.191Z
- 摘要: 6 个 SQLBot 工具名别名全部注册并真实委托既有实现，token 身份绑定仅复用已签发令牌，access_token 三字段并存且值一致，mcp_start 响应键与 SQLBot 对齐；全量回归 642 用例零失败，改动范围干净。结论：通过验收，oid 工作空间切换语义差异作为已知风险记录，不阻塞。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | 验收：A1 使用 SQLBot 工具名（mcp_start/mcp_ws_list/mcp_datasource_list/mcp_model_list/mcp_question/mcp_assistant）调用 MCP 服务，每个别名均可调用且行为与对应 askdata 原生工具等价（mcp_start 响应含 access_token 与 chat_id） | 6 个 SQLBot 别名 @Tool(AskdataMcpTools.java:403/445/452/460/466/481)真实委托既有实现，与 SQLBot mcp.py 7 个 operation_id 一一对应；mcp_start 响应含 access_token+chat_id(:389-396)；applyTokenIdentity 仅复用已签发令牌(:363-370)；snake_case 参数全部声明并经 longArgOrNull 归一 |
| A2 | passed | brief.md | 验收：A2 access_token 工具成功响应同时包含 access_token、token、tokenName 三个字段，access_token 值等于签发令牌 | access_token 响应同时输出 token(:163)/tokenName(:164)/access_token(:166) 三字段，access_token 与 token 同为签发 tokenValue，既有调用方兼容 |
| A3 | passed | brief.md | 验收：A3 全量回归通过（mvn clean test，JDK 17），既有用例无回退，新增测试覆盖别名等价与响应结构 | clean test(JDK 17) Tests run: 642, Failures: 0, Errors: 0, Skipped: 5，BUILD SUCCESS(/tmp/full-regression.log)；新增 McpSqlbotCompatTest 4/4 通过；git diff 确认无既有测试删除或断言放水 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| 全量回归(clean test，JDK 17) | -o -pl askdata/askdata-server clean test -Dmaven.test.skip=false | . | passed | 0 | 154553 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- compile: passed — mvn -o -pl askdata/askdata-server compile 通过(zulu-17)
- new-tests: passed — McpSqlbotCompatTest 4/4 通过
- full-regression: passed — clean test 642 run / 0 failures / 0 errors / 5 skipped，BUILD SUCCESS(/tmp/full-regression.log)

## 阻塞项

_无。_

## 风险与跳过的工作

- mcp_start 的 oid 参数被接受但忽略：SQLBot 会校验成员资格并切换工作空间，askdata 保持当前会话工作空间；单工作空间客户端不受影响
- McpSqlbotCompatTest 为反射注册 + 纯函数级验证，非真实 MCP 端到端调用；别名等价由委托实现的代码审查确认

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | pass | — | 6 个 SQLBot 工具名别名全部注册并真实委托既有实现，token 身份绑定仅复用已签发令牌，access_token 三字段并存且值一致，mcp_start 响应键与 SQLBot 对齐；全量回归 642 用例零失败，改动范围干净。结论：通过验收，oid 工作空间切换语义差异作为已知风险记录，不阻塞。 | 2026-10-24T06:02:43.191Z |



## 结论

6 个 SQLBot 工具名别名全部注册并真实委托既有实现，token 身份绑定仅复用已签发令牌，access_token 三字段并存且值一致，mcp_start 响应键与 SQLBot 对齐；全量回归 642 用例零失败，改动范围干净。结论：通过验收，oid 工作空间切换语义差异作为已知风险记录，不阻塞。
