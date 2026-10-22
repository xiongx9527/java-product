# 系统管理 / 鉴权 / 安全 / 审计 / 国际化 / 工作空间-用户管理 —— 实现保真度审计报告

- 审计日期：2026-10-22
- 基准（真值来源）：SQLBot（Python / FastAPI）`/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot/backend`
- 被审对象：askdata（Java / Spring Boot）`/Users/xiongxiao/Workspaces/Idea/codes/New/java-product/askdata/askdata-server`
- 审计性质：**只读**。本轮未修改任何源码文件；仅新增本报告工件。
- 判定词表：**等价** / **打折** / **缺失** / **更强**
- 引用约定：所有结论均给出两侧真实 `file:line`。SQLBot 侧路径相对 `SQLBot/backend/`，askdata 侧路径相对 `askdata/askdata-server/src/main/`，特殊标注除外。

> 说明：本次审计**不采信** `docs/comet/archive/**/coverage.md` 等既有自述材料，全部结论由源码逐行核对得出。

---

## 结论摘要

### 1.1 总体判定

| 维度 | 判定 | 依据摘要 |
|---|---|---|
| 公共路径白名单 | **等价** | 两侧各 **35** 条（不是 33 条），内容与顺序逐条一致；匹配语义（原始路径取值、前缀剥离、精确优先、通配正则、锚定）一致；仅尾斜杠归一化有 1 处窄差异 |
| 鉴权通道（4 条） | **打折** | 通道顺序、请求头名、scheme、双阶段验签、embedded 两条安全红线全部对齐；但嵌入助手通道的**签名密钥永不解析**（配置键名写错），且 embedded 通道缺 `assistant_info.oid` 回写 |
| Host 头校验 | **打折** | 正则与 400 响应体逐字一致；Host 头缺失时 askdata 回退放行、SQLBot 直接 400 |
| 审计（`@system_log`） | **缺失** | SQLBot 54 处审计点，askdata 仅 29 处且**全部落在术语/训练/看板/提示词/权限/嵌入模块**；系统管理面（用户/工作空间/成员/API Key/参数/AI 模型）**0 审计** |
| 国际化（5 语言） | **打折** | 消息包覆盖度满分（SQLBot 177 键 100% 命中、占位符 0 差异），但**用户可见错误仍大量硬编码英文** |
| 加密 / 密钥 | **打折** | 口令散列（MD5）、前端 AES 互通、simple_aes 全部对齐；但应用密钥**缺省值硬编码进版本库**，且嵌入通道密钥**取不到** |
| 雪花 ID / 分页 / 缓存 | **打折** | 雪花位段逐位一致 + 2^53 安全序列化一致；分页排序生效但**不回显**；**缓存层整体缺失** |
| 响应信封 + 错误分类 | **打折** | 信封结构与跳过规则一致（且 askdata 多跳过 String/非 JSON/下载）；但错误体形态、CORS 来源、权限拒绝状态码与 SQLBot 不一致 |
| 工作空间 / 用户模型 | **打折** | 成员绑定、权重语义、角色判定基本对齐且**权限判定更严**；但**默认口令不可预测**、**初始口令强制改密缺失**、**登出不走第三方 SSO**、Excel 导入列集不同 |

### 1.2 问题分布

| 严重度 | 条数 | 概括 |
|---|---|---|
| **高** | 3 | 应用密钥硬编码缺省值；嵌入通道密钥配置键名写错导致永不解析；`login.initial_pwd_disabled` 强制改密逻辑整体缺失 |
| **中** | 6 | 默认口令不可预测；系统管理面审计全缺；非登录通道审计被跳过；审计资源名映射缺 CHAT 且 `sys_apikey.name` 列不存在；embedded 通道未回写 `oid`；权限拒绝状态码不一致 |
| **低** | 10 | 缓存层缺失；登出无 SSO 分支；Host 缺失回退；白名单尾斜杠归一化；审计 IP 头链顺序；硬编码英文错误；登录 token 形态/有效期；Excel 导入列集；`sys_logs_resource` 重复 DDL；审计关联行写入条件不同 |
| **更强（askdata 优于 SQLBot）** | 5 | 审计资源名解析失败安全；JWT 签名定长比较；白名单用原始 requestURI；非成员工作空间权重判定更严；异常全量落日志 |

### 1.3 最需要优先处理的三件事

1. **`application.yaml:49` 的 `AskDataSecret001`** 与 **`EmbeddedTokenAuthenticator.java:58` 的 `askdata.secret-key`** 是两个独立的高危缺陷叠加：前者把密钥写进版本库，后者让嵌入通道永远拿不到配置密钥（每次重启变成随机 UUID），导致嵌入助手 token 在重启后必然失效且密钥管理形同虚设。
2. **系统管理面 0 审计**：用户增删改、密码重置、工作空间与成员绑定、API Key 签发/停用、参数与 AI 模型变更——这些是合规审计最关心的面，askdata 全部无痕。SQLBot 在同一位置有 21 处审计点。
3. **`login.initial_pwd_disabled` 未落地**：`schema.sql:181` 已把开关种子写入数据库，但没有任何代码读它。SQLBot 在该开关打开时会**拒绝发放 token**并要求改密（`login.py:50-55`），askdata 直接放行——等于该安全开关在 askdata 侧是空壳。

---

## 白名单逐条对照表

两侧白名单条目数均为 **35**（SQLBot `common/utils/whitelist.py:7-41`；askdata `common/web/AuthWhitelist.java:39-73`），内容与顺序**逐条一致**（已用脚本比对，35/35 完全相同）。

> 注：askdata 侧注释 `AuthWhitelist.java:13` 与 `AuthWhitelist.java:34` 均写作「33 条」，属注释漂移（实际 35 条），不影响运行时行为。

| # | 路径 | SQLBot（`common/utils/whitelist.py`） | askdata（`common/web/AuthWhitelist.java`） | 判定 |
|---|---|---|---|---|
| 1 | `/` | :7 | :39 | 等价 |
| 2 | `/docs` | :8 | :40 | 等价 |
| 3 | `/login/*` | :9 | :41 | 等价 |
| 4 | `*.ico` | :10 | :42 | 等价 |
| 5 | `*.html` | :11 | :43 | 等价 |
| 6 | `*.js` | :12 | :44 | 等价 |
| 7 | `*.css` | :13 | :45 | 等价 |
| 8 | `*.png` | :14 | :46 | 等价 |
| 9 | `*.jpg` | :15 | :47 | 等价 |
| 10 | `*.jpeg` | :16 | :48 | 等价 |
| 11 | `*.gif` | :17 | :49 | 等价 |
| 12 | `*.svg` | :18 | :50 | 等价 |
| 13 | `*.woff` | :19 | :51 | 等价 |
| 14 | `*.woff2` | :20 | :52 | 等价 |
| 15 | `*.ttf` | :21 | :53 | 等价 |
| 16 | `*.eot` | :22 | :54 | 等价 |
| 17 | `*.otf` | :23 | :55 | 等价 |
| 18 | `*.css.map` | :24 | :56 | 等价 |
| 19 | `/mcp*` | :25 | :57 | 等价 |
| 20 | `/system/license` | :26 | :58 | 等价 |
| 21 | `/system/config/key` | :27 | :59 | 等价 |
| 22 | `/images/*` | :28 | :60 | 等价 |
| 23 | `/sse` | :29 | :61 | 等价 |
| 24 | `/system/appearance/ui` | :30 | :62 | 等价 |
| 25 | `/system/appearance/picture/*` | :31 | :63 | 等价 |
| 26 | `/system/assistant/info/*` | :32 | :64 | 等价 |
| 27 | `/system/assistant/app/*` | :33 | :65 | 等价 |
| 28 | `/system/assistant/picture/*` | :34 | :66 | 等价 |
| 29 | `/system/assistant/validate/*` | :35 | :67 | 等价 |
| 30 | `/system/authentication/platform/status` | :36 | :68 | 等价 |
| 31 | `/system/authentication/login/*` | :37 | :69 | 等价 |
| 32 | `/system/authentication/sso/*` | :38 | :70 | 等价 |
| 33 | `/system/platform/sso/*` | :39 | :71 | 等价 |
| 34 | `/system/platform/client/*` | :40 | :72 | 等价 |
| 35 | `/system/parameter/login` | :41 | :73 | 等价 |

