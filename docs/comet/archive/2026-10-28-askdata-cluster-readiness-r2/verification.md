---
generated_from_state_version: 17
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 4
- 验证器尝试次数: 1
- 完成时间: 2026-10-28T16:49:23.134Z
- 摘要: A1–A15 全部通过。上轮失败的三项已独立复核：A5 现场在 A 生成术语错误明细后 B 按展示名下载成功（200/3618 bytes，DB 为 minio 随机存储名，B 本机无文件），另有设置 logo 与助手 logo 跨实例往返/替换删除 404；A11 生产端点 ScheduledTaskController#runNow 确实带 @Debounce，真实 Redis 两 debouncer 单测 + 实盘顺序/5 轮并发 + 真实任务 3 轮并发（200/429，仅 1 条运行记录）均只放行一次；A13 在 A 建 SSE、messages 全部打到 B 完成 initialize/tools_list/tools_call，反向亦通过，并经无粘性 round-robin LB 复跑通过。其余项由实盘日志（自检 8 项通过、Redis 单例清理守卫、workerID 15/16、会话/禁用用户跨实例）与独立测试（全量 1096 tests 0 failures）支撑。残余风险主要是未真实 kill -9、无默认大模型导致 A6/A9 未产出成功导出/记录、A7 未在真实 MinIO 生成图表、compose 未内置 LB。另已校验运行容器 app.jar 中 ClusterSseRegistry/RequestDebouncer/AskdataFileStorage/ScheduledTaskController 的 class SHA256 与当前 HEAD 编译产物完全一致，排除镜像与候选提交不一致。刷新检查（action askdata-cluster-readiness-r2:17：cluster-suite exit 0/43037ms、e2e-cross-instance exit 0/441ms、e2e-probe exit 0/600ms）后结论不变：复核时 HEAD 仍为 d3f315d872321d73d386c917a997635353f3b7f6，git status --short 仍只有两个 Runtime 管理的 untracked comet-state.yaml，且刷新回执与上述独立实盘/自跑测试观察无矛盾（cluster-suite 日志中的 A1 负向 ERROR 行属单测预期输出，执行状态 passed）。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1 集群自检：设置 `askdata.cluster.enabled=true` 且元数据库仍是 H2 文件库 / `ASKDATA_SECRET_KEY` 为空 / 会话仍是 memory 时，启动日志给出逐项可读诊断与修复建议；`askdata.cluster.fail-fast=true` 时拒绝启动并指出第一处不满足项。 | 独立运行 `mvn -pl askdata/askdata-server -Dmaven.test.skip=false -Dtest=ClusterReadinessCheckerTest test` → Tests run: 8, Failures: 0, Errors: 0。用例 h2MemoryAndLocalStorageAreFailures 断言 H2 文件库/空密钥/memory 会话/memory 缓存/local 存储均判 FAIL；failFastRejectsStartup 断言 `checker.run(null)` 抛 IllegalStateException 且消息含『元数据库』。代码审阅 ClusterReadinessChecker#run：逐项输出『[通过]/[告警]/[不满足] 检查项：说明；修复：…』并打印汇总，fail-fast 在全部项打印后才抛出。 |
| A2 | passed | brief.md | A2 集群自检通过：元数据库为 MySQL/PostgreSQL、Redis 可用、对象存储可用、密钥已配置时，自检全部通过并在启动日志打印汇总（无告警、无 fail-fast）。 | `docker logs askdata-cluster-app-a-1` 启动日志：8 条『集群自检 [通过]』（MySQL、密钥长度 33、会话 redis、缓存 redis、锁 redis、文件存储 minio、雪花 workerId 由 WORKER_NODE 分配、MCP http-sse+Redis 中继），汇总『通过 8 项 / 告警 0 项 / 不满足 0 项（fail-fast=true）』，随后正常监听 8000；app-b 同样 8 项通过。 |
| A3 | passed | brief.md | A3 通用文件跨实例：实例 A 上传助手 logo，实例 B 通过 `/api/file/download` 取回字节完全一致；替换 logo 后旧文件在 B 上返回 404。 | 自写临时探针（/private/.../comet-recovery/verify-extra.mjs）：A 创建助手并 `PATCH /api/v1/system/assistant/ui` 上传 PNG → 200；B 调 `/api/v1/system/assistant` 取得 configuration.logo，再 `GET /api/v1/system/assistant/picture/{fileId}` → status=200 bytes=30 same=true；A 替换 logo 后 B 再取旧 fileId → 404。证明上传/下载/替换删除都走共享存储且跨实例。 |
| A4 | passed | brief.md | A4 Excel 跨实例：实例 A 调 `/datasource/parseExcel` 上传，实例 B 用返回的 `filePath` 调 `/datasource/importToDb` 成功建表；单实例旧行为（返回本地文件名）仍可解析。 | `node askdata/askdata-server/e2e/probe-cluster.mjs`：A `POST /api/v1/datasource/parseExcel` 200；B 用 A 返回的 filePath `POST /api/v1/datasource/importToDb` 200 并建表（响应含 excel_Sheet1_892bf3d6f5 等表结构）。ExcelStorageCrossInstanceTest 2/2 通过（上传只写共享存储、导入时本机无副本按存储名回源）；ExcelService 读取逻辑为先查本地目录再 `fileStorage.readByName(excel, filename)`，历史本地文件名仍可解析。 |
| A5 | passed | brief.md | A5 术语/训练/设置文件跨实例：在 A 触发术语导入与错误行导出，B 能读取/下载同一文件；设置 logo 上传后 B 可取回。 | 自写探针 a5-fresh.mjs 现场生成：A `POST /api/v1/system/terminology/uploadExcel`（1 行非法数据）→ error_excel_filename=verifier_a5_1793205355186_5d296305-c_error.xlsx；B `POST /api/v1/system/download-fail-info` 按该展示名下载 → 200、3618 bytes、可解压为 9 项 xlsx。MySQL 行：filename=6ae2246ce4b0ff50966539be.xlsx，original_filename=verifier_a5_..._error.xlsx，platform=minio；B 容器内不存在该文件，证明按展示名回源共享存储。另 A 上传设置 logo 后 B `GET /api/v1/system/appearance/picture/{fileId}` → 200 且字节一致；旧现场文件 verifier_terms_2026_22e7c936-f_error.xlsx 在 B 下载 200/3618 bytes。 |
| A6 | passed | brief.md | A6 定时任务导出跨实例：任务在 A 执行并导出，运行记录中的 `export_path` 在 B 上可下载且行数与查询结果一致。 | ScheduledExportDownloadTest 3/3 通过：export_path 为共享存储引用时任意实例可读、历史本地绝对路径仍可读、缺失时返回可读错误；代码审阅 ScheduledTaskExecutor#export 在 fileStorage.enabled() 时把 CSV/XLSX 存入 `scheduled` 路径并把返回 url 写入 export_path，ScheduledTaskService#exportFile 优先 fileStorage.info/read、再回退本地文件。未在集群中真实跑出成功导出（环境无默认大模型）。 |
| A7 | passed | brief.md | A7 图表图片跨实例：A 生成图表图片并返回 token，B 用同一 token 访问 `/api/chart/image` 返回同一张 PNG；token 过期后返回 404。 | ChartImageClusterTest 3/3 通过：两个独立 ChartImageService 节点（各自内存与本地目录，共享 H2 元数据库和共享文件存储），A renderFromResult 生成的 token 在 B get 返回 PNG（首字节 0x89）；过期 token 返回 null 且共享映射删除；cleanupExpired 跨实例删除过期行。ChartImageTokenLifecycleTest 7/7 通过（重启后映射仍在、篡改/过期返回 404、只清过期）。 |
| A8 | passed | brief.md | A8 图表清理单实例：两个实例同时运行清理任务，只产生一次清理动作（单实例守卫生效），不出现互相删除仍在有效期的文件。 | 实盘双实例启动即触发图表清理：`docker logs app-a` 显示『已获取单 worker 守卫：askdata:chart:cleanup（后端：redis）』并随后释放；`docker logs app-b` 显示『单 worker 守卫 askdata:chart:cleanup 在防抖窗口内已执行过，跳过（窗口 300s）』→ 同一轮清理只有一个实例执行。代码 ChartImageService#scheduledCleanup 使用 SingleWorkerGuard（RedisWorkerLock：SET NX PX + 持有者校验释放）。 |
| A9 | passed | brief.md | A9 定时任务租约回收：任务在 A 执行期间 A 被强杀（`running` 残留），租约到期后 B 的轮询能回收该任务并重新执行，最终产生成功运行记录。 | ScheduledTaskLeaseTest 6/6 通过：live 租约不可被第二实例抢、过期租约可回收、仅 owner 可续租、release 清空 owner/lease、dueTasks 包含过期租约行且排除 live 行。实盘：创建任务后用 SQL 制造 running=1 / lock_owner=dead-node-x / lock_expire_at 过期，约 32s 后轮询实例重新 claim 并执行，行恢复 running=0、lock_owner=NULL、next_run_at 前移，scheduled_task_run 新增一条 SCHEDULE 记录（FAILED: 默认大模型未配置）。未真实 kill -9 容器。 |
| A10 | passed | brief.md | A10 会话与缓存跨实例：在 A 登录，B 携带同一 token 访问返回 200；在 A 禁用用户后，B 立即（不等待 TTL）拒绝该用户请求。 | probe-cluster：A 登录后 B 连续 6 次 `GET /api/v1/system/assistant` 均 200（会话跨实例）。自写探针：A 创建用户并在 A 登录 → B `GET /api/v1/user/info` 200；A `PATCH /api/v1/user/status {id,status:0}` 后同一 token 立即请求 B → 401『认证无效【账号已禁用】』（未等待 TTL）。 |
| A11 | passed | brief.md | A11 防抖跨实例：同一用户对同一接口在两个实例上并发重复提交，只有一次被执行，另一次返回防抖错误。 | 源码确认生产端点 `ScheduledTaskController#runNow` 带 `@Debounce(args={"0"})`；DebounceEndpointCoverageTest 1/1 通过；RequestDebouncer Redis 分支为 Lua 原子『读占位+比较过期+PSETEX』。DebounceRedisCrossInstanceTest（真实 Redis 127.0.0.1:13379，两个独立 debouncer 实例）3/3 通过（含过期边界并发）。实盘：probe-cross-instance 并发 statuses=429,400；自写探针顺序 A→B 立即 429、1.5s 后恢复；5 轮 A/B 并发每轮恰好一个 429；真实任务 3 轮并发均为 200,429，且从 A、B 查询 runs 都只有 1 条运行记录。 |
| A12 | passed | brief.md | A12 雪花 ID 唯一：两实例各生成 1 万个 ID，全量去重后数量为 2 万，无重复；`fw.plus.uid.enabled=false` 且未配置 `askdata.id.worker-id` 时自检给出撞号告警。 | SnowflakeIdUniquenessTest 1/1：两个 workerId 各生成 1 万 ID，合并去重 20000。实盘 MySQL WORKER_NODE 中 id=15/16 两行，host_name=43b68737c3c3 / b9a89c9d7ed9，正好是 app-a/app-b 容器 ID；启动日志分别打印 workerID:15 与 workerID:16。ClusterReadinessCheckerTest.workerIdFallsBackWithPidWarning 断言 `fw.plus.uid.enabled=false` 且未配置 askdata.id.worker-id 时自检输出 WARN。 |
| A13 | passed | brief.md | A13 MCP SSE 跨实例可用：集群模式启用 Redis 后，MCP 自动启用框架内置的 Redis 集群注册表（`RedisServerSseRegistry` + `McpRedisBroadcaster` pub/sub）；实例 A 建立 `/mcp/sse` 流、`POST /mcp/messages` 落到实例 B 时，仍能完成 initialize 与 tools/call，无需粘性会话。集群模式但未启用 Redis（容器内无 `StringRedisTemplate`）时，启动自检告警说明当前为进程内会话、需粘性会话或启用 Redis。 | `node e2e/probe-cross-instance.mjs`：A 建 `/mcp/sse` 200（endpoint=/mcp/messages/<uuid>），initialize / notifications/initialized / tools/list / tools/call 全部 POST 到 B 均 200，响应经 A 的 SSE 流返回（tools=14，tools/call 返回 isError=false）；反向 B 建流、initialize POST 到 A 同样 200 并经 B 流返回。另起一次性 round-robin 代理（18999→18099/18100，无粘性，命中 A=12/B=12）复跑同一探针 failures=0，证明不依赖粘性会话。启动自检 MCP 项为『http-sse + Redis 会话归属与请求中继』；无 Redis/无中继时 WARN 含『粘性会话』由 ClusterReadinessCheckerTest 覆盖。 |
| A14 | passed | brief.md | A14 单实例零回归：不设置集群开关时，H2 + 本地文件 + 内存会话仍可完整启动并通过既有全量测试。 | 独立跑全量 `mvn -pl askdata/askdata-server -Dmaven.test.skip=false -Daskdata.test.redis.port=13379 test` → Tests run: 1096, Failures: 0, Errors: 0, Skipped: 7, BUILD SUCCESS（跳过的仅为 Oracle/SQLServer/PG/MySQL live dialect、LLM live 等条件测试）。关闭集群不告警/不异常由 ClusterReadinessCheckerTest.disabledClusterSkipsChecks 覆盖；默认 H2+本地存储+内存缓存的上下文测试在全量套件中通过。 |
| A15 | passed | brief.md | A15 部署基线可运行：`docker-compose.cluster.yml` 起 MySQL + Redis + MinIO + 两实例，`probe-cluster.mjs` 在负载均衡下全部通过。 | `docker compose -f e2e/docker-compose.cluster.yml ps`：mysql/redis/minio/app-a/app-b 均 Up/healthy；`node e2e/probe-cluster.mjs` 直连两实例全部 PASS（登录态跨实例、助手跨实例、Excel 上传→导入、导出端点）。因 compose 本身未含 LB 服务，我额外起一次性 round-robin 代理 18999→18099/18100（无粘性，A/B 各 12 次），BASE_A=BASE_B=http://127.0.0.1:18999 跑 probe-cluster 得 RESULT CLUSTER_OK failures=0，probe-cross-instance 也 failures=0。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| 集群相关单元/集成测试（askdata-server，显式 JAVA_HOME，关闭 test skip，含 Redis 跨实例防抖用例） | -c export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home; export PATH="$JAVA_HOME/bin:$PATH"; exec /usr/local/bin/mvn -q -pl askdata/askdata-server -Dmaven.test.skip=false -DfailIfNoTests=false -Daskdata.test.redis.port=13379 -Dtest=ClusterReadinessCheckerTest,SettingsFileStorageFallbackTest,SettingsFileOriginalNameFallbackTest,ExcelStorageCrossInstanceTest,ScheduledExportDownloadTest,ScheduledTaskLeaseTest,ChartImageClusterTest,ChartImageTokenLifecycleTest,DebounceIntegrationTest,DebounceEndpointCoverageTest,DebounceRedisCrossInstanceTest,McpSseClusterBridgeTest,ClusterSseRegistryTest,SnowflakeIdUniquenessTest test | . | passed | 0 | 43037 ms |
| A5/A11/A13 跨实例实盘探针（双实例容器 + 共享 Redis/MySQL/MinIO） | askdata/askdata-server/e2e/probe-cross-instance.mjs | . | passed | 0 | 441 ms |
| 双实例集群探针 | askdata/askdata-server/e2e/probe-cluster.mjs | . | passed | 0 | 600 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- 集群相关 14 个测试类（mvn，显式 JAVA_HOME + 关闭 test skip + Redis 13379）: passed — 本轮实际执行 exit 0，逐类 surefire 计数 Failures: 0 / Errors: 0：ClusterReadinessChecker 8、SettingsFileStorageFallback 1、SettingsFileOriginalNameFallback 2、ExcelStorageCrossInstance 2、ScheduledExportDownload 3、ScheduledTaskLease 6、ChartImageCluster 3、ChartImageTokenLifecycle 7、DebounceIntegration 5、DebounceEndpointCoverage 1、DebounceRedisCrossInstance 3、McpSseClusterBridge 7、ClusterSseRegistry 4、SnowflakeIdUniqueness 1。DebounceRedisCrossInstanceTest 另单独连跑 3 次均通过。
- 单实例上下文冒烟（A14 零回归）: passed — 不设集群开关：ValueAddModulesSmokeTest 9/9、AskdataAssistantWiringTest 1/1、McpFrameworkE2ETest 1/1，确认新构造参数与 MCP 集群配置未影响单实例装配。
- 实盘 e2e/probe-cross-instance.mjs（双实例容器）: passed — 12 项全 PASS、RESULT CROSS_INSTANCE_OK failures=0，连续 4 次稳定；覆盖 A13 双向（A 流+B POST、B 流+A POST）、A11（statuses=400,429 或 429,400）、A5（B 下载 3618 字节）。
- 实盘 e2e/probe-cluster.mjs（既有集群探针）: passed — 新代码容器重建后仍 RESULT CLUSTER_OK failures=0（会话/助手/Excel 跨实例、导出端点）。
- 已知限制: A9 的租约回收由 ScheduledTaskLeaseTest 以「租约过期」模拟崩溃残留，未在实盘对容器执行真实 kill -9。
- 已知限制: A15 的双实例探针直接交替访问 18099/18100，未经过真实负载均衡器；容器与探针本身按 compose 基线运行。
- 已知限制: A6 的失败明细/导出回执在实盘仅验证了按展示名下载与 400 可读错误路径，未覆盖每个业务模块的失败明细内容正确性。

