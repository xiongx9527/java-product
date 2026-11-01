# askdata 统一认证对接（SSO / 企业 IM 集成）

本文面向把 askdata 接入统一身份认证（IdP）或企业 IM（飞书 / 钉钉 / 企业微信 / LarkSuite）的运维与集成方，说明认证源类型、管理端点、登录与登出流程、回调地址规则、用户属性映射与账号对齐，以及各协议在真实环境里踩过的坑。

后端契约集中在：

| 组件 | 职责 |
| --- | --- |
| `AuthenticationService` | SSO 编排：授权 URL、换码、本地用户解析 |
| `AuthenticationController` | `/system/authentication/**` 端点 |
| `SsoAuthenticator` | JustAuth 封装（OIDC / OAuth2）、属性映射 |
| `CasSsoClient` | CAS 授权 URL、`serviceValidate` 解析 |
| `LdapSsoClient` | LDAP 两段 bind 登录 |
| `Saml2SsoClient` | SAML2 断言解析（后端已实现，见 §6.5） |
| `SsoLogoutUrlResolver` | 登出联动地址 |
| `EnterprisePlatformService` | 企业 IM 扫码、组织树、clientInfo |
| `PlatformSyncService` | 企业 IM 用户批量同步 |

> 前端入口：`web/src/views/login/xpack/*`（登录页）与 `web/src/views/system/authentication/*Editor.vue`（配置页）。

## 1. 认证源类型总览

认证源统一存于 `sys_authentication`，`type` 区分协议 / 平台：

| type | 名称 | 类别 | 说明 |
| --- | --- | --- | --- |
| `1` | CAS | SSO 协议 | 回调地址用 `casCallbackDomain`，回调只带 `ticket` |
| `2` | OIDC | SSO 协议 | 标准 `redirect_uri` + `state` |
| `3` | LDAP | SSO 协议 | 目录服务，无授权跳转，直接账号口令登录 |
| `4` | OAuth2 | SSO 协议 | 标准 `redirect_uri` + `state` |
| `5` | SAML2 | SSO 协议 | 后端协议已实现，但管理台/登录页均无入口，当前不可用（见 §6.5） |
| `6` | 企业微信 | 企业 IM | `corpid` / `agent_id` / `corpsecret` / `redirect_uri` |
| `7` | 钉钉 | 企业 IM | `corpid` / `agent_id` / `client_id`(AppKey) / `client_secret` |
| `8` | 飞书（国内 `open.feishu.cn`） | 企业 IM | `client_id` / `client_secret` |
| `9` | LarkSuite（国际 `larksuite.com`） | 企业 IM | `client_id` / `client_secret` |

- type `1..5`：`AuthenticationController` 的 `login/{type}` / `sso/{type}`。
- type `6..9`：`PlatformController` 的 `client/{origin}` / `sso/{type}`，另带组织树与用户同步。

## 2. 管理端点

