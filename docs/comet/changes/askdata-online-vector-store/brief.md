# Outcome

为 askdata 引入可插拔的在线向量库后端，替代当前「数据库 embedding JSON 列 + 应用内余弦」的向量存储与召回方式，作为术语 / SQL 示例 / 表 / 数据源四类对象的统一向量存储与 ANN 检索后端；先落地 Qdrant 实现，并为 Weaviate / Milvus 预留抽象扩展点。保留「无可用向量库 / 无向量模型」时的既有降级行为，问数主链路不报错。

# Scope

## 目录结构

### 新建
- src/main/java/com/xiongx9527/askdata/ai/vector/VectorStore.java — 向量存储抽象接口（ensureCollection / upsert / query / delete / health）。
- src/main/java/com/xiongx9527/askdata/ai/vector/Langchain4jQdrantVectorStore.java — Qdrant 实现，复用 langchain4j 的 QdrantEmbeddingStore（gRPC），不重复造轮子；含集合管理、向量写入、ANN 查询、候选过滤。
- src/main/java/com/xiongx9527/askdata/ai/vector/DbVectorStore.java — 兼容回退实现，沿用现有 DB JSON 列 + 应用内余弦（无在线库时启用，保留现有行为）。
- src/main/java/com/xiongx9527/askdata/ai/vector/VectorStoreAutoConfiguration.java — 按 askdata.vector-store.type 装配对应实现；type=none 时不装配在线库。
- src/test/java/com/xiongx9527/askdata/ai/vector/ — VectorStore 抽象契约（DbVectorStore）与 EmbeddingService 路由（记录型替身）测试。
- src/test/java/com/xiongx9527/askdata/chat/service/ChatModelClientEmbeddingResponseTest.java — embedding 大响应体不被截断的回归测试。

### 修改
- ai/EmbeddingService.java — 注入 VectorStore（setter 注入，构造期默认 DbVectorStore，保持既有测试构造不变）；writeEmbedding / recallByVector / vectorScores 委托给 VectorStore；新增 candidateRequiresEmbeddingColumn() / isVectorStoreOnline()。
- common/startup/EmbeddingBackfillRunner.java — 回填经 writeEmbedding 统一路由到当前后端；补充后端与集合前缀的启动日志（回填开关语义不变）。
- datasource/embedding/DatasourceEmbeddingService.java、TableEmbeddingService.java、terminology/TerminologyRecallService.java、training/TrainingRecallService.java — 召回经 EmbeddingService 统一路由，四类对象均走统一入口；候选 SQL 的 embedding IS NOT NULL 门槛改为仅 DB 回退路径生效。
- chat/service/ChatModelClient.java — 新增 readAllFully()；embedding 业务响应体改为全量读取（旧 readAll 截断到 800 字符，导致 1024 维向量 JSON 解析失败）。
- terminology/TerminologyRecallService.java — 修复 saveEmbeddings 的 JDBC 参数绑定（ids.toArray() 传两次只算 2 个 varargs 参数，导致术语向量写入一直静默失败）。
- resources/application.yaml — 新增 askdata.vector-store.{type, qdrant.url, qdrant.api-key, qdrant.grpc-port, collection-prefix}；保留 embedding.model-path 与 backfill-enabled。
- askdata-server/README.md — 新增「向量存储（可插拔后端）」章节。
- askdata/askdata-server/pom.xml — 新增 dev.langchain4j:langchain4j-qdrant:1.22.0-beta32。

### 删除
无（保留现有 DB embedding 列与 DbVectorStore 作为回退，不删列，避免破坏既有数据与回放）。

### 明确不建
- 独立向量管理后台 / 手动索引页面 — 超出本次范围。
- 多向量库同时写入 — 仅单一 type 生效，预留抽象不等于同时多后端。

# Non-goals

- 不替换 embedding 模型本身（远程 / 本地 ONNX 仍负责产出向量）。
- 不引入 pgvector 等关系型向量算子；向量检索全部交由在线向量库。
- 不在这次接入 Weaviate / Milvus 具体实现，仅预留抽象与配置位。
- 不改变词法召回（无向量模型时）的既有行为。

# Acceptance examples

- A1: 配置 askdata.vector-store.type=qdrant 且 Qdrant 可用时，四类对象的向量写入进入 Qdrant 集合，问数 / 术语召回走 ANN 查询而非应用内余弦。
- A2: 配置 type=none 或 Qdrant 不可达时，自动回落到 DB JSON 列 + 应用内余弦（现有行为），问数主链路不报错。
- A3: 无可用 embedding 模型时仍回落词法召回（现有行为保持不变）。
- A4: 启动期回填把向量 upsert 到当前后端（在线库或 DB 列），启动日志标明所用后端与集合前缀；无可用 embedding 模型时跳过，问数主链路不报错。
- A5: Qdrant 连接失败 / 集合缺失时，EmbeddingService 不抛异常，仅记日志并回落，问数主链路可用。
- A6: 新增向量库后端（如 Weaviate）只需实现 VectorStore 并在 VectorStoreAutoConfiguration 注册，不改动 EmbeddingService 与召回调用点。

