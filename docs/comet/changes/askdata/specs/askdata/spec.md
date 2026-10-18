# askdata 完整目标规格

本文描述归档后 capability 的完整行为。协议、字段名与行为与 SQLBot 开源版
（/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot）逐字兼容；仅 xpack 依赖面按 brief D4 重写。

## 1. 工程与构建

### 场景 1：Maven 聚合构建

在仓库根执行 Maven 构建，三级 POM 链（根聚合 → askdata 聚合 → askdata-server）解析成功，
fw-plus-boot/ai 两个 BOM 的依赖管理生效，askdata-server 产出可运行 Spring Boot jar。
构建不需要真实 LLM/数据库网络。

验收：A1

### 场景 2：前端拷贝与构建托管

`askdata/askdata-server/web/` 为 SQLBot frontend 的拷贝（前后端同模块同进程，dist 产物经
maven-resources-plugin 拷入静态资源），`npm install && npm run build` 成功；
xpack 调用点（LicenseGenerator、loadXpackStatic、generateRouters、X-SQLBOT-KEY）已按
brief D1 补丁并集中标注。开发模式 Vite dev server 可代理 `/api/v1` 到本地后端。

验收：A2

## 2. 功能规格

### 2.1 系统底座（登录/用户/工作空间/模型/参数/变量/API Key）

- `/login/access-token`：取 x-www-form-urlencoded 的 username/password（前端经 D1 AES 混淆），
  解密后 MD5 匹配 sys_user.password，经 Sa-Token（D10：`StpUtil.login`）签发 token（X-SQLBOT-TOKEN: Bearer），
  payload 含用户标识；失败返回 SQLBot 错误结构。
- `/login/change-pwd`、`/login/logout`。
- `/user/*`：info（含 uid/account/语言/isAdmin）、pager、CRUD、pwd 重置/修改、status、language、ws 切换、template/batchImport/errorRecord、defaultPwd。
- `/system/workspace` + `/system/workspace/uws`：工作空间 CRUD 与成员绑定/解绑/分页/选项。
- `/system/aimodel`：CRUD、`{id}` 查询、`/default/{id}` 设默认、`/status` 连通检查（POST，流式帧），
  api_key/api_domain 前端 AES 混淆→服务端 SECRET_KEY AES 落库、返回前端时再加密；
  `/ws_mapping`（模型×工作空间映射）、`/list/by_ws`。
- `/system/parameter`：login/chat 读取与写入。
- `/sys_variable`：save/delete/listAll/listPage。
- `/system/apikey`：CRUD 与状态启停（internal 认证用）。
- 鉴权（D10）：Sa-Token 统一承担——拦截器/注解鉴权，token 从 `X-SQLBOT-TOKEN: Bearer` 读取，复用 fw-plus-auth-satoken（sa-token 1.46.0）；管理员判定移植 `id==1 && account=='admin'`；不自写 JWT/过滤器。
- 种子数据：H2 首次启动建库并写入 admin（SQLBot@123456 MD5=`8f32d1e371702c1b1b7346f4b07a701d`）、默认工作空间、系统参数。

### 场景：登录与鉴权

验收：A3（登录闭环）、A4（aimodel）、A11（用户/工作空间/参数/变量/API Key）

### 2.2 数据源模块

与 SQLBot `/datasource/*`、`/table_relation/*`、`/recommended_problem/*` 端点一一对应：
list/get/check（新配置/按 id）/add/update/delete/getTables/getTablesByConf/getSchemaByConf/
getFields/syncFields/chooseTables/tableList/fieldList/editTable/editField/previewData/
execSql/uploadExcel/parseExcel/importToDb/exportDsSchema/uploadDsSchema/ws。

- 引擎策略：JDBC 封装 + 按 type 的连接串/方言/类型映射/元数据查询/分页 limit 。
- 类型覆盖：excel、mysql、pg（首选集，含真实连通与元数据/执行）；sqlServer、sqlite、ck、
  doris、starrocks（JDBC 通用，需可测连接）；oracle、dm、kingbase、redshift、es、hive（至少
  连接级检查与方言策略，无法保证语义级时行为按 SQLBot 错误约定返回）。
- 数据源连接密码沿用前端 CryptoJS AES（key `SQLBot1234567890`）→ 服务端 SECRET_KEY AES 落库。
- Excel：上传→解析→导入为数据表（对应 importToDb 流程）。
- 内置演示：预置演示数据源及样例业务表（订单/用户/商品类），默认可达。

### 场景：数据源全周期

验收：A5（首选引擎全周期）、A12（类型完备性）

### 2.3 对话核心（协议逐字兼容）

