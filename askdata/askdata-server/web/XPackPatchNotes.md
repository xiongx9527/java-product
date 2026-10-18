# xpack 补丁点集中标注（Decisions D1）

本前端是 SQLBot `frontend` 的拷贝。SQLBot 开源版依赖闭源 `sqlbot_xpack` 包提供的
`/xpack_static/license-generator.umd.js` 全局脚本（挂载 `LicenseGenerator`）。
本拷贝不引入任何闭源代码，所有原调用点改为本地等价实现，统一以
`[askdata-xpack-patch]` 注释标记。

| # | 原调用点 | 位置 | 补丁方式 |
| --- | --- | --- | --- |
| 1 | `LicenseGenerator.sqlbotEncrypt`（登录表单、aimodel api_key/api_domain 混淆） | `src/utils/xpackPatch.ts`（新文件，挂载全局 `LicenseGenerator`） | 本地 AES-ECB/PKCS7（CryptoJS，key=`SQLBot1234567890`，Base64），与 SQLBot 数据源密码前端惯例一致；Java 侧同 key 解密互通 |
| 2 | `LicenseGenerator.getLicense()`（`stores/appearance.ts`、`views/system/user/User.vue`、`views/system/parameter/xpack/PlatformParam.vue`、`views/login/xpack/Handler.vue`） | `src/utils/xpackPatch.ts` | 固定返回 `{ status: 'invalid' }`，各页面按社区版分支降级（免费主题、隐藏同步按钮等） |
| 3 | `LicenseGenerator.generateRouters(router)`（`src/router/watch.ts`） | `src/utils/xpackPatch.ts` + `watch.ts` 标记 | 空实现，不注入付费路由 |
| 4 | `loadXpackStatic()`（`src/router/watch.ts`，原来请求 `/xpack_static/license-generator.umd.js`） | `src/router/watch.ts` | 改为 `Promise.resolve()`，不再加载远程脚本；`import '@/utils/xpackPatch'` 挂载本地 stub |
| 5 | `X-SQLBOT-KEY` 请求头（`src/utils/request.ts` 中 `LicenseGenerator.generate()`） | `src/utils/request.ts`（原版即为注释状态） | 保持注释，不发送 |
| 6 | `LicenseGenerator.init(VITE_API_BASE_URL)`（原 watch.ts） | `src/utils/xpackPatch.ts` | 空实现（返回 resolved Promise） |

## 与 Java 后端的互通约定

- 登录/改密表单字段（username/password、account/pwd/new_pwd、api_key/api_domain）：
  前端 AES-ECB/PKCS7（key `SQLBot1234567890`）→ Base64 → 后端同 key 解密 → MD5 校验口令。
- `X-SQLBOT-TOKEN: Bearer <token>` 为鉴权请求头（Sa-Token token-name/token-prefix），
  与 request.ts 的请求拦截器逐字兼容。

后端侧对应实现：`askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/auth/CryptoUtil.java`
