# AI 层文档目录（`docs/ai-layer`）

> 这套文档是**一次 AI 层改造的完整设计**，四份文件分工明确，不要混读。
> 本目录自包含：`§6.x`、`附录 A–D`、`P0 #n` 三种引用都指向本目录内的文件。
> 全部结论标注了「已实测 / 未实测」，实测部分可复跑；未实测部分在 `springboot4-springai2-upgrade.md` §11 列了清单。

## 1. 读哪一份

| 文件 | 解决什么问题 | 什么时候读 | 规模 |
| --- | --- | --- | --- |
| [`springboot4-springai2-upgrade.md`](./springboot4-springai2-upgrade.md) | 平台怎么升（SB 2.7 → 4.1.2、Spring AI）、实施顺序、验收、回退、**迁移与兼容窗口** | 决定"做不做、怎么排期" | 628 行 |
| [`ai-port-layer.md`](./ai-port-layer.md) | 端口层怎么设计（§6.1~§6.36） | 动手改代码前 | 5232 行 |
| [`risk-register.md`](./risk-register.md) | 现状有哪些坑（附录 A 图表 / B SQL / C 会话与意图 / D 模型监测） | 排查具体缺陷、写 bug 单 | 936 行 |
| [`enhancement-plan.md`](./enhancement-plan.md) | 还欠什么、先做哪个（P0 17 / P1 18 / P1-b 7 / P1-c 6 / P2 9 / P2-b 4 / P3 3） | 排优先级、切迭代 | 414 行（P0/P1/P1-b/P1-c 明细已补） |

另有两份与本套无关的文档，在上级目录：[`../deployment.md`](../deployment.md)、[`../api-integration.md`](../api-integration.md)。

## 2. 三条阅读路径

**决策者（只想知道做什么、多久、什么风险）**

1. 本文件 §1 → 四份文档的分工
2. `springboot4-springai2-upgrade.md` §0 决策记录 → §1 三条候选路径 → §10 风险与回退 → §12 结论
3. `enhancement-plan.md` §1 总表 → §3 波次建议

**实施者（要动手改）**

1. `springboot4-springai2-upgrade.md` §8 实施顺序（29 步 + 新增 13.2 / 26.5）
2. `enhancement-plan.md` §2 P0 明细（15 条，每条带代码证据与验收）
3. `ai-port-layer.md` 里对应的 §6.x（见下表）

**排查者（线上有问题）**

1. `risk-register.md` 附录 A–D 按现象找
2. 找不到 → `ai-port-layer.md` §6.16（错误模型）→ §6.26（模型侧故障）

## 3. 主题索引（§6.x → 一句话）

