# 问数（askdata）产品设计

日期：2026-10-18
状态：待评审
载体仓库：`java-product`（本仓库）

## 1. 意图与背景

在 `java-product` 仓库内构建第一个产品「问数」：一个基于大模型的智能问数（ChatBI / NL2SQL）系统。
用户以自然语言提问，系统自动选择数据源、生成并校验 SQL、执行并返回表格与图表，并支持后续分析、预测。

实现路径：
- **后端**：从零用 Java 实现，参数级移植 SQLBot 的 Python FastAPI 后端
  （参考仓库：`/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot/backend`）。
- **前端**：整体复制 SQLBot 的 `frontend`（Vue 3 + Vite + Element Plus），只做最小补丁。
- **框架底座**：依赖 `fw-plus` 框架库层（Spring Boot 2.7.18 / fw-plus-ai-boot-starter，
  LangChain4j 自适应装配），版本 `0.1.1-SNAPSHOT`，Maven 仓库根：
  `/Users/xiongxiao/Workspaces/Idea/codes/New/java-framework`。

SQLBot 开源许可为 AGPL。本产品前端与"参考实现"（Python 后端源码）来源于此，属试用与学习场景；
后端 Java 代码为新写实现。

## 2. 目标

1. `java-product` 仓库按 `fw-plus-sample` 的"根聚合器 + 分组模块"模式组织工程（Maven）。
2. 问数产品的后端能力对齐 SQLBot 开源版后端：登录/用户/数据源/AI 模型/对话全链路/训练/术语库/仪表盘等。
3. 问数产品的前端即 SQLBot 前端拷贝，界面与交互不重写；前端对后端的 API/流协议兼容性保持原样。
4. 对话链路跑通：问题 → SQL 生成 → 只读校验 → 执行 → `sql-data` → 图表（chart）→ 分析/预测 → 推荐问题。
5. 问答核心模型接入由 fw-plus-ai 承担：OpenAI 兼容协议（openai/vllm/tongyi/ollama 等兼容端点一律按 base_url+api_key 接入），本地 Ollama 可用。
6. 元数据/业务数据默认存 H2（内嵌、开箱即跑），可切换 MySQL/PostgreSQL（配置驱动）。
7. 前端构建产物由 askdata-server 托管为静态资源；开发时 Vite dev server 代理到 Java 后端。

## 3. 非目标（本期不做）

- 不实现 SQLBot 闭源 xpack 模块（license 管理、`generateRouters` 注入的付费功能、`/xpack_static/license-generator.umd.js`）。见 §6 中替代方案。
- 不做 SQLBot 的 MCP 服务、嵌入(assistant/embedded)模式的商业侧扩展（协议入口可预留）。
- 不重写/不美化前端。

## 4. 反目标（宁可不做也不能做成这样）

- 不引入与 fw-plus 框架重复的底层组件（自建包装的 ORM 增强等）。
- 不把前端协议"改造成 Java 顺手的样子"——流协议、字段名、行为必须与 SQLBot 原版逐字兼容，否则复制的 UI 崩。
- 不为了跑通把鉴权、加密从两端"各写一套"。

## 5. 工程结构

```
java-product/                     # git 仓库根（现有 README/LICENSE/.gitignore 保留）
├── pom.xml                       # 根聚合器：packaging=pom，无 parent，版本 0.1.0-SNAPSHOT
├── docs/                         # 设计文档所在
└── askdata/                      # 产品「问数」
    ├── pom.xml                   # 产品聚合器：packaging=pom，parent=根聚合器
    └── askdata-server/           # Java 后端（唯一 Maven 构建的 jar 模块）
        ├── pom.xml
        ├── web/                  # 前端（SQLBot frontend 拷贝；npm 工程，不进 Maven 构建树）
        │   ├── package.json
        │   └── …/                # 与 SQLBot frontend 相同结构
        └── src/…/
            └── …                 # 前端 dist 构建后拷入 src/main/resources/static
```

