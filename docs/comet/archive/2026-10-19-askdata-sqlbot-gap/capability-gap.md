# askdata vs SQLBot 能力对照与缺口分析

> 对照对象：SQLBot（DataEase 开源 Python ChatBI，路径 /Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot）。
> 被对照对象：askdata（java-product 下的 Java 问数系统，路径 /Users/xiongxiao/Workspaces/Idea/codes/New/java-product/askdata）。
> 方法：逐模块/逐能力比对 SQLBot backend/apps/* 与 askdata askdata-server/src/main/java/com/xiongx9527/askdata/*，以包/文件/类名作为证据。

## 1. 结论速览

askdata 在结构上刻意对齐 SQLBot（datasource/engine/DatasourceType.java 注释明确写“对齐 SQLBot apps/db/constant.py 的 DB 枚举”），因此绝大多数问数/ChatBI 能力双方都已具备。经 Build 阶段逐文件核实，真正缺失或偏弱的是：MCP 集成（缺失）、Elasticsearch 数据源单测覆盖（引擎已内置但缺验证）、i18n 多语言成熟度（偏弱）；向量/RAG 检索已具备（向量召回 + 词法降级 + 测试覆盖），判定为与 SQLBot 持平。

## 2. 能力对照表

| 能力 | SQLBot | askdata | 状态 | 证据 |
| --- | --- | --- | --- | --- |
| 问数 / Text-to-SQL | apps/chat | chat（ChatService/ChatQuestionService） | 具备 | com/xiongx9527/askdata/chat/service/ChatQuestionService.java |
| 术语库 | apps/terminology | terminology（TerminologyService） | 具备 | .../terminology/TerminologyService.java |
| 数据训练 | apps/data_training | training（TrainingService） | 具备 | .../training/TrainingService.java |
| 仪表盘 | apps/dashboard | dashboard（DashboardService） | 具备 | .../dashboard/DashboardService.java |
| 用户/工作空间/RBAC | apps/system | system（UserService/WorkspaceService/system/auth/permission/DsPermissionService） | 具备 | .../system/service/*, .../permission/DsPermissionService.java |
| 自定义提示词 | 自定义提示词 | prompt（CustomPromptService） | 具备 | .../prompt/CustomPromptService.java |
| 数据权限 | 细粒度数据权限 | permission（DsPermissionService） | 具备 | .../permission/DsPermissionService.java |
| AI 模型管理 | apps/ai_model | system/service/AiModelService | 具备 | .../system/service/AiModelService.java |
| 推荐问题 | generate_guess_question | RecommendedProblemService | 具备 | .../datasource/service/RecommendedProblemService.java |
| 预测 / forecast | template/generate_predict | 预测（predict/预测 8/7 处） | 具备 | grep 预测 7 处、predict 8 处 |
| embedding / RAG | apps/datasource/embedding/*, apps/ai_model/embedding.py | SchemaContextBuilder 向量召回（embedding + cosine）+ 词法降级，ChatRagTest 覆盖 | 具备/持平 | engine 同级 SchemaContextBuilder.java（embedAll/selectByVector）、chat/service/ChatRagTest.java |
| MCP 集成 | apps/mcp/mcp.py | 无 | 缺失 | grep mcp 0 处 |
| Elasticsearch 数据源 | apps/db/es_engine.py | JdbcEngine 已内置完整 ES HTTP 路径（checkConnection→ping、tables→_cat/indices、fields→_mapping、execSql→_sql），缺单测 | 已实现/补测 | datasource/engine/JdbcEngine.java（esBaseUrl/checkEs/esIndices/esFields/esExec）、engine/EsEngineTest.java（离线单测 8 项）、engine/EsEngineIntegrationTest.java（真实集群联调，默认跳过） |
| i18n / 多语言 | locales + i18n | Locale/i18n 痕迹 6 处，无 MessageSource | 偏弱 | grep Locale 6、i18n 6、MessageSource 0、国际化 0 |

## 3. 缺口判定与优先级

- G1 MCP 集成（缺失，P0）：askdata 完全没有 MCP。SQLBot 通过 mcp.py 把问数、助手、数据源以 MCP 工具对外暴露，可对接 n8n / Dify / MaxKB / DataEase。这是 askdata 对外集成能力的最大缺口。
- G2 Elasticsearch 数据源（已实现，P1）：经核实 JdbcEngine 已完整实现 ES HTTP 引擎（连通检查 ping、索引元数据 _cat/indices、字段映射 _mapping、执行走 ES _sql），并非占位。A2 交付物为补充单测（EsEngineTest，8 项全过）与核实，而非从零新建 EsEngine。
- G3 i18n 成熟度（偏弱，P2）：askdata 有少量 Locale/i18n 痕迹但无 Spring MessageSource 体系与完整 locales，距离 SQLBot 的多语言差一截。
- G4 向量库 / RAG 检索成熟度（观察项，P3）：经核实 SchemaContextBuilder 已实现向量召回（embedding + cosine 相似度）+ 无向量时的词法降级召回，且 ChatRagTest 已覆盖离线证据。判定与 SQLBot 持平，A5 交付物为核实结论与文档（无额外代码）。

## 4. 路线图建议

| 优先级 | 缺口 | 建议落地方式 | 验收 |
| --- | --- | --- | --- |
| P0 | G1 MCP | 新增 MCP server（stdio/SSE），复用 ChatQuestionService/DatasourceService，暴露 question_answer/datasource_list/assistant | A3 |
| P1 | G2 ES | 引擎已内置；补充 EsEngineTest（连通/索引/字段/_sql/鉴权/失败路径），核实可用 | A2 |
| P2 | G3 i18n | 引入 Spring MessageSource + messages_zh_CN/messages_en 消息包，核心接口/错误文案迁到消息包，按 locale 切换 | A4 |
| P3 | G4 向量/RAG | 核实为已具备（向量召回 + 词法降级 + ChatRagTest），记录“与 SQLBot 持平”结论 | A5 |

注：本 change 一次性补齐上述缺口（用户确认“缺少的都要加上”）。各缺口独立验收，未通过的回到 Build 修复后重新交接。

## 5. 交付状态与实现证据（Build 阶段）

本 change 在 Build 阶段一次性补齐 G1–G4 四项缺口，全部实现并附带自动化测试。模块全量测试 111/111 通过（基线 83 + 新增 28），BUILD SUCCESS，Spring 上下文可完整加载（ChatContextLoadTest / ValueAddModulesSmokeTest 均绿）。

> 修订（迭代 4，回 Build 后四项增强）：
> - (a) 真实 ES7 集群（192.168.1.100:9200）新建专用只读测试账号 `es_test_rw` / `EsTestRw@2026`（自定义角色 `es_test_rw_role`：cluster monitor + 索引 read/view_index_metadata/monitor），并写入资产 Excel 服务清单（行 56）。
> - (b) 扩大 i18n 覆盖：`MessageResolver` 注入 `DatasourceService` 与 `ExcelService`，新增 `ds.*`/`sql.*`/`chat.*` 消息键；新增 `MessageResolverTest`（5/5）。
> - (c) MCP 默认开启：`askdata.mcp.enabled` 改为 `matchIfMissing=true`（缺省即开），默认传输 `http`（`/api/v1/mcp/rpc` 与 `/api/v1/mcp/stream` SSE），可设 `false` 关闭；`ChatContextLoadTest` 确认默认加载 MCP 上下文。
> - (d) 真实 Docker MySQL 新建 `askdata` 库与专用账号 `askdata`@`%`（caching_sha2_password，`Askdata@123456`，`GRANT ALL ON askdata.*`），替代 root，并写入资产 Excel 服务清单（行 57）。

| 验收 | 缺口 | 交付内容 | 证据（新增/修改文件） | 测试 |
| --- | --- | --- | --- | --- |
| A1 | 对照分析 | 能力对照与缺口判定文档（本报告） | capability-gap.md | 文档评审 |
| A2 | G2 ES | 引擎已内置，补充离线单测（8 项）+ 真实集群联调测试（默认跳过，设 ASKDATA_ES_HOST/USER/PASS 后用专用只读账号 `es_test_rw` 联调真实集群的连通/索引/字段/SQL）；联调测试已加固：自动选取首个非 `.` 业务索引，规避点前缀 data-stream 索引无法被 ES SQL 解析的问题 | askdata-server/src/test/java/com/xiongx9527/askdata/datasource/engine/EsEngineTest.java（8 项）、askdata-server/src/test/java/com/xiongx9527/askdata/datasource/engine/EsEngineIntegrationTest.java（可配置联调，含 es_test_rw 文档） | 离线 8/8 通过；真实集群联调实测通过（192.168.1.100:9200，专用账号 es_test_rw，82 个业务索引，execSql 返回 fields/data/fields_info/sql） |
| A3 | G1 MCP | 零依赖手写 JSON-RPC 2.0 server（stdio/HTTP-SSE），复用 ChatQuestionService/DatasourceService，暴露 question_answer/datasource_list/assistant；**默认开启**（`askdata.mcp.enabled` 缺省即开，默认传输 `http`） | mcp/ 包（McpServer、McpStdioLauncher、McpSupport、McpTool、QuestionAnswerer、DatasourceLister）+ mcp/tools/（3 个工具）+ mcp/config/（McpConfig、McpController，二者均加 `@ConditionalOnProperty(... matchIfMissing=true)`） | McpServerTest.java 10/10 通过；ChatContextLoadTest 确认 MCP 上下文默认加载（2/2） |
| A4 | G3 i18n | Spring MessageSource + AcceptHeaderLocaleResolver + LocaleChangeInterceptor(?lang=)，核心登录错误/接口文案迁入消息包，并扩大覆盖到数据源与 Excel 服务层 | common/i18n/MessageResolver.java、common/i18n/LocaleConfig.java、system/controller/LoginController.java（迁移）、datasource/service/DatasourceService.java（注入 MessageResolver，ds.name.exists/ds.not.found/ds.table.not.exists）、datasource/excel/ExcelService.java（ds.parse.excel.failed）、messages.properties/messages_zh_CN.properties/messages_en.properties（新增 ds.*/sql.*/chat.*） | I18nTest.java 4/4、MessageResolverTest.java 5/5 通过 |
| A5 | G4 向量/RAG | 核实为已具备（向量召回 + 词法降级 + ChatRagTest），结论“与 SQLBot 持平”，无额外代码 | capability-gap.md 第 2/3/4 节判定 | 已有 ChatRagTest 覆盖 |