### 白名单匹配语义对照（逐条之外的整体语义）

| 语义环节 | SQLBot | askdata | 判定 |
|---|---|---|---|
| 路径取值（防 Host 注入绕过） | `request.scope.get("path")`（`apps/system/middleware/auth.py:37`） | `request.getRequestURI()`（`common/web/AuthWhitelist.java:127`） | 等价（均不依赖 Host） |
| API_V1 前缀剥离 | `whitelist.py:67-69`（`settings.API_V1_STR`） | `AuthWhitelist.java:148-150`（`askdata.api-v1-prefix`，缺省 `/api/v1`） | 等价 |
| context path 剥离 | `whitelist.py:71-73` | `AuthWhitelist.java:151-153` | 等价 |
| 空路径归一为 `/` | `whitelist.py:75-76` | `AuthWhitelist.java:154-156` | 等价 |
| 精确成员判定优先 | `whitelist.py:77-78` | `AuthWhitelist.java:157-159` | 等价 |
| 通配符编译（`.`→`\.`，`*`→`.*`，`^...$` 锚定） | `whitelist.py:50-64` | `AuthWhitelist.java:101-111` | 等价 |
| 尾斜杠归一 | `path.rstrip('/')`（`whitelist.py:80`，去除**全部**尾斜杠） | `path.substring(0, len-1)`（`AuthWhitelist.java:160`，只去除**一个**） | **打折**（见 F-03） |

> 差分实测（对 44 条代表性路径做双实现逐条比对）：仅 **2** 条路径判定不同，且差异方向均为 **askdata 更宽松**——`/login//`、`/images//`（SQLBot 拒绝，askdata 放行）。其余 42 条完全一致，含 `/system/license/`、`/system/license//`、`/mcp/`、`/api/v1/system/user/list`、`/../etc/passwd` 等边界。

---

## 鉴权通道对照表

SQLBot 四通道实现在单一中间件 `apps/system/middleware/auth.py` 内按固定顺序判定（:39 → :65 → :73 → :87）；askdata 拆为 Servlet Filter `common/web/AuthChannelFilter.java` + Sa-Token 拦截器 `config/SaTokenConfigure.java`，通道顺序保持一致。

| 通道 | SQLBot 实现 | askdata 实现 | 判定 | 说明 |
|---|---|---|---|---|
| **① 主通道 `Bearer`** | `auth.py:87-95`（取头）→ `validateToken` `auth.py:144-173`；JWT HS256 + `settings.SECRET_KEY` 验签（`:151-153`）；用户不存在 / `status != 1` / `oid == 0` 三重拒绝（`:157-166`） | `AuthChannelFilter.java:119-120`（不判定，转交 Sa-Token）→ `SaTokenConfigure.java:34-41`（`StpUtil.checkLogin()`）；token 头 `X-SQLBOT-TOKEN` + 前缀 `Bearer`（`application.yaml:71-83`）；`CurrentUserService.java:41-61` 解析当前用户 | **打折** | 校验语义等价，但 **token 形态与有效期不同**：SQLBot 签发 PyJWT HS256（`common/core/security.py:15-21`），有效期 8 天（`common/core/config.py:36`）；askdata 签发 Sa-Token **UUID** token（`application.yaml:78` timeout 2592000s = 30 天，`token-style: uuid`）。见 F-07 |
| **② API-Key `sk`** | `auth.py:65-71`（取头）→ `validateAskToken` `auth.py:100-142`；scheme 必须 `sk`（`:104`）；**两阶段验签**：先不验签取 `access_key`（`:107-109`）→ 查 `sys_apikey` → `status` 检查（`:119-120`）→ 用 `secret_key` 二次验签（`:121-123`）→ 用户三重检查（`:124-136`） | `AuthChannelFilter.java:92-102`（取头）→ `ApiKeyTokenAuthenticator.java:50-98`；scheme `sk`（`:55`）；两阶段验签**逐行一致**（`:63` 不验签 → `:72-79` 查库与状态 → `:83` 二次验签 → `:88-96` 用户三重检查） | **等价** | 该通道是 4 条通道中保真度最高的一条；`userView` 保留整行含 `system_variables`（`ApiKeyTokenAuthenticator.java:107-109`），保证行权限对该通道生效，与 SQLBot `get_user_info` 取整行一致（`apps/system/crud/user.py:30-41`） |
| **③ assistant** | `auth.py:73-85`（取头）→ `validateAssistant` `auth.py:176-206`；scheme `assistant`（`:185-186`）；用 `settings.SECRET_KEY` 验签（`:187-189`）；载荷须含 `assistant_id`（`:191-192`）；当前用户为内部助手 `sqlbot-inner-assistant`（`apps/system/crud/assistant.py:get_assistant_user`）；`assistant_info.oid = int(assistant_info.oid)`（`:200`） | `AuthChannelFilter.java:104-117`（取头）→ `EmbeddedTokenAuthenticator.java:92-118`；scheme `assistant`（`:85-87`）；验签（`:95`）→ 校验 `assistant_id`（`:99-102`）→ 查助手（`:104-105`）→ 构造 `sqlbot-inner-assistant` 合成用户（`:108-116`） | **打折** | 流程一致，但**验签密钥来源不同**：SQLBot 用单一 `settings.SECRET_KEY`；askdata 用 `@Value("${askdata.secret-key:}")`（`EmbeddedTokenAuthenticator.java:58`），而配置里定义的是 `askdata.secret.key`（`application.yaml:48-49`）→ **该占位符永远解析为空** → 回退为运行期随机 UUID（`:62-63`）。见 F-02 |
| **④ embedded** | `auth.py:208-260`；scheme `embedded`（`:183-184`）；先不验签取 `appId`/`embeddedId`/`account`（`:213-217`）；`embeddedId` 缺失时 `xor_decrypt(app_key)` 回退（`:221`、`:262-266`）；**红线 1**：`assistant_info.type != 4` 拒绝（`:229-230`）；用 `app_secret` 二次验签（`:231-233`）；查用户并三重检查（`:237-249`）；**红线 2**：管理员账号拒绝（`:252-253`）；**回写** `assistant_info.oid = int(session_user.oid)`（`:254-255`） | `EmbeddedTokenAuthenticator.java:121-171`；`embeddedId` 回退 XOR（`:177-187`、`:193-205`，密钥 `0xABCD1234L` 与 SQLBot 一致）；**红线 1** `type != 4` 拒绝（`:143-145`）；`app_secret` 二次验签（`:147-152`）；用户三重检查（`:154-165`）；**红线 2** 管理员拒绝（`:167-169`，判定 `id==1 && account=='admin'`） | **打折** | 两条安全红线均已逐字复现（这是 SQLBot 修复 CNVD 嵌入鉴权绕过时的关键改动）。但 ① 验签密钥同样取不到（见 F-02）；② **缺 `assistant_info.oid` 回写**——SQLBot 在 `:254-255` 把助手 oid 改成嵌入用户的 oid，askdata `validateEmbedded` 返回时（`:170`）未做该赋值，助手 oid 停留在应用自身 oid。见 F-08 |
| **⑤ OPTIONS 预检** | `auth.py:41-59`；`/system/assistant/info/(\d+)` 正则 + origin 与助手 domain 匹配 → 200 + 动态 CORS 头 | `AuthChannelFilter.java:77-85`、`:138-170`；正则 `"/system/assistant/info/(\\d+)"`（`:50`）、`OriginSupport.originMatchDomain`（`:160`）、CORS 头集合逐项一致（`:163-168`） | **等价** | 语义与响应头完全一致 |
| **⑥ 鉴权失败响应** | `auth.py:70-71`、`:84-85`、`:94-95`：`JSONResponse(message, status_code=401, headers={"Access-Control-Allow-Origin": "*"})`，其中 `message = trans('i18n_permission.authenticate_invalid', msg=data)`（**已国际化**） | `AuthChannelFilter.java:179-189`：`{"code":401,"data":null,"msg":"Authentication invalid【…】"}` + `Access-Control-Allow-Origin: *` | **打折** | 响应体结构与 CORS 头一致，但文案**硬编码英文**，未走 i18n（消息包中 `i18n_permission.authenticate_invalid` 键**存在**，`messages_zh_CN.properties:223`，但未被使用）。见 F-06 |

