# 集群部署（多实例）

单实例部署是默认形态：H2 文件库 + 本地文件存储 + 进程内会话/缓存，零外部依赖即可启动。
多实例部署必须把所有「进程内状态」换成共享基础设施，否则同一个用户的两个请求落到不同实例会得到不同结果。

## 1. 必备外部依赖

| 依赖 | 作用 | 不共享时的症状 |
| --- | --- | --- |
| MySQL 5.7+ / PostgreSQL | 元数据库 | 各实例各一份数据，任务/会话/权限互相看不见 |
| Redis | 会话、缓存、防抖、单例锁、MCP SSE 消息路由 | 登录态不跨实例（401）、权限变更不生效、MCP 调用 404 |
| 对象存储（MinIO/OSS/COS/S3） | 所有业务文件 | 上传在 A、下载在 B 时 404 |

## 2. 启动方式

```bash
# 单实例（默认，无需外部依赖）
java -jar askdata-server.jar

# 集群
java -jar askdata-server.jar --spring.profiles.active=cluster
```

`application-cluster.yaml` 会一次性打开：共享元数据库、Redis 会话/缓存/锁、远端对象存储、集群自检。
若使用 PostgreSQL，追加 `,postgresql`：`--spring.profiles.active=cluster,postgresql`。

## 3. 关键配置

| 配置 | 环境变量 | 集群取值 | 说明 |
| --- | --- | --- | --- |
| `askdata.cluster.enabled` | `ASKDATA_CLUSTER_ENABLED` | `true` | 打开启动期自检 |
| `askdata.cluster.fail-fast` | `ASKDATA_CLUSTER_FAIL_FAST` | 建议 `true` | 自检不满足时拒绝启动；先观察可设 `false` |
| `askdata.secret.key` | `ASKDATA_SECRET_KEY` | 所有实例**同一个值** | 为空时每进程随机生成，跨实例解密失败 |
| `askdata.cache.type` | `ASKDATA_CACHE_TYPE` | `redis` | 用户/API Key/助手缓存共享 |
| `askdata.cache.sa-token-store` | `ASKDATA_SA_TOKEN_STORE` | `redis` | 登录态共享 |
| `askdata.lock.type` | `ASKDATA_LOCK_TYPE` | `redis` | 启动期单例任务 + 图表清理互斥 |
| `dromara.x-file-storage.default-platform` | `FILE_STORAGE_PLATFORM` | `minio`/`aliyun-oss`/`tencent-cos`/`amazon-s3` | 业务文件共享 |
| `askdata.scheduled.lease-seconds` | `ASKDATA_SCHEDULED_LEASE_SECONDS` | `120`~`300` | 任务执行权租约，节点被强杀后到期可回收 |

对象存储必填项见 `README.md` 的「文件存储平台」一节；缺项时启动期会告警并回退 `local`，
此时集群自检会把「文件存储」判为不满足。

## 4. 启动自检

开启集群模式后，启动日志会逐项输出：

```text
集群自检 [通过] 元数据库：jdbc:mysql://...
集群自检 [不满足] 应用密钥：askdata.secret.key 为空，各实例启动期会各自随机生成；修复：为所有实例配置同一个 ASKDATA_SECRET_KEY...
集群自检汇总：通过 6 项 / 告警 1 项 / 不满足 1 项（fail-fast=true）
```

检查项：元数据库、应用密钥、会话存储、缓存后端、单例锁、文件存储、雪花 workerId、MCP 会话。

## 5. MCP 与负载均衡

MCP 传输为 `http-sse` 时，会话与响应流绑定在建立连接的那个进程上。

- **启用 Redis 时**：框架自动装配 `RedisServerSseRegistry` + `McpRedisBroadcaster`，
  跨实例消息经 Redis pub/sub 路由到持有连接的实例，**不需要粘性会话**；
- **未启用 Redis 时**：SSE 会话为进程内，必须给负载均衡开启粘性会话（按 `X-SQLBOT-TOKEN` 或源 IP 哈希），
  否则 `POST /mcp/messages/{sessionId}` 落到其它实例会 `session not found`。

另外把负载均衡的读超时调到大于 SSE 心跳间隔（建议 ≥ 300s），否则长连接会被空闲超时掐断。

## 6. 容量换算

- `askdata.datasource.pool.max-pools`（默认 500）是**单实例**上限，N 个实例合计 N×500 条业务库连接；
  按目标库的最大连接数反推单实例上限。
- 定时任务轮询间隔 30s，实例数增加会提高抢占频率，但同一任务同一时刻只有一个实例执行（条件 UPDATE + 租约）。
- 图表清理任务由单实例锁保护，每轮只在一个实例执行，不影响吞吐。

## 7. 验证

```bash
# 双实例（不需要负载均衡）：直接交替访问两个实例
BASE_A=http://127.0.0.1:18099 BASE_B=http://127.0.0.1:18100 node e2e/probe-cluster.mjs
```

最小编排见 `e2e/docker-compose.cluster.yml`（MySQL + Redis + MinIO + app-a/app-b）。

## 8. 常见错误对照

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 登录后随机 401 | 会话未共享 | `ASKDATA_SA_TOKEN_STORE=redis` |
| 改权限/禁用用户后另一实例仍放行 | 缓存未共享 | `ASKDATA_CACHE_TYPE=redis` |
| 上传成功但下载 404 | 文件写在本地 | `FILE_STORAGE_PLATFORM` 指向对象存储 |
| 导入 Excel 报 File not found | 上传实例与导入实例不同且未共享存储 | 同上 |
| 数据源密码解密失败 | `ASKDATA_SECRET_KEY` 各实例不同 | 统一密钥后重新保存数据源 |
| MCP 工具调用偶发 404 | SSE 会话进程内 | 启用 Redis 或开粘性会话 |
| 定时任务不再执行 | 节点被强杀留下 running 残留 | 等租约到期自动回收；确认 `lease-seconds` 已生效 |