## 阻塞项

_无。_

## 风险与跳过的工作

- 未真实 kill -9 容器验证 A9；用 SQL 制造过期租约后由真实轮询器回收并重跑，且最终运行记录为 FAILED（环境无默认大模型），未观测到成功运行记录。
- 环境无默认大模型，A6 未做真实定时任务导出，只验证了共享存储引用读取/历史本地路径两个分支的单测与代码路径；A9 的 SUCCESS 记录同理未观测。
- A7 的图表 token 跨实例只用两个 service 实例 + H2 + 共享文件存储的单测验证，未在真实容器/MinIO 上生成并跨节点取图。
- docker-compose.cluster.yml 未内置负载均衡服务（文档也写明探针不需要 LB）；A15 的『负载均衡下通过』由我临时 round-robin 代理证明，属于编排基线缺口。
- probe-cluster.mjs 实际未覆盖 spec A15 场景列举的图表图片、租约回收、防抖与缓存一致性（这些由 ChartImageClusterTest/ScheduledTaskLeaseTest/Debounce* 与自写探针分别覆盖）。
- A5 按 original_filename 回源未按 oid/租户过滤，同名展示名会取最新一条；当前依赖文件名中的随机后缀降低碰撞与猜测风险。
- 实盘环境为同机双容器，未经过真实跨主机网络/真实硬件 LB，网络分区与异步复制延迟不在验证范围内。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | recovery | — | Runtime checks were interrupted: refresh-check-input-snapshot. Preserve their receipts and submit a new Builder candidate after repair. | 2026-10-28T13:09:56.639Z |
| 1 | 1 | 1 | recovery | — | The user chose to repair the implementation after Runtime checks stopped. | 2026-10-28T13:43:52.744Z |
| 1 | 2 | 0 | recovery | — | Runtime checks failed: cluster-suite. Preserve their receipts and submit a new Builder candidate after repair. | 2026-10-28T13:52:36.778Z |
| 1 | 3 | 1 | fail | A5, A11, A13 | 独立只读核验（HEAD 48497c0，双实例实盘 + surefire + 代码审阅）：12 项通过，A5、A13 失败，A11 证据不足（blocked）。关键阻断为 MCP SSE 跨实例 POST 404（框架 Redis 注册表未共享入口会话）与术语/训练错误明细下载在生成实例自身即 400（文件名映射错误）。建议返回 Build 修复 A5、A13，并补 A11 跨实例证据；A6/A9/A15 的端到端演练缺口见 risks。 | 2026-10-28T15:39:31.728Z |
| 1 | 4 | 1 | pass | — | A1–A15 全部通过。上轮失败的三项已独立复核：A5 现场在 A 生成术语错误明细后 B 按展示名下载成功（200/3618 bytes，DB 为 minio 随机存储名，B 本机无文件），另有设置 logo 与助手 logo 跨实例往返/替换删除 404；A11 生产端点 ScheduledTaskController#runNow 确实带 @Debounce，真实 Redis 两 debouncer 单测 + 实盘顺序/5 轮并发 + 真实任务 3 轮并发（200/429，仅 1 条运行记录）均只放行一次；A13 在 A 建 SSE、messages 全部打到 B 完成 initialize/tools_list/tools_call，反向亦通过，并经无粘性 round-robin LB 复跑通过。其余项由实盘日志（自检 8 项通过、Redis 单例清理守卫、workerID 15/16、会话/禁用用户跨实例）与独立测试（全量 1096 tests 0 failures）支撑。残余风险主要是未真实 kill -9、无默认大模型导致 A6/A9 未产出成功导出/记录、A7 未在真实 MinIO 生成图表、compose 未内置 LB。另已校验运行容器 app.jar 中 ClusterSseRegistry/RequestDebouncer/AskdataFileStorage/ScheduledTaskController 的 class SHA256 与当前 HEAD 编译产物完全一致，排除镜像与候选提交不一致。刷新检查（action askdata-cluster-readiness-r2:17：cluster-suite exit 0/43037ms、e2e-cross-instance exit 0/441ms、e2e-probe exit 0/600ms）后结论不变：复核时 HEAD 仍为 d3f315d872321d73d386c917a997635353f3b7f6，git status --short 仍只有两个 Runtime 管理的 untracked comet-state.yaml，且刷新回执与上述独立实盘/自跑测试观察无矛盾（cluster-suite 日志中的 A1 负向 ERROR 行属单测预期输出，执行状态 passed）。 | 2026-10-28T16:49:23.134Z |



