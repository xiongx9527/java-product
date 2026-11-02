# askdata E2E（Playwright）

浏览器级端到端测试，覆盖登录、主路由渲染、接口鉴权与 CRUD、定时任务闭环。

## 前置

1. 启动后端（默认 8000，元数据库使用 MySQL 8.0+/MariaDB 或 PostgreSQL 10+；H2 文件库已移除）：
   \`\`\`bash
   cd askdata/askdata-server
   mvn spring-boot:run
   \`\`\`
2. 安装依赖与浏览器：
   \`\`\`bash
   cd askdata/askdata-server/e2e
   npm i
   npx playwright install chromium   # 或使用系统 Chrome（config 已设置 channel: chrome）
   \`\`\`

## 运行

\`\`\`bash
npm test                       # 全部
npx playwright test tests/login.spec.ts
\`\`\`

默认账号 \`admin / SQLBot@123456\`（数据库初始化种子）。测试会自行创建并清理数据源与定时任务。

## 覆盖范围

| 文件 | 覆盖 |
| --- | --- |
| \`tests/login.spec.ts\` | 登录页渲染、错误口令提示、登录跳转、未登录重定向 |
| \`tests/routes.spec.ts\` | 15 个主路由逐页渲染 + 无未捕获 JS 异常 |
| \`tests/api.spec.ts\` | 健康检查、用户信息、数据源/会话列表、未授权拦截、数据源增删闭环 |
| \`tests/scheduled.spec.ts\` | 定时任务创建 → 立即执行 → 运行历史 → 停用 → 删除 |
| `tests/aimodel.spec.ts` | AI 模型配置：新增/编辑/搜索/删除闭环、默认模型保护；stub provider 驱动验证远程拉取（source=remote/preset/none）、连通校验（401→不可用/200→可用）、向量校验命中 `/v1/embeddings`、上游格式 Anthropic 命中 `/v1/messages`、五语言切换 |
| \`tests/chat-real.spec.ts\` | 真实问数闭环：选真实库 → 提问 → SSE 帧（sql/sql-data/chart/imageUrl）→ 落库数据 → 图表渲染与图片下载 |

> 说明：\`/#/set/assistant\` 在 \`web/src/router/index.ts\` 中被注释，未纳入路由冒烟。

### AI 模型配置用例与 stub provider

`tests/aimodel.spec.ts` 中「AI 模型配置（stub provider）」描述下的用例，使用 `e2e/stub-provider.mjs` 在测试进程内启动一个本地 HTTP 服务（监听 127.0.0.1 随机端口），不依赖任何真实 API Key：

- `startStub()` 返回 `{ url, port, setMode(m), resetHits(), hits(), close() }`；
- `setMode('ok' | 'unauthorized' | 'notfound' | 'empty')` 控制上游行为；
- 业务路径（仅 `/v1/*`）会被记录，可通过 `hits()` 断言前端实际命中的上游端点。

覆盖点（与控制器契约 `POST /system/aimodel/models`、`POST /system/aimodel/status` 对齐）：

| 场景 | stub 模式 | 断言 |
| --- | --- | --- |
| 远程拉取成功 | ok | 基础模型选择出现「远程」标签，唯一模型自动选中 |
| 上游 404 回退 | notfound（内置供应商） | 出现「预设」标签 |
| 自定义供应商不可达 | notfound（自定义） | 无兜底，展示错误提示 |
| 连通校验失败 | unauthorized | 卡片标记「不可用」，默认模型按钮置灰 |
| 连通校验成功 | ok | 卡片标记「可用」 |
| 向量模型校验 | ok | 命中 `/v1/embeddings` |
| 上游格式 Anthropic | ok | 命中 `/v1/messages` 而非 `/v1/chat/completions` |
| 五语言切换 | - | 自定义供应商名称随 locale 变化（自定义 / Custom / …） |

运行：

~~~bash
npx playwright test tests/aimodel.spec.ts
~~~

### chat-real 的前置

该用例不做 mock，需要后端已配置**可用的对话大模型**与一个**真实数据库数据源**
（默认名 \`e2e-mysql-askdata_verify\`，含 \`orders\` 表）；模型/数据源缺失属环境问题，不是产品缺陷。
可用环境变量覆盖：

\`\`\`bash
E2E_DS_ID=123 E2E_QUESTION='各订单状态的订单数量和总金额分别是多少？按订单状态展示' \\
  npx playwright test tests/chat-real.spec.ts
\`\`\`


## 真实环境 API 集成脚本（run-real-e2e.mjs）

run-real-e2e.mjs 是固化自真实环境验证的纯 API 链路脚本（Node >= 18，无需 Playwright/浏览器），
覆盖：登录 -> 用户信息 -> 数据源（列表/取表/取字段/选表同步）-> 会话创建 -> 问数（SSE 帧校验）
-> 记录落库数据 -> 会话列表 -> 定时任务（save/run/list/runs/stop/delete）-> 导出 -> 权限（用户 B 隔离）
-> API Key 非法拦截 -> 嵌入助手端点。

    cd askdata/askdata-server/e2e
    node run-real-e2e.mjs
    BASE=http://10.0.0.5:8080 E2E_DS_NAME=my_ds E2E_QUESTION='...' node run-real-e2e.mjs

脚本逐步打印 STEP n PASS/FAIL 并给出汇总与退出码（0=全绿）。各步失败会标注 status= 与响应摘要。

## CI 跳过策略

chat-real.spec.ts 与 run-real-e2e.mjs 都依赖真实对话大模型与真实数据库数据源，
没有这些外部环境时不应让 CI 失败。约定：

- Playwright：CI 默认只跑不依赖真实模型的用例；真实问数用例用环境变量门控，
  例如 E2E_REAL=1 npx playwright test tests/chat-real.spec.ts，CI 不设置 E2E_REAL 即跳过。
- run-real-e2e.mjs：仅在具备真实模型/数据源的 Runner 上手动或定时执行，不进入 mvn test 或默认 CI。
- 其余用例（login/routes/api/scheduled/aimodel）依赖本地元数据库（MySQL/PostgreSQL），不依赖真实对话大模型，可在 CI 默认执行（aimodel 的 stub 用例另需后端在线）。

模型输出具有不确定性：真实问数断言只校验结构/契约（SSE 帧类型、数据非空、图表配置含 multi-quota 等），
不校验固定文本。

## 企业级能力集成脚本（probe-enterprise.mjs）

`probe-enterprise.mjs` 在真实后端（默认 `http://127.0.0.1:18099`，可用 `BASE` 覆盖）上覆盖管理面企业能力，共 45 项断言：
用户/工作空间 CRUD、普通用户越权返回 403、API Key 创建/停用/删除、系统参数（multipart）读取与保存往返、
审计分页/筛选项/导出、License 与版本白名单、认证方式与 SAML 配置、平台设置、嵌入应用 CRUD 与密钥重置、
小助手 CRUD 与可用数据源、数据源授权白名单与行列权限规则 CRUD。

    cd askdata/askdata-server/e2e
    node probe-enterprise.mjs

前置：后端已启动且 `admin / SQLBot@123456` 可登录；脚本创建以 `e2e_` 前缀命名的临时用户/工作空间/
嵌入应用/小助手/权限规则并在结束时清理。新建用户口令取自部署配置 `askdata.user.default-password`
（未配置时为随机口令，脚本中普通用户登录会失败）。

## 真实环境探针清单

| 脚本 | 覆盖 | 关键环境变量 |
| --- | --- | --- |
| `run-real-e2e.mjs` | 31 步主链路（登录/数据源/问数/导出/定时任务/权限） | `BASE`、`ADMIN_PASSWORD`、`E2E_MYSQL_PASSWORD` |
| `probe-chat.mjs` | 真实问数 SSE 帧与落库数据（A3） | 同上 |
| `probe-a5.mjs` / `probe-a5-retry.mjs` | 定时任务立即执行 + 导出（A5） | 同上 |
| `probe-a6.mjs` | 10 万行大表问数（A6） | 同上 |
| `probe-mcp.mjs` | MCP 工具链路（start/question/图表） | 同上 |
| `probe-perm.mjs` | 数据源授权白名单 + 会话越权 403（A4） | 同上 |
| `probe-enterprise.mjs` | 企业级管理面 45 项（用户/空间/API Key/参数/审计/License/认证/嵌入/小助手/行列权限） | `BASE` |
| `probe-storage.mjs` | 对象存储上传→读回→替换删除（MinIO/S3） | `FILE_STORAGE_PLATFORM` |
| `probe-cluster.mjs` | 双实例跨节点：会话、助手文件、Excel 上传→导入、定时导出端点 | `BASE_A`、`BASE_B` |

集群探针不依赖负载均衡：直接交替访问两个实例更能证明「任意实例处理任意请求」。
最小集群编排：`docker compose -f e2e/docker-compose.cluster.yml up -d --build`（MySQL + Redis + MinIO + app-a/app-b），
随后 `BASE_A=http://127.0.0.1:18099 BASE_B=http://127.0.0.1:18100 node e2e/probe-cluster.mjs`。

数据库口令不写入脚本：需要 `E2E_MYSQL_PASSWORD`（root 口令）与 `ADMIN_PASSWORD`（管理员口令，默认 `SQLBot@123456`）。