- `POST /chat/question`：body `{question, chat_id}`；流式响应帧 `data:{json}\n\n`，每帧含 `type`：
  id / regenerate_record_id / question / info(阶段说明) / brief(会话摘要+首问) / model(默认模型)
  / datasource(选定数据源+库类型) / error(msg) / sql-result(执行结果) / sql(生成的 SQL) /
  sql-data(格式化结果列+行) / chart-result(图表生成状态) / chart(AntV 图表配置 JSON) /
  analysis / analysis_thinking / predict / predict_content / predict_data /
  recommended_question / finish(结束帧)——事件与字段名按
  `views/chat/answer/ChartAnswer.vue`、`BaseAnswer.vue`、`PredictAnswer.vue`、
  `AnalysisAnswer.vue`、`RecommendQuestion*.vue` 的解析 switch 全集实现。
- 内部链路（由 fw-plus-ai 承担模型接入与工具）：默认模型选定 → 数据源 RAG 选库（训练样例/表结构
  embedding + cosine）→ schema 提示词（选中表 DDL/注释/希料行）→ LLM 生成 SQL → 只读 SELECT
  校验与语义修复重试（错误回喂一轮）→ 执行 → 表格数据 → 图表生成（可选）→ 分析/预测（记录级）。
- 会话：`/chat/list`、`/{id}`、`/{id}/with_data`、`/start`、`/rename`、`/delete/{id}`；
  记录：`/record/{rid}/data|data_live|predict_data|log|usage`、`/record/{rid}/{action_type}`
  (regenerate/favorite 类)；`/assistant/start`（嵌入通道，行为同 start）。
- `/chat/recent_questions/{ds}`、`/chat/recommend_questions/{rid}`（推荐问题生成流式）、
  `/chat/record/{rid}/excel/export/{chat_id}` 导出。
- 多会话历史持久化于 H2（chat/chat_record/chat_log 等表，字段名同 SQLBot）。
- 停止：客户端 abort（AbortController）时后端可中断生成（尽力）。

### 场景：对话主链路 / 图表 / 分析预测

验收：A6（主链路）、A7（图表）、A8（analysis/predict）

### 2.4 增值模块

- SQL 示例训练 `/system/data-training`：分页/增删改/启停/getOne/导出/模板/Excel 导入；
  向量化存储与检索（D5）在问数链路生效。
- 术语库 `/system/terminology` 与 settings/terminology：同上能力集。
- 自定义提示词 `/system/custom_prompt/{type}/page` 等：分页/CRUD/导出；类型区分（问数/其他）。
- 仪表盘 `/dashboard`：list_resource/load_resource/create/update/delete/move_resource/
  create_canvas/update_canvas/check_name。
- 审计 `/system/audit`：page/get_options/export（audit_log 写入于各操作点，移植 LogConfig 约定）。
- 数据权限 `/ds_permission`：list/save/delete（行/列权限随问数执行生效，移植 026/027 DDL 与过滤约定）。
- 嵌入管理 `/system/embedded` + `/system/assistant`：列表/CRUD/secret 重置/picture/ui 等。
- `/system/license`：返回社区版只读信息（validate/version），不校验许可。
- `/settings/* download-fail-info`：按 SQLBot 返回。

### 场景：增值模块对齐

验收：A9、A10、A13

## 3. xpack 替代实现

- 前端补丁点（D1）：登录、aimodel、`ws` 相关 xpack 调用改本地 AES；`loadXpackStatic` 空返回；
  `generateRouters(router)` 空实现；`X-SQLBOT-KEY` 不发送。
- Java 侧：AES-128-CBC/PKCS5 + Base64（与前端同 key，`common/crypto` 内实现与测试）；
  MD5 工具（`md5pwd`）、JWT 签发校验。
- 数据源密码沿用前端原 CryptoJS AES-ECB（key `SQLBot1234567890`），服务端解密后以应用
  SECRET_KEY AES 落库。

## 4. 数据与部署

- schema.sql：全量移植 SQLBot alembic 001-072 中仍在 schema 的表至 H2 兼容 DDL
  （字段名不变；PG 专有类型做映射；种子：admin/默认工作空间/系统参数/演示数据源）。
- 启动：`java -jar askdata-server.jar`，默认 `server.port=8000`，H2 文件库 `./data/askdata`。
- 生产形态可切 MySQL/PostgreSQL（`spring.datasource` 配置；schema.sql 版本相应提供）。
- 前端 dist 托管：`spring.web.resources` 静态映射 + SPA 兜底（非 /api、非静态资源路径回 index.html）。
- JDBC 驱动以扩展目录加载，README 说明；不做 alembic 运行时迁移。

## 5. 实现参考

- 协议/端点唯一事实源：SQLBot Python 后端路由 + 前端 apicaller 解析逻辑（文件路径在 Source coverage 表）。
- fw-plus-ai 用法参考 `fw-plus-sample/fw-plus-ai-samples/fw-plus-ai-chat-sample`（AssistantBuilder/ChatContextLogger 惯例）。
