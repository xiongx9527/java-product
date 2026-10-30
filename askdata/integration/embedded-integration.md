# askdata 嵌入对接（Embedded Integration）

本文面向需要把 askdata 问数助手嵌入到第三方业务系统的集成方，说明嵌入应用的管理接口、公共端点、签名校验、会话与问数流程。SQLBot 侧对应能力见 `backend/apps/system/api/assistant.py`、`backend/apps/chat/api/chat.py`（`/assistant/start`）与前端 `assistant.js` 挂件。

> 本文只描述 askdata-server 提供的后端契约。`assistant.js` 前端挂件与嵌入页 UI 由前端工程提供，不在本仓库。

## 1. 两种嵌入形态

| 助手 type | 形态 | 数据源来源 |
| --- | --- | --- |
| `0` | 普通小助手（固定数据源） | 平台内已配置的数据源；离线（`online=false`）时仅暴露 `public_list` |
| `1` / `3` | 高级应用（动态数据源） | 宿主系统实时下发（HTTP 拉取 / 请求体直传），不落库 |

高级应用的动态数据源支持表级子查询占位符：提示词引导模型引用 `askdata_dynamic_temp_table_<表名>`，执行前由服务端确定性地替换为宿主下发的真实子查询。

## 2. 端点总览

### 2.1 公共端点（免登录，走白名单）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/system/assistant/info/{id}` | 助手信息 + 访问 token（嵌入页首屏用）；需 Origin 命中 domain + 合法签名头 |
| GET | `/api/v1/system/assistant/app/{appId}` | 按 `app_id` 取嵌入应用信息；需 Origin 命中 domain |
| GET | `/api/v1/system/assistant/validate/{id}` | 校验助手可访问性，返回 `{valid, origin}`；需 Origin 命中 domain |
| GET | `/api/v1/system/assistant/picture/{file_id}` | 助手外观图片（嵌入页未登录也要能取） |

以上路径在 `AuthWhitelist` 中放行（`/system/assistant/info/*`、`/app/*`、`/validate/*`、`/picture/*`），与 SQLBot `common/utils/whitelist.py` 逐条对应。

**CORS**：命中 domain 时服务端按请求 Origin 动态回写 `Access-Control-Allow-Origin`；不命中直接拒绝。

### 2.2 管理面（需 `X-SQLBOT-TOKEN` 登录态）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/system/assistant` | 助手列表 |
| GET | `/api/v1/system/assistant/advanced_application` | 高级应用列表 |
| POST / PUT / DELETE | `/api/v1/system/assistant` | 助手增改删 |
| PATCH | `/api/v1/system/assistant/ui` | 外观配置（主题色、标题等） |
| GET | `/api/v1/system/assistant/ds` | 当前助手可用数据源；`id` 缺省时按 token 的 `assistant_id` 解析 |
| POST | `/api/v1/system/assistant/dynamic_datasource` | 校验宿主下发的动态数据源（不落库、不回传连接密钥） |

嵌入应用（对外发布的密钥载体）由 `/api/v1/system/embedded/*` 管理：分页列表、详情、增改删、`PATCH /system/embedded/secret/{id}` 重置密钥。

### 2.3 会话与问数（嵌入通道）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/chat/assistant/start` | 开启嵌入会话，`origin` 缺省置为 `2`；行为与 `/chat/start` 一致 |
| POST | `/api/v1/chat/question` | 问数（SSE 流式）。请求体可带 `dynamic_datasource` 走高级应用 |

问数请求与 Web 主链路完全同一条管线（`ChatQuestionService.streamQuestion`）：同一套提示词结构、选表、行/列权限与 SQL 校验，不因嵌入而分叉。

## 3. 签名与鉴权

### 3.1 Origin 校验

请求 Origin（`Origin` 头，或 `X-SQLBOT-HOST-ORIGIN`，或由 `Referer` 推导）必须落在助手 `domain` 列表内，比较前会去掉尾部斜杠。

