# askdata 统一认证对接（SSO / 企业 IM 集成）

本文面向需要将 askdata 接入统一身份认证（IdP）或企业 IM（飞书 / 钉钉 / 企业微信 / LarkSuite）的运维与集成方，说明认证源的类型、管理端点、登录流程、回调地址推导规则，以及常见接入坑。后端契约集中在 `AuthenticationService`（SSO）与 `EnterprisePlatformService`（企业 IM）。

> 本文只描述 askdata-server 提供的后端契约与对接约定；具体 UI 编辑交互由前端 `web/src/views/system/authentication/*Editor.vue` 与 `web/src/views/login/xpack/*` 提供，不在本仓库详述。

## 1. 认证源类型总览

认证源统一存于 `sys_authentication` 表，`type` 字段区分协议 / 平台：

| type | 名称 | 类别 | 说明 |
| --- | --- | --- | --- |
| `1` | CAS | SSO 协议 | 中央认证服务，回调地址用 `casCallbackDomain` |
| `2` | OIDC | SSO 协议 | OpenID Connect，标准 `redirect_uri` |
| `3` | LDAP | SSO 协议 | 目录服务，不使用授权跳转 |
| `4` | OAuth2 | SSO 协议 | 标准 `redirect_uri` / `redirect_url` |
| `5` | SAML2 | SSO 协议 | SAML 2.0 断言 |
| `6` | 企业微信 | 企业 IM | `corpid` / `agent_id` / `state` / `redirect_uri` |
| `7` | 钉钉 | 企业 IM | `corpid` |
| `8` | 飞书（国内 `open.feishu.cn`） | 企业 IM | `client_id` |
| `9` | LarkSuite（国际 `larksuite.com`） | 企业 IM | `client_id` |

- type `1..5`：标准 SSO 协议，走 `AuthenticationController` 的 `login/{type}` / `sso/{type}`。
- type `6..9`：企业 IM 扫码 / 网页授权，走 `PlatformController` 的 `client/{origin}` / `sso/{type}`。

## 2. 管理端点（需 `X-SQLBOT-TOKEN` 登录态）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/system/authentication` | 认证源列表（type `< 9` 过滤后的标准 SSO 源） |
| GET | `/api/v1/system/authentication/{id}` | 按 id 取单个认证源配置（各 Editor 加载用） |
| PUT | `/api/v1/system/authentication` | 新增 / 修改认证源（`id` / `type` / `name` / `config` JSON） |
| PATCH | `/api/v1/system/authentication/enable` | 启停认证源 |
| PATCH | `/api/v1/system/authentication/status` | 连通性校验（管理台「测试连接」） |
| GET | `/api/v1/system/authentication/status` | 全部认证源启停 / 校验状态 |
| GET | `/api/v1/system/authentication/platform/status` | 企业平台（type ≥ 6）状态（公共路径） |
| GET | `/api/v1/system/authentication/login/{type}` | 第三方授权跳转 URL（公共路径，触发 SSO 登录） |
| POST | `/api/v1/system/authentication/sso/{type}` | SSO 登录换码（公共路径） |
| GET | `/api/v1/system/platform/client/{origin}` | 平台客户端信息 `clientInfo`（公共路径，前端取 `client_id` 渲染二维码） |
| POST | `/api/v1/system/platform/sso/{type}` | 企业 IM 扫码登录（公共路径） |

## 3. 登录流程

### 3.1 标准 SSO（type 1..5）

1. 前端调用 `GET /system/authentication/login/{type}`，后端 `AuthenticationService.toLogin` 按 `type` 生成 IdP 授权 URL（含 `redirect_uri`）。
2. 浏览器跳转到 IdP 登录授权页，用户完成认证。
3. IdP 携带 `code` / `state` / `ticket` 重定向回 `redirect_uri`（即 askdata 自身前端）。
4. 前端读取回调参数，调用 `POST /system/authentication/sso/{type}`（`code` / `state` 置于 body），后端 `ssoLogin` 用同一 `redirect_uri` 向 IdP 换用户信息并建会话。

### 3.2 企业 IM（type 6..9）

1. 前端调用 `GET /system/platform/client/{origin}` 取 `clientInfo`（`client_id` 等），结合当前页面域名拼出 `redirect_uri`。
2. 以 `redirect_uri` + `client_id` + `state` 构造授权 / 二维码 URL（飞书走 `LarkQr.vue` 的 QR SDK）。
3. 用户扫码 / 授权后，IM 平台携带 `code` 回跳 `redirect_uri`。
4. 前端把 `code` 与 `redirect_uri` 一并 POST 到 `/system/platform/sso/{type}`，后端 `ssoLogin` 完成换码与用户同步。

## 4. 回调地址（redirect_uri）动态推导 —— 不写死

**设计原则**：回调地址**不写死在配置里**，而是由用户实际访问 askdata 的域名动态推导。这样在 `localhost`、`127.0.0.1`、内网 IP、花生壳 / 公网域名之间切换时，无需改配置即可保持一致，且保证「授权跳转」与「换码」两端使用的 `redirect_uri` 完全相同（否则 IdP 会报 `redirect_uri mismatch`）。

后端 `AuthenticationService.redirectUri(HttpServletRequest)` 的推导优先级：

1. **反向代理透传**：`X-Forwarded-Proto` + `X-Forwarded-Host`（可选 `X-Forwarded-Port`）。花生壳 / Nginx 等隧道或反代场景请用这组头，服务端据此还原公网入口。
2. **浏览器 `Origin` 头**：同源请求自带的 `Origin`（最贴近用户实际访问入口）。
3. **`Referer` 头**：兜底。
4. **请求自身**：`request.getScheme()/getServerName()/getServerPort()`。