---

## 逐项对照表

| # | 检查项 | SQLBot（真值） | askdata | 判定 | 说明 |
|---|---|---|---|---|---|
| 1 | **鉴权通道（4 种 token 方案）** | `apps/system/middleware/auth.py:26-266`（4 通道 + XOR 解密 + 预检） | `common/web/AuthChannelFilter.java:44-197`、`ApiKeyTokenAuthenticator.java:27-110`、`EmbeddedTokenAuthenticator.java:39-245`、`config/SaTokenConfigure.java:30-57` | **打折** | 通道顺序、请求头、scheme、双阶段验签、embedded 红线全部对齐；差异集中在**密钥来源**与**token 形态**。详见「鉴权通道对照表」 |
| 2 | **公共路径白名单** | `common/utils/whitelist.py:6-93`（35 条） | `common/web/AuthWhitelist.java:32-178`（35 条） | **等价** | 35/35 内容与顺序一致；匹配语义一致；仅尾斜杠归一化有窄差异（F-03） |
| 3 | **Host 头校验** | `common/core/host_validation.py:9`（正则 `^[A-Za-z0-9.\-:\[\]]{1,255}$`）、`:14-20`（缺失或非法 → 400 `{"code":400,"data":null,"msg":"invalid host header"}`）；中间件顺序为**最外层**（`main.py:243-244`） | `common/web/HostValidationFilter.java:43`（正则逐字一致）、`:46-47`（响应体逐字一致）、`:39`（`@Order(HIGHEST_PRECEDENCE)`，同为最外层）、`:66-78`（缺失时回退 `getServerName():getServerPort()`） | **打折** | 正则、响应体、过滤器优先级完全一致；**差异在 Host 头缺失时**：SQLBot 返回 400，askdata 放行。见 F-04 |
| 4 | **SQL 安全（仅鉴权相关部分）** | `common/core/security.py:24-29`（bcrypt 上下文）；真实链路全部走 MD5（`:31-37` `md5pwd`/`verify_md5pwd`、`:42-44` `default_md5_pwd`）；`apps/system/crud/user.py:44-50`（`authenticate` 用 `verify_md5pwd`） | `system/auth/CryptoUtil.java:248-264`（`md5Hex`）；`system/controller/LoginController.java:57`、`:97`（比对 MD5）；`system/service/UserService.java`（用户创建/重置用 MD5） | **等价** | SQLBot 的 bcrypt（`security.py:9,24-29`）是**死代码**，真实路径只用 MD5；askdata 只有 MD5。功能等价。SQL 注入防护本身不在本轮范围 |
| 5 | **工作空间 / 用户模型** | 成员绑定 `apps/system/crud/user.py:38-40`（`weight = ws.weight if ws else -1`）；角色判定 `apps/system/schemas/permission.py:79-91`（`isAdmin` / `ws_admin`）；工作空间接口 `apps/system/api/workspace.py:20-236`（含 6 处 `@system_log`：`:127,151,172,200,210,232`）；管理员专属 12 处（`apps/system/api/user.py:27,32,38,48,57,253,264,303,353,363,381,407`） | 成员绑定 `system/repository/UserWsRepository.java:28-30`（`weightOf`，缺失行返回 0）；角色判定 `system/service/CurrentUserService.java:63-90`（`isAdmin` / `requireAdmin` / `requireWsAdmin`）；工作空间接口 `system/controller/WorkspaceController.java:33-97` + `system/service/WorkspaceService.java`；管理员校验 `UserService.java:101,123,155,…` | **打折** | 模型与角色判定**语义等价且 askdata 更严**（见「更强」清单）；缺口在**默认口令**（F-04/F-05）、**初始口令强制改密**（F-03）、**登出 SSO**（F-02）、**Excel 导入列集**（F-08）与**系统管理面审计全缺**（F-06） |
| 6 | **审计（`@system_log`）** | `common/audit/schemas/logger_decorator.py:489-717`（装饰器语义）、`:396-486`（落库）、`:124-165`（客户端 IP）、`:52-76`（`LogConfig`）；`common/audit/schemas/log_utils.py:20-144`（14 表资源名 union）；审计点 **54 处**（`user.py` 8、`workspace.py` 6、`terminology.py` 5、`data_training.py` 5、`chat.py` 5、`assistant.py` 4、`aimodel.py` 4、`dashboard_api.py` 4、`apikey.py` 3、`datasource.py` 3、`login.py` 2、`parameter.py` 1、`mcp.py` 1、`table_relation.py` 1、`recommended_problem.py` 1） | `common/audit/AuditLog.java:21-43`（注解）、`system/audit/AuditLogAspect.java:50-79`（切面）、`system/audit/AuditRecorder.java:76-140`（落库）、`system/audit/AuditResourceNameResolver.java:21-74`（13 表映射）、`common/audit/AuditClientIpResolver.java:26-101`（IP 链）；审计点 **29 处**（`AssistantService` 8、`DashboardService` 6、`TrainingService` 5、`TerminologyService` 4、`CustomPromptService` 4、`DsPermissionService` 2） | **缺失** | `system/` 下 **0 个 `@AuditLog`**（`system/controller/{User,Workspace,ApiKey,Parameter,Variable,AiModel}Controller.java` 全部无审计注解）；SQLBot 在同一范围有 **21 处**（user 8 + workspace 6 + apikey 3 + parameter 1 + aimodel 4 中相关项）。另：资源名映射**缺 CHAT**（SQLBot union 14 表见 `log_utils.py:34-39`，askdata `TABLE_MAP` 13 项见 `AuditResourceNameResolver.java:21-35`）；`sys_apikey` 映射到不存在的 `name` 列（`:33` vs `resources/schema.sql:80-87` 只有 `access_key`），SQLBot 用的是 `access_key`（`log_utils.py:121`） |
| 7 | **国际化（5 语言）** | `common/utils/locale.py:23-31`（`Accept-Language` 首段，未命中回落 `zh-cn`）；`locales/{en,ko-KR,th-TH,zh-CN,zh-TW}.json`，各 **177** 键 | `common/i18n/AskdataLocaleResolver.java:33-54`（`?lang=` → `lang` Cookie → `Accept-Language` q 值择优 → 默认 zh-CN）；`resources/messages.properties` + `messages_{en,ko_KR,th_TH,zh_CN,zh_TW}.properties`，各 **281** 键 | **打折** | **消息包覆盖度满分**：SQLBot 177 键在 askdata 5 个 bundle 中 **100% 命中（缺失 0）**，占位符集合 **0 差异**，5 语言之间占位符 **0 差异**，askdata 额外多 104 键。**但语言协商更宽松**（askdata 支持 `?lang=`/Cookie/q 值，SQLBot 仅 `Accept-Language` 首段），且**大量用户可见错误仍硬编码英文**（F-06） |
| 8 | **加密 / 密钥** | `common/core/config.py:34`（`SECRET_KEY = secrets.token_urlsafe(32)`，每次启动随机）、`:63-65`（`TOKEN_KEY` / `DEFAULT_PWD` / `ASSISTANT_TOKEN_KEY`）；`common/core/security.py:12,15-21`（HS256 + `SECRET_KEY`） | `resources/application.yaml:47-49`（`askdata.secret.key: ${ASKDATA_SECRET_KEY:AskDataSecret001}` ← **硬编码缺省**）；`system/auth/CryptoUtil.java:61-64`（读 `askdata.secret.key`）、`:72-78`（非 16 字节按 SHA-256 前 16 字节派生）、`:271-273`（未配置时运行期随机兜底）、`:41`（前端 AES key `SQLBot1234567890`）、`:125-154`（`simpleAes` 与 SQLBot 一致）、`:248-264`（MD5） | **打折** | 前端 AES-ECB 互通与 `simple_aes` 逐字一致；口令散列一致；但应用密钥的**缺省值硬编码进版本库**（F-01），且嵌入通道的密钥占位符**与配置键名不匹配**（F-02）。askdata 自带的 `NoHardcodedSecretsTest.java:32-34` 已声明「`@Value` 不得带非空缺省值」，但 `application.yaml` 的 YAML 层缺省值不在该测试的扫描范围内 |
| 9 | **雪花 ID / 分页 / 缓存** | 雪花：`common/utils/snowflake.py:3-57`（41/5/5/12 位段、epoch=0、`worker_id=1`（`:57`）、时钟回拨拒绝（`:40-41`））；`SnowflakeBase.Config.json_encoders`（超 2^53-1 转字符串）；分页：`apps/system/api/user.py` 分页响应含 `order_by`/`desc` 回显；缓存：`common/core/sqlbot_cache.py:56-160`（`cache`/`clear_cache`/`init_sqlbot_cache`），`get_user_info` 与 `get_api_key` 走缓存（`apps/system/crud/user.py:29`、`apps/system/crud/apikey_manage.py:10`），改密/重置/停用/切空间时 `clear_cache`（`apps/system/api/user.py:235,304,370,382,392,408`、`apps/system/crud/user.py:77,89`） | 雪花：`common/util/SnowflakeIdGenerator.java:31-198`（同位段、epoch 可配但缺省 0、时钟回拨拒绝 `:111-114`、worker id 按 PID 派生 `:185-197`）；`common/util/IdGenerator.java:22-39`；安全整数序列化 `common/web/JsonLongConfig.java:25-50`；分页：`system/service/UserService.java:98-120`；缓存：**无任何缓存层** | **打折** | 雪花**逐位等价**（含「超 2^53-1 转字符串」这一 SQLBot 同款做法）。分页：排序**生效**（`UserService.java:104` 把 `orderBy/desc` 传给查询），但 `new PageResult<>(items, total, page, size)`（`:119`，4 参构造）**丢失 `order_by`/`desc` 回显**。缓存：**完全缺失**（全仓 `Caffeine`/`@Cacheable`/`@CacheEvict`/`EnableCaching`/`RedisTemplate` 命中 **0**；`pom.xml:164` 虽有 `fw-plus-redis` 依赖但未使用） |
| 10 | **响应信封 + 错误分类** | `common/core/response_middleware.py:27-97`（信封包装）；跳过 `JSONResponse`（`:42`）、跳过 `{code,data,msg}` 已包装体（`:56`）、非 200 不包装（`:46`）、非 JSON 不包装（`:48`）、静态资源加 CSP `frame-ancestors`（`:90-96`）；异常处理 `:100-117`（`http_exception_handler` / `global_exception_handler`，均带 `Access-Control-Allow-Origin: *`，错误体为 `JSONResponse(content=exc.detail)` → **JSON 字符串**） | `common/web/ResponseEnvelopeAdvice.java:44-114`（信封包装）；跳过 `String` 体（`:53-55`）、跳过非 JSON（`:57-58`）、跳过文件下载/流式（`:60-61`）、跳过 `Result` 对象与 `{code,data,msg}`（`:102-114`）；异常 `common/web/ApiExceptionHandler.java:32-90`（400 / 401 / 401 / 500，错误体为**纯文本** `detail`；CORS 头回显 `Origin`，无 Origin 时 `*`） | **打折** | 信封结构 `{code:0,data,msg:null}` 与跳过规则一致，askdata **多跳过** String / 非 JSON / 下载三类（更稳）。差异：①错误体形态（SQLBot 是 JSON 字符串，askdata 是 `text/plain`）；②CORS 来源（SQLBot 固定 `*`，askdata 回显 `Origin`）；③权限拒绝状态码（SQLBot `raise Exception` → **500**，askdata `BadRequestException` → **400**，见 F-09） |