登录态用 Sa-Token：请求头 **`satoken: Bearer <token>`**（`Bearer` 前缀必需）。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/system/authentication` | 管理员 | 认证源列表（含 `config` / `enable` / `valid`） |
| GET | `/api/v1/system/authentication/status` | 管理员 | 同上（管理台状态列） |
| GET | `/api/v1/system/authentication/{id}` | 管理员 | 单个认证源（Editor 加载） |
| POST | `/api/v1/system/authentication` | 管理员 | 新增（`{id,type,name,config}`，`config` 为 JSON 字符串） |
| PUT | `/api/v1/system/authentication` | 管理员 | 修改 |
| PATCH | `/api/v1/system/authentication/enable` | 管理员 | 启停（`{id,enable}`） |
| PATCH | `/api/v1/system/authentication/status` | 管理员 | 连通性校验（「测试连接」） |
| GET | `/api/v1/system/authentication/platform/status` | 公共 | 全部认证源 + 聚合项 `{name:"qrcode",enable}` |
| GET | `/api/v1/system/authentication/login/{type}` | 公共 | 返回第三方授权 URL |
| POST | `/api/v1/system/authentication/sso/{type}` | 公共 | SSO 换码登录 |
| POST | `/api/v1/login/logout` | 公共 | 登出，返回 IdP 登出地址（见 §7） |
| GET | `/api/v1/system/platform` | 管理员 | 企业 IM 平台卡片列表（type ≥ 6） |
| GET | `/api/v1/system/platform/client/{origin}` | 公共 | `clientInfo`（前端渲染二维码用） |
| POST | `/api/v1/system/platform/org/{id}` | 管理员 | 组织 / 用户树 |
| POST | `/api/v1/system/platform/user/sync` | 管理员 | 批量同步平台用户 |
| POST | `/api/v1/system/platform/sso/{type}` | 公共 | 企业 IM 扫码登录 |

`sso/{type}` 的响应统一为：

```json
{"code":0,"data":{"access_token":"<sa-token>","exp":691200,"platform_info":{"origin":2,"account":"sunsx"}}}
```

## 3. 登录流程

### 3.1 标准 SSO（type 1..5）

1. 前端 `GET /system/authentication/login/{type}`，后端 `toLogin` 生成 IdP 授权 URL。
2. 浏览器跳 IdP，用户完成认证。
3. IdP 带 `code` / `state` / `ticket` 回跳 `redirect_uri`（即 askdata 前端首页）。
4. 前端识别回调参数 → `POST /system/authentication/sso/{type}`，后端用同一 `redirect_uri` 换码、解析本地用户、签发 token。

**回调识别依赖 state 标记**。后端在 `toLogin` 里生成 `state = fit2cloud-<源>-<随机串>`，前端 `Handler.vue` 用 `state.includes(源名)` 判断是哪个源：

| 源 | state 标记 |
| --- | --- |
| CAS | **没有 state**，靠「URL 带 `ticket` 且 CAS 已启用」识别 |
| OIDC | `fit2cloud-oidc-*` |
| OAuth2 | `fit2cloud-oauth2-*` |
| LDAP | 无跳转，表单直接提交 |
| SAML2 | `fit2cloud-saml2-*` |

> 自建 IdP 若自定义 state（例如直接用 JustAuth 默认随机 state），前端会当成普通访问而不发起换码，表现为「登录完停在登录页」。

### 3.2 企业 IM（type 6..9）

1. 前端 `GET /system/platform/client/{origin}` 取 `client_id` / `corpid` / `redirect_uri`。
2. 拼授权 / 二维码 URL（飞书、钉钉走各自 JS SDK）。
3. 用户扫码授权后，平台带 `code` 回跳 `redirect_uri`。
4. 前端 `POST /system/platform/sso/{type}`，后端用 `code` 换平台用户 → 本地用户 → token。

### 3.3 LDAP（type 3）

无跳转阶段。登录页 LDAP 表单把 `{username,password}` 直接 POST 到 `/system/authentication/sso/3`；`toLogin/3` 会返回「LDAP 不支持授权重定向」。

## 4. 回调地址（redirect_uri）

`AuthenticationService.resolveRedirect(config, request)` 的优先级：

1. **配置里显式填写的值**：`redirect_uri` → `redirect_url` → `casCallbackDomain`（去空格）。
2. 都为空时才按当前访问来源动态推导（`RequestBaseUrl.resolve`）。

动态推导优先级（`RequestBaseUrl`）：

1. 反代透传 `X-Forwarded-Proto` + `X-Forwarded-Host`（可选 `X-Forwarded-Port`）；
2. 浏览器同源请求的 `Origin`；
3. `Referer`；
4. 请求自身 `scheme/host/port`。

结果追加 `contextPath` 并以 `/` 结尾，例如 `https://25946kt718.goho.co/`。

要点：

- **配置优先**是为了兼容「IdP 只登记了隧道域名、本地用 127.0.0.1 调试」的场景：此时把 IdP 登记值填进配置，就不会被 IdP 以 `Invalid redirect` 拒绝。
- `toLogin` 与 `ssoLogin` 用同一函数取值，保证授权与换码两端 `redirect_uri` 逐字一致（否则 `redirect_uri mismatch`）。
- 管理台 Editor 的「回调地址」校验只校验 URL 形态，不再要求等于当前访问域名。
- 企业 IM 的 `redirect_uri` 由前端 `getSQLBotAddr()`（`origin + pathname`）或 `clientInfo` 里配置的固定值提供，不走上面这套推导。

## 5. 用户属性映射与账号对齐

### 5.1 mapping 字段

配置项 `mapping` 是 JSON 字符串，键为本地字段、值为 IdP 属性名：

```json
{"account":"user","name":"displayName","email":"email","platform_uid":"username"}
```