推导结果追加 `contextPath`（通常为空）并以 `/` 结尾，例如 `https://25946kt718.goho.co/`。

**配置字段语义**：
- 标准 SSO（1..5）的 `redirect_uri` / `redirect_url` / `casCallbackDomain`：后端在 `toLogin` / `ssoLogin` 中**以动态推导值覆盖**，DB 中留空即可（前端 Editor 打开时若字段为空会自动回退为当前域名 `getSQLBotAddr()`，仅作展示默认值）。
- 企业 IM（6..9）：`redirect_uri` 由前端按 `location.origin + pathname`（`getSQLBotAddr()`）传入登录 body，等于用户打开登录页的域名；后端在 `ssoLogin` 中直接使用该值。

> 若确有固定入口需求，可让反代稳定下发 `X-Forwarded-*` 头，或在标准 SSO 配置中显式填写 `redirect_uri`（当前实现以动态值优先，配置留空即走动态）。

## 5. 各认证源对接要点

### 5.1 CAS（type 1）
- 配置：`serverUrl`、`loginUrl`、`callbackUrl`、`casCallbackDomain` 等标准字段。
- 回调：`casCallbackDomain` 由动态推导注入（`toLogin` / `ssoLogin` 均注入），对应 CAS Service 登记的回调基地址。

### 5.2 OIDC（type 2）/ OAuth2（type 4）
- 配置：`client_id`、`client_secret`、授权 / token / userInfo 端点、`scope` 等。
- 回调：`redirect_uri` / `redirect_url` 由动态推导注入，必须与该 IdP「重定向 URL / 回调地址」登记值**逐字一致**（协议、域名、结尾斜杠都要一致）。

### 5.3 LDAP（type 3）
- 不使用授权跳转，`toLogin` 返回「LDAP 不支持授权重定向」错误；登录走专用 LDAP 表单。

### 5.4 SAML2（type 5）
- 基于 SP 元数据与 IdP 元数据交换断言；回调由 SAML ACS 端点处理。

### 5.5 企业微信（type 6）
- 配置：`corpid`、`agent_id`、`state`、`redirect_uri`（前端传入当前域名）。
- 依赖企业微信「网页授权可信域名」与「AgentId / CorpId」匹配。

### 5.6 钉钉（type 7）
- 配置：`corpid`（及扫码 / 第三方应用相关凭证）。
- 依赖钉钉「登录与分享」回调域名登记。

### 5.7 飞书 / LarkSuite（type 8 / 9）
- 配置：`client_id`（及 `client_secret` 等）；`redirect_uri` 由前端 `getSQLBotAddr()` 传入。
- 飞书（8）端点固定国内 `passport.feishu.cn` / `open.feishu.cn`；LarkSuite（9）走国际 `larksuite.com`。**两者后台不可混用**：国内应用用 type 8，国际应用用 type 9。

## 6. 常见坑

### 6.1 飞书扫码报「请求无效，错误码 4401」
根因几乎都在飞书开放平台侧，与 askdata 后端无关：

1. **重定向 URL 未发布**：在「安全设置 → 重定向 URL」填好 `https://<你的域名>/` 后，**必须点「发布版本」**。飞书只认已发布版本的配置，草稿态不生效，表现就是扫码换码时 `redirect_uri` 比对失败 → 4401。
2. **重定向 URL 与访问域名逐字不一致**：后台登记的必须是 `https://<域名>/`（https + 结尾斜杠），与前端实际打开登录页的域名一致；`localhost` / `127.0.0.1` / 无斜杠版本都要避免。
3. **填错设置项**：「应用功能 → 网页应用 → 主页」是给飞书客户端内打开网页应用用的，**不解决**扫码登录 4401；真正校验的是「安全设置 → 重定向 URL」。
4. **应用未开通能力 / 未发布**：确认应用已启用网页应用 / 扫码登录能力，且改配置后已发布版本。
5. **国内 / 国际混用**：`cli_aa6…` 这类 `open.feishu.cn` 应用用 type 8；`larksuite.com` 后台创建的应用用 type 9。

### 6.2 花生壳 / 公网隧道映射
- 隧道（如花生壳）把 `https://<域名>/` 映射到本机 `:8000` 时，浏览器 `Origin` 已是公网域名，动态推导自然得到公网地址；若中间有反代，请确保透传 `X-Forwarded-Proto` / `X-Forwarded-Host`，否则服务端可能推导成本机地址导致回调不一致。

### 6.3 MaxKey 等自建 IdP
- 将应用的 `WEB_SERVER_REDIRECT_URI` / `LOGINURL` / CAS `SERVICE` 设为同一个 askdata 公网入口（`https://<域名>/`），并重启 MaxKey 清缓存，避免旧回调残留。

## 7. 验证方式

- 管理台「测试连接」：`PATCH /system/authentication/status` 触发连通性校验。
- 端点自检：
  - `GET /system/authentication/platform/status` 查看企业平台启停 / 校验状态。
  - `GET /system/platform/client/8` 取飞书 `clientInfo`，确认 `client_id` 就绪、`enable=true`。
  - 带 `Origin: https://<域名>` 请求 `GET /system/authentication/login/2`，确认返回的授权 URL 中 `redirect_uri` 等于该域名（验证动态推导生效）。
