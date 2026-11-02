# askdata-server（问数 · 单体后端）

Java 17 + Spring Boot 3.5.8 单体工程；前端（SQLBot frontend 拷贝）作为嵌套 npm 工程
放在 `web/`，其构建产物 `web/dist` 经 Maven 资源目录挂入 `BOOT-INF/classes/static`，
实现单 jar 前后端同进程托管（端口 8000）。

## 构建

```bash
# 仓库根（三级 POM：根聚合 askdata-root → askdata → askdata-server）
mvn package                 # 默认执行测试（maven.test.skip=false）
mvn package -Dmaven.test.skip=true   # 只打包、不跑测试
# 产物：askdata/askdata-server/target/askdata-server.jar
```

### 测试

测试默认执行。Spring 上下文测试与集成测试连**真实依赖**（元数据库是真实 MySQL 8 的临时
schema，不再使用 H2；模型/向量也是真实服务），依赖不可达的用例整类 assume-skip：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
# 元数据库：MySQL 8.0+（Flyway 社区版不支持 5.7）
export ASKDATA_DB_HOST=127.0.0.1 ASKDATA_DB_PORT=3306 ASKDATA_DB_NAME=askdata_it_app \
       ASKDATA_DB_USER=askdata ASKDATA_DB_PASSWORD='***'
# 向量存储 + 向量模型（缺省即 skip 向量用例）
export ASKDATA_QDRANT_URL=http://127.0.0.1:6333 ASKDATA_QDRANT_API_KEY='***'
export ASKDATA_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:3306 ASKDATA_IT_MYSQL_USER=root ASKDATA_IT_MYSQL_PASSWORD='***'
export ASKDATA_IT_EMBEDDING_URL=http://127.0.0.1:9528 ASKDATA_IT_EMBEDDING_MODEL=bge-m3
# 跨实例防抖（Redis 开启 requirepass 时必填；端口缺省 6379）
export ASKDATA_TEST_REDIS_HOST=127.0.0.1 ASKDATA_TEST_REDIS_PORT=6379 ASKDATA_TEST_REDIS_PASSWORD='***'
# 真实 ES 联调（缺省探测本机 9200；集群开了 xpack.security 时给只读测试账号）
export ASKDATA_ES_HOST=127.0.0.1 ASKDATA_ES_PORT=9200 ASKDATA_ES_USER=es_test_rw ASKDATA_ES_PASS='***'
# 真实 LLM 生成 SQL（缺省探测本机 Ollama 11434）
export ASKDATA_LLM_DOMAIN=http://127.0.0.1:11434 ASKDATA_LLM_MODEL=gpt-oss:120b-cloud
mvn -o test
```

集成测试每次创建 `askdata_it_*` 临时 schema，结束即删除，不污染既有库。

真实服务类用例（Redis / ES / LLM / Qdrant / bge-m3）在缺省参数下会先探测本机默认端口，
探测不到才 assume-skip 并打印配置方式；白名单 parity 用例使用仓库内固定夹具
`src/test/resources/sqlbot-reference/whitelist.py`（SQLBot 源码原样拷贝），无需克隆外部仓库。

依赖版本管理：

- fw-plus BOM（import）：`com.xiongx9527.fw.plus.boot:fw-plus-boot-dependencies:0.1.1-SNAPSHOT`
- fw-plus AI BOM（import）：`com.xiongx9527.fw.plus.ai:fw-plus-ai-boot-dependencies:0.1.1-SNAPSHOT`
- 鉴权（D10）：`com.xiongx9527.fw.plus:fw-plus-auth-satoken`（Sa-Token 1.46.0）

## 前端

```bash
cd web
npm install
npm run build   # 产物 dist/ → Maven 打包时挂入 static
# 开发模式（Vite dev server:5173，/api/v1 代理到 127.0.0.1:8000）
npm run dev
```

xpack 闭源调用点的本地补丁说明见 `web/XPackPatchNotes.md`（统一 `[askdata-xpack-patch]` 标记）。

## 运行与验证（登录闭环 A3）

```bash
java -jar target/askdata-server.jar
# 访问 http://localhost:8000 → 登录页
# 种子管理员：admin / SQLBot@123456（MD5 口令落库）
```

元数据库：**MySQL 8.0+**（Flyway 社区版已不支持 MySQL 5.7）。连接参数走环境变量，
缺省 `jdbc:mysql://127.0.0.1:3306/askdata`（用户 `root`、空口令）：

