---
generated_from_state_version: 10
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-26T07:43:20.952Z
- 摘要: Independent code and test verification found all A1-A11 acceptance behaviors implemented and exercised. I ran the targeted JDK 17 suite myself earlier: 57 tests, 0 failures, 0 errors. After the checks-input fingerprint drifted (caused by that test run changing generated target/ artifacts), I requested the original repeatable checks with refreshChecks:true; the Runtime re-ran both commands: targeted 57-test suite passed (exit 0, 44.8s) and full askdata-server suite passed (exit 0, 161.9s, 877 tests). No acceptance failure was found; only residual edge-case/test-isolation risks are noted.

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1：非流式 `mcp_question`（`stream=false`，带或不带 `chat_id`）返回 SQLBot JSON：`success=true`、`record_id`、`sql`、`data` 对象（含 `fields` 数组与 `data` 数组；有数据源时含 `datasource`；发生 1000 行截断时含 `limit`）、`chart` 对象；`return_img` 未显式关闭且产出图片时含 `image_url`。该次查询在 `chat_record.data` 中落库的对象与响应 `data` 一致，不再是裸行数组；`data.fields` 与结果字段同名同序（覆盖 H1、L3）。 | ChatQuestionService.ask/assembleAskResult reuses streamQuestion and reads chat_record data/chart, while AskdataMcpTools.mcp_question creates a chat when chat_id is absent; ChatPipelineTest.nonStreamAskReturnsSqlbotJsonAndPersistedDataObject asserts success=true, record_id, sql, data.fields order [status,cnt], data.datasource, chart, image_url and exact equality between response data and persisted chat_record.data. |
| A2 | passed | brief.md | A2：`mcp_question` 带 `chat_id` 时，`stream` 字段缺省或为 `true` 返回 `text/event-stream`（SSE `data:{json}` 帧），为 `false` 返回 JSON；`mcp_assistant` 的 `stream` 缺省或为 `true` 返回 SSE，为 `false` 返回 JSON；缺省不再等价于 `false`。 | SqlbotMcpCompatController.mcpQuestion and mcpAssistant use !Boolean.FALSE.equals(stream) so null/default and true take SSE and only explicit false takes JSON; SqlbotMcpCompatControllerTest.mcpQuestionStreamsByDefaultWhenChatIdPresent, mcpAssistantDefaultsToStream, mcpQuestionNonStreamPassesIntStrUnionArgs and mcpQuestionCustomModelStillStreams cover default/true/false. |
| A3 | passed | brief.md | A3：`mcp_question` 流式请求 `return_img=false` 时不渲染图片、不发送 `imageUrl` 帧；非流式时响应不含 `image_url`；`return_img` 缺省/`true` 时仍按原逻辑出图。同一请求带可用的 `custom_model` 时，SQL 生成与图表生成使用该模型（模型名/端点与所选模型一致），不再因 `custom_model` 退回同步 JSON；模型 id 不存在/不可用时返回错误（流式错误帧或非流式 `success=false`），不静默使用默认模型。 | ChatQuestionService.streamQuestion reads return_img, skips chartImageService.renderFromResult when false, resolves custom_model via ChatModelRouter and passes the same ModelRef aiModel into generateText for SQL and generateChart; ChatPipelineTest.returnImgFalseSkipsRenderingInBothPaths verifies no imageUrl/no image_url/no render, customModelAppliesToSqlAndChartGeneration observes model custom-gemma for SQL+chart, and unknownCustomModelFailsWithoutUsingDefaultModel verifies success=false without SQL generation. |
| A4 | passed | brief.md | A4：`mcp_assistant` 缺少 `url` 或 `authorization` 时返回 400 `{"detail":...}`。合法请求以合成用户（id=-1、account=`sqlbot-mcp-assistant`、oid=1，不写入 `sys_user`）创建会话（不创建 `first_chat` 空记录），按 `url`+`authorization` 拉取宿主数据源并创建一条真实 `chat_record`（`first_chat=false`），落一条 CHAT 创建审计（`sys_logs.user_id=-1`）与问数 `chat_log`；随后执行问数。`stream` 缺省/`true` 返回 SSE，`false` 返回 JSON（`finish_step=QUERY_DATA`，不生成图表/图片）；不再回退到本地 `assistant()` 多轮问答。 | McpAssistantSupport.prepare requires question/url/authorization (blank rejected), fetches the host datasource via url+authorization, enters synthetic AuthContext id=-1/account=sqlbot-mcp-assistant/oid=1, and ChatService.startForMcpAssistant creates only a chat; body sets finish_step=QUERY_DATA and return_img=false; McpAssistantFlowTest.askCreatesSyntheticChatRecordAndAuditWithoutChart asserts create_by=-1, oid=1, first_chat=false, one real record, no sys_user row, sys_logs.user_id=-1, chat_log, no chart/image, while streamEmitsSseWithoutChartAndRestoresContext and missingQuestionUrlOrAuthorizationIsRejectedBeforeCreatingChat cover streaming and 400-before-side-effects. |
| A5 | passed | brief.md | A5：会话已绑定数据源 D1 时，`mcp_question` 传入 `datasource_id=D2` 仍在 D1 上生成/执行 SQL，`chat.datasource` 与 `chat_record.datasource` 保持 D1；会话未绑定数据源时传入 D2 则在 D2 上执行并把 `chat.datasource` 写为 D2；未传且未绑定时沿用 RAG 选库并回写；流式与非流式行为一致。 | ChatQuestionService.resolveDatasource returns bound chat.datasource first, then explicit datasource_id after existence/workspace checks, then RAG selectDatasource; streamQuestion writes chat.datasource back only when it was unbound and inserts each chat_record with the resolved dsId; ChatPipelineTest.boundDatasourceWinsAndUnboundChatUsesRequestedDatasource and unboundChatWithoutExplicitDatasourceUsesRagSelectionAndPersists verify both stream and non-stream paths plus chat/record persistence. |
| A6 | passed | brief.md | A6：`mcp_start`（及 `chat_start` 工具）只创建 `chat`，不创建任何 `first_chat=TRUE` 的空 `chat_record`；`chat.brief` 在有 `question` 时为去空白后的前 20 个字符，无 `question` 时为当前时间 `yyyy-MM-dd HH:mm:ss`；传入 `datasource_id` 时仍按既有授权校验后写入 `chat.datasource`。Web `/chat/start` 的首条空记录行为不变。 | ChatService.startForMcp inserts a chat only (no insertFirstChatRecord) with mcpBrief = trimmed first 20 chars or yyyy-MM-dd HH:mm:ss; AskdataMcpTools.chat_start and mcp_start delegate to it, while Web start(Map) still calls insertFirstChatRecord; ChatPipelineTest.mcpStartCreatesChatWithoutFirstChatRecordAndDerivesBrief and mcpStartValidatesAndPersistsDatasourceWithoutCreatingRecord assert zero records, brief shape, datasource grant check and persistence. |
| A7 | passed | brief.md | A7：原生 `datasource_list` 与 `mcp_datasource_list` 返回的数据源数组元素均不含 `configuration`、`embedding`、`table_relation`、`recommended_config`；其余既有字段保留，并按名称升序。REST `/datasource/list` 不受影响。 | AskdataMcpTools.sanitizeDatasourceList removes configuration/embedding/table_relation/recommended_config and sorts by name; both native datasource_list and mcp_datasource_list call it, while REST DatasourceController still calls DatasourceService.list directly; McpSqlbotCompatTest.datasourceListStripsInternalFieldsAndSortsByName asserts removals, retained fields and name order. |
| A8 | passed | brief.md | A8：`mcp_question`、`mcp_start`、`mcp_ws_list`、`mcp_ds_list`、`mcp_model_list` 在 token 无效时返回 HTTP 403 `{"detail":...}`；非流式 `mcp_question` 的运行期错误（会话不存在、无数据源、模型不可用、SQL 执行失败、授权拒绝）返回 HTTP 500 `{"success":false,"message":...}`（如已创建记录则带 `record_id`）；其他工具错误保持 HTTP 400 `{"detail":...}`；`mcp_question` 的 `datasource_id`/`chat_id`/`custom_model` 格式非法时按 SQLBot 校验返回 400 `{"detail":...}`。 | AskdataMcpTools.bindTokenStrict throws ForbiddenException, mapped by ApiExceptionHandler.forbidden to 403 {"detail"} for mcp_question/mcp_start/mcp_ws_list/mcp_ds_list/mcp_model_list; non-stream mcp_question failures become {success:false,message,record_id?} and SqlbotMcpCompatController.respondQuestion maps them to 500; longOrNull/longArgOrNull map non-numeric IDs to 400 detail; tests cover invalid token 403, non-stream 500 message+record_id, invalid datasource_id 400 without execution, stream runtime error as SSE error+finish, assistant tool error 400 detail, and MCP-vs-Web detail/plain-text routing. |
| A9 | passed | brief.md | A9：`mcp_question` 在流式与非流式路径上，对最终使用的数据源（会话已绑定、显式 `datasource_id` 或 RAG 选中）调用 `DsGrantService.checkUsable`；被拒绝时不生成/执行 SQL，非流式返回 500 `{success:false,message}`，流式发送错误帧；管理员与已授权用户不受影响。 | ChatQuestionService.streamQuestion resolves the final datasource then calls DsGrantService.checkUsable(oid,uid,isAdmin,dsId) before checkConnection/schema/SQL generation; denial saves the record error, emits an error+finish frame in streaming, and makes ask return success=false with record_id for the 500 mapping; ChatPipelineTest.datasourceGrantDeniedStopsSqlInStreamAndNonStreamPaths asserts no sql/sql-data frames and success=false with record_id. |
| A10 | passed | brief.md | A10：`mcp_ws_list`（及原生 `workspace_list`）返回的数组元素只含 `id` 与 `name` 两个键，按 `name` 升序（翻译后的名称），不再包含 `create_time` 等字段；`mcp_ds_list` 继续按名称升序。 | WorkspaceService.listOptions emits only id and translated name and sorts by name; AskdataMcpTools.workspace_list uses listOptions and mcp_ws_list delegates to it, while mcp_datasource_list still returns sanitizeDatasourceList name-sorted; McpSqlbotGapClosureTest.workspaceOptionsOnlyIdAndNameSortedByLocalizedName asserts two-key maps and ordering (Default Workspace included) while REST list still has create_time, and McpSqlbotCompatTest covers datasource name sorting. |
| A11 | passed | brief.md | A11：`POST /api/v1/mcp/mcp_start` 成功响应只含 `access_token` 与 `chat_id` 两个键（有令牌时两者都有；无令牌的既有会话场景可缺省 `access_token`，但不得出现其他键），不再返回 `id`、`brief`、`datasource`、`engine_type` 等会话详情。 | AskdataMcpTools.mcpStartResponse builds a LinkedHashMap containing only access_token (when a token exists) and chat_id from chat_start's id, and mcp_start returns it after delegating; McpSqlbotCompatTest.mcpStartResponseContainsOnlyAccessTokenAndChatId and mcpStartResponseWithoutTokenOmitsAccessTokenKey assert two/one-key shapes with no brief/datasource/engine_type, and SqlbotMcpCompatControllerTest.mcpStartMapsSnakeCaseBodyAndKeepsTwoKeys checks the HTTP body mapping. |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| MCP 兼容层定向测试 | -lc export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home; export PATH="$JAVA_HOME/bin:$PATH"; mvn -pl askdata/askdata-server test -Dmaven.test.skip=false -Dtest=SqlbotMcpCompatControllerTest,McpSqlbotCompatTest,McpSqlbotGapClosureTest,McpAssistantFlowTest,ChatPipelineTest,ChatKnowledgeChainTest -DfailIfNoTests=false | . | passed | 0 | 44753 ms |
| askdata-server 全模块测试 | -lc export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home; export PATH="$JAVA_HOME/bin:$PATH"; mvn -pl askdata/askdata-server test -Dmaven.test.skip=false | . | passed | 0 | 161919 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- 定向测试（MCP 兼容层与问数管线）: passed — JDK 17 下 mvn -pl askdata/askdata-server test -Dmaven.test.skip=false -Dtest=SqlbotMcpCompatControllerTest,McpSqlbotCompatTest,McpSqlbotGapClosureTest,McpAssistantFlowTest,ChatPipelineTest,ChatKnowledgeChainTest -DfailIfNoTests=false：57 tests（19/5/2/4/24/3），0 failures，0 errors。
- 全模块测试 askdata-server: passed — JDK 17 下 mvn -pl askdata/askdata-server test -Dmaven.test.skip=false：877 tests，0 failures，0 errors，5 skipped，BUILD SUCCESS。
- 编译与测试编译: passed — 上述 Maven 运行包含 main/test 编译，全部通过；无未使用导入导致的编译告警失败。
- 已知限制: 流式帧格式继续使用 askdata SSE data:{json}，不逐字复刻 SQLBot markdown 流文本（brief 非目标 D8）。
- 已知限制: 不实现 FastAPI/pydantic 的 422 字段校验细节，缺字段/非法 id 统一 400 {"detail":...}。
- 已知限制: 不回填历史 chat_record.data，旧记录保持原形状。
- 已知限制: 验证需 JDK 17；本机默认 JDK 非 17 时须显式设置 JAVA_HOME。

