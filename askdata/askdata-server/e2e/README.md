# askdata E2E（Playwright）

浏览器级端到端测试，覆盖登录、主路由渲染、接口鉴权与 CRUD、定时任务闭环。

## 前置

1. 启动后端（默认 8000，H2 文件库）：
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

默认账号 \`admin / SQLBot@123456\`（H2 初始化种子）。测试会自行创建并清理数据源与定时任务。

## 覆盖范围

| 文件 | 覆盖 |
| --- | --- |
| \`tests/login.spec.ts\` | 登录页渲染、错误口令提示、登录跳转、未登录重定向 |
| \`tests/routes.spec.ts\` | 15 个主路由逐页渲染 + 无未捕获 JS 异常 |
| \`tests/api.spec.ts\` | 健康检查、用户信息、数据源/会话列表、未授权拦截、数据源增删闭环 |
| \`tests/scheduled.spec.ts\` | 定时任务创建 → 立即执行 → 运行历史 → 停用 → 删除 |

> 说明：\`/#/set/assistant\` 在 \`web/src/router/index.ts\` 中被注释，未纳入路由冒烟。