## 结论

A1–A15 全部通过。上轮失败的三项已独立复核：A5 现场在 A 生成术语错误明细后 B 按展示名下载成功（200/3618 bytes，DB 为 minio 随机存储名，B 本机无文件），另有设置 logo 与助手 logo 跨实例往返/替换删除 404；A11 生产端点 ScheduledTaskController#runNow 确实带 @Debounce，真实 Redis 两 debouncer 单测 + 实盘顺序/5 轮并发 + 真实任务 3 轮并发（200/429，仅 1 条运行记录）均只放行一次；A13 在 A 建 SSE、messages 全部打到 B 完成 initialize/tools_list/tools_call，反向亦通过，并经无粘性 round-robin LB 复跑通过。其余项由实盘日志（自检 8 项通过、Redis 单例清理守卫、workerID 15/16、会话/禁用用户跨实例）与独立测试（全量 1096 tests 0 failures）支撑。残余风险主要是未真实 kill -9、无默认大模型导致 A6/A9 未产出成功导出/记录、A7 未在真实 MinIO 生成图表、compose 未内置 LB。另已校验运行容器 app.jar 中 ClusterSseRegistry/RequestDebouncer/AskdataFileStorage/ScheduledTaskController 的 class SHA256 与当前 HEAD 编译产物完全一致，排除镜像与候选提交不一致。刷新检查（action askdata-cluster-readiness-r2:17：cluster-suite exit 0/43037ms、e2e-cross-instance exit 0/441ms、e2e-probe exit 0/600ms）后结论不变：复核时 HEAD 仍为 d3f315d872321d73d386c917a997635353f3b7f6，git status --short 仍只有两个 Runtime 管理的 untracked comet-state.yaml，且刷新回执与上述独立实盘/自跑测试观察无矛盾（cluster-suite 日志中的 A1 负向 ERROR 行属单测预期输出，执行状态 passed）。
