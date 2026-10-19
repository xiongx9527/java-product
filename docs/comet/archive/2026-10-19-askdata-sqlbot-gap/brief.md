# 目标

对照 SQLBot（DataEase 开源 Python ChatBI / 问数产品）分析 askdata（java-product 下的 Java 问数系统）的能力差距，并**补齐**相对 SQLBot 仍缺失/偏弱的问数产品能力：**MCP 集成、Elasticsearch 数据源可用化、i18n 多语言成熟度**，并核实**向量/RAG 检索成熟度**。交付：能力对照分析文档 + 上述缺口的实现与单测。

# 范围

## 目录结构

### 新建

- `docs/comet/changes/askdata-sqlbot-gap/capability-gap.md` — 能力对照与缺口分析主文档（SQLBot 能力 × askdata 是否具备、证据、缺口判定与路线图）。
- Elasticsearch 数据源引擎与方言实现（如 `datasource/engine/EsEngine.java` 及对应元数据/预览/执行 SQL）。
- MCP 服务端接入（对外暴露问数/助手/数据源工具，可对接 n8n/Dify/MaxKB/DataEase）。
- i18n 消息源与 locale 资源（Spring `MessageSource` + 中文/英文消息包）。

### 修改

- `datasource/engine/DatasourceType.java`：ES 由“占位/语义不保证”升级为可用引擎（新增 `EsEngine` 并接入）。
- 问数/助手/数据源相关 Service：暴露为 MCP 工具（注册到 MCP server）。
- 现有面向用户的文案/接口消息：迁到 `MessageSource` 多语言。

### 删除

无。

### 明确不建

- 不做跨语言代码移植（SQLBot 为 Python，askdata 为 Java），只做能力维度补齐。
- 不重写 askdata 已有能力（问数、术语库、训练、仪表盘、用户/权限、提示词、数据权限、AI 模型、推荐问题、预测、embedding/RAG、API Key、审计），只补齐缺口。
- 不实现与本次缺口无关的新产品能力。

## 差距发现（分析结论，供验收与路线图引用）

askdata 在结构上已高度对齐 SQLBot（`DatasourceType.java` 注释明确写“对齐 SQLBot”），下列能力**双方都已具备**：问数/Text-to-SQL（chat）、术语库（terminology）、数据训练（training）、仪表盘（dashboard）、用户/工作空间/RBAC（system+auth）、自定义提示词（prompt）、数据权限（permission）、AI 模型管理（AiModelService）、推荐问题（RecommendedProblemService）、预测/forecast（预测）、embedding/RAG（embedd 12 处）、API Key、审计（audit）。

相对 SQLBot，askdata **仍缺失或偏弱**的能力（按优先级 G1>G2>G3>G4）：

- **G1 MCP 集成（缺失，最大缺口）**：askdata 代码中 mcp 相关为 0 处；SQLBot 有完整 `backend/apps/mcp/mcp.py`，将问数/助手/数据源通过 MCP 对外暴露，可集成 n8n/Dify/MaxKB/DataEase 等。本 change 需新增 MCP server。
- **G2 Elasticsearch 数据源可用化（缺口）**：askdata 在 `DatasourceType.java` 声明了 ES，但归入“连接级检查 + 方言策略、驱动缺失或语义不保证”的占位，没有 `EsEngine` 实现；SQLBot 有完整 `es_engine.py` 真实查询 ES。本 change 需补齐 EsEngine。
- **G3 i18n / 多语言成熟度（偏弱）**：askdata 有 `Locale`/`i18n` 痕迹（6 处）但无 Spring `MessageSource` 体系与完整 `locales`；SQLBot 有 locales + i18n 完整多语言。本 change 需引入 MessageSource + 消息包。
- **G4 向量库/RAG 检索成熟度（观察项）**：askdata 有 embedding 用法（12 处）但无 pgvector/faiss（0 处），向量机制较轻；SQLBot 用向量做 RAG 检索。本 change 在 Build 阶段核实，若明显弱于 SQLBot 则补强，否则记录结论。

# 非目标

- 不比较性能/规模/部署形态。
- 不做具体缺口之外的业务实现。

# 验收示例

- **A1 能力对照文档**：逐维度（问数、术语库、数据训练、仪表盘、用户/权限、提示词、数据权限、AI 模型、推荐问题、预测、embedding/RAG、MCP、ES、i18n）给出 askdata 是否具备 + 证据（包/文件/类名），并列出 G1–G4 缺口判定与优先级路线图。
- **A2 ES 数据源可用化**：新增/启用 `EsEngine`，ES 数据源可完成连通检查、元数据（表/字段）、预览、执行 SQL；提供单测（ES 不可在单测内启时，以集成测试 + 文档说明兜底）。
- **A3 MCP 集成**：askdata 提供 MCP server，暴露问数（question_answer）、助手、数据源列表等工具，可被 MCP client 调用；有启动/连通验证。
- **A4 i18n**：核心用户/接口文案接入 Spring `MessageSource` + 至少中文/英文消息包，支持按请求 `locale` 切换。
- **A5 向量/RAG 核实**：核实 embedding/RAG 检索链路；若明显弱于 SQLBot 则补强（向量库/检索），否则记录结论。
