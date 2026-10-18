# Outcome

在 `java-product` 仓库内构建产品「问数」（askdata，ChatBI / NL2SQL），对齐 SQLBot 开源版：

- 后端：Java 实现（Spring Boot 2.7.18 + fw-plus-ai 0.1.1-SNAPSHOT），SRC = SQLBot Python FastAPI 后端的开源功能面（151 个路由，含流式对话协议）。
- 前端：整体复制 SQLBot `frontend`（Vue 3 + Vite + Element Plus），只做最小补丁（见 Decisions D1），产物由 askdata-server 托管为静态资源。
- 引擎：问答主链路（文本→SQL→只读校验→执行→表格→图表→分析/预测→推荐问题）全部打通并由 fw-plus-ai 承担模型接入。
- 元数据/业务库默认 H2（内嵌开箱即跑），可配置切换 MySQL/PostgreSQL。
- Maven 结构仿 `fw-plus-sample` 的聚合器模式：根聚合 pom → askdata 聚合 pom → askdata-server。

# Scope

## 目录结构

### 新建

```text
askdata/
├── pom.xml                       — 产品聚合 POM（packaging=pom，parent=根聚合器）
└── askdata-server/
    ├── pom.xml                   — 后端工程 POM（import fw-plus-boot/ai 两个 BOM）
    ├── web/                      — 前端（SQLBot frontend 拷贝；npm 工程，不进 Maven 构建树，
    │                               build 产物 dist/ 由 maven-resources-plugin 拷入 src/main/resources/static）
    └── src/
        ├── main/java/com/xiongx9527/askdata/
        │   ├── AskDataApplication.java — 启动类
        │   ├── common/           — 鉴权(JWT/MD5)、AES、Result/分页/异常、流式帧输出（移植 common/*）
        │   ├── system/           — 登录/用户/工作空间/aimodel/parameter/apikey/variable/assistant
        │   ├── datasource/       — 数据源/表/字段/关系/预览/Excel 导入
        │   ├── chat/             — 对话全链路：会话、记录、question 流式、analysis/predict、推荐问题
        │   ├── training/         — SQL 示例训练 + 向量化检索
        │   └── settings/         — 术语库、提示词、审计、嵌入(assistant/embedded)、license 兜底
        └── main/resources/
            ├── application.yaml  — H2 默认数据源、server.port=8000、静态资源、日志
            └── schema.sql        — 从 SQLBot alembic DDL 移植的 H2 版表结构
```

前后端一体（用户决定）：二者同属 askdata-server 一个 Maven 模块、一个 jar 一个进程；`web/` 为其中嵌套的 npm 工程，仅 dist 产物进后端构建。

### 修改

- `pom.xml`（仓库根，已存在）— becomes 根聚合 POM：packaging=pom、modules=[askdata]、version 0.1.0-SNAPSHOT。
- `docs/`（已存在）— 保留设计文档；`docs/comet/` 为 Native 产物，由工作流管理。

### 删除

无

### 明确不建

- xpack 闭源补丁的对等实现（license 校验、`license-generator.umd.js`）— 见 Non-goals；前端调用点改为本地 AES 与空逻辑。
- MCP Server（SQLBot apps/mcp）— 本期预留不做，后续 change 再评估。
- 独立的鉴权服务/注册中心 — 单体内 JWT 鉴权足够。

## Source coverage

覆盖边界（用户指定「全量对齐 SQLBot」的标准）：

- 来源 A：`docs/superpowers/specs/2026-10-18-askdata-design.md`（结构、命名、分期、关键决定）。
- 来源 B：SQLBot 前端 `frontend/src/api/*.ts`（19 模块，838 行，已全部读取）与其调用的后端路由 = SQLBot 后端开源 API 面（`apps/api.py` 注册的 15 个 router，151 个端点）。
- 来源 C：SQLBot 前端解析逻辑（`views/chat/answer/*.vue` 等）定义的 `data:{...}\n\n` 流式协议。

要求保留的行为以 B、C 为准（逐字兼容）；来源 A 提供结构与工程决定。xpack 闭源面（`sqlbot_xpack`：license、ds_permission API、audit API、embedded API 的 Python 侧）以"行为可用"为标准重写，不要求逐字对齐协议细节。