| 节 | 内容 | 一句话 |
| --- | --- | --- |
| 6.1~6.7 | 端口层基础 | 业务只依赖自研端口；`NativeChatPort` 保持现有行为，`SpringAi2ChatPort` / `Langchain4jChatPort` 可切换 |
| 6.8 | 重排 | `RerankPort` 默认 `weighted_score`，不引入额外依赖 |
| 6.9 | 会话与上下文 | `MemoryStore` + `ContextWindow` + token 预算，替换"只取紧邻一条" |
| 6.10 | 意图识别 | `IntentPort`，结构化输出 + 三条校验 |
| 6.11 | 模型监测 | `ModelListener` 三段回调 + 健康度 + 熔断 + OTLP 到 Langfuse / LangSmith |
| 6.12 | Rerank 页面 | 模型类型 / 字段矩阵 / 检索配置区 |
| 6.13 | 可选能力 | **可选 = 默认关闭，不是可以不实现**；每个端口都要生产实现 + Noop + 双跑 |
| 6.14 | 护栏 / 反馈 / 评估 | L0 输入 / L1 SQL / L2 输出 + `chat_feedback` + `eval_case` |
| 6.15 | 工具调用与 MCP | `ToolPort` / `ToolCallingPort`；能委托框架就委托，只自研四样 |
| 6.16 | 接口一致性 | `CallContext` / `PortError` / deadline / 重试 / 命名规则 |
| 6.17 | agentic 前瞻 | `WorkflowPort` 留口，先做 SQL 修复环 |
| 6.18 | 多模态 | 只留口：`Message.parts` / `ContentPart` / `capabilities`；语音与导出是边界适配器 |
| 6.19 | AUTH 可选 | 通用流程不假设有租户，问数才有 |
| 6.20 | Pipeline 节点 | 24 个节点 + 候选池；`GEN_SQL` / `CHART` 属 domain pack |
| 6.21 | 抽象模型 | 7 阶段 + `PipelineStep` + 3 个拦截器 + `DomainPack` |
| 6.22 | 文本输入 | 归一、长度上限、相对时间锚定；归一必须在护栏之前 |
| 6.23 | 文件输入 | langchain4j `DocumentParser` 抽象 + **Tika** 实现；三种解析器已实测对比 |
| 6.24 | 对外契约 | REST + MCP 一个内核两个 adapter；MCP 要用 `isError` |
| 6.25 | 嵌入方式 | iframe + 免登录 token；发现全仓无 CSP / `X-Frame-Options` |
| 6.26 | 模型故障转移 | 欠费/挂掉的候选链 + 熔断 + 多 key；**只在首帧前能转移** |
| 6.27 | 大数据量导出 | **接口分页**（cursor）+ 查询分页（keyset）+ SXSSF；不用多 sheet |
| 6.28 | 性能与容量 | SSE 占 Servlet 线程、默认 200 线程是天花板；压测必须 mock 模型 |
| 6.29 | 安全基线 | 威胁模型、**SSRF（9 处 `new URL(apiDomain)` 无校验）**、越权矩阵、加固清单 |
| 6.30 | 场景覆盖矩阵 | 41 个场景：完整 10 / 部分 19 / 未覆盖 12，以及决定架构的三个场景 |
| 6.31 | 智能预约（非问数） | 槽位状态机、服务目录与人工选择、确定性反问、写操作幂等与补偿 |
| 6.32 | 反问与选项（通用） | 四种触发、`clarify` 帧协议、选项来源与分档、五条硬约束 |
| 6.33 | 人工确认机制 | 四级确认、`confirmToken` 防"确认 A 提交 B"、与幂等键的分工 |
| 6.34 | Skill（本地 / 远程） | Tool / Skill / DomainPack 三层区分、`SKILL.md` 加载、远程 registry、描述召回与 token 预算 |
| 6.35 | 缓存失效协议 | `cache_epoch` 一个 epoch 管所有缓存，key 必带 epoch，不做遍历删除 |
| 6.36 | 质量基线 | 三层判定（可执行 / 结果等价 / 口径正确）+ 五个指标 + 门禁阈值 |
| 6.37 | 全链路日志 | 一次提问一棵 span 树，复用 `chat_log` 扩字段，L0/L1/L2 三级内容 |
| 6.38 | SQL 生成通用封装 | 提成纯函数式 `SqlGenerationCapability`，内联与工具调用共用；落点 `fw-plus-ai-base`、三后端薄适配 |
| 6.39 | 非业务数据源（图库 / 向量库） | Neo4j/Nebula/向量不是业务问数目标；类型可纳入 `DatasourceType`（ES 先例），问数内核只对 SQL 生效 |
| 6.40 | base 端口抽象与分工 | base 以 langchain4j 1.23.0 为主抽象、吸收 Spring AI / AgentScope；`fw-plus-ai-support` 承载增强抽象（编排 + 横切）；标注出处清单（直抄 vs 自行抽象）与「组件子模块实现 base / adapter 实现 support」分工 |

## 4. 按问题查