---

## 打折与缺失明细

严重度定义：**高** = 可直接导致安全绕过 / 密钥泄露 / 功能不可用；**中** = 明显功能或合规缺口，影响运维与审计可信度；**低** = 边界行为差异、注释漂移、可读性或体验问题。

---

### F-01 【高】应用密钥缺省值硬编码进版本库

- **SQLBot**：`common/core/config.py:34` → `SECRET_KEY: str = secrets.token_urlsafe(32)`（进程启动时随机生成，不落版本库）。
- **askdata**：`resources/application.yaml:47-49`
  ```yaml
  # 应用密钥（落库加密用；缺省与历史常量一致，可用环境变量 ASKDATA_SECRET_KEY 覆盖）
  secret:
    key: ${ASKDATA_SECRET_KEY:AskDataSecret001}
  ```
  缺省值 `AskDataSecret001` 明文写在版本库中，任何拿到仓库的人都能解密 `sys_datasource.configuration` 的落库密文。
- **消费点**：`system/auth/CryptoUtil.java:61-64`（`@Value("${askdata.secret.key:}")`）、`:72-78`（`askDataSecretKey()`，非 16 字节时 SHA-256 前 16 字节派生）、`:104-110`（`decryptWithAppSecret` / `encryptWithAppSecret`）。
- **旁证**：askdata 自己的静态检查 `NoHardcodedSecretsTest.java:32-34` 明确声明「`@Value` 注解中 secret/password/key/token 类配置不得带非空缺省值（历史漏检：askdata.secret-key 缺省 AskDataSecret001）」——但该测试只扫 `src/main/java` 与 `schema.sql`（`:39-46`），**不扫 YAML**，因此这条 YAML 缺省值逃过了自家守卫。
- **影响**：落库加密等价于无加密；密钥轮换需要重加密全部历史数据。
- **建议**：删除 YAML 缺省值（改为 `${ASKDATA_SECRET_KEY:}`），并把 `NoHardcodedSecretsTest` 的扫描范围扩展到 `src/main/resources/*.yaml|*.properties`；`CryptoUtil` 已具备运行期随机兜底（`:271-273`），缺省改为空不影响启动。

---

### F-02 【高】嵌入助手通道的验签密钥配置键名写错，永不解析

- **配置实际定义**：`resources/application.yaml:48-49` → `askdata.secret.key`（点）。
- **代码读取**：
  - `common/web/EmbeddedTokenAuthenticator.java:58` → `@Value("${askdata.secret-key:}") String secretKey`（**连字符**）
  - `embedded/AssistantPublicController.java:45` → `@Value("${askdata.secret-key:}") String secretKey`（**连字符**）
- **后果**：两个占位符永远解析为空字符串 → 回退为 `java.util.UUID.randomUUID().toString()`（`EmbeddedTokenAuthenticator.java:62-63`、`AssistantPublicController.java:48-49`）。
- **对照 SQLBot**：`apps/system/middleware/auth.py:188`（assistant 通道）与 `:232`（embedded 通道的 `app_secret`）用的是**同一个** `settings.SECRET_KEY`/`app_secret` 体系；SQLBot 侧不存在「每个组件各持一把密钥」的问题。
- **影响**：
  1. assistant 通道与 `/system/assistant/info/{id}` 签发的访问 token 与 `CryptoUtil` 的 `askdata.secret.key` **不是同一把密钥**，语义分裂；
  2. 重启后密钥变化 → 此前签发的嵌入/助手 token **全部失效**；
  3. 与 F-01 叠加：`application.yaml` 里那把「有值的」密钥（`AskDataSecret001`）**根本没被这两处使用**，反而落库加密在用——运维改 `ASKDATA_SECRET_KEY` 不会影响嵌入鉴权，改嵌入通道行为却需要改代码。
- **建议**：把两处 `@Value("${askdata.secret-key:}")` 统一改为 `@Value("${askdata.secret.key:}")`（或更好：直接注入 `CryptoUtil`，复用 `askDataSecretKey()` 这一唯一密钥源）。

---

### F-03 【高】`login.initial_pwd_disabled` 强制改密逻辑整体缺失

- **SQLBot**：
  - `apps/system/api/login.py:22-25` — `initial_pwd_disabled(session)` 读系统参数 `login.initial_pwd_disabled`。
  - `apps/system/api/login.py:50-55` — 非 id=1 用户且处于默认口令且开关为真时，**返回 `Token(access_token='', need_change_pwd=True)`**，即**不发放任何可用 token**。
  - `apps/system/api/login.py:81-82` — 改密时若新口令等于默认口令且开关为真 → 拒绝（`i18n_login.new_pwd_is_initial`）。