### 3.2 嵌入签名头（推荐）：`SQLBOT-EMBEDDED-TOKEN`

宿主侧签发的 AES-GCM 信封（Base64URL），明文 JSON 字段：

| 字段 | 说明 |
| --- | --- |
| `assistant_id` | 助手 id，须与路径 id 一致 |
| `target` | 宿主站点 origin，须与请求 Origin 相等 |
| `sign_time` | 秒级时间戳，须落在当前时间 ±10 秒内 |
| `user_agent` | 须与请求 `User-Agent` 逐字一致 |
| `timezone` | 须与请求时区（`X-SQLBOT-TIMEZONE`）一致 |
| `webdriver` | 不得为 `true`（自动化环境拒绝） |

AES 密钥来自 `askdata.embedded.aes-key`，可轮换；未配置时返回可读错误。

### 3.3 旧签名头（兼容，默认关闭）：`SQLBOT-EMBEDDED-SIGN`

`assistantId.timestamp.hmac` 格式，密钥为助手的 `app_secret`。仅在 `askdata.embedded.legacy-hmac-enabled=true` 时接受，缺省关闭；助手未配置 `app_secret` 视为未开放嵌入，直接拒绝。

### 3.4 访问 token

`/system/assistant/info/{id}` 成功时返回 `token`（JWT），payload 含 `id`（`virtual` 查询参数）、`account=sqlbot-inner-assistant`、`oid`、`assistant_id`，有效期 8 天。

后续业务请求携带：

```
X-SQLBOT-TOKEN: Bearer <token>
```

## 4. 在线 / 离线通道

请求头 `X-SQLBOT-ASSISTANT-ONLINE` 在运行时覆盖助手配置中的 `online`：

- `true` / `1` / `online` / `yes` → 在线：返回该 oid 下全部数据源；
- 其余值 → 离线：仅返回 `public_list` 内的数据源。

未带该头时，取助手 `configuration.online`，缺失默认 `true`。

## 5. 典型对接流程

1. 管理面创建助手（type=0 或 1/3），配置 `domain`（允许嵌入的站点 origin）与外观。
2. 在业务系统页面引入嵌入挂件（SQLBot 侧为 `<script src="https://<host>/assistant.js?id=<assistant_id>">`）。
3. 嵌入页首屏：带 Origin + `SQLBOT-EMBEDDED-TOKEN` 调 `GET /api/v1/system/assistant/info/{id}` → 拿到助手配置与 `token`。
4. 拉数据源：`GET /api/v1/system/assistant/ds`（高级应用可带 `X-SQLBOT-ASSISTANT-ONLINE`）。
5. 开会话：`POST /api/v1/chat/assistant/start` → `chat_id`。
6. 问数：`POST /api/v1/chat/question`（SSE），高级应用在 body 里带 `dynamic_datasource`。

## 6. 与 SQLBot 的对应关系

| 能力 | SQLBot | askdata |
| --- | --- | --- |
| 助手 CRUD | `apps/system/api/assistant.py` | `AssistantController`（`/system/assistant`） |
| 公共信息端点 | `/system/assistant/info|app|validate` | `AssistantPublicController`（同名路径） |
| 嵌入会话 | `POST /assistant/start` | `POST /chat/assistant/start`（origin=2） |
| 问数 | `POST /chat/question` | `POST /chat/question` |
| 动态数据源 | `dynamic_datasource` / 宿主 API | `DynamicDatasourceResolver` + `dynamic_datasource` |
| 签名 | `SQLBOT-EMBEDDED-SIGN` / xpack 信封 | `SQLBOT-EMBEDDED-TOKEN`（主）+ 旧 HMAC（默认关） |

## 7. 已知边界

- 本仓库不含前端，`assistant.js` 挂件与嵌入页由前端工程实现；后端只保证上述契约。
- `sys_assistant` 表无 `online` 列，在线状态取自 `configuration.online`，可被请求头覆盖。
- 动态数据源只在请求期生效，不落库、不回传连接密钥。
