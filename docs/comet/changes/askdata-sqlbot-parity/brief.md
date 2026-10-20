# 目标

对照 SQLBot 开源社区版（/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot，DataEase 问数产品）逐项消灭 askdata 的能力差距：既补**缺失的接口**，也补**已打折的实现**。核心目标是让 askdata 的问数能力在功能与质量上与 SQLBot 社区版等价——重点是恢复被简化的 RAG/向量链路，而不是只求“接口能返回”。

# 范围

## 目录结构

### 新建

- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/embedding/` — 表级向量召回包：表文档生成、embedding 计算与落库、向量+关键词混合排序、标识符边界感知的表名/注释提及匹配、按 rank 选表、异步回填；对齐 SQLBot `apps/datasource/embedding/table_embedding.py` 与 `common/utils/embedding_threads.py`。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/datasource/embedding/` — 表级召回单测：精确表名优先、重叠名称取最长、注释提及命中、向量缺失时混合降级、TOP-N 截断、异步回填。

### 修改

- `chat/service/SchemaContextBuilder.java` — 选表由“取前 8 张勾选表”改为经表级向量召回排序后取 TOP-N；数据源选择同样接入向量（保留词法降级）。embedding model 走 `ChatModelRouter.embeddingModel()`（未配置时整体降级，不报错）。
- `system/service/UserService.java`、`system/controller/UserController.java` — 实现用户 Excel 批量导入：模板下载、批量导入（失败行记录）、`/user/errorRecord/{fileId}` 失败文件下载；替换现有抛异常的桩。
- `system/controller/AssistantController.java` — 新增 `GET /system/assistant/ds`（当前助手可用数据源，按 `public_list`/oid 过滤）。
- `common/i18n/` 消息包 — 补全剩余用户可见文案（用户/权限/仪表盘/助手/审计等模块）。
- `datasource/` 相关 Repository 与表结构 — 表级 embedding 存储列与读写（与现有 `core_table` 元数据同库）。

### 删除

无

### 明确不建

- SQLBot xpack 商业能力（行/列权限、SSO/OIDC/LDAP/CAS、企业微信-钉钉-飞书同步、License 校验、`/system/appearance*` 外观定制）— SQLBot 社区版后端本身未实现这些端点（`/system/appearance*` 只出现在鉴权白名单中），不在社区版等价范围内。
- SQLBot 的 `/mcp/*` 7 个手写 REST 端点 — askdata 已由 `fw-plus-ai-mcp-server` 提供等价工具（question_answer/datasource_list/assistant/server_info），能力等价、协议形态不同，不重复实现。
- SQLBot 小助手嵌入协议端点 `GET /system/assistant/validate/{id}`、`GET /system/assistant/app/{appId}` — askdata 已有等价的自有嵌入体系 `/system/embedded/*`（前端已改用），本 change 不重复实现第二套协议（Q1 决策 A）。
- 前端改动 — 前端已是 SQLBot 前端的适配拷贝，本 change 只改后端。

## Source coverage

覆盖边界：SQLBot 开源社区版后端（`backend/apps/**` 的 REST 能力 + 关键实现模块）的**能力等价**；不含 xpack、部署形态与性能基准。

| 来源条目与位置 | 读取状态 | 需要保留的内容（askdata 现状 → 目标） | Spec 位置 | 验收 ID | 覆盖状态 | 理由或替代关系 |
| --- | --- | --- | --- | --- | --- | --- |
| S1：SQLBot `apps/datasource/embedding/table_embedding.py` | complete | 表级向量召回（embedding+关键词混合排序、标识符边界匹配、rank 选表）缺失 → 目标对齐 | spec 第 1 节 | A1 | 待实现 | 用户明确指出“向量模型没用到”；当前仅训练样本用向量 |
| S2：SQLBot `common/utils/embedding_threads.py` | partial | 异步回填 embedding | spec 第 1 节 | A1 | 待实现 | 同步查询时向量可能未就绪 |
| S3：SQLBot `apps/system/api/user.py`（template/batchImport/errorRecord） | complete | 三端点在 askdata 为抛异常桩 → 目标真实实现 | spec 第 2 节 | A2 | 待实现 | 明确的实现打折 |
| S4：SQLBot `apps/system/api/assistant.py` 的 `/ds` | complete | 当前助手数据源列表缺失 → 目标实现（public_list/oid 过滤） | spec 第 3 节 | A3 | 待实现 | 前端 `views/chat/ChatCreator.vue` 调用，当前 404 |
| S5：SQLBot `assistant.py` 的 `validate/{id}`、`app/{appId}` | complete | 不实现（保留 askdata 自有 `/system/embedded` 体系） | — | — | non-goal | Q1 决策 A：能力已由自有嵌入体系等价提供 |
| S6：SQLBot `apps/mcp/mcp.py` 的 7 个 REST 端点 | complete | 不实现（能力由框架 MCP 等价提供） | — | — | non-goal | Q1 决策 A：协议形态不同、能力等价 |
| S7：SQLBot xpack（行列权限、SSO、平台同步、License、appearance） | complete | SQLBot 社区版后端未实现这些端点 | — | — | non-goal | 社区版不含；`/system/appearance*` 仅在白名单出现 |
| S8：SQLBot 其余已匹配端点 | complete（端点级核对） | 行为等价性以既有规格与测试为准；i18n 文案覆盖不全 → 补全 | spec 第 4 节 | A4 | 部分实现 | 上轮 gap change 自述“其余业务文案未全量迁入消息包” |
| S9：既有回归基线 | complete | 全部既有测试保持通过 | spec 第 5 节 | A5 | 待验证 | 防止补齐缺口时回退 |