- **askdata**：
  - `resources/schema.sql:181` — `VALUES (1, 'login.initial_pwd_disabled', 'false', 'str', 1);`（**只有种子数据，无任何代码读取**）。
  - `system/controller/LoginController.java:46-84`（`accessToken`）— 只有 `oid == 0`（`:65-67`）、`status != 1`（`:68-70`）、`origin != 0`（`:71-73`）三项检查，**无默认口令判定、无 `need_change_pwd` 返回路径**。
  - `system/controller/LoginController.java:86-116`（`changePwd`）— 只校验格式（`:104-106`），**无「新口令不得等于默认口令」判定**。
  - `system/web/TokenVO.java` — 无 `need_change_pwd` 字段。
- **影响**：SQLBot 的「初始口令必须改」安全开关在 askdata 侧是**空壳**——数据库里可以把它打开，但没有任何一行代码会读，管理员以为已启用强制改密，实际所有用户可用默认口令直接登录。
- **建议**：在 `LoginController.accessToken` 中读该系统参数并复刻 `login.py:50-55` 的分支；`TokenVO` 增补 `need_change_pwd`；`changePwd` 复刻 `login.py:81-82` 的拒绝。

---

### F-04 【中】默认口令不可预测，重置密码后无法告知用户

- **SQLBot**：`common/core/config.py:64` → `DEFAULT_PWD: str = "SQLBot@123456"`；`common/core/security.py:39-44`（`default_pwd` / `default_md5_pwd`）；`apps/system/api/user.py:383-389`（`pwdReset` 直接写入 `default_md5_pwd()`）。
- **askdata**：`system/service/UserService.java:43-44`
  ```java
  @Value("${askdata.user.default-password:}")
  private String defaultPasswordConfig;
  ```
  `system/service/UserService.java:48-54` — `resolveDefaultPassword()` 在配置为空时回退 `java.util.UUID.randomUUID().toString()`。
- **配置缺失**：全仓检索 `askdata.user.default-password` 在 `application.yaml` 与其他配置文件中**无任何定义**（只有 `@Value` 的默认值 `""`）。
- **影响**：`resetPassword` 与 `create` 写入的口令是一个**运维和用户都无法得知的随机 UUID**；且因 `resolvedDefaultPassword` 是进程内单例缓存字段（`:46,49`），**每次重启都会换成另一个新口令**——同一账号在不同部署实例上的初始口令还不一致。
- **建议**：在 `application.yaml` 显式配置 `askdata.user.default-password`（或改为与 SQLBot 一致的固定值），并去掉随机回退；如坚持不落版本库，应改为「启动时生成并打印一次到日志 + 落库到一张配置表」的方案，而不是每次重启都变。

---

### F-05 【中】系统管理面审计全缺（用户 / 工作空间 / 成员 / API Key / 参数 / AI 模型）

- **SQLBot 审计点分布**（`grep -rn "@system_log"` 全仓命中 **54** 处，含 `common/audit/schemas/logger_decorator.py:489` 的定义处 1 处；业务侧 53 处）：
  - `apps/system/api/user.py` **8** 处（`:236` ws_change、`:265` user_create、`:305` update、`:354` delete、`:364` batch_del、`:383` pwdReset、`:393` pwdUpdate、`:409` statusChange）
  - `apps/system/api/workspace.py` **6** 处（`:127` 成员绑定、`:151` 成员改权重、`:172` 成员解绑、`:200` 建空间、`:210` 改空间、`:232` 删空间）
  - `apps/system/api/apikey.py` **3** 处、`apps/system/api/aimodel.py` **4** 处、`apps/system/api/parameter.py` **1** 处、`apps/system/api/login.py` **2** 处（`:29` 登录、`:65` 改密）
- **askdata 审计点分布**（`grep -rn "@AuditLog"` 全仓命中 **29** 处）：
  - `embedded/AssistantService.java` 8、`dashboard/DashboardService.java` 6、`training/TrainingService.java` 5、`terminology/TerminologyService.java` 4、`prompt/CustomPromptService.java` 4、`permission/DsPermissionService.java` 2
  - **`system/` 目录下 0 处**：`system/controller/UserController.java`、`WorkspaceController.java`、`ApiKeyController.java`、`ParameterController.java`、`VariableController.java`、`AiModelController.java` 全部无 `@AuditLog` 注解。
  - 登录 / 改密走 `system/audit/AuditRecorder.java:120-134` 的 `recordLogin` 手工补记（`system/controller/LoginController.java:76-77`、`:108-109`），但**记的 module 恒为 `AuditModule.USER`**（`AuditRecorder.java:129`），且不带 `resource_id`。
- **影响**：用户增删改、密码重置、账号启停、工作空间与成员绑定/解绑、API Key 签发/停用、系统参数与 AI 模型配置变更——**全部无审计痕迹**。这是合规审计最关注的操作面，SQLBot 侧有完整留痕。
- **建议**：在 `system/service/{UserService,WorkspaceService,ApiKeyService,ParameterService,VariableService,AiModelService}.java` 的写方法上补 `@AuditLog`，module 分别用 `AuditModule.USER` / `WORKSPACE` / `MEMBER` / `API_KEY` / `PARAMS_SETTING` / `AI_MODEL`（枚举已具备，见 `common/audit/AuditModule.java:10-22`）。

---

### F-06 【中】API Key / 嵌入助手通道的操作永远不会被审计

- **askdata**：`system/audit/AuditRecorder.java:79-83`
  ```java
  try {
      Long uid = currentUid();
      if (uid == null) {
          return;              // ← 无登录用户则整条审计直接返回
      }
  ```
  `currentUid()`（`:142-148`）只尝试 `StpUtil.getLoginIdAsLong()`；支线通道（sk / assistant / embedded）没有 Sa-Token 登录态，抛异常被吞 → 返回 `null` → **审计被跳过**。
- **SQLBot**：`common/audit/schemas/logger_decorator.py:345-356`（`get_current_user` 从 `request.state.current_user` 取用户）、`:414-416`（`user_id` / `user_name` 落库）、`:434`（`oid`）。SQLBot 的 `TokenMiddleware` 对**三条通道都写 `request.state.current_user`**（`apps/system/middleware/auth.py:68`、`:76`、`:91`），因此支线通道的操作**同样有审计**。
- **影响**：通过 API Key 或嵌入助手令牌执行的删除/修改操作（尤其 data_training、terminology、custom_prompt 这三类带 `@AuditLog` 的服务）**在审计表中完全不可见**。这与 F-05 叠加后，askdata 的审计覆盖面在「系统管理面」和「支线通道」两个维度同时失守。
- **建议**：`AuditRecorder.currentUid()` 改为先读 `AuthContext.current().getCurrentUser()`（该上下文在三条通道都已填好，见 `common/web/AuthContext.java:55-61`、`AuthChannelFilter.java:95-98`/`:108-113`），再回落 Sa-Token。

---

### F-07 【中】审计资源名映射缺 CHAT，且 `sys_apikey` 映射到不存在的列

- **SQLBot**：`common/audit/schemas/log_utils.py:20-144` 的 `build_resource_union_query()` 覆盖 **14** 张表：`ai_model`(:28-32)、**`chat`(:35-39)**、`dashboard`(:42-46)、`datasource`(:49-53)、`custom_prompt`(:56-60)、`data_training`(:63-67)、`ds_permission`(:70-74)、`ds_rules`(:77-81)、`user`(:84-88)、`member`(:91-95)、`sys_workspace`(:98-102)、`terminology`(:105-109)、`sys_assistant`(:112-116)、`sys_apikey`(:119-123)。
  - `sys_apikey` 的 name 列用的是 **`access_key`**（`:121`）。
- **askdata**：`system/audit/AuditResourceNameResolver.java:21-35` 的 `TABLE_MAP` 仅 **13** 项，**无 `AuditModule.CHAT`**（枚举存在但未映射：`common/audit/AuditModule.java:7`）。
  - `sys_apikey` 映射为 `new String[]{"sys_apikey", "id", "name"}`（`:33`），但 `resources/schema.sql:80-87` 的 `sys_apikey` 只有 `id / access_key / secret_key / create_time / uid / status`——**没有 `name` 列**。
  - `resolve()` 的 `catch` 兜底返回 `null`（`:62-64`），所以不会抛错，但 **API Key 的资源名永远解析不出**。
