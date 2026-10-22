# Capability: askdata-sqlbot-xpack-parity

askdata 智能问数 / ChatBI 能力在覆盖 SQLBot 开源能力的基础上，补齐 xpack 商业能力，并消除既有「实现打折」项。本规格描述变更后该能力的完整行为。

## 1. NL2SQL 方言感知（A1）

- 主聊天问数路径在生成 SQL 前，依据所选数据源的 `DatasourceType` 取得方言标识（如 `PostgreSQL`、`Oracle`、`ClickHouse`、`MySQL` 等）并注入 system prompt。
- 方言信息同时驱动引号包裹、分页写法（`LIMIT` / `ROWNUM` / `TOP`）、类型名与保留字转义，使生成的 SQL 与目标库语法一致。
- 对非 MySQL 数据源，生成的 SQL 必须在该方言下语法正确且可执行；由面向非 MySQL 数据源的集成/单测验证。

## 2. MCP 与助理 NL2SQL 路径对齐（A2）

- `AskdataNl2Sql` / `AskdataSqlRetriever` / `AskdataAssistantConfig` 中不得再出现字面量 `"MySQL"`，也不得再每次问数通过 `DatabaseMetaData` 全表扫描生成完整 schema。
- MCP `question_answer` 与 `SimpleAssistant` 复用与主聊天一致的流程：top-N RAG schema 选择、方言注入、只读单语句校验。
- 对非 MySQL 数据源，MCP 问数产出与方言一致的 SQL。

## 3. 多轮上下文与校准降级可观测（A3）

- 追问（follow-up）时，将前序问答上下文注入 SQL 生成，使后续问题能引用前文实体与口径。
- 术语库 / SQL 示例等校准知识在以下情况显式记录降级，不再静默失效：未配置 embedding 模型（退化为词法匹配）、对应知识表缺失（关闭 few-shot）。降级须可在日志/状态中观测。

## 4. 服务端图表出图（A4）

- 提供独立于前端运行时的服务端图表渲染能力（等价 SQLBot `g2-ssr`），接收 G2 图表配置，输出 PNG。
- MCP / 非交互问答与导出场景在存在图表时返回图片 URL。

## 5. 导出与密钥治理（A5）

- 问数结果除现有 XLSX 导出外，支持 CSV 导出。
- 移除代码与 `schema.sql` 中的硬编码口令/密钥字面量（如 `SQLBot@123456`、demo 数据源 `root/Root57@123456`、`AskDataSecret001`）。密钥/口令改为配置项，缺省值不写入版本库；配置缺失时应用可正常启动。

## 6. 非 MySQL 引擎执行保证（A6）

- 声明的 13 种数据源中，`oracle`/`dm`/`kingbase`/`redshift`/`es`/`hive` 等非 MySQL 引擎，在对应 JDBC 驱动就位时，元数据（表/字段/类型）可提取且样本查询可执行。
- 补全缺失驱动依赖与方言策略（引号、分页、类型映射），并以测试或容器验证覆盖。

## 7. 外部身份提供方（A7）

- 在既有 LDAP/SSO 基础上，扩展支持 OIDC、CAS、SAML2、企业微信、钉钉、飞书、Lark 授权登录。
- 每种协议完成授权登录后自动开通/绑定本地账号；提供可配置端点；支持以 mock/测试 IdP 验证授权码流程。

## 8. 动态数据源高级应用（A8）

- 高级应用（assistant type 1/3）支持在每次请求时从宿主拉取数据源（动态数据源），该数据源不被持久化。
- 动态数据源的问数行为与静态数据源路径一致，并有集成测试覆盖。

## 9. 商业级行/列权限（A9）

- 行/列权限支持 advanced-app 知识作用域隔离。
- 权限模型对齐 SQLBot 商业级表达：基于 `DsPermission`/`DsRules`（含 `user_list`/`permission_list` JSONB 匹配），多规则组合为行过滤 AND、列权限取交集。
- 运行时通过 SQL 改写强制：行级将过滤表包装为子查询 `SELECT * FROM (SELECT * FROM t WHERE <filter>) t`，列级从结果集剔除无权限列；管理员/白名单短路。

## 非目标

- 不重写已等价实现的开源能力（workspace、i18n、dashboard、分析/预测/推荐、上传建数据源、越问越准）。
- 不实现 SQLBot 未提供的定时调度推送。
- 不替换 Sa-Token 登录主体。