| 来源条目与位置 | 读取状态 | 需要保留的内容 | Spec 位置 | 验收 ID | 覆盖状态 | 理由 |
| --- | --- | --- | --- | --- | --- | --- |
| S1 设计文档（来源 A） | complete | 工程结构/命名 askdata/fw-plus BOM 对接/分期/H2 默认库 | specs/askdata/spec.md §1-§6 | A1,A2 | covered | 已确认结构决定 |
| S2 frontend/api/login.ts + system/api/login.py | complete | /login/access-token、/change-pwd、/logout、表单 AES 混淆互通 | spec §2.1 | A3 | covered | 开源面 |
| S3 frontend/api/user.ts + system/api/user.py | complete | /user/info、pager、CRUD、pwd、status、language、ws | spec §2.1 | A3,A11 | covered | 开源面 |
| S4 frontend/api/workspace.ts + system/api/workspace.py + aimodel ws_mapping | complete | 工作空间 CRUD+uws 成员管理、aimodel×ws 映射 | spec §2.1 | A11 | covered | 开源面 |
| S5 frontend/api/system.ts + system/api/aimodel.py | complete | aimodel CRUD/默认/连通检查(流式)/list_by_ws/api_key 落库加密 | spec §2.1 | A4 | covered | 开源面 |
| S6 frontend/api/datasource.ts + datasource/api/*.py | complete | 数据源 CRUD/check/getTables/getFields/execSql/previewData/tableList/fieldList/editTable/editField/uploadExcel/parseExcel/importToDb/exportDsSchema/uploadDsSchema/syncFields/table_relation/recommended_problem | spec §2.2 | A5,A12 | covered | 开源面 |
| S7 frontend/api/chat.ts + chat/api/chat.py | complete | 会话 CRUD/rename/record 数据/usage/log、POST /chat/question 流式、analysis/predict、recommend_questions、recent_questions、excel 导出 | spec §2.3 | A6,A7,A8 | covered | 开源面，协议按 C |
| S8 views/chat/answer/*.vue 流协议解析 | complete | data:{json}\n\n 帧与 id/question/info/brief/error/sql-result/sql/sql-data/chart-result/chart/datasource/finish 等事件语义 | spec §2.3 | A6,A7,A8 | covered | 协议真源 |
| S9 frontend/api/training.ts + data_training/api/*.py | complete | SQL 示例分页/增删改/启停/导出/模板/Excel 导入（/system/data-training） | spec §2.4 | A9 | covered | 开源面 |
| S10 frontend/api/professional.ts + terminology/api/*.py | complete | 术语库分页/CRUD/启停/导出/模板/导入（/system/terminology） | spec §2.4 | A9 | covered | 开源面；pr/L3 |
| S11 settings.ts(term 部分)--/system/custom_prompt* | complete | 自定义提示词分页/CRUD/导出（/system/custom_prompt，xpack 起点路由已开源于 alembic 046） | spec §2.4 | A9 | covered | D4 约束下实现 |
| S12 frontend/api/dashboard.ts + dashboard/api/*.py | complete | 仪表盘资源 CRUD/加载/画布/check_name/move | spec §2.4 | A10 | covered | 开源面 |
| S13 frontend/api/audit.ts + common/audit 模型 | complete | 审计日志分页/选项/导出（/system/audit，开源后端路由注释掉） | spec §2.4 | A13 | covered | xpack 依赖面→按行为重写(D4) |
| S14 frontend/api/permissions.ts + sqlbot_xpack ds_permission | complete | /ds_permission list/save/delete 数据权限配置 | spec §2.4 | A13 | covered | xpack 依赖面→按行为重写(D4) |
| S15 frontend/api/embedded.ts + system/api/assistant.py | complete | 嵌入应用(assistant) CRUD/picture/ui 与 embedded 管理 | spec §2.4 | A13 | covered | xpack 依赖面→按行为重写(D4) |
| S16 frontend/api/setting.ts(settings/terminology/download) + settings/api/base.py | complete | /settings/terminology 分页/CRUD、download-fail-info | spec §2.4 | A9 | covered | 开源面 |
| S17 frontend/api/variables.ts + variable_api.py | complete | 系统变量 save/delete/listAll/listPage | spec §2.1 | A11 | covered | 开源面 |
| S18 frontend/api/license.ts + common/utils/crypto.py | complete | /system/license 读取兜底（社区版信息），不实现校验 | spec §2.4 | — | non-goal | D4；前端页面可用即可 |
| S19 backend alembic/versions/*.py DDL | complete | 表结构全量移植为 H2 schema.sql（字段名不变） | spec §4 | A3 | covered | 结构契约 |
| S20 前端登录/鉴权 xpack 调用点（watch.ts/loadXpackStatic 等） | complete | 补丁为本地 AES + 空逻辑（D1） | spec §3 | A2,A3 | covered | 闭源不可得→D1 |

# Non-goals

- 不实现 SQLBot 闭源 xpack 模块对等：license 校验/托管、`/xpack_static/license-generator.umd.js`、付费路由注入（`generateRouters`）；`/system/license` 返回社区版兜底信息。
- 不移植 MCP Server（SQLBot apps/mcp）与嵌入端商业扩展；`/mcp` 路由本期不提供。
- 不重写/美化前端界面、不改 SQLBot 的交互与协议。
- 不做 SQLBot 多实例部署相关（分布式锁、外部 Postgres 迁移体系 alembic 运行时化）。

# Acceptance examples

- A1 Maven 构建：`mvn -Pskip-test package`（或等价命令）在根目录对 `askdata/askdata-server` 构建成功，产出可独立运行的 jar；根/产品/模块三级 POM 与 BOM 引入路径正确。`Acceptance: A1`（spec §1 场景 1）
- A2 前端构建与补丁：`askdata/askdata-server/web`（SQLBot 前端拷贝）完整可 `npm install && npm run build` 产出 dist；xpack 补丁点集中标注；托管进 askdata-server 后以 `./api/v1` baseURL 正常访问。`Acceptance: A2`（spec §1 场景 2）
- A3 登录闭环：启动后访问 http://localhost:8000 出现登录页，`admin/SQLBot@123456` 登录进入工作台；/login/access-token、/user/info、鉴权过滤、登出生效；MD5 口令与 xpack→AES 混淆互通。`Acceptance: A3`（spec §2.1 场景）
- A4 AI 模型配置：系统-AI 模型页可新增 Ollama 与 OpenAI 兼容模型、设默认、连通检查（/system/aimodel 全端点）；重启后配置不丢；api_key 前端 AES→Java 落库加密。`Acceptance: A4`（spec §2.2 场景）
- A5 数据源管理：在数据源页面添加 MySQL/H2/PostgreSQL 数据源、浏览/勾选表与字段、执行 SQL、预览数据、编辑表/字段注释、Excel 上传建表（/datasource 基础面 + excel 类型）。`Acceptance: A5`（spec §2.2 场景）
- A6 对话 SQL+表格：预置演示数据源+模型后，新会话提问，流式返回 id/question/info/brief/sql/sql-data/finish 帧并渲染表格；token 用量/记录日志/recent_questions/recommend_questions/会话分页可用。`Acceptance: A6`（spec §2.3 场景）
- A7 图表：提问返回 chart/chart-result 帧，前端渲染柱/饼图等（G2 图表配置由模型产出）。`Acceptance: A7`（spec §2.3 场景）
- A8 分析与预测：记录级 analysis/predict 调用可用并按相应帧返回渲染。`Acceptance: A8`（spec §2.3 场景）
- A9 训练+术语+提示词：数据训练、术语库、自定义提示词三组管理页 CRUD/启停/导入导出让用户可用。`Acceptance: A9`（spec §2.4 场景）
- A10 仪表盘：仪表盘资源/画布 CRUD+加载可用，可在前端创建并打开仪表盘。`Acceptance: A10`（spec §2.4 场景）
- A11 系统/工作空间/参数/变量/API Key：用户分页+CRUD+重置密码、工作空间与成员、参数页、系统变量、API Key 各页面对后端调用全部正常。`Acceptance: A11`（spec §2.1/2.4 场景）
- A12 数据源类型完备性：新增数据源类型下拉含 13 类（excel+12 库），必测类型（MySQL/H2/Postgresql 等）真实连通；各类型至少具备连接测试与方言元数据策略。`Acceptance: A12`（spec §2.2 场景）
- A13 审计/数据权限/嵌入：审计日志页分页+导出；数据权限配置可用；嵌入应用(assistant/embedded)列表及密钥生成可用。`Acceptance: A13`（spec §2.4 场景）

# Constraints and invariants

- Java 8 + Spring Boot 2.7.18 + MyBatis-Plus；唯一后端模块无多版本分支。
- 只能通过 BOM 引入 fw-plus 依赖（`fw-plus-boot-dependencies` / `fw-plus-ai-boot-dependencies` 0.1.1-SNAPSHOT）；禁引闭源 xpack。
- 流式协议帧格式与事件语义逐字兼容 SQLBot 前端（data:{json}\n\n + type 语义）。
- 元数据表字段名与 SQLBot DDL 一致（前端/移植代码契约）。
- 口令传输走 D1 的 AES 混淆；服务器/Metadata 库内仍为 MD5 hex。
- 鉴权统一使用 Sa-Token（D10）：复用 fw-plus-core 的 `fw-plus-auth-satoken`（sa-token 1.46.0，由 fw-plus-dependencies 管理），不自写 JWT 与鉴权过滤器。
- 提审时必须给出可复现命令与结果。

# Decisions

- D1 xpack 替代（我判断，参考 CryptoJS 前例）：前端拷贝内 `LicenseGenerator.sqlbotEncrypt` → 本地 AES（CryptoJS 可用，key 独立配置），Java 侧同 key 解密；`loadXpackStatic`/`generateRouters`/`X-SQLBOT-KEY` 改为空实现。数据源密码保持 SQLBot 原样加密（key `SQLBot1234567890`）。
- D2 命名：产品「问数」，目录/模块 `askdata`（用户确认）。GroupId `com.xiongx9527.askdata`。
- D3 静态托管：`spring.web.resources` 直接挂 dist，VITE_API_BASE_URL 保持 `./api/v1`；开发模式 Vite dev server 代理到后端。前后端同属 askdata-server 模块（用户决定）：`askdata/askdata-server/web/` 为 npm 工程，dist 拷入 resources/static。
- D4 xpack 依赖面（audit/ds_permission/embedded/license）Java 侧按"行为可用"重写，协议细节自定，不在 D1 补丁闭源调用点。
- D5 RAG/向量：训练与表结构 embedding 向量存 H2(JSON) 并启动重建内存索引，复用 fw-plus-ai 的 Embedding 模型接口。
- D6 引擎覆盖分期：P2=MySQL/H2/PostgreSQL/excel；P4=其余（ck/doris/starrocks/sqlServer/sqlite/oracle/dm/kingbase/redshift/es/hive）。JDBC 驱动不打进 jar，扩展目录约定。
- D7 模型环境（用户确认，Q1）：Ollama 与 OpenAI 兼容两种都支持且都可用于验收；验收默认用本机 Ollama（http://127.0.0.1:11434，gemma3 系列对话 + bge-m3/nomic-embed-text embedding）。
- D8 演示数据源（用户确认，Q2）：192.168.1.100:3306 的 MySQL 5.7（root/Root57@123456），新建专用演示库 `askdata_demo`（订单/用户/商品样例表），由后端首次启动或初始化脚本建库。
- D9 Supervisor 拆分与推进（用户确认，Q3）：按 5 个子任务拆分（skeleton-auth → system-foundation → {datasource-engine ∥ value-add-modules} → chat-core，children.yaml 已登记）；推进方式=A 多会话协作（独立会话/Agent 优先，不可用自动改 subagent）。
- D10 鉴权（用户决定）：登录态与管理统一用 Sa-Token——登录后 `StpUtil.login` 签发 token，鉴权用 Sa-Token 拦截/注解；token 从 `X-SQLBOT-TOKEN: Bearer <token>` 读取（token-name/token-prefix 按此配置）；复用 fw-plus-auth-satoken 封装（SaTokenJwtAuthService/SaTokenLoginAuthService、AuthHeaderExtractor），不自写 JWT/过滤器；管理员判定与 MD5 口令校验逻辑不变。

# Open questions

无（Q1→D7、Q2→D8、Q3→D9 已确认）。