| 本地字段 | OIDC / OAuth2 | CAS | LDAP |
| --- | --- | --- | --- |
| `account` | `user` → `preferred_username` → `userId` → `uuid` → `email` → `username` | `username` → `uid` → `email` → `<cas:user>` | `uid` → `cn` → `sAMAccountName` → 登录名 |
| `name` | `name` → `displayName` → `nickname` → `username` → `uuid` | `displayName` → `cn` → `realName` → `<cas:user>` | `cn` → `displayName` → `realName` → 登录名 |
| `email` | `email` | `email` → `mail` → `emailAddress` | `mail` → `emailAddress` |
| `platform_uid` | `uuid` → `sub` → `username` → `userId` → `email` | `<cas:user>` | 登录名 |

邮箱**不回退到用户名**（避免把账号当邮箱写入），取不到就留空。

映射优先级：mapping 指定的属性有值就用它，否则走上面的缺省顺序。

### 5.2 本地账号对齐

`resolveLocalUser` 的顺序：

1. `sys_user_platform` 已有 `(origin, platform_uid)` 绑定 → 直接用绑定用户；
2. 未绑定但本地存在同 `account` 的用户 → 复用该用户并建立绑定（例如 OIDC 与 LDAP 同一个人的账号可以收敛到一条记录，`origin` 保持原值）；
3. 都没有 → 自动建号，`origin` 记为本次来源，归属默认工作空间。

企业 IM（origin ≥ 6）的已绑定用户，每次登录会用平台最新 `name` / `email` 刷新本地资料；账号仍是「平台原始 ID」时才会同步为更友好的账号，手工改过的账号不覆盖。

用户被禁用（`status != 1`）或无工作空间（`oid = 0`）时登录会被拒绝。

## 6. 各认证源对接要点

### 6.1 CAS（type 1）

配置字段：

| 字段 | 说明 |
| --- | --- |
| `idpUri` | CAS 服务基地址，如 `http://127.0.0.1:19527/sign/authz/cas` |
| `casCallbackDomain` | Service 地址；留空则按当前访问来源推导 |
| `mapping` | 属性映射 |

- 授权：`{idpUri}/login?service={service}`；换票：`{idpUri}/serviceValidate?service=&ticket=`。
- `service` 必须与 IdP 侧登记值**逐字一致**（含结尾斜杠），否则 IdP 侧会以「service 未注册」拒绝。
- ticket 一次性，重复校验返回 `INVALID_TICKET`。
- 属性节点通常带命名空间前缀（`<cas:username>`），中文值常被编码为 `base64:xxx`；`CasSsoClient` 已去前缀并解码，接入其它 IdP 时注意同样处理。

### 6.2 OIDC（type 2）

配置字段：`client_id`、`client_secret`、`metadata_url`、`authorize_url`、`token_url`、`userinfo_url`、`scope`、`redirect_uri`、`mapping`、`end_session_endpoint`。

- 走 JustAuth `AuthRequest`；`metadata_url` 能取到端点时可只填它。
- 回调换码用 `redirect_uri`（与授权时一致）。
- `end_session_endpoint` 用于登出联动（见 §7）。

### 6.3 OAuth2（type 4）

配置字段：`authorize_url`、`token_url`、`userinfo_url`、`scope`、`client_id`、`client_secret`、`token_auth_method`（`form` / `header`）、`userinfo_auth_method`、`redirect_uri` / `redirect_url`、`mapping`、`end_session_endpoint`。

- 非标准 OAuth2（没有 `metadata_url`）时四个端点都要填。
- userinfo 若返回 `{code,message,data:{...}}` 会自动取 `data`，并兼容 camelCase（`displayName` / `userId` / `user`）。

### 6.4 LDAP（type 3）

配置字段：

| 字段 | 说明 |
| --- | --- |
| `server_address` | `ldap://host:port` |
| `bind_dn` / `bind_pwd` | 查询用的服务账号 |
| `ou` | 搜索基点，如 `dc=test,dc=local` |
| `user_filter` | 用户过滤器，`?` 为账号占位，如 `(uid=?)` |
| `mapping` | 属性映射（默认 `uid` / `cn` / `mail`） |

登录是两段 bind：服务账号 bind → 按 `ou` + `user_filter` 找 DN → 以该 DN + 用户口令 bind → 读属性。

错误语义（用于区分问题）：

| 报错 | 含义 |
| --- | --- |
| `LDAP user not found: <账号>` | 过滤器没匹配到用户 |
| `LDAP login failed: invalid credentials for <账号>` | 用户口令错误 |
| `LDAP bind failed: ...` | 服务账号 / 服务地址有问题 |

