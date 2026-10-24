# 目标

把「依赖部署形态」的部分做成可配置：askdata 既能在单机、无外部依赖的形态下直接跑，也能在多实例共享 Redis 的形态下用上分布式缓存与共享登录态；同时把在线向量库从单一 Qdrant 扩展为可插拔的六种后端。

- 向量后端：qdrant / milvus / weaviate / pgvector / elasticsearch / redis 六种在线后端；type=none 或在线库不可达时回落到「DB JSON 列 + 应用内余弦」。
- 缓存后端：askdata.cache.type = memory（Caffeine 进程内）/ redis / none。
- Sa-Token 会话存储：askdata.cache.sa-token-store = memory（缺省，进程内）/ redis（多实例共享登录态）。
- OpenAPI 文档元数据：askdata.openapi.{title,version,description} 可配置，version 缺省取构建版本；文档里的 token 头取 sa-token.token-name（不再硬编码）。
- 单 worker 启动守卫：askdata.lock.type = db（缺省，元数据库表行锁）/ redis，并支持防抖窗口 askdata.lock.debounce-seconds。
- 接口防抖：askdata.debounce.{enabled,window-ms,paths}，窗口内重复请求返回 429 + Retry-After。

# 范围

## 目录结构

### 新建
- src/main/java/com/xiongx9527/askdata/ai/vector/Langchain4jMilvusVectorStore.java、Langchain4jWeaviateVectorStore.java、Langchain4jPgVectorVectorStore.java、Langchain4jElasticsearchVectorStore.java、Langchain4jRedisVectorStore.java — 五个新增在线后端；各自只实现后端差异（客户端构造、集合/索引创建、id 约束），其余共用 AbstractLangchain4jVectorStore。
- src/main/java/com/xiongx9527/askdata/common/cache/CacheNames.java、HotCacheService.java — 热点数据缓存（用户信息 / API Key 信息 / 助手信息）与缓存名常量。
- src/main/java/com/xiongx9527/askdata/common/lock/WorkerLock.java、DbWorkerLock.java、RedisWorkerLock.java、WorkerLockConfig.java — 守卫锁后端抽象与两种实现。
- src/main/java/com/xiongx9527/askdata/common/debounce/DebounceProperties.java、RequestDebouncer.java — 接口防抖核心。
- src/main/java/com/xiongx9527/askdata/common/web/RequestDebounceFilter.java — 防抖过滤器。
- src/main/java/com/xiongx9527/askdata/config/CacheConfig.java、CacheProperties.java、RedisClientConfig.java、OpenApiProperties.java、SaTokenStoreInitializer.java — 缓存后端装配、Redis 客户端按需装配、OpenAPI 元数据、Sa-Token 会话存储选择。
- src/main/resources/askdata-build.properties — 构建版本（Maven 资源过滤注入 project.version）。
- 单测：Langchain4jElasticsearchVectorStoreTest、ConfigurableRuntimeOptionsTest、SingleWorkerGuardLockTest、RequestDebouncerTest、AbstractLangchain4jVectorStoreClientFilterTest；各后端默认跳过的 live 集成测试。

### 修改
- ai/vector/VectorStore.java、VectorStoreAutoConfiguration.java — 登记 5 个新后端 type（qdrant/milvus/weaviate/pgvector 之外新增 elasticsearch、redis）。
- ai/vector/AbstractLangchain4jVectorStore.java — 新增 toPointId 钩子（强制 UUID 的后端覆写）、客户端候选过滤兜底（服务端 filter 不可靠时）。
- common/util/Rows.java — 新增 lowerCaseKeys（缓存边界 key 归一化）。
- common/web/ApiKeyTokenAuthenticator.java、EmbeddedTokenAuthenticator.java — 鉴权热路径改走热点缓存。
- system/repository/UserRepository.java、ApiKeyRepository.java、embedded/AssistantRepository.java — 写方法挂 @CacheEvict（失效覆盖全部服务层变更路径）。
- config/OpenApiConfig.java — 标题/版本/说明可配置；token 头取 sa-token 配置。
- config/InfraToolchainConfig.java — 雪花 ID 生成器改为委托框架 UidGenerator（DB 化 worker id）。
- common/lock/SingleWorkerGuard.java — 委托 WorkerLock，支持防抖。
- resources/application.yaml — 新增 askdata.cache / askdata.lock / askdata.debounce / askdata.openapi、spring.data.redis、spring.autoconfigure.exclude。
- resources/schema.sql — 新增 WORKER_NODE 表。
- resources/messages*.properties（6 语种）— 新增 i18n_debounce.too_frequent。
- askdata-server/pom.xml — 新增 langchain4j-community-redis、spring-boot-starter-cache、spring-boot-starter-data-redis、caffeine、sa-token-redis-template。
- askdata-server/README.md — 向量后端与缓存/锁/防抖配置说明。

### 删除
无。

### 明确不建
- 多级缓存（Redis 模式不再叠加本地缓存）与缓存预热面板。
- 分布式限流（令牌桶/漏桶）与配额管理 —— 本次只做「同一调用方 + 接口在窗口内一次」的防抖。
- 缓存指标的监控看板。

# 非目标

- 不替换 embedding 模型本身（远程 / 本地 ONNX 仍负责产出向量）。
- 不做多向量库同时写入（仅单一 type 生效）。
- 不改词法召回路径（无向量模型时的既有行为保持不变）。
- 不改鉴权语义：只在读路径加缓存，失效点挂在 Repository 写方法上。
- 不把 Redis 变成必选依赖：未启用 Redis 时应用可无 Redis 启动。

# 验收示例

- A1: askdata.cache.type=memory 时热点数据（用户信息 / API Key 信息 / 助手信息）走 Caffeine 进程内缓存，鉴权热路径不再每次查库；type=redis 时走 Redis 且多实例共享；type=none 时不缓存。
- A2: 用户改密 / 停用 / 删除 / 切工作空间、API Key 停用或删除、助手配置或密钥变更后，对应缓存立即失效（失效挂在 Repository 写方法上），不会出现「已停用仍可鉴权」。
- A3: 未启用 Redis 时应用无需部署 Redis 也能启动（框架的 MCP Redis 集群模式不会因 spring-data-redis 位于 classpath 而被激活并急切连接 Redis）。
- A4: askdata.cache.sa-token-store=redis 时登录态存 Redis、多实例共享；缺省为进程内。
- A5: 单 worker 启动守卫的锁后端可配置（db / redis），并支持防抖窗口：一次成功执行后，窗口内的后续启动不再重复执行同一任务。
- A6: 开启 askdata.debounce.enabled 后，同一「调用方 + 接口」在窗口内的重复请求返回 429 且带 Retry-After；缺省关闭时既有行为不变。
- A7: OpenAPI 文档的标题 / 版本 / 说明可配置，版本缺省取构建版本，文档中的 token 头名与 sa-token.token-name 一致。
- A8: 新增向量后端（milvus / weaviate / pgvector / elasticsearch / redis）只需实现 VectorStore 并在 VectorStoreAutoConfiguration 登记，不改动 EmbeddingService 与召回调用点。
- A9: 全量回归通过（681 passed / 0 failed / 0 errors / 5 skipped），既有测试的断言语义无需修改。
