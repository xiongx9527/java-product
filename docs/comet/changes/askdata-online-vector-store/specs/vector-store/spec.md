# Vector Store 能力规格（askdata-online-vector-store）

## 概述

askdata 通过统一的 VectorStore 抽象管理术语、SQL 示例、表、数据源四类对象的向量存储与相似度召回。向量由现有 embedding 模型（远程 / 本地 ONNX）产出；VectorStore 仅负责持久化与最近邻检索。在线向量库启用时作为唯一存储后端，替代现有 DB embedding JSON 列加应用内余弦；未启用时回落既有路径。

## 配置

- askdata.vector-store.type：none（默认）| qdrant | milvus
- askdata.vector-store.qdrant.url：Qdrant REST 基址，如 http://qdrant:6333（仅用于健康检查）
- askdata.vector-store.qdrant.api-key：可选
- askdata.vector-store.qdrant.grpc-port：gRPC 端口，缺省 6334（与 REST 相互独立；向量读写走此端口）
- askdata.vector-store.milvus.host / port：Milvus gRPC 地址（缺省 19530）；token / database / health-url / metric-type / index-type / consistency-level 可选
- askdata.vector-store.collection-prefix：集合名前缀，缺省 askdata
- 保留 askdata.embedding.model-path 与 askdata.embedding.backfill-enabled

## 抽象契约（VectorStore）

- ensureCollection(name, dimension)：集合不存在则创建（cosine 距离，维度与向量一致）。
- upsert(collection, id, vector, payload)：写入 / 更新单条向量与载荷。
- query(collection, vector, topK, filter)：返回按相似度降序的命中（id + score）。
- delete(collection, id)：删除单条。
- health()：连通性探测；不可用时返回 false。

## 行为

- 当 type 不等于 none 且 health 通过：EmbeddingService.writeEmbedding / recallByVector 委托给在线 VectorStore；DB embedding 列不再参与召回。
- 当 type 等于 none 或在线库不可达：回落 DbVectorStore（DB JSON 列 + 应用内余弦），保持现有行为；不抛异常。
- 无可用 embedding 模型：向量为 null，召回回落词法（现有行为）。
- 集合命名：{collection-prefix}-{object-type}；object-type 属于 terminology、data_training、core_table、core_datasource；payload 仅存 id 与必要过滤字段。

## 启动回填

- EmbeddingBackfillRunner：当 embedding 模型可用且 backfill-enabled 开启，把四类对象的向量 upsert 到当前后端（受 SingleWorkerGuard 控制，集群单次执行）。
- type 等于 none：按 DB 向量列为空筛选待回填记录（写库后自然收敛）。
- type 不等于 none：DB 向量列不再写入，无法据此判断是否已回填，故全量选取；upsert 以记录 id 为 point id，重复写入即覆盖，保证幂等。

## 场景

### 场景：Qdrant 启用下的写入与 ANN 召回
Acceptance: A1
给定 type=qdrant 且 Qdrant 可用，写入术语、表、数据源、SQL 示例向量后，问数与术语召回走 Qdrant ANN 查询而非应用内余弦。

### 场景：无在线库时回落
Acceptance: A2
type=none 或 Qdrant 不可达时，自动回落 DB JSON 列加应用内余弦，主链路不报错。

### 场景：无 embedding 模型回落词法
Acceptance: A3
无可用 embedding 模型时仍回落词法召回（现有行为保持不变）。

### 场景：启动期回填
Acceptance: A4
启动回填把向量 upsert 到当前后端；无可用 embedding 模型时跳过；启动日志标明所用后端与集合前缀。

### 场景：在线库故障不阻断主链路
Acceptance: A5
Qdrant 连接失败或集合缺失时，EmbeddingService 仅记日志并回落，问数主链路可用。

### 场景：新增后端只需实现抽象
Acceptance: A6
新增向量库后端（如 Weaviate / Milvus）只需实现 VectorStore 并在 VectorStoreAutoConfiguration 注册，不改动 EmbeddingService 与召回调用点。

## 后端扩展点（A6 落点，2026-10-24 补充）

VectorStore 抽象面向 langchain4j 的 EmbeddingStore<TextSegment> 设计，所有 langchain4j 官方向量存储后端都可按同一模式接入。共用逻辑已抽到 AbstractLangchain4jVectorStore（upsert / query / delete / 命中 id 解析 / 候选 row_id 过滤），子类只需提供：

- createStore(collection, dimension)：构造底层存储（各后端客户端不同）；
- health()：连通性探测（各后端端口/协议不同）；
- 可选 ensureCollection：需要显式建集合的后端（Qdrant）覆写，其余依赖首次写入自动创建。

| 后端 | 状态 | langchain4j 模块 | 接入注意 |
| --- | --- | --- | --- |
| Qdrant | 已实现 | langchain4j-qdrant | REST 6333 / gRPC 6334 分离；point id 接受数值串 |
| Milvus | 已实现 | langchain4j-milvus | 有 builder()；集合首次写入自动创建（VarChar id + JSON metadata）；id 字段为 VarChar，数值串可用 |
| Weaviate | 已实现 | langchain4j-weaviate | 有 builder()；ensureCollection 显式建类（vectorizer=none，声明 text/row_id）；id 强制 UUID（由记录 id 派生），row_id 存 metadata；search 忽略服务端 filter，由基类客户端兜底候选过滤；类名须大写开头故 collectionFor 做了清洗 |
| pgvector | 已实现 | langchain4j-pgvector | JDBC + Postgres 向量扩展；每张集合表首次写入自动建表（createTable=true）；id 强制 UUID，row_id 存 JSON 列；服务端 filter 经 metadata handler 生效；注意需 Postgres 已装 vector 扩展 |
| Elasticsearch | 已实现 | langchain4j-elasticsearch | 有 builder()；store 不自动建索引且 dimension 参数已废弃，故 ensureCollection/首次 upsert 用 REST 显式建索引（vector 映射为 dense_vector+cosine，metadata.row_id 建 text+keyword 子字段以命中元数据过滤器）；索引名须小写故 collectionFor 做了清洗；写入默认 refresh=WaitFor 保证写后即查 |
| OpenSearch | 预留 | 对应模块 | 需先建索引与 mapping |
| Chroma / Pinecone / Oracle / Azure AI Search | 预留 | 对应模块 | 云托管为主 |

接入步骤（以新后端为例）：新增依赖 → 新增 Langchain4jXxxVectorStore extends AbstractLangchain4jVectorStore（仅客户端构造与建集合不同）→ 在 VectorStoreAutoConfiguration.onlineStores() 登记一行。EmbeddingService 与四类对象的召回调用点无需任何改动。

注：redis / neo4j / infinispan / cassandra / couchbase / vearch 本地仓库缺 1.22.0 版本，离线构建暂不可用。