```bash
ASKDATA_DB_HOST=127.0.0.1 ASKDATA_DB_PORT=3306 ASKDATA_DB_NAME=askdata \
ASKDATA_DB_USER=askdata ASKDATA_DB_PASSWORD='***' \
java -jar target/askdata-server.jar
```

首次启动由 Flyway 执行 `src/main/resources/db/migration/common`（种子）与 `db/migration/{vendor}`
（方言 baseline）自动建表并写入种子；旧 `schema.sql` 已移除，运行期不再由 `spring.sql.init` 执行建表脚本。

**元数据库只支持 MySQL（含 MariaDB 等衍生版本）与 PostgreSQL**：迁移前由
`MetadataDatabaseGuard` 读取真实数据库产品与版本，MySQL < 8.0、MariaDB < 10.2、
PostgreSQL < 10、H2 以及其它产品一律拒绝启动并给出明确提示（Flyway 社区版对 MySQL 5.7
只报 Teams 版文案）。H2 已完全移除：既不是元数据库，也不再是数据源类型。

## MCP 兼容层（SQLBot）

`/api/v1/mcp/*` 提供 SQLBot MCP 的 7 个 operation_id（`access_token`、`mcp_start`、`mcp_ws_list`、
`mcp_datasource_list`、`mcp_model_list`、`mcp_question`、`mcp_assistant`），同时保留 askdata 原生工具名。
关键语义：

- `mcp_question` / `mcp_assistant` 的 `stream` 缺省为 `true`（SSE `data:{json}` 帧）；`stream=false` 时
  `mcp_question` 返回 `{success,record_id,sql,data,chart,image_url?}`，并把同一 `data` 对象
  （`{fields,data,datasource,limit?}`）写入 `chat_record.data`。
- `return_img=false` 不生成图片；`custom_model` 同时作用于 SQL 与图表生成，模型不可用时返回错误，
  不静默回落默认模型。
- `datasource_id` 仅在会话尚未绑定数据源时生效；最终使用的数据源（已绑定 / 显式指定 / RAG 选中）
  都要通过 `DsGrantService.checkUsable`。
- `mcp_start` / `chat_start` 只创建会话，不创建 `first_chat` 空记录；`brief` 在有 `question` 时取
  去空白后的前 20 个字符，否则取当前时间；`mcp_start` 响应只含 `access_token` 与 `chat_id`。
- `mcp_assistant` 必填 `url` / `authorization`，以合成用户（id=-1、`sqlbot-mcp-assistant`、oid=1，
  不写 `sys_user`）创建会话并拉取宿主数据源，按 `QUERY_DATA` 只返回 SQL 与数据，不生成图表/图片。
- MCP 列表裁剪：数据源不含 `configuration` / `embedding` / `table_relation` / `recommended_config`；
  工作空间仅 `{id,name}`；两者均按名称升序。REST `/datasource/list` 与 Web `/chat/start` 契约不变。
- 错误码：无效 token → 403 `{"detail"}`；非流式 `mcp_question` 运行期错误 → 500
  `{"success":false,"message":...}`；其他工具错误 → 400 `{"detail"}`；`datasource_id` / `chat_id` /
  `custom_model` 格式非法 → 400 `{"detail"}`。

## 向量存储（在线 Qdrant）

术语 / SQL 示例 / 表 / 数据源四类对象的向量，统一经 `EmbeddingService` 写入与召回。
**唯一后端是 Qdrant（REST）**，没有本地后端：向量不落 DB 的 `embedding` JSON 列，也不会在
在线库不可达时退回应用内余弦——同一份向量只能有一个真相，否则切换维度或迁移集合时会静默漏召回。

```yaml
askdata:
  vector-store:
    type: ${ASKDATA_VECTOR_STORE_TYPE:qdrant}   # qdrant 已实现；milvus/weaviate/pgvector/es/redis 预留
    collection-prefix: askdata    # 集合名 = {prefix}-{object-type}
    qdrant:
      url: ${ASKDATA_QDRANT_URL:}         # REST 基址，如 http://qdrant:6333；必填
      api-key: ${ASKDATA_QDRANT_API_KEY:} # 开启鉴权时必填
```

要点：

- **未配置 `url`**：应用仍可启动（其它功能不受影响），但启动日志打印错误，且任何向量读写都会抛出
  「未配置在线向量存储」异常；绝不静默写本地。