- 命名（已与 owner 确认）：产品名「问数」，英文/模块名 **askdata**，目录 `askdata`。
- GroupId：`com.xiongx9527.askdata`（建库者判断，参考 fw-plus 的 `com.xiongx9527.*` 风格）。
- 版本：仓库根 POM 版本 `0.1.0-SNAPSHOT`；不引入 `${revision}` + flatten（产品仓库不需要多库层联动）。
- 后端依赖管理：askdata pom 的 `dependencyManagement` import 两个 BOM（与 fw-plus-ai-samples 同法）：
  - `com.xiongx9527.fw.plus.boot:fw-plus-boot-dependencies:0.1.1-SNAPSHOT`
  - `com.xiongx9527.fw.plus.ai:fw-plus-ai-boot-dependencies:0.1.1-SNAPSHOT`
- Java 版本 1.8，Spring Boot 2.7.18（随 fw-plus parent 传递）。
- 静态托管：`askdata-web` 里 `npm run build` 产物 `dist/`，经 maven-resources-plugin
  （profile `web`，默认在 package 阶段从 `../askdata-web/dist` 拷入 `src/main/resources/static`）。
  开发时：`npm run dev`（Vite dev server）+ 本地后端 8000 端口（VITE_API_BASE_URL 保持 SQLBot 默认）。

后端内部包结构（建议，可由建库者调整）：

```
com.xiongx9527.askdata
├── AskDataApplication
├── common/        # AES/MD5/JWT/Result/分页/异常/流式输出封装（移植 common/*）
├── system/        # 用户、登录、aimodel、workspace、apikey、parameter（对应 apps/system）
├── datasource/    # 数据源、表、字段、关系、行权限、excel（对应 apps/datasource）
├── chat/          # 对话全链路 + 流式协议（对应 apps/chat 与 apps/chat_record 等）
├── training/      # SQL 示例训练 + 向量库
└── settings/      # 术语库（对应 apps/terminology、apps/settings）
```

## 6. 关键移植决定（已定项）

### 6.1 xpack 闭源依赖的替代（前端补丁，最小集合）

SQLBot 前端有两处调闭源 `LicenseGenerator`：
1. 登录、aimodel 的 `sqlbotEncrypt(...)`（对 username/password/api_key 做 RSA/秘密加密）。
2. `loadXpackStatic`/`generateRouters`（拉取 `/xpack_static/license-generator.umd.js`，注入付费路由）。

补丁（AskData 前端拷贝中一次性完成，保持改动最小并加注释标记补丁点）：
- `sqlbotEncrypt` 替换为本地 AES-CBC/PKCS7 + Base64（密钥两端固定，Java 侧解密；密钥规则参
  考 SQLBot 数据源密码前端加密的前例：CryptoJS AES-ECB/PKCS7，key=`SQLBot1234567890`——该处**保留原样**，Java 侧按同 key 解密）。
- `loadXpackStatic`、`generateRouters`、request.ts 里 `X-SQLBOT-KEY` 相关注销，返回固定空逻辑。
- 付费路由（如 system/xpack 下的 PlatformParam 等）由 `generateRouters` 原本注入 → 缺省无入口，
  可用 hidden 路由原样存在但不生效。

后端相应地：
- `common/utils/crypto.py` 的 `sqlbot_encrypt/decrypt`（来至 xpack）→ Java 实现为 §6.1 的 AES 解密。
- 目标协议只是传输混淆，登录口令落库仍为 MD5（移植 `md5pwd`，种子管理员 `admin / SQLBot@123456`）。

### 6.2 流式协议（chat）

`POST /chat/question`：以 `data:{json}\n\n` 为帧（含 `id/question/info/brief/error/sql-result/sql/sql-data
/chart-result/chart/datasource/finish/…` 事件流），前端解析在
`frontend/src/views/chat/answer/*.vue` 与 `ChatRow.vue`。后端按前端解析器的 case 全集实现
（**协议以 Python 后端 + 前端解析为准，不自行发明**）。