| 你遇到的现象 | 先看 |
| --- | --- |
| 模型欠费 / 默认模型挂了，全站不可用 | §6.26；附录 D.4 |
| 导出 300 万行 OOM 或被截断 | §6.27；附录 A.1 |
| 上传 PDF / Word 读不出内容 | §6.23 |
| 想接 Langfuse 看模型调用 | §6.11.6（本机 v3 已跑通） |
| 想接 LangSmith | §6.11.6（实现同源，无账号未实测） |
| 想接 MCP client | §6.15.6 |
| 图表 URL 泄露 / 免登录可访问 | 附录 A.1 |
| 多轮对话上下文丢失 | 附录 C.1；§6.9 |
| 默认模型改了不生效 | 附录 D.1~D.3 |
| 每问 4 次全表扫描 | 附录 C.3 |
| 意图识别拖慢首字 | 附录 C.8；§6.10 |
| 想加语音 / 图片 / 文件输入 | §6.18、§6.22、§6.23 |
| 并发一上来就卡死 / 想知道能扛多少 | §6.28 |
| 担心 SSRF、越权、密钥泄露 | §6.29 |
| 这套设计到底覆盖多少业务场景 | §6.30 |
| 要做预约 / 表单收集这类"缺参数就反问"的流程 | §6.31（+ P1-b） |
| 要让 AI 反问并给选项让人选 | §6.32 |
| 写操作前要人工确认 / 防重复提交 | §6.33 |
| 想做可插拔能力包 / 接第三方 skill | §6.34（+ P2-b） |
| 改了 schema / 权限后还在返回旧答案 | §6.35 |
| 怎么判断"答对了"、灰度拿什么卡 | §6.36 |
| 上线时 Flyway 迁移冲突、回滚后数据怎么办 | 升级文档 §5.4 |
| 想查"这次提问为什么慢 / 为什么错" | §6.37 |
| 要把 Neo4j / Nebula / 向量库接入数据源 | §6.39（注意：非业务问数目标，向量走 RAG 召回、图走专用接口或 NL2Graph） |
| base 该抽象哪些 / 适配器要实现哪些 / 每个抽象的出处 | §6.40（6.40.6 出处清单：langchain4j 直抄 / AgentScope 特殊 / 自行抽象） |

## 5. 约定

- **`§6.x` 一律指 `ai-port-layer.md`**；**`附录 A–D` 一律指 `risk-register.md`**。
- **P0 / P1 只在 `enhancement-plan.md` 内有效**；`risk-register.md` 里的 P0/P1 是另一套编号（按风险等级），两者不通用。
- **「已实测」** 表示本机真跑过，文档里给了命令/结果；**「未实测」** 表示只基于源码、字节码或官方文档推断，全部汇总在升级文档 §11。
- 行数会随修改变化，以实际文件为准。

## 6. 当前状态

- 代码：**零改动**。所有实施等 `aimodel-config-flow-revamp` 归档后开新 change。
- 文档：四份齐备，P0 已展开到「问题 / 方案 / 验收」；P1 / P1-b / P1-c 明细已补（问题 + 代码证据 + 验收，标注「已核实 / 待核实」，待核实项开新 change 时再读码确认）。
- 未闭环：`aj-mcp-client` 坐标未确认、MCP client 未联调、LangSmith 无账号、模型故障转移与导出只有设计无实现。
- 落点决定：通用接口抽象（含 §6.38 SQL 生成能力）放框架 `fw-plus-ai-base`，产品侧 `askdata-server` 接线；`fw-plus-ai-base` 是各后端 / 领域封装 / 产品共享的契约纽带（已落 langchain4j 1.23.0 形状的端口层）。三后端都是 `fw-plus-ai` 下的薄适配框架模块：langchain4j 已现成（`fw-plus-ai-langchain4j`，含 `-core` + 组件子模块），**Spring AI 新建 `fw-plus-ai-spring-ai`**，**native 新建 `fw-plus-ai-native`（含独立 `-core` 基础模块 + `-adapter` 作为 base 的具体实现并做 bean 注入 + 各模型 / 向量组件子模块；装配由 `-boot` starter 负责）**；产品现有 NativeChatPort 直连逻辑回流到 native 子模块；SQL 生成领域封装新建 **`fw-plus-ai-askdata`**（DomainPack，依赖 base、由后端 adapter 落地、不反向依赖 adapter），产品 askdata-server 只做专属接线；**base 已落 langchain4j 1.23.0 形状的完整端口层**（模型 / 嵌入 / 检索 / 消息 / 工具 / 模板 / MCP client+server / AiServices / 技能），并**新增 `fw-plus-ai-support`（base 的增强抽象层：WorkflowPort / AgenticServices 编排 + CallContext / 韧性 / 成本 / 缓存 / 幂等 / 边界 IO / 远程技能横切端口，由各 adapter 实现）**，以及**新增 `fw-plus-ai-agentscope`**（编排端口的 AgentScope 实现，Java 17 / SB4、默认关）；抽象出处与「组件子模块 vs adapter」分工见 §6.40；实现等 `aimodel-config-flow-revamp` 归档后新 change。
- 产品→框架回流（同一思路，产品只留业务接线）：`askdata-server` 的 `common/debounce` 计划外移——`RequestDebouncer` 归框架 `fw-plus-boot` 实现侧（当前框架无同名类，属迁回），其余 debounce 类归 `fw-plus-common`。