- **向量模型**：只使用 `ai_model` 中 `model_type=1` 且 `enable=1`、`valid=1` 的在线模型
  （OpenAI 兼容 `/v1/embeddings`）。本地 ONNX 模型已移除。
- **集合维度取自向量本身**（`ensureCollection(collection, vector.length)`），与模型维度自动对齐；
  维度不一致时**拒绝写入并保留既有向量**（记 error 并提示重建），绝不隐式删除集合。
  切换向量模型后执行「向量索引重建」即可：重建会先删除四类集合并按新维度全量回填。
- **模型解析缓存**：默认向量模型解析结果缓存 60s，模型配置保存/删除/设默认/连通校验后立即失效。
- **不镜像 DB 向量列**：`terminology.embedding` / `core_table.table_embedding` 等列不再写入，
  召回候选也不再以 `embedding IS NOT NULL` 过滤。
- **启动期回填**：无法用 DB 列判断「是否已回填」，因此按业务范围全量重算；upsert 以记录 id 为
  point id，重复写入即覆盖，保证幂等。重建任务按工作空间逐个执行，单空间失败不影响其它空间。
- **失败即显式失败**：Qdrant 不可达时写入返回 false、召回返回空表，由调用方降级为词法召回，
  日志中可见原因；不会出现「配置了在线库、实际却写本地」的隐性路径。
- **真实环境测试**：向量用例直接打真实 Qdrant / MySQL / bge-m3（依赖不可达时整类 skip），
  环境变量见 `docs/comet/specs/vector-store/spec.md` 的「验证」一节。

## 单 worker 启动守卫（锁后端可配置 + 防抖）

启动期的一次性任务（如向量回填）由 `SingleWorkerGuard` 保证「集群内只跑一次」，
锁后端与防抖窗口均可配置：

| askdata.lock.type | 后端 | 作用域 |
| --- | --- | --- |
| `db`（缺省） | 元数据库表行独占锁 | 与被保护数据一致：多实例共享同一元库时集群内一次；各实例独立库时各跑各的 |
| `redis` | Redis `SET NX PX` | 全局；仅在「共享 Redis + 各实例独立元数据库」的部署下有意义 |

- `askdata.lock.debounce-seconds`（缺省 300）：一次成功执行后，该窗口内的后续启动不再重复执行同一任务，
  防「应用反复重启 → 一次性任务被反复触发」；0 表示不防抖。

## 接口防抖

同一「调用方 + 接口」在窗口内只放行一次，重复请求直接返回 429（带 `Retry-After`）：

```yaml
askdata:
  debounce:
    enabled: ${ASKDATA_DEBOUNCE_ENABLED:false}   # 缺省关闭
    window-ms: ${ASKDATA_DEBOUNCE_WINDOW_MS:1000}  # 防抖窗口
    paths: ${ASKDATA_DEBOUNCE_PATHS:}              # Ant 风格路径；为空表示全部
```

- 调用方识别：优先取鉴权头（`X-SQLBOT-TOKEN` / `X-SQLBOT-ASK-TOKEN` / `X-SQLBOT-ASSISTANT-TOKEN`）的摘要，无则用来源 IP。
- 后端复用缓存层：`memory` 为单实例防抖，`redis` 为多实例全局防抖。

## 缓存（后端可配置）

鉴权热点数据（用户信息 / API Key 信息 / 助手信息）走 Spring Cache，**后端可配置**：

| askdata.cache.type | 后端 | 说明 |
| --- | --- | --- |
| `memory`（缺省） | Caffeine | 进程内缓存，单机有效，零外部依赖 |
| `redis` | Redis | 多实例共享；连接参数用 Spring Boot 标准位置 `spring.data.redis.*` |
| `none` | 不缓存 | 等价关闭缓存，便于排障与对比 |

```yaml
askdata:
  cache:
    type: "${ASKDATA_CACHE_TYPE:memory}"          # memory | redis | none
    max-size: ${ASKDATA_CACHE_MAX_SIZE:10000}      # 内存后端：单缓存条目上限
    ttl-seconds: ${ASKDATA_CACHE_TTL_SECONDS:300}  # 存活时间
    sa-token-store: "${ASKDATA_SA_TOKEN_STORE:}"   # Sa-Token 会话存储：memory | redis；留空跟随 type
spring:
  data:
    redis:                                        # 仅 type=redis / sa-token-store=redis 时生效
      host: ${ASKDATA_REDIS_HOST:127.0.0.1}
      port: ${ASKDATA_REDIS_PORT:6379}
      password: ${ASKDATA_REDIS_PASSWORD:}
      database: ${ASKDATA_REDIS_DATABASE:0}
```

