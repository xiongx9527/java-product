# Vector Store 能力规格（askdata 在线向量库）

## 概述

askdata 通过统一的 VectorStore 抽象管理术语、SQL 示例、表、数据源四类对象的向量持久化与最近邻召回。
向量由在线 embedding 模型（`ai_model.model_type=1` 且 `enable=1`、`valid=1`）产出；VectorStore 只负责
持久化与检索，不关心向量如何生成。

**在线向量库是唯一存储**：向量不落 DB 的 `embedding` / `table_embedding` JSON 列，也不存在「在线库不可达
就退回应用内余弦」的回落路径。同一份向量只能有一个真相，否则切换维度或迁移集合时会静默漏召回。

## 配置

- `askdata.vector-store.type`：后端类型，缺省 `qdrant`（当前唯一实现）。
- `askdata.vector-store.collection-prefix`：集合名前缀，缺省 `askdata`；集合名 = `{prefix}-{object-type}`。
- `askdata.vector-store.qdrant.url`：Qdrant REST 基址，如 `http://qdrant:6333`；必填。
- `askdata.vector-store.qdrant.api-key`：可选，Qdrant 开启鉴权时必填。
- `askdata.embedding.backfill-enabled`：启动期回填开关，缺省开启。
- 已移除：`askdata.vector-store.qdrant.grpc-port`、`askdata.vector-store.milvus.*` 等其它后端配置，
  以及 `askdata.embedding.model-path`（本地 ONNX 模型已删除）。

## 抽象契约（VectorStore）

- `collectionPrefix()` / `collectionFor(objectType)`：集合名前缀与命名。
- `ensureCollection(collection, dimension)`：集合不存在则创建（cosine 距离）；已存在但维度不同则**抛错**，
  不删除集合。写入路径绝不隐式删数据。
- `dropCollection(collection)`：删除集合（不存在视为成功）。**只允许显式重建调用**。
- `upsert(collection, id, vector)`：写入 / 更新单条向量，id 即 point id，重复写入覆盖。
- `query(collection, vector, topK, candidateIds)`：按相似度降序返回 id + score；`candidateIds` 非空时服务端过滤。
- `delete(collection, id)`：删除单条。
- `health()`：连通性探测，不可用返回 false。

## 行为

- 写入：`EmbeddingService.writeEmbedding` 只调用在线 VectorStore；不写 DB 向量列。
- 召回：`recallByVector` / `vectorScores` 只查在线库；候选行不再以 `embedding IS NOT NULL` 过滤。
- 失败：在线库不可达时写入返回 false、召回返回空表，调用方降级为词法召回并记日志；**不抛到主链路，
  也不写本地**。
- 维度不一致：`ensureCollection` 抛错，`writeEmbedding` 返回 false 并记 warn；既有集合与向量保持不动。
  `EmbeddingService` 会记住「该集合 + 该维度」已知损坏并短路后续写入（回填上千行不刷屏），
  只有显式重建才清除该状态。
- 显式重建：`EmbeddingIndexService.rebuild` 先调用 `EmbeddingService.resetCollections` 删除四类集合
  （全量重算，旧 point 一律作废），再逐工作空间回填；维度切换后无需人工删集合。
- 无可用 embedding 模型：`embed` 返回 null，问答入口按统一错误阻断（见模型配置规格）。
- 模型解析缓存：`ChatModelRouter.embeddingModel()` 结果缓存 60s；缓存键含 `AiModelRepository` 的写版本号，
  任何经仓储的模型写操作（增删改、设默认、连通校验回写）都会让版本自增、缓存立即失配，保证「保存即生效」。
- 未配置 `qdrant.url`：装配占位实现，应用可启动，但任何向量读写抛「未配置在线向量存储」异常。
- 配置预留后端（milvus / weaviate / pgvector / elasticsearch / redis）或 `none`/`db`：启动期显式失败并给出提示，
  不静默降级。

## 启动回填