- **附带**：`AuditModule.CHAT` 在 askdata 全仓无任何使用点；SQLBot `apps/chat/api/chat.py` 有 **5** 处 `@system_log`（`:120,136,154,170,471`），其中 4 处 module 为 CHAT，askdata 无对应实现。
- **影响**：审计列表中 API Key 与 CHAT 类记录的资源名列为空，无法回溯「改的是哪个 Key / 哪次问数」。
- **建议**：`AuditResourceNameResolver.java:33` 的 name 列改为 `access_key`；补 CHAT → `chat` 表（name 列用 `brief`，对齐 `log_utils.py:37`）；同步补齐 chat 模块的 `@AuditLog`。

---

### F-08 【中】embedded 通道未回写 `assistant_info.oid`

- **SQLBot**：`apps/system/middleware/auth.py:252-256`
  ```python
  if session_user.isAdmin:
      return False, f"Admin account is not allowed for embedded token!"
  if session_user.oid:
      assistant_info.oid = int(session_user.oid)
  return True, session_user, assistant_info
  ```
  管理员拒绝之后，把助手的 oid **改成嵌入用户的 oid**——保证嵌入会话里的数据归属落到实际用户的工作空间。
- **askdata**：`common/web/EmbeddedTokenAuthenticator.java:166-170`
  ```java
  if (isAdmin(user)) {
      throw new AuthFailureException("Admin account is not allowed for embedded token!");
  }
  return new AuthResult(ApiKeyTokenAuthenticator.userView(user), assistant);
  ```
  **没有 `assistant.oid = user.oid` 的回写**，`assistant` 保持应用自身的 oid（`:139-140` 查出后原样返回）。
- **影响**：嵌入会话中「助手所属工作空间」与「用户所属工作空间」不一致；以助手 oid 为准的数据过滤/行权限会落到错误的工作空间上下文。
- **建议**：在 `EmbeddedTokenAuthenticator.java:170` 之前补 `assistant.put("oid", Rows.lng(user, "oid", 0L))`（注意 `assistant` 是 `Map`，需保证 key 命名与 `AssistantHeader` 一致）。

---

### F-09 【中】权限拒绝的 HTTP 状态码不一致（500 vs 400）

- **SQLBot**：`apps/system/schemas/permission.py:86-91`
  ```python
  if 'admin' in role_list and not current_user.isAdmin:
      raise Exception(trans('i18n_permission.only_admin'))
  if 'ws_admin' in role_list and current_user.weight == 0 and not current_user.isAdmin:
      raise Exception(trans('i18n_permission.only_ws_admin'))
  ```
  抛的是**裸 `Exception`**，被 `common/core/response_middleware.py:110-117` 的 `global_exception_handler` 捕获 → **HTTP 500**，响应体为 `JSONResponse(content=str(exc))`（JSON 字符串）。同文件 `:107-113` 的 `http_exception_handler` 才是 4xx。
- **askdata**：`system/service/CurrentUserService.java:75-79`（`requireAdmin`）与 `:82-90`（`requireWsAdmin`）抛 `BadRequestException` → `common/web/ApiExceptionHandler.java:32-36` → **HTTP 400**，响应体为 `text/plain` 的 `messages.get("perm.admin.only")` / `messages.get("perm.ws.admin.only")`。
- **影响**：
  1. 状态码语义不同（askdata 更合理，但与 SQLBot 不一致，前端若按 5xx 统一提示「系统错误」会掩盖「无权限」的语义）；
  2. **响应体形态不同**：SQLBot 是 JSON 字符串（`"仅支持工作空间管理员调用！"`），askdata 是纯文本。SQLBot 前端 `request.ts` 展示 `response.data` 时，JSON 字符串会带引号。
- **建议**：若目标是「与 SQLBot 完全等价」，需让 askdata 的权限拒绝走 `global_exception_handler` 语义（500 + JSON 字符串）；若接受 askdata 的改进，则在报告中显式记录为**有意偏离**并同步前端。

---

### F-10 【低】缓存层整体缺失

- **SQLBot**：`common/core/sqlbot_cache.py:56-90`（`cache` 装饰器）、`:92-124`（`clear_cache` 装饰器）、`:127-142`（`init_sqlbot_cache`，支持 memory / redis 两种后端）、`:145-160`（`is_cache_initialized`）。
  - 缓存对象：`get_user_info`（`apps/system/crud/user.py:29`）、`get_api_key`（`apps/system/crud/apikey_manage.py:10`）、`get_ds_id_list`（`apps/datasource/crud/datasource.py:654`）、助手信息（`apps/system/crud/assistant.py:40`）。
  - 失效点：改密 / 重置 / 停用 / 切空间等 **12 处** `clear_cache`（`apps/system/api/user.py:235,304,370,382,392,408`、`apps/system/crud/user.py:77,89`、`apps/system/crud/apikey_manage.py:15`、`apps/datasource/crud/datasource.py:70,661`、`apps/system/api/assistant.py:238,346,374`）。
- **askdata**：**无任何缓存层**。全仓检索 `Caffeine` / `@Cacheable` / `@CacheEvict` / `@CachePut` / `EnableCaching` / `RedisTemplate` 命中 **0**；`askdata-server/pom.xml:164` 虽声明了 `fw-plus-redis` 依赖，但代码中未使用。
- **影响**：功能上无差异（askdata 每次都查库，结果更「新鲜」）；差异在**性能**：每次鉴权都要打一次 `sys_user` 查询（`ApiKeyTokenAuthenticator.java:89`、`EmbeddedTokenAuthenticator.java:154-159`、`CurrentUserService.java:59`），高 QPS 场景下数据库压力显著高于 SQLBot。
- **建议**：若追求性能等价，可引入 Spring Cache + Caffeine，并在用户/密钥变更处加 `@CacheEvict`。此项**不阻塞功能等价性**，可按负载决定。

---

### F-11 【低】登出未走第三方 SSO 分支

- **SQLBot**：`apps/system/api/login.py:89-93`
  ```python
  @router.post("/logout")
  async def logout(session: SessionDep, request: Request, dto: LogoutSchema):
      if dto.origin != 0:
          return await xpack_logout(session, request, dto)
      return None
  ```
  `origin != 0`（第三方来源账号）时委托 xpack 做单点登出。
- **askdata**：`system/controller/LoginController.java:118-122`
  ```java
  @PostMapping({"/login/logout"})
  public Result<Void> logout() {
      StpUtil.logout();
      return Result.ok();
  }
  ```
  无 origin 分支，无第三方登出。
- **影响**：第三方来源账号在 askdata 侧登出只清本地 Sa-Token 会话，IdP 侧会话仍在（单点登出不完整）。askdata 已有 `system/sso/`（Cas/DingTalk/Feishu/OIDC/SAML/WechatWork）与 `system/platform/PlatformSyncService.java`，具备做这件事的基础。
- **建议**：在 `logout` 中读取请求体的 `origin`，非 0 时补第三方登出。

---

### F-12 【低】Host 头缺失时行为不一致

- **SQLBot**：`common/core/host_validation.py:14-20`
  ```python
  host = request.headers.get("host")
  if not host or not _HOST_RE.match(host):
      return JSONResponse({"code": 400, "data": None, "msg": "invalid host header"}, status_code=400)
  ```
  Host **缺失**（`not host`）同样返回 400。
- **askdata**：`common/web/HostValidationFilter.java:66-78`
  ```java
  String host = request.getHeader("Host");
  if (host == null) {
      // Host 头缺失：回退到 Servlet 容器的 server name:port（HTTP/1.0 客户端场景）
      String serverName = request.getServerName();
      ...
      host = port > 0 ? serverName + ":" + port : serverName;
  }
  return HOST_PATTERN.matcher(host).matches();
  ```
  Host 缺失时回退到容器 server name 再校验（`getServerName()` 为空才拒绝）。
- **注**：正则（`:43`）、400 响应体（`:46-47`）、过滤器优先级（`:39` `@Order(HIGHEST_PRECEDENCE)`，与 SQLBot `main.py:243-244` 的「最后注册即最外层」等价）**完全一致**。
- **影响**：HTTP/1.0 客户端（不发 Host 头）在 SQLBot 会被 400 拒绝，在 askdata 会放行。askdata 的取舍在 HTTP/1.0 兼容性与「Host 缺失 = 无注入源」的安全性推理上是合理的（`:29-36` 注释已说明），但属于**行为偏离**。
- **建议**：确认为有意偏离并写进文档；或统一为「Host 缺失 → 400」。