- **Sa-Token 会话存储**：`sa-token-store=redis` 时登录态（token / session）存 Redis，多实例共享；缺省为进程内。
- **Redis 可选**：未启用 Redis 时容器内不存在任何 Redis bean，框架的 MCP Redis 集群模式不会激活，**应用无需部署 Redis 也能启动**。
- **API 文档元数据**：`askdata.openapi.{title,version,description}` 可配置；`version` 缺省取构建版本（随构建自动更新），token 头名取自 `sa-token.token-name`。

## 文件存储平台（local / MinIO / OSS / COS / S3）

文件存储基于 x-file-storage，默认 `local`（`./data/files`，离线可用）。切换平台只需设置环境变量并重启，无需改代码：

| 平台 | `FILE_STORAGE_PLATFORM` | 必填环境变量 |
| --- | --- | --- |
| 本地 | `local`（默认） | 无 |
| MinIO | `minio` | `MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET` |
| 阿里云 OSS | `aliyun-oss` | `ALIYUN_OSS_ENDPOINT`、`ALIYUN_OSS_ACCESS_KEY_ID`、`ALIYUN_OSS_ACCESS_KEY_SECRET`、`ALIYUN_OSS_BUCKET` |
| 腾讯云 COS | `tencent-cos` | `TENCENT_COS_SECRET_ID`、`TENCENT_COS_SECRET_KEY`、`TENCENT_COS_REGION`、`TENCENT_COS_BUCKET` |
| 通用 S3 | `amazon-s3` | `AMAZON_S3_ENDPOINT`、`AMAZON_S3_ACCESS_KEY_ID`、`AMAZON_S3_SECRET_ACCESS_KEY`、`AMAZON_S3_BUCKET`（`AMAZON_S3_REGION` 默认 `us-east-1`） |

- 可选：`*_DOMAIN` 配置对外访问域名（留空时使用预签名 URL）。
- 选中平台缺少必填项或平台名未知时，启动日志输出 `文件存储平台配置不可用（…），已回退 local` 并回退 local，服务照常启动。
- 启动日志 `文件存储平台: <name>` 表示实际生效平台，便于部署核对。
- 真实环境验证：`e2e/probe-storage.mjs`（上传 → 读回内容比对 → 替换后旧对象删除）。

## 多实例集群（`--spring.profiles.active=cluster`）

单实例是默认形态（本地文件 + 进程内会话）。多实例必须把「进程内状态」换成共享基础设施：

| 依赖 | 用途 | 不共享的症状 |
| --- | --- | --- |
| MySQL / PostgreSQL | 元数据库 | 各实例各一份数据 |
| Redis | 会话、缓存、防抖、单例锁、MCP SSE 消息路由 | 登录随机 401、权限变更不生效、MCP 404 |
| 对象存储 | 业务文件（Excel/导出/图片/logo） | 上传在 A、下载在 B 时 404 |

```bash
java -jar askdata-server.jar --spring.profiles.active=cluster
```

`application-cluster.yaml` 一次性打开共享化开关，并在启动期执行**集群就绪自检**：
逐项检查元数据库、应用密钥、会话存储、缓存后端、单例锁、文件存储、雪花 workerId、MCP 会话，
输出可读诊断；`ASKDATA_CLUSTER_FAIL_FAST=true` 时存在不满足项会拒绝启动。

要点：

- `ASKDATA_SECRET_KEY` 必须在所有实例上一致，否则跨实例无法解密数据源密码等密文。
- Excel 上传与导入、定时任务导出、术语/训练错误明细、图表图片 token 映射均已走共享存储，
  请求落到任意实例都能读回。
- 定时任务执行权带租约（`askdata.scheduled.lease-seconds`）：节点被强杀后 `running` 残留会在租约到期后
  被其它实例回收，不会永久卡死。
- MCP `http-sse` 在启用 Redis 时自动使用框架的 `RedisServerSseRegistry`（pub/sub 跨实例路由），
  不需要粘性会话；未启用 Redis 时才需要负载均衡粘性。

完整说明（配置矩阵、容量换算、错误对照）见 [docs/cluster-deployment.md](docs/cluster-deployment.md)；
最小可运行编排见 `e2e/docker-compose.cluster.yml`，验证脚本 `e2e/probe-cluster.mjs`。
