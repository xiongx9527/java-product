# 目标

让 askdata（问数）在**多实例集群**下正确工作：消除所有「节点本地状态」，把会话、缓存、防抖、单例后台任务、文件读写统一到共享基础设施（元数据库 / Redis / 对象存储），并给出集群模式下的启动自检与部署基线，使任意请求落到任意实例都得到一致结果。

当前代码是「单实例默认可跑」，多副本部署会在四处直接失效：元数据库为本地 H2 文件、应用密钥每进程随机、Sa-Token 会话在进程内、Excel 上传与导入是两个独立请求且文件落本机。此外还有三个未接入对象存储的本地路径（Excel 目录、定时任务导出目录、图表 token 映射）会造成跨节点 404，以及缓存/防抖/单例任务/定时任务租约等一致性问题。

# 范围

## 目录结构

### 新建

- `askdata-server/src/main/java/com/xiongx9527/askdata/cluster/ClusterProperties.java`：集群模式开关与自检策略（enabled / fail-fast / 检查项开关）。
- `askdata-server/src/main/java/com/xiongx9527/askdata/cluster/ClusterReadinessChecker.java`：启动期集群自检（元数据库、密钥、会话、缓存、锁、文件存储、雪花 workerId），输出可读诊断并按策略告警或拒绝启动。
- `askdata-server/src/main/java/com/xiongx9527/askdata/cluster/ClusterReadinessReport.java`：自检结果（检查项 / 状态 / 说明 / 修复建议）。
- `askdata-server/src/main/java/com/xiongx9527/askdata/chart/server/ChartTokenRepository.java`：图表图片 token → 文件引用映射的落库访问层（跨实例共享）。
- `askdata-server/src/main/resources/db/migration/common/V3__chart_image_token.sql`（编号以实际迁移序列为准）：图表 token 映射表。
- `askdata-server/src/main/resources/application-cluster.yaml`：集群 profile 基线（元数据库、Redis 会话/缓存/锁、对象存储、租约参数）。
- `askdata-server/e2e/probe-cluster.mjs`：双实例跨节点探针（登录态、上传→导入、导出、图片、定时任务租约）。
- `askdata-server/e2e/docker-compose.cluster.yml`：MySQL + Redis + MinIO + 两个 askdata 实例的最小集群编排。
- `askdata-server/docs/cluster-deployment.md`：集群部署说明（必备依赖、配置矩阵、粘性会话要求、容量注意）。

### 修改

- `application.yaml`：新增 `askdata.cluster.*`；删除死配置 `askdata.file-storage.local-path`；补充集群相关默认值与注释。
- `datasource/excel/ExcelService.java`：上传落对象存储，`parseExcel` 返回可跨实例的存储引用，`importToDb` 按引用读取（不再依赖本机路径）。
- `datasource/controller/DatasourceController.java`、`settings/SettingsFileController.java`、`terminology/TerminologyService.java`、`training/TrainingService.java`：所有 Excel/模板/错误行/设置文件读写改走 `AskdataFileStorage`。
- `scheduled/ScheduledTaskExecutor.java`、`scheduled/ScheduledTaskProperties.java`：导出文件落对象存储，`scheduled_run.export_path` 存可跨节点解析的引用；保留本地路径兼容读取。
- `scheduled/ScheduledTaskRepository.java`、`ScheduledTaskScheduler.java`：`running` 增加租约（owner + expire_at），轮询时回收过期租约，节点异常退出后任务可继续执行。
- `chart/server/ChartImageService.java`：token 映射改落库；清理任务纳入单实例守卫；本地 JSON 仅作为无库降级。
- `common/lock/*`、`config/CacheConfig.java`、`config/CacheProperties.java`：集群模式下会话/缓存/防抖/锁的默认值与校验。
- `common/startup/EmbeddingBackfillRunner.java`：集群下明确使用共享锁后端，锁不可用时跳过并告警。
- `askdata-server/README.md`、`e2e/README.md`：补充集群运行方式与探针清单。

### 删除

- `application.yaml` 中未被任何代码读取的 `askdata.file-storage.local-path`。

