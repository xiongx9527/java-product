# 目标

在真实环境（真实数据库、真实 Ollama 大模型、真实 TEI embedding、真实前后端与已提交前端产物）对 `askdata/askdata-server` 做完整集成验证，并把验证中发现的问题全部修复到可回归的状态。

具体含义：

- **真实环境、不 mock**：使用本机已就绪的 MySQL 5.7 / PostgreSQL / SQLServer / Oracle / ClickHouse / Elasticsearch、Ollama（`gpt-oss:20b-cloud`）、TEI（`bge-m3`，1024 维）与仓库内 `web/dist`；只有无法稳定复现的外部依赖才允许用桩，且必须在验证记录中注明。
- **验证范围覆盖全部剩余风险项**：MySQL / PostgreSQL 元数据库、SQLServer / Oracle / ClickHouse / ES 问数、权限与多用户、定时任务 cron、大数据量与慢 SQL / 导出、前端 `npm build`。
- **发现即修复**：验证中暴露的问题（含此前已观察但未修的性能、图表、标题、推荐问题、401、耗时展示、启动告警、E2E 断言等）都要在本 change 内修复并给出证据，不允许只记录不处理。
- **可回归**：修复要有对应单测或 E2E 用例，最终 `mvn test`、Playwright、真实环境 API 脚本全部通过。

本 change 的候选实现已包含本轮真实环境验证中先期完成的修复（未提交工作区改动），Build 阶段在其基础上继续补齐剩余验证与修复。

# 范围

## 目录结构

### 新建