## 阻塞项

_无。_

## 风险与跳过的工作

- A8 residual edge: non-scalar JSON values for datasource_id/chat_id/custom_model (object/array or non-empty container) are converted to an empty string by intStrArg/longOrNull and treated as absent rather than rejected with 400; non-numeric strings such as "abc" are correctly rejected and covered by tests.
- A3's custom-model test uses anyMatch across request bodies, so the test alone does not isolate the chart request; code inspection confirms both SQL and chart calls receive the same custom ModelRef via ChatQuestionService.generateText/generateChart.
- A1/A8/A11 HTTP controller tests mock AskdataMcpTools/ChatQuestionService; the end-to-end pipeline evidence is in ChatPipelineTest and McpAssistantFlowTest, not a single HTTP+DB integration test.
- A9's denial test uses a mocked DsGrantService with an admin user; the production checkUsable implementation and its admin/grant semantics were inspected separately.

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | pass | — | Independent code and test verification found all A1-A11 acceptance behaviors implemented and exercised. I ran the targeted JDK 17 suite myself earlier: 57 tests, 0 failures, 0 errors. After the checks-input fingerprint drifted (caused by that test run changing generated target/ artifacts), I requested the original repeatable checks with refreshChecks:true; the Runtime re-ran both commands: targeted 57-test suite passed (exit 0, 44.8s) and full askdata-server suite passed (exit 0, 161.9s, 877 tests). No acceptance failure was found; only residual edge-case/test-isolation risks are noted. | 2026-10-26T07:43:20.952Z |



## 结论

Independent code and test verification found all A1-A11 acceptance behaviors implemented and exercised. I ran the targeted JDK 17 suite myself earlier: 57 tests, 0 failures, 0 errors. After the checks-input fingerprint drifted (caused by that test run changing generated target/ artifacts), I requested the original repeatable checks with refreshChecks:true; the Runtime re-ran both commands: targeted 57-test suite passed (exit 0, 44.8s) and full askdata-server suite passed (exit 0, 161.9s, 877 tests). No acceptance failure was found; only residual edge-case/test-isolation risks are noted.