### 明确不建

- 不引入新的分布式协调组件（ZooKeeper / etcd），只用已有的元数据库 + Redis。
- 不重写 MCP SSE 传输层：直接启用框架内置的 Redis 集群注册表，不自行实现跨实例消息总线。
- 不做读写分离、分库分表、灰度路由。

# 非目标

- 不改变现有单实例默认行为：不配置 `askdata.cluster.enabled` 时仍可零外部依赖启动（H2 + 本地文件 + 内存会话）。
- 不改变任何对外 HTTP 接口的路径、请求/响应字段（除 Excel 上传返回的 `filePath` 语义由本地文件名变为存储引用，需保持旧值可解析）。
- 不改动问数主流程（SQL 生成、语义校验、图表渲染）的业务逻辑。
- 不涉及前端改造。

# 验收示例

- A1 集群自检：设置 `askdata.cluster.enabled=true` 且元数据库仍是 H2 文件库 / `ASKDATA_SECRET_KEY` 为空 / 会话仍是 memory 时，启动日志给出逐项可读诊断与修复建议；`askdata.cluster.fail-fast=true` 时拒绝启动并指出第一处不满足项。
- A2 集群自检通过：元数据库为 MySQL/PostgreSQL、Redis 可用、对象存储可用、密钥已配置时，自检全部通过并在启动日志打印汇总（无告警、无 fail-fast）。
- A3 通用文件跨实例：实例 A 上传助手 logo，实例 B 通过 `/api/file/download` 取回字节完全一致；替换 logo 后旧文件在 B 上返回 404。
- A4 Excel 跨实例：实例 A 调 `/datasource/parseExcel` 上传，实例 B 用返回的 `filePath` 调 `/datasource/importToDb` 成功建表；单实例旧行为（返回本地文件名）仍可解析。
- A5 术语/训练/设置文件跨实例：在 A 触发术语导入与错误行导出，B 能读取/下载同一文件；设置 logo 上传后 B 可取回。
- A6 定时任务导出跨实例：任务在 A 执行并导出，运行记录中的 `export_path` 在 B 上可下载且行数与查询结果一致。
- A7 图表图片跨实例：A 生成图表图片并返回 token，B 用同一 token 访问 `/api/chart/image` 返回同一张 PNG；token 过期后返回 404。
- A8 图表清理单实例：两个实例同时运行清理任务，只产生一次清理动作（单实例守卫生效），不出现互相删除仍在有效期的文件。
- A9 定时任务租约回收：任务在 A 执行期间 A 被强杀（`running` 残留），租约到期后 B 的轮询能回收该任务并重新执行，最终产生成功运行记录。
- A10 会话与缓存跨实例：在 A 登录，B 携带同一 token 访问返回 200；在 A 禁用用户后，B 立即（不等待 TTL）拒绝该用户请求。
- A11 防抖跨实例：同一用户对同一接口在两个实例上并发重复提交，只有一次被执行，另一次返回防抖错误。
- A12 雪花 ID 唯一：两实例各生成 1 万个 ID，全量去重后数量为 2 万，无重复；`fw.plus.uid.enabled=false` 且未配置 `askdata.id.worker-id` 时自检给出撞号告警。
- A13 MCP SSE 跨实例可用：集群模式启用 Redis 后，MCP 自动启用框架内置的 Redis 集群注册表（`RedisServerSseRegistry` + `McpRedisBroadcaster` pub/sub）；实例 A 建立 `/mcp/sse` 流、`POST /mcp/messages` 落到实例 B 时，仍能完成 initialize 与 tools/call，无需粘性会话。集群模式但未启用 Redis（容器内无 `StringRedisTemplate`）时，启动自检告警说明当前为进程内会话、需粘性会话或启用 Redis。
- A14 单实例零回归：不设置集群开关时，H2 + 本地文件 + 内存会话仍可完整启动并通过既有全量测试。
- A15 部署基线可运行：`docker-compose.cluster.yml` 起 MySQL + Redis + MinIO + 两实例，`probe-cluster.mjs` 在负载均衡下全部通过。