---

### F-13 【低】白名单尾斜杠归一化规则不同（askdata 略宽松）

- **SQLBot**：`common/utils/whitelist.py:80` → `path = path.rstrip('/')`（去除**全部**尾斜杠）。
- **askdata**：`common/web/AuthWhitelist.java:160` → `path.endsWith("/") ? path.substring(0, path.length() - 1) : path`（只去除**一个**）。
- **差分实测**（44 条代表性路径双实现逐条比对）：仅 **2** 条判定不同，且**差异方向均为 askdata 更宽松**：
  - `/login//` — SQLBot 拒绝（`rstrip` 后 `/login` 不匹配 `^/login/.*$`），askdata 放行（去掉一个斜杠后 `/login/` 匹配）；
  - `/images//` — 同理。
  - 其余 42 条完全一致，包括 `/system/license/`、`/system/license//`（两侧都拒绝）、`/mcp/`、`/mcp//`、`/api/v1/system/user/list`、`/../etc/passwd`。
- **影响**：极窄。仅当路径末尾有 **≥2 个连续斜杠**且去掉全部后不再匹配、去一个后仍匹配时，askdata 会额外放行。**不构成白名单绕过**（这些路径仍需通过通道鉴权），但判定口径与 SQLBot 不一致。
- **建议**：把 `AuthWhitelist.java:160` 改为 `path.replaceAll("/+$", "")`，与 SQLBot 逐字一致。

---

### F-14 【低】审计客户端 IP 头链顺序不同

- **SQLBot**：`common/audit/schemas/logger_decorator.py:131-138`
  ```
  x-forwarded-for → x-real-ip → cf-connecting-ip → x-client-ip → x-forwarded → forwarded
  ```
  （RFC 7239 `Forwarded` 排在**最后**）
- **askdata**：`common/audit/AuditClientIpResolver.java:26-27`（`SINGLE_HEADERS` = `X-Forwarded-For, X-Real-IP, CF-Connecting-IP, X-Client-IP, X-Forwarded`）+ `:52-58`（`Forwarded` 在 `SINGLE_HEADERS` 之后才读）→ 顺序与 SQLBot **一致**。
  - 但 `X-Forwarded` 的处理**语义不同**：SQLBot `:144-152` 把 `x-forwarded` 当普通单值头（`value.strip()`）；askdata `:43-49` 把 `X-Forwarded` 当作 RFC 7239 风格解析 `for=`（解析失败则继续回退）。两者在 `X-Forwarded: for=1.2.3.4` 这种写法下解析结果不同。
- **影响**：多数场景（标准 `X-Forwarded-For`）无差异；仅 `X-Forwarded` 头内含 RFC 7239 `for=` 语法时，askdata 解析出 IP 而 SQLBot 解析出整串字符串。
- **建议**：统一 `X-Forwarded` 的解析口径（建议按 RFC 7239 解析，askdata 的做法更正确，但需在报告中标注偏离）。

---

### F-15 【低】用户可见错误大量硬编码英文，未走 i18n

消息包覆盖度是满分（SQLBot 177 键在 askdata 5 个 bundle 中 100% 命中），但下列**面向用户**的报错仍是硬编码英文，切换语言后不随之变化：

| askdata 位置 | 硬编码文案 | SQLBot 对应 |
|---|---|---|
| `system/service/UserService.java:330-331` | `Workspace [...] is not available for the current user` | `apps/system/api/workspace.py` / `user.py` 使用 `trans(...)` |
| `system/service/UserService.java:333` | `Workspace [...] does not exist` | 同上 |
| `system/service/UserService.java:398` | `Missing required argument: [key]` | 同上 |
| `system/service/UserService.java:411` | `Invalid system_variables` | 同上 |
| `system/service/UserService.java:180` | `account is required for external identity` | 同上 |
| `system/service/WorkspaceService.java:153` | `Missing required argument: [oid]` | 同上 |
| `system/service/WorkspaceService.java:169` | `Missing required argument: [keyword]` | 同上 |
| `system/service/WorkspaceService.java:190` | `Missing required argument: [uid_list]` | 同上 |
| `system/service/WorkspaceService.java:208` | `Missing required argument: [oid, uid]` | 同上 |
| `system/service/WorkspaceService.java:211` | `uws not exist` | 同上 |
| `system/controller/UserController.java:197`、`:211` | `Login user does not exist` | 同上 |
| `common/web/AuthChannelFilter.java:183-184` | `Authentication invalid【...】` | `apps/system/middleware/auth.py:70/84/94` 用 `trans('i18n_permission.authenticate_invalid')` |
| `common/web/ApiExceptionHandler.java:41` | `authenticate invalid: ...` | 同上 |
| `common/web/ApiKeyTokenAuthenticator.java:90,92,95` | `Account does not exist` / `User is disabled` / `No associated workspace` | `auth.py:127,131,134` 用 `trans('i18n_not_exist'/'i18n_login.user_disable'/'i18n_login.no_associated_ws')` |
| `common/web/EmbeddedTokenAuthenticator.java:137,156,161,164` | 同上 4 条 | 同上 |

- **旁证**：`messages_zh_CN.properties:223` 已存在 `i18n_permission.authenticate_invalid=认证无效【{msg}】`（5 个 bundle 全有），**键存在但未被使用**。
- **影响**：非中文用户在鉴权失败与参数错误场景看到英文；与 SQLBot 的「全部用户可见文案 i18n」基线不一致。
- **建议**：把上表中的字面量迁入 `messages*.properties`（键名可沿用 SQLBot 的 `i18n_*` 键以复用现有 177 键），并在 `ApiKeyTokenAuthenticator` / `EmbeddedTokenAuthenticator` 中注入 `MessageResolver`。

---

### F-16 【低】登录 token 形态与有效期不同

- **SQLBot**：`common/core/security.py:15-21` 用 PyJWT 签发 **HS256 JWT**（payload 含用户字段 + `exp`）；`common/core/config.py:36` → `ACCESS_TOKEN_EXPIRE_MINUTES = 60*24*8`（**8 天**）。
- **askdata**：`application.yaml:71-83` 使用 Sa-Token：
  ```yaml
  sa-token:
    token-name: X-SQLBOT-TOKEN
    token-prefix: Bearer
    is-read-header: true
    is-read-cookie: false
    timeout: 2592000      # 30 天
    is-concurrent: true
    is-share: false
    token-style: uuid
  ```
  签发的是 **UUID token**（`system/controller/LoginController.java:75-78`），**有效期 30 天**（SQLBot 是 8 天）。
- **注**：askdata **另行实现了一套真 HS256 JWT**（`system/auth/JwtCodec.java:41-60` / `:67-106`），供 sk / assistant / embedded 三条支线通道使用，与 SQLBot 逐字互通（`:14-18` 注释）——**主通道反而不互通**。
- **影响**：若前端或其他系统依赖「主通道 token 是可离线校验的 JWT」，askdata 不满足；且 token 有效期比 SQLBot 长 3.75 倍。
- **建议**：把 `timeout` 调为 `11520`（= 8 天）以对齐；确认前端不依赖 JWT 结构（askdata 前端用 Sa-Token 会话则无影响）。

---

### F-17 【低】Excel 用户导入列集不同

- **SQLBot**：`apps/system/crud/user_excel.py:137-147` 的 `get_i18n_head_list()` 返回 **8 个 i18n 列**：`account / name / email / workspace / role / status / origin / platform_user_id`；`:149-156` 的 `validate_head` 要求**列数与列名逐字完全匹配**（且表头是**翻译后的**文案）。
- **askdata**：`system/service/UserImportService.java:54`
  ```java
  static final List<String> IMPORT_HEADERS = Arrays.asList("account", "name", "password", "email");
  ```
  仅 **4 列**（account / name / password / email），表头**不做 i18n**；`:74` → `MIN_PASSWORD_LENGTH = 6`（SQLBot 用 `PWD_REGEX` 要求 8-20 位且必须含大小写+数字+特殊字符，见 `apps/system/schemas/system_schema.py:14-18`）。