- `EmbeddingBackfillRunner` 在 embedding 模型可用且 `backfill-enabled` 开启时，对四类对象按范围全量重算，
  受 `SingleWorkerGuard` 控制（集群内单次执行）。
- 无法用 DB 列判断「是否已回填」，因此不做空值筛选；upsert 以记录 id 为 point id，重复写入即覆盖，保证幂等。
- 重建任务按工作空间逐个执行，单空间失败只记录原因并继续下一个空间。

## 场景

### 场景：写入与 ANN 召回
给定 `type=qdrant` 且 Qdrant 可用，写入术语、表、数据源、SQL 示例向量后，问数与术语召回走 Qdrant ANN 查询。

### 场景：在线库不可达
给定 Qdrant 不可达，写入返回 false、召回返回空表，调用方降级为词法召回，日志可见原因，DB 不新增向量。

### 场景：未配置 url
给定 `qdrant.url` 为空，应用可启动；任何向量读写抛「未配置在线向量存储」，不写本地。

### 场景：维度误配
给定集合已按 768 维建立，写入 1024 维向量：写入返回 false 并记 error/warn，集合与既有向量保持不变；
执行向量索引重建后集合按 1024 维重建并全量回填。

### 场景：配置预留后端
给定 `type=milvus`（或其它未实现取值），启动期抛错并提示「尚未实现；当前仅支持 qdrant」。

### 场景：启动期回填
给定 embedding 模型可用，启动回填把四类对象向量全量 upsert；无可用模型时跳过并记日志。

### 场景：新增后端只需实现抽象
新增在线后端只需实现 VectorStore 并在 VectorStoreAutoConfiguration 加一个分支，不改动 EmbeddingService
与各召回调用点。

## 后端扩展点

| 后端 | 状态 | 说明 |
| --- | --- | --- |
| Qdrant | 已实现 | REST（`PUT /collections/{c}`、`DELETE /collections/{c}`、`PUT /collections/{c}/points`、`POST /collections/{c}/points/delete`、`POST /collections/{c}/points/search`）；HTTP 客户端为 Spring `RestTemplate`（超时、错误流、JSON 编解码由框架处理），不依赖 langchain4j |
| Milvus / Weaviate / pgvector / Elasticsearch / Redis | 预留 | 配置后启动失败并提示尚未实现；实现时按同一 VectorStore 契约接入 |

接入步骤：实现 `VectorStore`（ensureCollection / dropCollection / upsert / delete / query / health）→ 在
`VectorStoreAutoConfiguration.vectorStore()` 增加一个 `type` 分支 → 补协议级测试。
EmbeddingService 与四类对象的召回调用点无需改动。

## 验证（真实环境，不用桩）

| 用例 | 依赖 | 覆盖 |
| --- | --- | --- |
| `QdrantVectorStoreTest` | 真实 Qdrant | 建集合幂等、写入、`has_id` 过滤、删除、维度拒绝、删集合重建 |
| `EmbeddingDimensionChangeTest` | 真实 Qdrant + 真实 MySQL | 维度不一致拒写且保数据、重建后可换维度、非法入参不触达后端 |
| `EmbeddingServiceVectorStoreRoutingTest` | 真实 Qdrant + 真实 MySQL + 真实 bge-m3 | 写入落正确集合、真实 ANN 召回与排序、无模型降级、`vectorScores` |

依赖不可达时整类 assume-skip（不伪造结果），运行前设置：

```bash
export ASKDATA_QDRANT_URL=http://127.0.0.1:6333
export ASKDATA_QDRANT_API_KEY=...
export ASKDATA_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:3307
export ASKDATA_IT_MYSQL_USER=root
export ASKDATA_IT_MYSQL_PASSWORD=...
export ASKDATA_IT_EMBEDDING_URL=http://127.0.0.1:9528
export ASKDATA_IT_EMBEDDING_MODEL=bge-m3
```

MySQL 用例每次运行创建独立 schema（`askdata_it_*`）并在结束时删除，不污染既有库。

## 验收

- 验收：A8