### 6.3 数据源引擎

前端类型全集：excel、mysql、oracle、pg、sqlServer、ck、dm、doris、redshift、es、kingbase、
starrocks、hive。实现策略：
- JDBC 通用驱动封装（DriverManager/HikariCP），按 type 生成 JDBC URL/方言、类型映射、分页/limit 语法、
  元数据查询（tables/fields）。
- 分阶段覆盖（见 §8）：P2 先 mysql、pg、h2(开发默认演示库)+excel；P3 补 ck/doris/starrocks/sqlServer/sqlite；
  P4 补 oracle/dm/kingbase/redshift/es/hive。未覆盖类型在"新增数据源"界面不列出。
- JDBC driver 依赖不打进 jar（版权与体积），提供 `askdata-server` 的扩展目录约定与 README 说明。

### 6.4 AI 模型与 RAG

- `system/aimodel` 模型配置对外接口与 SQLBot 一致（CRUD/默认/连通性检查）。
  加密字段 api_key/api_domain 用 §6.1 AES 解密后 H2 内仍按 AES-128 落库（参考 `aes_crypto.py`，密钥用本应用 SECRET_KEY）。
- LLM 调用与流式补全：为了全链路（含 Embedding、memory、工具）统一走 fw-plus-ai：
  - 对话模型：OpenAI 兼容（`base_url + api_key + base_model`，兼容 openai/vllm/tongyi/ollama 兼容端点）
    以及 Ollama 原生协议。
  - Embedding 模型：OpenAI 兼容 + Ollama。
- RAG/向量：训练数据向量化可存 H2 表（`vec_json`）+ 启动后重建内存索引并提供 cosine 检索；
  如 LangChain4j 的 InMemoryEmbeddingStore 可承载则优先复用（建库者判断）。

### 6.5 数据库与 DDL

- 默认 H2（`jdbc:h2:file:./data/askdata` 或可配）；兼容 MySQL/PG 由 `spring.datasource` 配置切换，MyBatis-Plus（fw-plus-boot）作为 ORM。
- DDL：从 SQLBot `backend/alembic/versions/*.py` 整理移植成 H2 可执行的 `schema.sql`（PG 专有类型做映射），
  字段名与原版一致（与前端契约耦合的字段名不可改）。

## 7. API 面概览（移植范围）

对照 `SQLBot/frontend/src/api/*.ts` 与 `backend/apps/**`，覆盖：
`login`、`user`、`workspace`、`permissions`、`datasource`、`chat`、`dashboard`、`training`、
`prompt`、`terminology(settings)`、`system(aimodel/parameter/apikey/variable)`、`recommended`、
`audit`、`professional`、`setting/settings`、`embedded/assistant`。
具体端点以 Python 路由表与前端调用为准（实现期不断对齐），分类归属见 §5 包结构。

## 8. 交付分期

| 期 | 内容 | 验收 |
|----|------|------|
| P0 骨架 | 根/产品/后端 pom、前端拷贝+补丁、静态托管、dev 代理、登录鉴权走通、`/user/info`、公私用 Result/异常/分页规范 | 登录进入空白工作台 |
| P1 系统底座 | aimodel CRUD+默认+连通检查、appearance、workspace 骨架、user 列表/详情接口 | 能在系统配置里添加模型并"检查连通"成功 |
| P2 数据源 | 数据源 CRUD/check/getTables/getFields/execSql/previewData、mysql/pg/h2/excel、演示数据源预置 | 能添加演示 MySQL/H2 数据源并浏览表、执行 SQL |
| P3 对话核心 | `/chat/question` 全流（SQL/表格/图表/多会话/停止/推荐问题）+ analysis/predict + 数据源选定/新会话 | 与 SQLBot 前端对话体验一致（含图表出图） |
| P4 增值 | 训练/术语库/仪表盘/审计日志/参数/剩余数据源类型/等其余对齐项 | SQLBot 页面逐页对齐 |
