# AskData 真实环境集成验证与缺陷修复——完整目标规格

本规格描述本 change 完成后 `askdata/askdata-server` 在真实环境下应具备的行为与可验证边界。行为按「元数据库可移植性、多方言问数、权限、调度、性能与数据量、问数体验、前端与 E2E」分组。

## 元数据库可移植性

### Requirement: MySQL 元数据库可初始化

系统应支持以 MySQL 5.7 作为应用元数据库启动，并在空库上自动完成全部 Flyway 迁移。DDL 不得使用 MySQL 5.7 不支持的默认值或多 `TIMESTAMP` 列组合，也不得把 MySQL 保留字直接作为未引用列名。

#### Scenario: 空库启动并完成全链路

- **WHEN** `askdata_meta` 为空库，应用以 `--spring.profiles.active=mysql` 启动
- **THEN** Flyway 全部迁移成功，应用健康检查通过，登录、创建 MySQL 数据源、选表、字段同步、提问、读取记录数据均成功；重启后记录仍可读取

验收：A1

### Requirement: PostgreSQL 元数据库可初始化

系统应支持以 PostgreSQL 作为应用元数据库启动，并在空库上自动完成全部 Flyway 迁移，且与 H2/MySQL 保持相同的 API 行为。

#### Scenario: 空库启动并跑通同一脚本

- **WHEN** 新建空库并以 `--spring.profiles.active=postgresql` 启动
- **THEN** 同一套真实环境 API 集成脚本全部通过；会话、记录、定时任务、导出数据均可落库与回读

验收：A2

## 多方言数据源

### Requirement: SQLServer / Oracle / ClickHouse / Elasticsearch 可完成问数闭环

四种非 MySQL/PostgreSQL 数据源应支持 `getTables → chooseTables → fieldList → 提问` 的完整链路，方言元数据查询与 SQL 执行不得因方言差异返回未捕获 500。

#### Scenario: 四种数据源逐一问数

- **WHEN** 分别对 SQLServer、Oracle、ClickHouse、Elasticsearch 数据源执行选表、字段同步并提交一个业务问题
- **THEN** 每次返回可读结果：成功时 `data.data` 非空且字段与表结构一致；失败时返回明确错误信息与错误类型，不出现 500 堆栈直出

验收：A3

## 权限与多用户

### Requirement: 多用户与多工作空间隔离

普通用户只能访问自己在当前工作空间内的会话与数据源；管理员不得通过同一工作空间隐式读取他人私有会话。

#### Scenario: 用户 B 访问用户 A 的资源

- **WHEN** 用户 B 请求用户 A 的会话或数据源
- **THEN** 返回 403 或不可见（列表不出现），不泄露会话内容、SQL 或数据

验收：A4

### Requirement: 行列权限运行时生效

行权限过滤条件应进入实际执行 SQL，列权限应对结果列做隐藏；嵌入助手与 API Key 通道按各自规则鉴权。

#### Scenario: 配置行/列权限后问数

- **WHEN** 为普通用户配置行过滤与隐藏列后发起问数
- **THEN** 执行 SQL 含行过滤条件，返回结果不含隐藏列；嵌入助手 token 仅 `type=4` 且非管理员可用；API Key 非法/停用返回 401

验收：A4

## 定时任务

### Requirement: cron 自动触发与运行历史

启用且配置 cron 的定时任务应在到期后自动执行，写入运行历史与导出文件；停用后不再触发；失败任务支持重试。

#### Scenario: 每分钟 cron 自动执行

- **WHEN** 创建每分钟执行的定时任务并等待至多 2 分钟
- **THEN** 至少产生一条自动触发的运行记录，状态为 `SUCCESS`，导出文件存在且行数与查询结果一致；停用后 2 分钟内不再新增运行记录；失败运行可通过重试入口再次执行

验收：A5

## 性能与大数据量

### Requirement: 大数据量查询与超时行为

系统应对大数据量结果执行限额/分页控制，对超过阈值的慢查询在有限时间内返回明确超时错误，导出应完成且与图表配置一致。

#### Scenario: 10 万行表查询、慢 SQL 与导出

- **WHEN** 对不少于 10 万行的表发起问数、分页与 Excel 导出，并构造一条超过 SQL 超时阈值的查询
- **THEN** 正常查询返回受限额控制的结果且分页正确；慢查询在阈值 + 2 秒内返回可读超时错误且不挂起；导出文件行列数与图表配置一致

验收：A6

### Requirement: 问数时延优化

问数主链路不得让非关键 LLM 调用串行阻塞结果产出；推荐问题等非关键内容应在不延迟主结果的前提下完成。

#### Scenario: 连续 5 次问数时延对比

- **WHEN** 在同一模型与数据集上连续提问 5 次
- **THEN** `finish` 帧时延中位数相对改前基线下降 ≥ 20%；推荐问题、图表、落库数据仍正常产出，且验证记录包含改前/改后逐次时延

验收：A8

## 问数体验与正确性

### Requirement: 多度量图表可读

当结果包含多个数值度量时，图表配置应能表达多度量（多轴/多配额），不得因同轴叠加导致系列不可见。

#### Scenario: 一个维度 + 两个度量

- **WHEN** 问数结果包含 1 个维度列与 2 个数值列
- **THEN** 图表配置含 `axis.multi-quota` 或双 Y 轴等价结构，两个度量系列均可渲染；单测与真实渲染至少各有一份证据

验收：A9

### Requirement: SQL 语义错误可拦截并修复

系统应在执行前校验生成 SQL 的表/列/聚合语义；发现语义错误时触发一次修复轮，修复失败则返回明确错误且不执行 SQL。

#### Scenario: 构造 10 组语义错误模型输出