# 非目标

- 不移植 xpack 商业能力（见 S7）。
- 不重写 askdata 已有且等价的模块（问数编排、术语库、训练、仪表盘、工作空间/用户、提示词、AI 模型、审计、推荐问题、Excel 数据源等）。
- 不做性能基准对比，不改变 SQLBot 的部署形态。
- 不引入外部向量库（表级 embedding 存现有元数据库，沿用自研余弦检索）。
- 不实现 SQLBot 小助手嵌入协议与 `/mcp/*` REST 面（见 S5/S6 与 Q1 决策）。

# 验收示例

- A1 表级向量召回（核心）：给定数据源下多张已勾选表，提问中显式提到某表名（含中文表名、加引号标识符）时，召回结果中该表排第一；仅提及表注释时也能命中；未配置 embedding 模型（或向量缺失）时自动降级为词法相似度且不报错；`SchemaContextBuilder` 生成的 schema 上下文按 rank 取 TOP-N 张表。
- A2 用户批量导入：`GET /user/template` 返回可下载的导入模板；`POST /user/batchImport` 导入合法 Excel 后用户可登录；导入含错误行的文件时错误行可经 `GET /user/errorRecord/{fileId}` 下载；三端点均不再返回“not supported”。
- A3 助手数据源：`GET /system/assistant/ds` 对 type=0 助手按 `public_list` 过滤、对高级应用按 oid 返回数据源列表，字段与 SQLBot 一致（id/name/description/type/type_name/num）。
- A4 i18n 补全：用户/权限/仪表盘/助手/审计模块的用户可见文案取自消息包，`?lang=en` 切换后返回英文文案；新增消息键有单测覆盖。
- A5 不回退：全模块既有测试（含 MCP E2E、问数主链路、RAG、datasource 引擎）保持通过。

# 约束与不变量

- Java 17 / Spring Boot 2.7.18 / fw-plus 版本线（构建需 JDK 17，默认 JDK 8 无法编译）。
- 向量链路必须可降级：未配置 embedding 模型、embedding 端点不可用、向量列为空时，问数主链路仍可用（词法召回），不得抛错。
- 表级 embedding 只读该用户已勾选（checked）的表；SQL 表名白名单授权逻辑不变。
- 鉴权继续复用 Sa-Token；新端点沿用 `/api/v1` 前缀与现有 `Result` 包装约定。
- 既有 DB 结构只做增量列；不更换数据库形态。

# 决策

- D1：覆盖对象为 SQLBot **开源社区版**能力，xpack 明确排除（见 S7）。
- D2：MCP 采用“能力等价”口径，不对齐 SQLBot 手写 `/mcp/*` 端点（见 S6，Q1 决策 A）。
- D3：表级 embedding 存现有元数据库（`core_table` 增量列），不引入外部向量库（Q2 决策 A：完整对齐——落库 + 混合排序 + 边界匹配 + 异步回填）。
- D4：本 change 只改后端与测试，不动前端（前端已是适配拷贝）。
- D5：不实现 SQLBot 小助手嵌入协议端点（Q1 决策 A）；保留 askdata 自有 `/system/embedded` 体系。
- D6：按 Supervisor 拆分为 5 个子任务推进（Q4 决策 A：多会话协作）；`integration-regression` 依赖其余四个子任务。
- D7：归档时把上一 change（askdata-mcp-framework）留在工作区的实现与本 change 实现一并提交（Q3 决策 A）。

# 待解决问题

无（本轮 4 个问题已由用户确认，结论落入 D2/D3/D5/D6/D7）。