### 6.5 SAML2（type 5）—— 当前不可用

后端 `Saml2SsoClient` 已实现协议部分（`authorizeUrl`、`parseResponse` 解析断言、`metadata` / `logoutEndpointFromMetadata`），但**当前无法配置也无法登录**：

- 管理台认证源列表把 saml2 过滤掉了（`views/system/authentication/index.vue` 里 `filter(item => item.name !== 'saml2')`），`SAML2Editor.vue` 虽存在但入口不可达；
- 登录页 `Handler.vue` 未渲染 SAML2 按钮，`third_party_authentication` 对 `saml2` 回调直接返回 null。

所以不要按「已支持 SAML2」对外承诺。要启用需：放开列表过滤、补登录页入口、让 `Handler` 处理 `fit2cloud-saml2-*` 回调。

### 6.6 企业微信（type 6）

字段：`corpid`、`agent_id`、`corpsecret`、`state`、`redirect_uri`。

依赖企业微信后台的「可信域名（企业域名）」与「企业可信 IP」：两者不满足时，`gettoken` 可能成功，但 `department/simplelist`、`user/simplelist` 会返回 `60020 not allow to access from your ip`，扫码登录也无法端到端验证。

### 6.7 钉钉（type 7）

字段：`corpid`、`agent_id`、`client_id`(AppKey)、`client_secret`、`redirect_uri`。

- 换 token 走 `oapi.dingtalk.com/gettoken?appkey=&appsecret=`，与 `topapi/*` 同 host（`api.dingtalk.com/v1.0/oauth2/accessToken` 在 oapi 上会 404）。
- `clientInfo` 返回 `client_id` + `corpid` + `redirect_uri`，前端 DTFrameLogin 需要 `client_id`。

### 6.8 飞书 / LarkSuite（type 8 / 9）

字段：`client_id`、`client_secret`、`redirect_uri`。

- 国内应用用 type 8（`open.feishu.cn`），国际应用用 type 9（`larksuite.com`），**不可混用**。
- `clientInfo` 返回固定 `redirect_uri`（配置值），避免按访问域名动态生成导致与后台登记不一致。

## 7. 登出联动

`POST /api/v1/login/logout`，body `{origin}`（`origin` 取登录时 `platform_info.origin`，0 表示本地登录）：

- 先 `StpUtil.logout()` 清本地会话；
- `origin != 0` 时返回 `data = IdP 登出地址`（解析不到则 `null`）。

`SsoLogoutUrlResolver` 取地址的顺序：

1. 配置显式项：`logoutUrl` / `logout_url` / `endSessionEndpoint` / `end_session_endpoint` / `sloUrl` / `slo_url`；
2. CAS：`{idpUri}/logout?service={casCallbackDomain}`；
3. SAML2：从 `idpMetaUrl` 的元数据取 SLO 端点。

返回前统一追加回跳参数：`redirect_uri=<当前访问来源>`，并对标准 OIDC 额外附带 `post_logout_redirect_uri`。回跳地址取 `Origin`，没有则用配置里登记的回调地址。

前端 `stores/user.ts` 拿到非空字符串即整页跳转，由 IdP 终止单点会话后再回到本系统登录页。

> 若 IdP 不认回跳参数（例如只跳自己的登出页），浏览器会停在 IdP 页面。此时改用 IdP 支持的参数名（不少实现用 `service` / `post_logout_redirect_uri`），或让 IdP 侧登记登出后跳回地址。

## 8. 企业 IM 组织树与用户同步

### 8.1 组织 / 用户树

`POST /api/v1/system/platform/org/{id}`，body `{lazy, pid}`（`lazy` 支持 `true/false` 与 `1/0`），返回：

```json
{"tree":[{"id":"811003770","name":"测试部门","options":{"is_user":false},"children":[...]},
         {"id":"manager1054","name":"熊枭","options":{"is_user":true,"email":"..."}}]}
```

- 每一层都**先返回该层的直属成员，再返回子部门**；展开某个部门时同样能看到「本部门成员」，而不是只有子部门。
- 用户节点 `id` 用可读的 `user_id`（飞书回退 `open_id`），与登录账号一致。
- 平台接口报错（如企业微信 60020）会直接抛出可读错误，不再静默返回空树。

### 8.2 用户同步

`POST /api/v1/system/platform/user/sync`，body：