- `askdata/askdata-server/e2e/tests/chat-real.spec.ts` — 真实问数闭环 Playwright 用例：选真实数据源 → 提问 → 校验 SSE 帧（sql / sql-data / chart / imageUrl / finish）→ 校验落库数据与图表渲染。
- `askdata/askdata-server/e2e/run-real-e2e.mjs` — 从本轮临时脚本固化的 31 步真实环境 API 集成脚本（支持 `BASE` 切换端口/元数据库实例），覆盖登录、数据源、选表、字段、问数、会话、导出、定时任务、权限等链路。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/datasource/repository/FieldRepositoryCrossDatasourceCleanupTest.java` — 跨数据源字段清理回归测试（防止再次误删其它数据源字段）。
- `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/chat/service/ChatDurationAndTitleTest.java` — 耗时（秒，向上取整）与会话标题只由首问生成的行为测试。
- `askdata/askdata-server/src/test/java/com/xiongxiao/...`（按实际包补充）— 针对图表多度量、SQL 语义校验、401 清除策略、推荐问题失败可见性的新增单测，遵循既有测试包结构。
- `askdata/askdata-server/e2e/package-lock.json` — 固化 e2e 依赖版本。

### 修改

- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/repository/ChatRepository.java` — `finishRecord` 写 `duration`（秒）并保证 >0 时最小 1；标题写入策略配合首问判定。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatQuestionService.java` — 推荐问题与主链路解耦/并行、推荐问题失败返回可见错误、SQL 语义错误回喂修复、标题生成去重、推荐问题空数组兜底修正。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/ChatChartBuilder.java` — 多度量图表产出 `multi-quota`/双 Y 轴配置，避免双度量同轴不可见。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/chat/service/SchemaContextBuilder.java` — 结构化 JSON 提示词骨架修正（已有候选实现）；按验证结果继续修正 SQL 语义约束提示。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/engine/DatasourceType.java` — PostgreSQL 系元数据别名加引号（已有候选实现），按多方言验证结果修正 SQLServer / Oracle / ClickHouse / ES 方言差异。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/repository/FieldRepository.java` — 字段清理按数据源收窄（已有候选实现）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/datasource/service/DatasourceService.java` — 字段清理调用传递数据源 id（已有候选实现）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/scheduled/ScheduledTaskRepository.java` — `scheduled_task_run.sql` 在不同元数据库（MySQL 保留字）下的标识符兼容；定时任务验证发现的问题修复。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/AuthWhitelist.java` — 公共元数据路径补充（已有候选实现：`/system/license/version`）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/ApiExceptionHandler.java` / `AuthChannelFilter.java` — 401 响应语义区分（登录态失效 vs 接口级未授权），配合前端拦截器。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/auth/CryptoUtil.java` — 无参构造不再误报「未配置密钥」（已有候选实现）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/storage/FileStorageConfig.java` — 消除 FileRecorder 误告警（显式注册落库实现并处理自动装配默认实现的告警噪声）。
- `askdata/askdata-server/src/main/resources/db/migration/mysql/V1__baseline.sql` — MySQL 5.7 兼容：`TIMESTAMP`→`DATETIME`（已有候选实现）、保留字列名 `` `sql` `` 加反引号。
- `askdata/askdata-server/src/main/resources/application.yaml` — 未知路径 404 日志级别、SQL 超时/大数据量相关配置（如验证需要）。
- `askdata/askdata-server/web/src/utils/request.ts` — 401 拦截器仅在登录态确实失效时清 token；`web/dist` 同步重新构建产物。
- `askdata/askdata-server/web/src/views/chat/...`（按定位结果）— 耗时 <1s 展示、推荐问题失败提示等前端展示修复（最小改动，保持 SQLBot 前端结构）。
- `askdata/askdata-server/e2e/tests/scheduled.spec.ts` — 恢复严格断言：区分「未配置模型」与「模型输出不可用」，不再用宽泛正则吞掉真实错误。
- `askdata/askdata-server/e2e/README.md` — 补充真实环境用例前置、环境变量、CI 跳过策略。
- `askdata/askdata-server/src/test/java/.../ChatKnowledgeChainTest.java`、`ChatPipelineTest.java`、`ChatRagTest.java`、`ChatServiceSmokeTest.java`、`McpAssistantFlowTest.java`、`AuthWhitelistTest.java`、`AuthWhitelistParityTest.java`、`ChatOwnershipParityTest.java`、`PostgresMetadbMigrationTest.java`、`DatasourceTypeStrategyTest.java` — 与产品契约对齐的测试桩/测试基建修正（已有候选实现）。
- `askdata/askdata-server/pom.xml` — 仅在验证发现依赖缺失时补充测试依赖；不升级 fw-plus 主版本。

### 删除

无。

### 明确不建

- 不新增前端页面、路由或交互样式 — 前端是 SQLBot 前端的逐字拷贝，只做必要的最小展示/行为修复。
- 不引入第四种元数据库形态或新的迁移框架 — 保持 H2 / MySQL / PostgreSQL 三套 Flyway baseline。
- 不替换 fw-plus、x-file-storage、Sa-Token 等基础依赖 — 只做配置/适配层修复。
- 不搭建独立的自动化测试数据生成平台 — 大数据量表、慢 SQL 场景用一次性 SQL 脚本准备并在验证记录中给出脚本。
- 不做 SQLBot 商业版（xpack）能力复刻 — 本 change 只验证并修复 askdata 现有能力。

# 非目标

- 不追求在真实公网/跨机器部署环境验证；验证环境固定为本机已就绪服务。
- 不改变 SQLBot 对齐的协议契约（SSE 帧名、响应信封、字段名），除非验证证明当前实现与已确认契约不一致。
- 不重写问数主链路架构；性能修复限于消除不必要的串行 LLM 调用与重复查询。
- 不做全量安全渗透测试、压测或容量规划；大数据量验证以功能正确性和超时行为为边界。
- 不把真实模型 E2E 强制纳入 CI 必跑（无模型环境应可跳过并明确原因）。
- 不修改 `docs/comet/` 之外的历史归档产物。

# 验收示例

- A1 MySQL 5.7 元数据库可启动并跑通全链路：清空 `askdata_meta` 后以 `--spring.profiles.active=mysql` 启动，Flyway 三套 baseline 的 MySQL 版全部成功；登录、创建 MySQL 数据源、选表、字段同步、提问、`GET /chat/record/{id}/data` 取到数据均成功；重启后数据仍在。
- A2 PostgreSQL 元数据库可启动并跑通全链路：新建空库后以 `--spring.profiles.active=postgresql` 启动，Flyway 成功；同一套 31 步 API 脚本全部通过。
- A3 多方言问数：SQLServer、Oracle、ClickHouse、Elasticsearch 四个数据源分别完成 `getTables → chooseTables → fieldList → /chat/question`，问数返回非空 `data`（ES 走 DSL/查询路径）；方言/元数据错误不得以 500 未捕获异常形式出现，必须是可读错误或成功结果。
- A4 权限与多用户：新增普通用户 B 后，B 看不到用户 A 的会话与数据源；工作空间隔离生效；行权限过滤条件进入执行 SQL、列权限隐藏对应列；嵌入助手 token 仅对 `type=4` 且非管理员生效；API Key 合法通过、非法/停用返回 401；越权访问返回 403 而非数据泄露。
- A5 定时任务 cron：创建每分钟执行的 cron 任务，等待至多 2 分钟自动触发一次，运行历史状态为 `SUCCESS` 且导出文件存在、行数正确；停用后不再产生新运行记录；失败任务可通过重试入口再次执行。
- A6 大数据量与慢 SQL：对不少于 10 万行的表执行问数/分页/导出，返回行数与限额、图表配置一致；构造超过 SQL 超时阈值的查询，在阈值 + 2 秒内返回明确超时错误（不挂起、不 500）；Excel 导出完成且行列数与图表配置一致。
- A7 前端构建：在 `askdata/askdata-server/web` 执行 `npm run build`（`vue-tsc -b` + `vite build`）成功；用新构建的 `dist` 启动页面，登录、问数、数据源、定时任务四个页面无控制台未捕获错误。
- A8 问数时延：推荐问题等非关键 LLM 调用不再串行阻塞主链路；同一模型、同一数据集连续 5 次问数，`finish` 帧时延中位数较本轮基线（改前实测记录）下降 ≥ 20%，且推荐问题、图表、落库数据仍正常；验证记录给出改前/改后逐次时延。
- A9 图表多度量：结果含 1 个维度 + 2 个数值度量时，图表配置包含 `axis.multi-quota`（或双 Y 轴等价配置），两个度量系列均可渲染可见；单元测试与真实问数截图/渲染断言至少各一份。
- A10 SQL 语义校验与修复：对 10 组构造的语义错误模型输出（引用不存在的列/表、错误聚合、维度缺失），校验层能拦截并触发一次修复轮；修复仍失败时返回明确错误且不执行 SQL；单测覆盖这 10 组输入。
- A11 会话标题稳定：同一会话连续提问 3 次，标题只在首问由 `brief` 生成，后续问数不覆盖标题；`brief` 缺失时回退首问截断且不重复追加；数据库 `chat.brief` 前后一致。
- A12 推荐问题失败可见：模型异常/不可用时，推荐问题接口返回错误帧与可读信息，前端显示失败提示而非静默 `[]`；模型恢复后重试成功并正常渲染推荐问题。
- A13 401 处理：只有业务响应明确表示登录态失效时才清除 `user.token` 并跳登录；嵌入助手/API Key 通道的 401 不影响主账号登录态；白名单端点（含 `/system/license/version`）的响应不会触发清 token 或整页刷新。
- A14 耗时展示：一次实际耗时 < 1 秒的问数，`chat_record.duration` 落库为 1 秒（向上取整），前端显示 `1 s` 或 `<1 s`，不再出现 `0 s`。
- A15 启动与运行噪声：正常启动与 5 分钟空转期间，日志不再出现「没有找到 FileRecorder 的实现类」WARN；`/v1/models`、`/favicon.ico` 等未知/静态路径不再以 WARN 刷屏（可访问静态资源或降为 DEBUG）。
- A16 E2E 可回归：`chat-real.spec.ts` 纳入 Playwright 并可通过；`scheduled.spec.ts` 恢复严格断言（明确区分未配置模型与模型输出不可用）；`e2e/README.md` 说明真实模型依赖与 CI 跳过方式；`run-real-e2e.mjs` 在当前真实环境下全部通过。
- A17 回归全绿：`mvn -pl askdata/askdata-server -am test -Dmaven.test.skip=false` 全部通过（0 failure）；Playwright 现有用例与新增用例全部通过；默认 H2 启动链路不回退。
- A18 MinIO 平台：以本地真实 MinIO 实例配置 `default-platform=minio` 启动，上传文件成功、预签名 URL 可下载且内容一致、删除后对象不存在、元数据可按 key 反查；切回 local 后既有上传/下载行为不变。
- A19 阿里云 OSS 平台：配置 `aliyun-oss` 平台（endpoint/bucket/AK/SK 来自环境变量）后，`getFileStorage("aliyun-oss")` 返回 OSS 实现；缺少必填项时启动告警并回退 local，不影响启动；无真实账号时以装配单测为证据并记录限制。
- A20 腾讯云 COS 平台：配置 `tencent-cos` 平台（region/bucket/secretId/secretKey 来自环境变量）后，`getFileStorage("tencent-cos")` 返回 COS 实现；缺少必填项时启动告警并回退 local；无真实账号时以装配单测为证据并记录限制。
- A21 平台切换不回归：`default-platform=local`（默认）下全量 `mvn test` 仍 0 失败，嵌入助手文件、审计导出、Excel 导出链路不受影响。
- A22 通用 S3 平台：配置 `default-platform=amazon-s3`（endpoint/bucket/AK/SK/region 来自环境变量）后，上传/预签名下载/替换删除在 S3 兼容存储（本地 MinIO 作为 S3 网关实测）上全部通过；缺 endpoint 时回退 local 并告警。

# 约束与不变量

- 真实环境优先：不得用 mock 替换真实数据库、Ollama、TEI；外部服务不可用时按阻塞处理并记录，不静默降级为 mock。
- 数据库 DDL 只做与既有 baseline 一致的修正；不新增 Flyway 版本，不在 baseline 之外做手工补丁。
- 协议契约不变量：SSE 帧类型、`/chat/record/{id}/data`、`{code,data,msg}` 信封、SQLBot 对齐字段名保持不变。
- 前端改动最小化：优先改 `web/src` 后重新构建 `web/dist`；不改前端整体结构、样式体系与路由。
- 性能修复不得降低结果正确性：任何异步化/并行化都必须保证 `finish` 前完成落库，推荐问题/图表失败不阻断主结果。
- SQL 安全不降级：语义校验只增加拦截，不放松既有只读/危险语句限制。
- 真实模型输出不可完全确定：涉及模型的断言必须基于结构/契约（字段存在、可解析、数据非空）而非固定文本；模型语义类缺陷用构造输入的单测覆盖。
- 不提交密钥、token、证书；真实连接信息仅用于本地验证脚本的环境变量。

# 决策

- D1 元数据库验证采用「H2 默认 + MySQL 5.7 + PostgreSQL」三形态；MySQL 5.7 的 `TIMESTAMP` 默认值与保留字 `sql` 均按 MySQL 方言修正，不改 SQLBot 对齐的表名/列名。
- D2 测试桩与本轮真实模型契约不一致时，视为测试代码缺陷，修桩不改产品契约（已在候选实现中执行）。
- D3 会话标题的权威来源是 SQLBot 结构化 SQL JSON 的 `brief`；仅首问生成，后续问数不覆盖（对齐前序 change A12 的标题语义）。
- D4 耗时 `duration` 继续沿用秒单位（SQLBot 契约），>0 时向上取整，避免 `<1s` 显示 `0 s`；不改为毫秒以免破坏前端/导出契约。
- D5 推荐问题失败采用「显式错误帧 + 前端可读提示」，不再静默返回空数组；这是对既有「空数组兜底」注释语义的修正。
- D6 真实模型 E2E 用例默认不在无模型环境强制运行；通过环境变量/配置跳过并在 README 说明，CI 不因缺少模型失败。
- D7 本 change 为单一 Native Change，不拆分 Supervisor：验证项共享同一环境、同一批修复文件，拆分协调成本高于收益。

# Open questions

无。

# 验证预期

- 每个验收项都要有可复查证据：命令、脚本输出摘要、日志片段、数据库查询结果、截图或单测报告；仅「已运行」不算通过。
- 真实环境验证脚本与日志保存在仓库外临时目录或 `target/` 下，不提交大体积日志；验证记录只摘录关键证据。
- 涉及真实模型的时延/成功率，至少记录 5 次样本并给出中位数与失败样本原因。
- 修复类验收项必须同时给出「改前现象」与「改后验证」；无法复现的现象要记录复现尝试与最终结论。

# 追加需求（2026-10-28）：文件存储多平台

用户追加要求：「文件存储要支持 minio、oss 以及腾讯的」。

## 目标

在保持 local 平台默认可用的前提下，让 askdata 的文件存储抽象支持 **MinIO**、**阿里云 OSS**、
**腾讯云 COS** 三类对象存储，通过配置切换平台而不改代码；上传、读取、删除、预签名 URL、
按 key 反查元数据在四类平台行为一致。

## 范围

### 修改

- `askdata/askdata-server/pom.xml` — 增加对象存储 SDK 依赖（`io.minio:minio`、`com.aliyun.oss:aliyun-sdk-oss`、
  `com.qcloud:cos_api`），由 x-file-storage 2.2.1 的平台实现按 classpath 激活；依赖缺失时不得影响启动。
- `askdata/askdata-server/src/main/resources/application.yaml` — `dromara.x-file-storage` 增加
  `minio` / `aliyun-oss` / `tencent-cos` 平台配置段，凭据全部走环境变量占位，`default-platform`
  可切换；缺省仍为 `local`。
- `askdata/askdata-server/src/main/java/.../common/storage/FileStorageConfig.java`（或新增
  `ObjectStorageProperties`）— 平台启停与必填项校验：启用平台但缺少 endpoint/bucket/凭据时给出
  可读启动告警并回退 `local`，不抛异常导致启动失败。
- `askdata/askdata-server/src/main/java/.../common/storage/AskdataFileStorage.java` — 统一按
  `default-platform` 选择平台，暴露当前平台名与平台能力（是否支持预签名）供上层与健康检查使用。
- `askdata/askdata-server/README.md` — 补充四类平台配置项、环境变量、切换方式与最小权限说明。

### 新建

- `src/test/java/.../common/storage/ObjectStoragePlatformTest.java` — 平台配置绑定与降级单测
  （local/minio/aliyun-oss/tencent-cos；缺配置回退 local；非法平台名报可读错误）。
- `e2e/probe-storage.mjs` — 真实环境对象存储探针：上传 → 预签名 URL 下载 → 删除 → 元数据反查，
  按 `STORAGE_PLATFORM` 选择平台，未配置该平台时跳过并说明原因。

### 明确不建

- 不做前端存储配置页面（沿用部署配置）。
- 不做多平台同时双写/迁移工具。
- 不引入新的存储抽象层，继续使用 x-file-storage。

## 约束

- 凭据不得硬编码：endpoint/bucket/AK/SK/region 一律来自环境变量或外部配置。
- 未配置平台 SDK 凭据时不得导致启动失败或问数主链路不可用。
- 默认平台保持 `local`，离线/无对象存储环境行为不变。
- 不改变既有 `FileStorageController` 的 URL 与响应契约。

## 决策

- D8 平台实现复用 x-file-storage 2.2.1 内置平台（`minio`/`aliyun-oss`/`tencent-cos`），只补 SDK 依赖与配置，
  不自研存储客户端。
- D9 真实环境验证以本地 MinIO 为准（可离线启动）；OSS/COS 无可用账号时以「装配 + 配置降级」单测为证据，
  并在验证记录中标注为环境限制。
