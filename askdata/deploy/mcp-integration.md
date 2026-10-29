# askdata MCP 业务系统对接文档

askdata-server 提供两套面向业务系统/客户端的 MCP 接入方式，能力对齐 SQLBot 的 MCP 7 operations（数据源列表、工作空间列表、模型列表、开启会话、换取 token、问数、助手）：

1. SQLBot 兼容 REST 层（POST /api/v1/mcp/*）：镜像 SQLBot apps/mcp/mcp.py 的路由路径、请求形态与响应键，便于已对接 SQLBot 的业务系统零改造迁移。
2. 原生 MCP（fw-plus，传输 http-sse，端点 /mcp）：标准 MCP 协议，工具由 AskdataMcpTools 暴露，适合按 MCP 客户端规范接入。

完整且权威的接口定义以运行时 OpenAPI 为准：GET /v3/api-docs、/doc.html（Knife4j）。

## 1. 鉴权模型

askdata 的鉴权统一基于 Sa-Token（令牌头名 X-SQLBOT-TOKEN、令牌前缀 Bearer），但两套 MCP 接入在「如何携带 / 校验令牌」上不同。

### 1.1 兼容 REST 层：白名单免会话头 + body 内 token 身份绑定
- 路由 /api/v1/mcp/** 在 AuthWhitelist 中放行（与 SQLBot whitelist.py 逐条等价，由 AuthWhitelistParityTest 字节级校验）。匹配时先剥离 /api/v1 前缀与 context-path，再与 /mcp* 等模式比对；匹配过程只依赖请求路径、不使用 Host 头，避免以 Host: evil.com/api/v1/mcp 伪造前缀绕过认证。
- 因此该层调用「不需要」在请求头带会话令牌；身份完全由请求体里的 token 字段决定。
- 每次业务调用（mcp_start / mcp_question / mcp_ds_list 等）都会执行 bindTokenStrict(token)：
  - token 为空 → 沿用当前已有会话（兼容内部 / 已登录调用）；
  - token 非空且有效（StpUtil.getLoginIdByToken != null）→ 将其绑定为本次调用的登录身份，后续 CurrentUserService 即以该用户执行，并按其工作空间过滤数据源 / 模型；
  - token 非空但无效 → 抛出 ForbiddenException（HTTP 403，detail=mcp.error.token.invalid），对齐 SQLBot get_user 的 403 语义。

### 1.2 换取令牌 access_token
- POST /api/v1/mcp/access_token（或 MCP 工具 access_token），请求体 {username, password}。
- 服务端用 MD5(password) 比对 sys_user.password，并校验：oid != 0（必须归属某工作空间）、status == 1（账号启用）、origin == 0（非第三方账号，第三方账号须走 SSO 而非口令）；任一不满足返回 {error:...}。
- 通过后 StpUtil.login(userId) 签发 Sa-Token 会话，响应同时给出三个键（与 SQLBot 客户端兼容）：
  - token：Sa-Token 令牌本身；
  - tokenName：令牌头名，固定为 X-SQLBOT-TOKEN；
  - access_token：与 token 同值的兼容别名（SQLBot 预设客户端按 access_token 读取）。
- 客户端只需保存其中任一令牌值即可。

### 1.3 原生 MCP：X-SQLBOT-TOKEN: Bearer 头
- 原生 MCP 端点 /mcp/sse（GET 建流）与 /mcp/messages/{uuid}（POST 发消息）不在白名单内，必须带 Sa-Token 会话头：X-SQLBOT-TOKEN: Bearer <token>。Cookie 方式已关闭（is-read-cookie=false）。
- auth.provider=sa-token-login：框架用该头还原登录身份后再派发到工具层；未带或无效令牌会被拒绝。

### 1.4 令牌生命周期
- 有效期由 ASKDATA_TOKEN_TIMEOUT 控制（秒，默认 691200 = 8 天，对齐 SQLBot ACCESS_TOKEN_EXPIRE_MINUTES）。
- 过期后接口返回 401，客户端需重新 access_token 换令牌；令牌本身无刷新接口，采用「重新登录」语义。

### 1.5 两套接入的令牌互通
- 兼容 REST 层与原生 MCP 共用同一套 Sa-Token 会话，因此用 /api/v1/mcp/access_token 换到的令牌，可直接作为 X-SQLBOT-TOKEN: Bearer <token> 用于原生 /mcp 调用；反之亦然（任何 Sa-Token 登录得到的令牌都通用）。


## 2. SQLBot 兼容 REST 层（/api/v1/mcp）

所有接口为 POST，请求体为 JSON（snake_case 字段），身份以 body 内 token 为准。工具返回含 error 键时，控制器转为 HTTP 400 {detail:...}（镜像 SQLBot HTTPException）；非流式问数的运行期错误返回 500 {success:false,message}。

| 端点 | 请求字段 | 说明 / 返回 |
|------|----------|------------|
| /access_token | username, password | {access_token, token, tokenName} |
| /mcp_start | username?, password?, token?, oid?, datasource_id?, question? | 开启会话，返回 {access_token, chat_id, ...} |
| /mcp_ws_list | token（也可 query） | 当前用户可管理的工作空间数组 |
| /mcp_ds_list（别名 /mcp_datasource_list） | token, oid? | 数据源数组（按用户过滤；管理员可指定 oid） |
| /mcp_model_list | token, oid | 当前工作空间可用模型数组 |
| /mcp_question | token, chat_id?, question, datasource_id?, custom_model?, lang?, stream?, return_img? | 问数。stream 缺省/true 返回 text/event-stream（SSE）；显式 false 返回 JSON。支持自定义模型与出图 |
| /mcp_assistant | question, url, authorization, stream?, datasourceId? | 助手对话。stream 缺省/true 返回 SSE；url/authorization 必填，以合成身份创建会话 |

问数 SSE 帧格式与 ChatQuestionService 一致（type=error / finish 等），前端按事件流消费即可。

## 3. 原生 MCP（/mcp，http-sse）

- GET /mcp/sse 建立 SSE 流；POST /mcp/messages/{uuid} 向该流发送消息（JSON-RPC 2.0）。
- 传输与端点由配置控制：fw-plus.ai.mcp.enabled=true（默认开）、transport=http-sse、endpoint=/mcp、auth.provider=sa-token-login。
- 客户端按 MCP 规范 initialize 后，可调用以下工具（@McpService 扫描注册）：

| 工具名 | 参数（required） | 说明 |
|--------|----------------|------|
| server_info | 无 | 连通性探针，返回 {app, mcp, transport} |
| access_token | username, password | 账号口令换 token |
| datasource_list | workspaceId? | 可用数据源（JSON 数组） |
| workspace_list | 无 | 当前用户可管理的工作空间 |
| model_list | 无 | 当前工作空间可用对话模型 |
| chat_start | datasourceId?, question? | 开启问数会话，返回 chat_id |
| question_answer | question, datasourceId?, chatId?, customModel?, returnImg? | 问数（NL2SQL → 只读执行 → 返回 SQL 与结果行） |
| assistant | message | 多轮记忆问数助手（基于默认对话模型） |

## 4. 业务系统对接示例（问数闭环）

以「业务系统把自然语言问题发给 askdata 取数」为例（兼容 REST 层）：

1. 换 token：POST /api/v1/mcp/access_token {username, password} → 取 access_token。
2. 列数据源：POST /api/v1/mcp/mcp_ds_list {token} → 选 datasource_id。
3. 问数（流式）：POST /api/v1/mcp/mcp_question {token, question, datasource_id, stream:true} → 消费 SSE，得到 NL2SQL 生成的 SQL 与结果行。
4. 如需多轮，带 chat_id 续接；需要图表可设 return_img:true。

若按标准 MCP 客户端接入，则用原生 /mcp：initialize → 调用 question_answer 工具（token 经 X-SQLBOT-TOKEN 头）。

## 5. 启用与配置

- 原生 MCP 默认即开（fw-plus.ai.mcp.enabled=true）；如需关闭在配置中置 false。
- 问数/助手（question_answer、assistant 工具与 /mcp_question）依赖大模型底座：需 ASKDATA_ASSISTANT_ENABLED=true 并在「系统 → AI 模型」配置默认对话模型（见部署文档 §2.5）。
- 兼容 REST 层的白名单放行项属于框架 AuthWhitelist，与 SQLBot 逐条等价（由 AuthWhitelistParityTest 校验）。

## 6. 对接实例代码（Python / curl）

以下示例均为真实可调用的形态。请把 <host>、<pwd>、<token> 替换为实际值。

### 6.1 兼容 REST 层：问数闭环（Python）

```python
import requests

BASE = "http://<host>:8000/api/v1/mcp"

# 1) 换取令牌
r = requests.post(f"{BASE}/access_token",
                  json={"username": "admin", "password": "<pwd>"})
body = r.json()
tok = body["access_token"]          # 也可取 body["token"]，两者同值

# 2) 列出可用数据源（按当前用户工作空间过滤）
ds = requests.post(f"{BASE}/mcp_ds_list", json={"token": tok}).json()

# 3) 自然语言问数（流式 SSE）
with requests.post(f"{BASE}/mcp_question",
                   json={"token": tok, "question": "上月销售额",
                         "datasource_id": 1, "stream": True},
                   stream=True) as resp:
    for line in resp.iter_lines(decode_unicode=True):
        if line:
            # SSE 帧形如 data: {type:..., content:...}，type 含 data/sql/rows/error/finish
            print(line)
```

### 6.2 兼容 REST 层：等价 curl

```bash
# 换 token
curl -s -X POST http://<host>:8000/api/v1/mcp/access_token \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<pwd>"}'

# 问数（非流式，便于调试）
curl -s -X POST http://<host>:8000/api/v1/mcp/mcp_question \
  -H 'Content-Type: application/json' \
  -d '{"token":"<token>","question":"上月销售额","datasource_id":1,"stream":false}'
```

### 6.3 原生 MCP（http-sse）：调用 question_answer（Python）

```python
import requests, json, uuid

BASE = "http://<host>:8000/mcp"
HEAD = {"X-SQLBOT-TOKEN": "Bearer <token>"}   # 原生 MCP 必须带 Sa-Token 头

# 1) 建立 SSE 流，并从首帧读取消息端点（形如 endpoint:/mcp/messages/<uuid>）
sse = requests.get(f"{BASE}/sse", headers=HEAD, stream=True)
endpoint = None
for line in sse.iter_lines(decode_unicode=True):
    if line and line.startswith("endpoint:"):
        endpoint = line.split("endpoint:", 1)[1].strip()
        break

msg_url = f"{BASE}{endpoint}"

# 2) 按 JSON-RPC 2.0 发送 initialize + tools/call
def rpc(payload):
    requests.post(msg_url, headers=HEAD, json=payload)

rpc({"jsonrpc": "2.0", "id": 1, "method": "initialize",
     "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                "clientInfo": {"name": "biz-client", "version": "1.0"}}})
rpc({"jsonrpc": "2.0", "method": "notifications/initialized"})
rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
     "params": {"name": "question_answer",
                "arguments": {"question": "上月销售额", "datasourceId": 1}}})

# 3) 工具结果从 SSE 流读取（data: 帧中的 JSON-RPC response）
for line in sse.iter_lines(decode_unicode=True):
    if line and line.startswith("data:"):
        print(line)
```

> 说明：原生 MCP 的工具调用结果与兼容 REST 层一致（question_answer 返回 NL2SQL 生成的 SQL 与只读结果行）。两套接入共用同一 Sa-Token 会话，故 6.1 换到的令牌可直接用于 6.3 的 X-SQLBOT-TOKEN 头。

## 7. 真实环境测试

MCP 兼容层有专门测试覆盖：SqlbotMcpCompatControllerTest（21 个用例）验证端点路径、token 身份、响应键、错误映射与流式/非流式切换，均已在真实 Spring Boot + 内嵌 PostgreSQL 下通过；原生 MCP 工具层由 AskdataMcpTools 单测与全量套件覆盖。详见 docs/verification/sqlbot-parity-verification.md。