- **WHEN** 模型输出引用不存在列/表、错误聚合或缺失维度
- **THEN** 校验层拦截并触发一次修复；修复仍失败时返回可读错误，不执行 SQL；10 组输入均有单测覆盖

验收：A10

### Requirement: 会话标题稳定且不重复

会话标题来自首问生成的结构化 `brief`，后续问数不得覆盖；`brief` 缺失时使用首问文本截断作为回退。

#### Scenario: 同一会话连续三问

- **WHEN** 同一会话连续提问 3 次，且模型每次都返回 `brief`
- **THEN** `chat.brief` 保持首问生成值不变，不追加、不重复、不被后续问数改写；`brief` 缺失时回退首问截断

验收：A11

### Requirement: 推荐问题失败可见

推荐问题生成失败时应向前端返回可读错误，而不是静默返回空数组；重试成功后正常渲染。

#### Scenario: 模型不可用时请求推荐问题

- **WHEN** 对话模型不可用或返回异常，调用推荐问题接口
- **THEN** 响应包含错误帧与可读信息，前端展示失败提示；模型恢复后重试返回非空推荐问题

验收：A12

### Requirement: 耗时展示准确

`chat_record.duration` 以秒记录实际耗时，`> 0` 秒时最小为 1，前端不得显示 `0 s`。

#### Scenario: 亚秒级问数

- **WHEN** 一次问数实际耗时小于 1 秒
- **THEN** `duration` 落库为 1，前端显示 `1 s` 或 `<1 s`

验收：A14

## 鉴权、日志与前端

### Requirement: 401 清理策略精确

前端仅在业务响应明确表示登录态失效时清除 token；嵌入助手/API Key 通道的 401 与白名单端点响应不得影响主账号登录态。

#### Scenario: 不同来源的 401

- **WHEN** 分别触发登录态失效、嵌入 token 失效、API Key 失效与白名单端点响应
- **THEN** 只有登录态确实失效时清除 `user.token` 并跳转登录；其他场景保留登录态并就地提示

验收：A13

### Requirement: 启动与运行日志无噪

正常启动与空转期间不出现误导性 FileRecorder 告警，未知/静态路径 404 不以 WARN 刷屏。

#### Scenario: 启动并空转 5 分钟

- **WHEN** 应用正常启动并空转 5 分钟，期间有 `/v1/models`、`/favicon.ico` 探测
- **THEN** 日志无「没有找到 FileRecorder」，上述路径不再产生 WARN 刷屏；静态资源可访问或降级为 DEBUG

验收：A15

### Requirement: 前端可构建

前端源码应能通过 `npm run build`（类型检查 + 打包），构建产物可正常加载关键页面。

#### Scenario: 构建前端

- **WHEN** 在 `web` 目录执行 `npm run build`，并用新构建的 `dist` 启动页面
- **THEN** 构建成功，登录、问数、数据源、定时任务页面均无控制台未捕获错误

验收：A7

### Requirement: E2E 可回归

真实问数与定时任务 Playwright 用例可回归，真实模型依赖有明确说明与跳过策略。

#### Scenario: 运行真实环境 E2E

- **WHEN** 运行 `chat-real.spec.ts`、严格断言的 `scheduled.spec.ts` 与 `run-real-e2e.mjs`
- **THEN** 当前真实环境下全部通过；README 说明真实模型前置与 CI 跳过方式

验收：A16

### Requirement: 既有能力不回退

本 change 的全部修复不得破坏既有测试与默认 H2 启动链路。

#### Scenario: 全量回归

- **WHEN** 运行 `mvn -pl askdata/askdata-server -am test -Dmaven.test.skip=false` 与全部 Playwright 用例
- **THEN** 测试 0 failure，默认 H2 启动可用，问数、MCP、定时任务、权限等既有链路行为不变

验收：A17

### Requirement: 文件存储支持对象存储平台

文件存储抽象在保留 local 默认平台的同时，支持 MinIO、阿里云 OSS、腾讯云 COS 三类对象存储，
通过 `dromara.x-file-storage.default-platform` 切换平台，业务侧（上传/导出/图表 PNG）无需改动。

#### Scenario: MinIO 平台读写

- **WHEN** 以 `default-platform=minio` 启动并配置本地 MinIO 实例的 endpoint/bucket/凭据
- **THEN** 上传成功、预签名 URL 可下载且内容一致、删除后对象不存在、元数据可按 key 反查

验收：A18

#### Scenario: OSS 平台装配与配置降级

- **WHEN** 配置 `aliyun-oss` 平台（endpoint/bucket/AK/SK 来自环境变量）
- **THEN** `getFileStorage("aliyun-oss")` 返回 OSS 实现；缺少必填项时启动告警并回退 local，不影响启动

验收：A19

#### Scenario: COS 平台装配与配置降级

- **WHEN** 配置 `tencent-cos` 平台（region/bucket/secretId/secretKey 来自环境变量）
- **THEN** `getFileStorage("tencent-cos")` 返回 COS 实现；缺少必填项时启动告警并回退 local，不影响启动

验收：A20

#### Scenario: 通用 S3 平台读写

- **WHEN** 以 `default-platform=amazon-s3` 启动，endpoint/bucket/AK/SK/region 来自环境变量（可指向任意 S3 兼容存储）
- **THEN** 上传成功、读回内容一致、替换后旧对象被删除；缺 endpoint 时启动告警并回退 local

验收：A22

#### Scenario: 默认 local 不回归

- **WHEN** 未设置 `FILE_STORAGE_PLATFORM`（默认 local）运行全量测试
- **THEN** 全量 `mvn test` 仍 0 失败，嵌入助手文件、审计导出、Excel 导出链路行为不变

验收：A21