```json
{"origin":7,"cover":false,"user_list":[{"id":"<平台用户ID>","name":"熊枭","email":"a@b.c","account":"xiongxiao"}]}
```

- 账号取 `account`，为空时用 `email`，再为空用平台用户 ID；**不再用姓名当账号**。
- 返回 `{total,created,updated,skipped,successCount,errorCount,users}`，前端用 `successCount` 判断是否需要刷新列表。

## 9. 常见坑

### 9.1 飞书扫码报「请求无效，错误码 4401」

几乎都在飞书开放平台侧：

1. 「安全设置 → 重定向 URL」填好后**必须点「发布版本」**，飞书只认已发布版本的配置；
2. 登记的 URL 必须与访问域名逐字一致（https + 结尾斜杠）；
3. 「应用功能 → 网页应用 → 主页」不解决 4401，那是客户端内打开网页应用用的；
4. 国内 / 国际应用不要混用 type 8 / 9。

### 9.2 隧道 / 反代场景

花生壳等隧道把 `https://<域名>/` 映射到本机 `:8000` 时，浏览器 `Origin` 已是公网域名；若中间还有反代，确保透传 `X-Forwarded-Proto` / `X-Forwarded-Host`，否则服务端会推导成本机地址导致回调不一致。

### 9.3 自建 IdP（MaxKey）常见问题

- **CAS 应用没配适配器**：`mxk_apps.ISADAPTER=0` 时 `serviceValidate` 会走到「Cas Adapter is not Set」分支，返回 `<cas:authenticationFailure code="null">null</cas:authenticationFailure>`（既不是 `INVALID_TICKET` 也没有原因），任何客户端都登不进来。需给应用指定 CAS 适配器（如 `CasDefaultAdapter`）并重启 MaxKey 清缓存。
- **OIDC 的 `name` / `preferred_username` 都是显示名**：MaxKey 把这两个声明都填成 `displayName`，真正的登录名在 `user` 声明里。按标准取 `preferred_username` 会拿到「孙尚香」这种名字，建议 mapping 配 `{"account":"user"}`。
- **登出只认 `redirect_uri`**：`/sign/force/logout` 忽略 `post_logout_redirect_uri`，只把 `redirect_uri` 拼到自己的登出页地址上。
- **回调地址必须与登记值一致**：OIDC/OAuth2 客户端只登记了隧道域名时，本地用 127.0.0.1 访问会被拒（`Invalid redirect`）；把登记值填进 askdata 的 `redirect_uri` 配置即可。

### 9.4 回调参数只能消费一次

`code` / `ticket` 都是一次性凭证。前端 `Handler.vue` 读到回调参数后会立即用 `history.replaceState` 从地址栏清掉，避免刷新页面或退出登录时重放已失效的凭证——重放的典型表现是 `missing account` 或 `INVALID_TICKET`。

### 9.5 企业微信「可信域名 / 可信 IP」

自建应用要求配置企业域名并完成归属校验，同时把服务器出口 IP 加入「企业可信 IP」。不满足时 `gettoken` 仍可能成功，但组织 / 用户接口报 `60020`，扫码登录无法端到端验证。

## 10. 验证方式

```bash
# 1) 认证源是否启用 / 是否 valid
curl -s /api/v1/system/authentication/platform/status

# 2) 授权 URL 与回调地址是否正确（带 Origin 验证配置优先 / 动态推导）
curl -s '/api/v1/system/authentication/login/2' -H 'Origin: https://your.domain'

# 3) LDAP 账号口令
curl -s -X POST /api/v1/system/authentication/sso/3 \
  -H 'Content-Type: application/json' \
  -d '{"username":"<账号>","password":"<口令>"}'

# 4) 登出联动地址
curl -s -X POST /api/v1/login/logout \
  -H 'Content-Type: application/json' -H 'Origin: https://your.domain' \
  -d '{"origin":2}'

# 5) 企业 IM 组织树 / 用户同步
curl -s -X POST /api/v1/system/platform/org/7 -H 'satoken: Bearer <token>' \
  -H 'Content-Type: application/json' -d '{"lazy":1,"pid":""}'
```

自检通过标准：

- `login/{type}` 返回的 URL 中 `redirect_uri` / `service` 与 IdP 登记值一致；
- `sso/{type}` 返回 `code=0` 且 `platform_info.account` 是可读登录名（不是显示名、不是平台数字 ID）；
- `logout` 返回的地址在浏览器里能结束 IdP 会话并回到 askdata 登录页。
