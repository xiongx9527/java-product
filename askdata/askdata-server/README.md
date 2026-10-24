# askdata-server（问数 · 单体后端）

Java 17 + Spring Boot 3.5.8 单体工程；前端（SQLBot frontend 拷贝）作为嵌套 npm 工程
放在 `web/`，其构建产物 `web/dist` 经 Maven 资源目录挂入 `BOOT-INF/classes/static`，
实现单 jar 前后端同进程托管（端口 8000）。

## 构建

```bash
# 仓库根（三级 POM：根聚合 askdata-root → askdata → askdata-server）
mvn -Pskip-test package
# 产物：askdata/askdata-server/target/askdata-server.jar
```

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

元数据库：H2 文件库 `./data/askdata`（首次启动自动建表 + 种子，见 `src/main/resources/schema.sql`）。

## 向量存储（可插拔后端）

术语 / SQL 示例 / 表 / 数据源四类对象的向量，统一经 `EmbeddingService` 写入与召回，
底层后端由 `askdata.vector-store.type` 决定（`ai/vector/` 包）：

| type | 后端 | 说明 |
| --- | --- | --- |
| `none`（缺省） | `DbVectorStore` | DB `embedding` JSON 列 + 应用内余弦；与既有行为完全一致 |
| `qdrant` | `Langchain4jQdrantVectorStore` | 在线向量库；基于 langchain4j 的 `QdrantEmbeddingStore`（gRPC），ANN 检索 |
| `milvus` | `Langchain4jMilvusVectorStore` | 在线向量库；基于 langchain4j 的 `MilvusEmbeddingStore`，ANN 检索（集合首次写入自动创建） |
| `weaviate` | `Langchain4jWeaviateVectorStore` | 在线向量库；基于 langchain4j 的 `WeaviateEmbeddingStore`（类需显式创建；search 忽略服务端 filter，由基类客户端兜底候选过滤） |
| `pgvector` | `Langchain4jPgVectorVectorStore` | 在线向量库；基于 langchain4j 的 `PgVectorEmbeddingStore`（JDBC + Postgres 向量扩展，每张集合表首次写入自动建表） |
| `elasticsearch` | `Langchain4jElasticsearchVectorStore` | 在线向量库；基于 langchain4j 的 `ElasticsearchEmbeddingStore`（kNN 检索 + metadata 过滤；索引需显式创建，`vector` 映射为 `dense_vector`） |
| `redis` | `Langchain4jRedisVectorStore` | 在线向量库；基于 langchain4j 社区版 `langchain4j-community-redis` 的 `RedisEmbeddingStore`（Jedis；**需 Redis Stack**：RediSearch 向量字段 + RedisJSON） |