# Decisions

- 在线库启用（type != none 且 health 通过）时，四类对象的向量读写完全由 VectorStore 承担，DB embedding 列不再参与召回；保留列与 DbVectorStore 实现作为 type=none 时的回退，不删除列以免破坏既有数据。
- 向量仍由现有 embedding 模型（远程 / 本地 ONNX）产出，VectorStore 只负责存储与检索。
- 集合命名：{collection-prefix}-{object-type}（terminology / data_training / core_table / core_datasource）；point id 取记录 id（数值串），payload 存 row_id 用于候选过滤。
- 启动回填经 writeEmbedding 路由到当前后端；保留 backfill-enabled 开关语义（集群内 SingleWorkerGuard 单次执行）。
- Qdrant 的 REST（缺省 6333，仅健康检查）与 gRPC（缺省 6334，向量读写）是两个独立端口，分别由 qdrant.url 与 qdrant.grpc-port 配置。
- 在线库启用时 DB 列仅在向量序列化后放得下（≤ 9000 字符）时镜像写入，用于「切回 type=none 免重填」；放不下（如 1024 维）则跳过，召回候选不再以 embedding IS NOT NULL 过滤。

# Verification expectations

- 单测覆盖 DbVectorStore 契约、EmbeddingService 路由（记录型替身）与 embedding 大响应体不截断。
- 现有 TableEmbeddingServiceTest 与召回相关测试在 type=none 回落路径下保持通过。
- 全量回归：665 passed / 0 failed / 5 skipped（基线 649 + 新增 16；真实环境 live 测试由 -Dqdrant.live=true 显式开启）。
- 真实环境端到端已验证：Qdrant（192.168.1.100，REST 6333 / gRPC 6334，API Key 鉴权）+ TEI bge-m3（1024 维），覆盖集合创建、向量 upsert（同 id 覆盖）、候选限定 ANN 查询、删除，以及 EmbeddingService → TerminologyRecallService 全链路。
## Build 期偏差与真实环境验证记录（2026-10-24）

以下为 Build 实施期经真实环境验证后对原设计的修正，均不改变 Outcome 与验收意图：

1. **Qdrant 走 langchain4j 封装，不自造 REST 客户端**：`Langchain4jQdrantVectorStore` 基于
   langchain4j `QdrantEmbeddingStore`（`langchain4j-qdrant:1.22.0-beta32`，与项目 langchain4j 1.22.0 对齐）；
   仅集合创建（在线库封装不暴露该能力）与健康检查分别用 `io.qdrant:client` 与 REST `/collections`。
2. **REST 与 gRPC 端口分离**：Qdrant 缺省 REST 6333 / gRPC 6334。`url` 仅用于健康检查，
   向量读写走 `askdata.vector-store.qdrant.grpc-port`（缺省 6334）。
3. **不镜像 DB 向量列**：在线库启用时向量只写在线库。DB `embedding VARCHAR(10000)` 装不下
   高维远程模型（bge-m3 1024 维约 12.7K 字符）；且在线库启用时该列不参与召回。
   相应地 `EmbeddingService.candidateRequiresEmbeddingColumn()` 为 false 时，各召回路径不再以
   `embedding IS NOT NULL` 过滤候选。
4. **启动回填选取口径**：`type=none` 仍按 DB 向量列为空选取（既有行为）；在线库启用时全量选取，
   依赖 upsert 以记录 id 为 point id 的幂等性。
5. **修复两个既有阻塞缺陷**（非本次引入，但不修则 A1/A4 对术语与表不成立）：
   - `TerminologyRecallService.saveEmbeddings` 用 `queryForList(sql, ids.toArray(), ids.toArray())`，
     varargs 只包装成 2 个参数而 SQL 需 2N 个占位符，术语向量从未写成功；改为压平单个数组。
   - `EmbeddingBackfillRunner.backfillTables` 以 `core_table.embedding IS NULL` 选取，
     但写入的是 `table_embedding`，永不收敛；改为与写入列一致。

**真实环境验证**（Qdrant 192.168.1.100:6333 / gRPC 6334 + TEI bge-m3 9528）：
集合按维度自动创建、ANN 语义排序、候选 id 过滤、同 id upsert 覆盖、删除、
`EmbeddingService` 到 `TerminologyRecallService` 全链路均符合预期；
`QdrantLiveIntegrationTest` 以 `-Dqdrant.live=true` 可复跑，缺省跳过。

**回归**：`mvn -pl askdata/askdata-server -am clean test` = 665 passed / 0 failed / 5 skipped。