### 5.1 关键设计说明

- MCP 零新增依赖：JSON-RPC 2.0 协议、stdio 与 HTTP/SSE 传输均用 JDK 原生实现（com.sun.net.httpserver 仅用于单测 mock ES，不进主依赖）。通过 QuestionAnswerer / DatasourceLister 接口注入，便于单测用 fake 验证，不依赖真实 DB/LLM。
- Java 8 兼容：全程规避 List.of / Map.of / 三参 PrintStream / var，使用 Arrays.asList / Collections.singletonList / emptyList 等。
- i18n 默认中文：messages.properties 为默认（中文），messages_zh_CN 与 messages_en 提供中英切换；?lang=en 经 LocaleChangeInterceptor 生效，Accept-Language 头亦生效。
- 不重复造 bean：ChatQuestionAnswerer 移除 @Service，仅由 McpConfig 的 @Bean 创建，避免与 questionAnswerer bean 冲突。

### 5.2 已知限制

- MCP server **默认开启**（`askdata.mcp.enabled` 缺省即开，默认传输 `http`）；如需关闭设 `askdata.mcp.enabled=false`。HTTP/SSE 暴露 `/api/v1/mcp/rpc` 与 `/api/v1/mcp/stream`，复用既有会话鉴权，未新增独立鉴权。
- ES 联调测试（EsEngineIntegrationTest）默认跳过，需设置 `ASKDATA_ES_HOST`/`ASKDATA_ES_USER`/`ASKDATA_ES_PASS` 后运行；已在 192.168.1.100:9200（elasticsearch-full 7.17.4）用专用只读账号 `es_test_rw` 实测通过：连通检查 / 索引元数据（82 个业务索引）/ 字段映射 / ES SQL 执行均正常，execSql 返回 fields/data/fields_info/sql。沙箱经 SSH 隧道 localhost:9200 访问该集群。该专用账号与 MySQL `askdata` 账号均已登记到资产 Excel 服务清单（行 56、57）。
- i18n 已覆盖登录错误、数据源服务层与 Excel 服务层文案（ds.*/sql.*/chat.*），其余业务文案未全量迁入消息包，后续可逐步扩展。
- MySQL `askdata` 专用账号为应用部署账号（caching_sha2_password），仅授予 `askdata.*`，替代 root；root 仍仅限容器 localhost。