```yaml
askdata:
  vector-store:
    type: none                    # none | qdrant | milvus | weaviate | pgvector | elasticsearch | redis
    collection-prefix: askdata    # 集合名 = {prefix}-{object-type}
    qdrant:
      url: ${ASKDATA_QDRANT_URL:}         # REST 基址，如 http://qdrant:6333（仅用于健康检查）
      api-key: ${ASKDATA_QDRANT_API_KEY:} # 开启鉴权时必填
      grpc-port: ${ASKDATA_QDRANT_GRPC_PORT:6334}  # gRPC 端口，与 REST 相互独立
    milvus:
      host: ${ASKDATA_MILVUS_HOST:}       # Milvus gRPC 主机
      port: ${ASKDATA_MILVUS_PORT:19530}  # gRPC 端口
      token: ${ASKDATA_MILVUS_TOKEN:}     # 鉴权 token（API Key 或 user:password），开启鉴权时必填
      database: ${ASKDATA_MILVUS_DATABASE:default}
      health-url: ${ASKDATA_MILVUS_HEALTH_URL:}  # RESTful 健康检查基址，如 http://milvus:9091；留空则不探测
      metric-type: ${ASKDATA_MILVUS_METRIC_TYPE:COSINE}
      index-type: ${ASKDATA_MILVUS_INDEX_TYPE:AUTOINDEX}
      consistency-level: ${ASKDATA_MILVUS_CONSISTENCY:STRONG}
    weaviate:
      scheme: ${ASKDATA_WEAVIATE_SCHEME:http}   # http / https
      host: ${ASKDATA_WEAVIATE_HOST:}           # Weaviate 主机
      port: ${ASKDATA_WEAVIATE_PORT:8080}       # REST/gRPC 端口
      api-key: ${ASKDATA_WEAVIATE_API_KEY:}     # 开启鉴权时必填
      consistency-level: ${ASKDATA_WEAVIATE_CONSISTENCY:QUORUM}
    pgvector:
      host: ${ASKDATA_PGVECTOR_HOST:}         # Postgres 主机（需已装 vector 扩展）
      port: ${ASKDATA_PGVECTOR_PORT:5432}
      database: ${ASKDATA_PGVECTOR_DATABASE:}
      user: ${ASKDATA_PGVECTOR_USER:}
      password: ${ASKDATA_PGVECTOR_PASSWORD:}
      use-index: ${ASKDATA_PGVECTOR_USE_INDEX:false}
    elasticsearch:
      server-url: ${ASKDATA_ELASTICSEARCH_URL:}          # ES 基址，如 http://es:9200
      api-key: ${ASKDATA_ELASTICSEARCH_API_KEY:}         # 可选：ES8+ ApiKey
      username: ${ASKDATA_ELASTICSEARCH_USERNAME:}       # 可选：Basic 用户名
      password: ${ASKDATA_ELASTICSEARCH_PASSWORD:}       # 可选：Basic 口令
      refresh: ${ASKDATA_ELASTICSEARCH_REFRESH:WaitFor}  # 写入可见性：WaitFor（写后即查）/ True / False
    redis:
      host: ${ASKDATA_VECTOR_REDIS_HOST:}        # Redis 向量库主机（需 Redis Stack）
      port: ${ASKDATA_VECTOR_REDIS_PORT:6379}
      username: ${ASKDATA_VECTOR_REDIS_USERNAME:}
      password: ${ASKDATA_VECTOR_REDIS_PASSWORD:}
      ssl: ${ASKDATA_VECTOR_REDIS_SSL:false}
      database: ${ASKDATA_VECTOR_REDIS_DATABASE:0}
```

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

> 新增后端成本极低：所有 langchain4j 向量存储都实现同一个 `EmbeddingStore<TextSegment>` 接口，
> 共用逻辑已抽到 `AbstractLangchain4jVectorStore`（upsert / query / delete / 命中 id 解析 / 候选过滤），
> 子类只需提供客户端构造（`createStore`）、连通性探测（`health`）与可选的集合创建（`ensureCollection`）。

要点：

- **向量模型不随之后端变化**：仍由 `ai_model` 表中 `model_type=1` 的远程模型（OpenAI 兼容
  `/v1/embeddings`）优先，其次本地 ONNX `shibing624/text2vec-base-chinese`（768 维，
  `askdata.embedding.model-path`）；两者都不可用时召回降级为词法，主链路不报错。
- **集合维度取自向量本身**（`ensureCollection(collection, vector.length)`），自动匹配模型维度；
  中途更换不同维度的模型需重建集合。
- **REST 与 gRPC 是两个端口**：Qdrant 缺省 REST `6333` / gRPC `6334`。`url` 只用于健康检查，
  向量读写走 `grpc-port`；部署若把两者合并到同一端口，需同步改 `grpc-port`。
- 在线库启用时向量**只落在在线库**，不写 DB 向量列——DB 列宽度（`VARCHAR(10000)`，约 768 维）
  装不下高维远程模型（bge-m3 1024 维约 12.7K 字符），且在线库启用时该列本就不参与召回。
  相应地，召回候选不再以 `embedding IS NOT NULL` 过滤（见
  `EmbeddingService.candidateRequiresEmbeddingColumn()`）。
- 启动期回填：`type=none` 按 DB 向量列为空选取（既有行为）；在线库启用时全量选取——
  无法用 DB 列判断是否已回填，而 upsert 以记录 id 为 point id，重复写入即覆盖，保证幂等。
- Qdrant 不可达 / 集合缺失 / 连接失败均不抛异常，仅记日志并回落，问数主链路可用。
- 新增后端（如 Weaviate）只需实现 `VectorStore` 并在 `VectorStoreAutoConfiguration` 注册，
  不改动 `EmbeddingService` 与各召回调用点。
