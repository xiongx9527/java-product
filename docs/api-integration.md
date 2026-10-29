# askdata-server API 对接文档

本文面向需要对接 askdata-server 的客户端/前端/集成方，说明鉴权模型、主要 HTTP 接口与集成流程。完整且权威的接口定义以运行时 OpenAPI 为准：GET /v3/api-docs（JSON）、/doc.html（Knife4j 页面）、/swagger-ui。

## 1. 基础信息

- 服务地址：http://<host>:8000（端口由 server.port=8000 决定）。
- API 前缀：/api/v1（多数业务接口）；MCP 兼容端点位于 /api/v1/mcp；原生 MCP 位于 /mcp。
- 统一响应包络：所有接口返回统一 JSON 包络（code / message / data），由 Result/JsonResult 封装；错误时 code != 0 并带 message。具体字段以 /v3/api-docs 为准。
- 健康检查：GET /health（无需鉴权）。

## 2. 鉴权模型

- 登录后获得访问 token；后续请求在请求头携带：X-SQLBOT-TOKEN: Bearer <token>。
- token 名称与前缀与 SQLBot 前端逐字兼容（sa-token 配置：token-name=X-SQLBOT-TOKEN，token-prefix=Bearer）。
- 默认有效期 8 天（ASKDATA_TOKEN_TIMEOUT 可调）；过期后服务端返回 401，客户端重新登录。
- 白名单（无需 token）含：登录/登出、SSO 回调、MCP 取 token、许可证/配置公开项、API 文档、静态资源、健康检查等。白名单与 SQLBot whitelist.py 逐条等价（见 AuthWhitelistParityTest）。

## 3. 登录与账号

- POST /api/v1/login/access-token：账号口令登录。请求体含 username、password；响应含 access_token、exp 与用户/平台信息。
- POST /api/v1/login/change-pwd：修改密码。
- POST /api/v1/login/logout：登出。
- POST /api/v1/system/authentication/login/{type}：第三方/平台登录回调相关入口（视 type）。

## 4. SSO 单点登录回调

统一入口：POST /api/v1/system/authentication/sso/{type}

type 取值（见 SsoAuthenticator.TYPE_*）：

- 1 = CAS（CAS 2.0/3.0，service ticket 校验 + 属性取回）
- 2 = OIDC（JustAuth 通用 endpoint）
- 3 = LDAP（直接 bind 登录，无浏览器跳转；前端直接提交 username/password）
- 4 = OAuth2（JustAuth 通用 endpoint）
- 5 = SAML2（IdP 经浏览器 POST 断言到 SP ACS，SP 解析并验签）
- 6..9 = 企业 IM 平台（platform sso）

请求体与流程随协议不同：

- LDAP（type=3）：请求体 {username, password}，服务端用服务账号 bind 后校验用户口令（真实 LDAP bind，见 LdapSsoClient）。
- SAML2（type=5）：请求体 {SAMLResponse: <base64 编码的断言>}，服务端验签（fail-closed，无证书则拒绝未验签断言）并解析属性。
- CAS / OIDC / OAuth2：请求体含 ticket/code、state 等，由对应客户端交换并取回用户属性。

成功响应统一为 {access_token, exp, platform_info}，与登录一致。

配置端点：

- GET /api/v1/setting/authentication/info/saml：读取当前 SAML2 配置（未配置返回空默认体）。
- /api/v1/sso（ExternalIdpController）：IdP 授权地址等辅助接口。

SSO 认证源在 sys_authentication 表中按 type 维护（config 为 JSON，enable 控制启用），并非硬编码。

## 5. MCP（SQLBot 兼容 + 原生）

MCP 业务系统对接的完整端点、工具与示例见 askdata/mcp/mcp-integration.md。要点：

SQLBot 兼容层（SqlbotMcpCompatController，/api/v1/mcp）：

- POST /api/v1/mcp/access_token：换取 MCP 会话 token。
- /api/v1/mcp/mcp_start、/api/v1/mcp/mcp_question、/api/v1/mcp/mcp_assistant 等：问数/助手流程的兼容端点（与 SQLBot 工具名对齐）。

原生 MCP（fw-plus，/mcp）：

- GET /mcp/sse：建立 SSE 流；POST /mcp/messages/{uuid}：发送消息。
- 鉴权复用 Sa-Token 登录会话（X-SQLBOT-TOKEN）。

## 6. 其他主要业务域（均在 /api/v1 下）

数据源 datasource/*、问数 chat/*、术语 terminology/*、训练 training/*、仪表盘 dashboard/*、权限 permission/*、审计 audit/*、系统 system/*（用户/工作区/参数/许可证/平台）、文件 /api/file/*、嵌入助理 embedded/*、定时任务 scheduled/* 等。完整路径与请求/响应字段见 /v3/api-docs。

## 7. 对接示例（伪代码）

1. 登录：POST /api/v1/login/access-token {username,password} → 取 access_token。
2. 后续调用：Header X-SQLBOT-TOKEN: Bearer <access_token>。
3. SSO 场景：前端引导到 IdP，IdP 回跳后将断言/票据 POST 到 /api/v1/system/authentication/sso/{type}，拿到 access_token 后同步骤 2。

## 8. 真实环境验证说明

上述 SSO（含 SAML2 真实签名回调）、鉴权、MCP 等接口均有真实环境测试覆盖：SAML2 真实 HTTP 端到端测试（SamlSsoRealHttpE2ETest）零 mock 验证签名校验与用户落库；SSO 子系统 23 个测试全绿；全量后端套件 1097 测试全绿。详见 docs/verification/sqlbot-parity-verification.md。