- **影响**：SQLBot 导出的模板**不能**在 askdata 导入；askdata 的导入**不做工作空间/角色/来源**分配（改为绑定到当前管理员的工作空间，见 `UserImportService.java:41-43` 注释），口令强度策略也更弱。
- **建议**：对齐为 8 列并复用 SQLBot 的 `PWD_REGEX` 校验强度。

---

### F-18 【低】`sys_logs_resource` 表在 `schema.sql` 中被定义两次

- `resources/schema.sql:526-532`
  ```sql
  CREATE TABLE IF NOT EXISTS sys_logs_resource (
      id BIGINT PRIMARY KEY, log_id BIGINT, resource_id VARCHAR(10000),
      resource_name VARCHAR(10000), module VARCHAR(255));
  ```
- `resources/schema.sql:637-643`
  ```sql
  CREATE TABLE IF NOT EXISTS sys_logs_resource (
      id BIGINT PRIMARY KEY, resource_id VARCHAR(255), module VARCHAR(255),
      resource_name VARCHAR(255), log_id BIGINT);
  ```
- 两次 DDL 的**列集合完全相同**，仅**列顺序与 VARCHAR 长度**不同（10000 vs 255）；因都是 `IF NOT EXISTS`，实际生效的是**第一条**（`:526`），第二次是空操作。
- **影响**：功能无影响（H2 忽略后一条）。但两处定义不一致容易在后续维护中误改——例如按 `schema.sql:637` 的 `VARCHAR(255)` 去理解 `resource_id` 上限，实际生效的却是 10000。
- **建议**：删除 `schema.sql:633-643` 的重复块（保留 `:525-532`），并在注释中指向 SQLBot 的 `common/audit/models/log_model.py:51-57`。

---

### F-19 【低】审计关联行（`sys_logs_resource`）的写入条件不同

- **SQLBot**：`common/audit/schemas/logger_decorator.py:454-470`
  ```python
  resource_ids = [str(resource_id)] if not isinstance(resource_id, list) else [...]
  for resource_id_details in resource_ids:
      resource_entry = SystemLogsResource(resource_id=resource_id_details, log_id=log.id, module=config.module)
  ```
  **每条审计都会写一条关联行**，即使 `resource_id` 是 `None`（`logger_decorator.py:441` 的 `resource_id=str(resource_id)` 会写成字符串 `"None"`）。
- **askdata**：`system/audit/AuditRecorder.java:100-109`
  ```java
  if (resourceId != null && !resourceId.isEmpty()) { ... INSERT INTO sys_logs_resource ... }
  ```
  **仅当 resourceId 非空时才写关联行**。
- **影响**：SQLBot 的关联表里会出现 `resource_id = "None"` 的噪声行；askdata 更干净，但**与 SQLBot 的关联表行数/内容不一致**——依赖该表做「按资源反查审计」的查询在两侧结果不同。
- **建议**：明确以哪一侧为准。若要求严格等价，需在 askdata 侧对空 resourceId 也写关联行（并把 `resourceId` 序列化成 `"None"`）；若认可 askdata 的做法，则记录为有意偏离。

---

### F-20 【低】白名单条目数注释漂移（「33 条」实为 35 条）

- `common/web/AuthWhitelist.java:13` — 「对齐 SQLBot `common/utils/whitelist.py` 的 **33 条**规则」
- `common/web/AuthWhitelist.java:34` — 「SQLBot `common/utils/whitelist.py` 的 wlist（**33 条**，顺序与内容逐字一致）」
- 实际两侧均为 **35 条**（`whitelist.py:7-41`、`AuthWhitelist.java:39-73`），已用脚本比对确认 35/35 内容与顺序完全一致。
- **影响**：无运行时影响；但注释误导后续维护者（也解释了本轮审计中「33 条」这一说法的来源）。
- **建议**：把两处注释改为 35 条。

---

## 附：askdata 侧优于 SQLBot 的实现（更强）

| # | 项目 | SQLBot | askdata | 说明 |
|---|---|---|---|---|
| S-01 | 审计资源名解析失败安全 | `common/audit/schemas/logger_decorator.py:484-486` — 失败时 `print` 并返回 `None`（静默，无日志级别） | `system/audit/AuditResourceNameResolver.java:62-64` — `catch` 后 `return null`；`system/audit/AuditLogAspect.java:74-77` — 审计异常 `log.warn` 且**绝不阻断业务** | 失败路径有分级日志，排障友好 |
| S-02 | JWT 签名比较 | PyJWT 的 `hmac.compare_digest` 之外的常规实现（`common/core/security.py:20` 走 PyJWT 默认路径） | `system/auth/JwtCodec.java:83` → `MessageDigestHolder.constantTimeEquals`（`:143-152`，逐字节 XOR 累加，**定长比较**） | 显式防时序侧信道 |
| S-03 | 白名单路径取值 | `apps/system/middleware/auth.py:34-37` 用 `request.scope.get("path")`（依赖 ASGI scope） | `common/web/AuthWhitelist.java:126-136` 用 `request.getRequestURI()`（Servlet 规范接口） | askdata 不依赖框架内部字段，更稳 |
| S-04 | 非成员的工作空间权重判定 | `apps/system/crud/user.py:38-40` — 无绑定行时 `weight = -1`；`apps/system/schemas/permission.py:89` 判定 `weight == 0` → **`-1 != 0`，非成员被误判为 ws_admin** | `system/repository/UserWsRepository.java:28-30` — `weightOf` 对缺失行返回 **0**；`system/service/CurrentUserService.java:87` 判定 `== 0` → 正确拒绝 | **SQLBot 侧存在越权风险，askdata 侧已修正**（见「逐项对照表」第 5 项） |
| S-05 | 异常日志 | `common/core/response_middleware.py:118-121`、`:484-486` — 审计落库失败仅 `print` | `common/web/ApiExceptionHandler.java:33-55` — 业务拒绝 `log.info`、未处理异常 `log.error` 并带堆栈；`system/audit/AuditRecorder.java:112` — 审计失败 `log.warn` | 可观测性更强 |

---

## 附：本轮审计的覆盖范围与边界

**已逐行读完并核对的文件（节选）**

- SQLBot：`apps/system/middleware/auth.py`、`apps/system/api/{login,user,workspace,parameter,apikey}.py`、`apps/system/crud/{user,user_excel}.py`、`apps/system/schemas/{permission,system_schema}.py`、`common/utils/{whitelist,locale,snowflake}.py`、`common/core/{config,security,host_validation,response_middleware,sqlbot_cache}.py`、`common/audit/schemas/{logger_decorator,log_utils}.py`、`common/audit/models/log_model.py`、`main.py`
- askdata：`common/web/{AuthChannelFilter,AuthWhitelist,ApiKeyTokenAuthenticator,EmbeddedTokenAuthenticator,HostValidationFilter,ResponseEnvelopeAdvice,ApiExceptionHandler,JsonLongConfig,AuthContext}.java`、`common/audit/*`、`common/i18n/*`、`common/util/{SnowflakeIdGenerator,IdGenerator}.java`、`config/SaTokenConfigure.java`、`system/audit/*`、`system/auth/{CryptoUtil,JwtCodec}.java`、`system/controller/*`、`system/service/*`、`system/repository/UserWsRepository.java`、`resources/{application.yaml,schema.sql}`

**本轮未纳入逐行比对的 askdata 模块**（本报告对其不作判定）：`system/sso/*`、`system/authentication/*`、`system/platform/*`、`datasource/support/DatasourceCrypto*`、前端与 `web/` 静态资源。

**程序化验证过的结论**

- 白名单 35/35 条内容与顺序一致；44 条代表性路径的双实现差分 → 2 条差异。
- i18n：SQLBot 5 语言 × 177 键 vs askdata 6 个 bundle × 281 键 → 缺失 0、占位符集合差异 0、askdata 5 语言间占位符差异 0。
- 审计点计数：SQLBot `@system_log` 54（含定义处 1）、askdata `@AuditLog` 29（`system/` 下 0）。
- 缓存层检索：askdata `Caffeine`/`@Cacheable`/`@CacheEvict`/`@CachePut`/`EnableCaching`/`RedisTemplate` 命中 0。
