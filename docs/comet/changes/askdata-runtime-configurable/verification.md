---
generated_from_state_version: 12
---

# 验证

## 当前结果

- 结果: **验收通过，需要你确认**
- 验证情况: **已完成检查，但需要你确认验证结果**
- 目标周期: 2
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-24T20:04:22.549Z
- 摘要: 独立只读验收：A1-A9 全部通过（Runtime 全量回归检查亦通过：681 tests / 0 failures / 0 errors / 5 skipped，exit 0，143.6s）。缓存后端三态可配、失效挂 Repository 写方法、Redis 真正可选（无 Redis 也能启动，框架 MCP 集群模式不激活）、Sa-Token 存储可选且显式注册给 SaManager、守卫锁后端可配含防抖、接口防抖 429+Retry-After、OpenAPI 元数据可配且 token 头同源、向量后端插件化且未改业务调用点。遗留风险集中在验证深度：Redis 相关分支无自动化测试、429 响应头无断言、缓存 Map 为共享可变实例。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1: askdata.cache.type=memory 时热点数据（用户信息 / API Key 信息 / 助手信息）走 Caffeine 进程内缓存，鉴权热路径不再每次查库；type=redis 时走 Redis 且多实例共享；type=none 时不缓存。 | config/CacheConfig.java:56-73 三分支（redis→RedisCacheManager / none→NoOpCacheManager / 缺省→CaffeineCacheManager）；common/cache/HotCacheService.java:44-62 四类热点 @Cacheable（用户/账号/API Key/助手）；鉴权热路径改走缓存：common/web/ApiKeyTokenAuthenticator.java:73,89 与 EmbeddedTokenAuthenticator.java:105,142,159,163；ConfigurableRuntimeOptionsTest 4/4 通过 |
| A2 | passed | brief.md | A2: 用户改密 / 停用 / 删除 / 切工作空间、API Key 停用或删除、助手配置或密钥变更后，对应缓存立即失效（失效挂在 Repository 写方法上），不会出现「已停用仍可鉴权」。 | 失效全部挂在 Repository 写方法：system/repository/UserRepository.java:146-199（updatePassword/updateStatus/deleteById/updateOid 等 @Caching 双 evict）、ApiKeyRepository.java:48,54,59（停用/删除 allEntries=true）、embedded/AssistantRepository.java:96,109,117,122,127,132；全仓无绕过 Repository 的 sys_user/sys_apikey 直写 SQL |
| A3 | passed | brief.md | A3: 未启用 Redis 时应用无需部署 Redis 也能启动（框架的 MCP Redis 集群模式不会因 spring-data-redis 位于 classpath 而被激活并急切连接 Redis）。 | resources/application.yaml:12-17 排除 RedisAutoConfiguration 与 cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate；config/RedisClientConfig.java:35 用 @ConditionalOnExpression 仅在启用 Redis 时建 bean；框架 McpAutoConfiguration$RedisClusterConfiguration 经 javap 确认为 @ConditionalOnBean(StringRedisTemplate)+@ConditionalOnClass，无模板即不激活；本机 6379 无 Redis 监听，McpFrameworkE2ETest（@SpringBootTest 全上下文 + 真实 MCP SSE 握手）通过 |
| A4 | passed | brief.md | A4: askdata.cache.sa-token-store=redis 时登录态存 Redis、多实例共享；缺省为进程内。 | config/CacheProperties.java resolveSaTokenStore 缺省 memory、留空跟随 type；config/SaTokenStoreInitializer.java:51 显式 SaManager.setSaTokenDao(dao) 注册 Redis DAO（框架自身不自动注册）；config/RedisClientConfig.java:89 提供 SaTokenDaoForRedisTemplate；ConfigurableRuntimeOptionsTest.cacheTypeAndSaTokenStoreAreNormalized 通过 |
| A5 | passed | brief.md | A5: 单 worker 启动守卫的锁后端可配置（db / redis），并支持防抖窗口：一次成功执行后，窗口内的后续启动不再重复执行同一任务。 | common/lock/WorkerLockConfig.java:36,44 互斥 @ConditionalOnProperty 选 db/redis；common/lock/DbWorkerLock.java:72-80 按 acquired_at 判防抖窗口，common/lock/RedisWorkerLock.java:67,83 用 done: 标记键；common/lock/SingleWorkerGuardLockTest 3/3 通过（窗口内跳过、窗口 0 可重入、锁故障退化为进程内守卫） |
| A6 | passed | brief.md | A6: 开启 askdata.debounce.enabled 后，同一「调用方 + 接口」在窗口内的重复请求返回 429 且带 Retry-After；缺省关闭时既有行为不变。 | common/debounce/RequestDebouncer.java:40,49 缺省 enabled=false 直接放行、启用后用 Cache.putIfAbsent 原子占位；common/web/RequestDebounceFilter.java:108-109 返回 429 并带 Retry-After，键为 method+path+调用方摘要（头摘要优先、回退 IP）；RequestDebouncerTest 4/4 通过 |
| A7 | passed | brief.md | A7: OpenAPI 文档的标题 / 版本 / 说明可配置，版本缺省取构建版本，文档中的 token 头名与 sa-token.token-name 一致。 | config/OpenApiConfig.java:41 token 头取 @Value(sa-token.token-name)（application.yaml:205 = X-SQLBOT-TOKEN），说明文案 {tokenHeader} 同步替换；OpenApiConfig.java:64 版本缺省取 OpenApiProperties.buildVersion()，读 askdata-build.properties（pom.xml:544-560 过滤注入，实测 target/classes = 0.3.0-SNAPSHOT）；OpenApiDocsEndpointTest 7/7 通过 |
| A8 | passed | brief.md | A8: 新增向量后端（milvus / weaviate / pgvector / elasticsearch / redis）只需实现 VectorStore 并在 VectorStoreAutoConfiguration 登记，不改动 EmbeddingService 与召回调用点。 | git diff 32a5662..3799d04 显示 ai/EmbeddingService.java 与 terminology/、datasource/、training/ 四类召回调用点零改动；新后端只在 ai/vector/VectorStoreAutoConfiguration.java:71-118 的 onlineStores() 登记，共用 AbstractLangchain4jVectorStore |
| A9 | passed | brief.md | A9: 全量回归通过（681 passed / 0 failed / 0 errors / 5 skipped），既有测试的断言语义无需修改。 | 新鲜 surefire 报告（112 个 XML）合计 681 tests / 0 failures / 0 errors / 5 skipped（676 passed + 5 skipped 为需外部服务的 live 集成测试）；既有测试仅 2 处构造器适配（ApiKeyTokenAuthenticatorTest、EmbeddedTokenAuthenticatorTest），无任何 assert 删除或放宽 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| 全量回归（zulu-17 + mvn test） | -pl askdata/askdata-server test -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false | . | passed | 0 | 143611 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- 全量回归（mvn -pl askdata/askdata-server test）: passed — 681 passed / 0 failed / 0 errors / 5 skipped
- 新增单测（缓存 / 锁 / 防抖 / OpenAPI / 向量后端）: passed — ConfigurableRuntimeOptionsTest 4、SingleWorkerGuardLockTest 3、RequestDebouncerTest 4、Langchain4jElasticsearchVectorStoreTest 3、AbstractLangchain4jVectorStoreClientFilterTest 2 全绿
- 无 Redis 启动验证（@SpringBootTest 全上下文）: passed — 本机无 Redis，全量回归中的 @SpringBootTest 上下文均正常加载，未出现 RedisConnectionException
- 已知限制: 三种 Redis 形态（cache.type=redis / sa-token-store=redis / lock.type=redis）未在真实 Redis 上端到端验证：单测覆盖了占位原子性、防抖标记与决策逻辑，真实 Redis 需另行部署验证
- 已知限制: milvus / weaviate / pgvector / elasticsearch / redis 五个向量后端未做真实环境端到端（各自需对应服务；Elasticsearch 需可建索引的实例，Redis 需 Redis Stack：RediSearch + RedisJSON），live 集成测试默认跳过
- 已知限制: 接口防抖的调用方识别依赖鉴权头摘要：主通道 Sa-Token 上下文在 DispatcherServlet 内建立，故主通道按 X-SQLBOT-TOKEN 请求头摘要区分调用方（未带该头时退回来源 IP）
- 已知限制: 响应信封改造（Result 继承框架 JsonResult）经用户要求在实现后追加，属独立关注点，不在本 change 的验收口径内，单独提交落库

## 阻塞项

- **user**: The generic Skill bridge cannot prove an independent Verifier execution; user confirmation is required before Archive. — next: `await-user`

## 风险与跳过的工作

- A2 覆盖粒度：UserRepository 的 updateProfile/updateLanguage 走 @Caching evict，但 USER_INFO_BY_ACCOUNT 为 allEntries=true 全清，高频写场景下缓存命中率会退化；AssistantRepository.insert 用 key=#result + beforeInvocation=false，语义正确但与其它方法风格不一致
- A5 Redis 锁后端无单测（需真实 Redis），仅 DB 后端有覆盖；RedisWorkerLock 在拿到锁时即写 done: 标记，任务中途失败仍会抑制窗口内重跑（DB 侧因事务回滚不刷新时间戳），两者语义存在差异
- A1 的 redis / none 分支无单测覆盖（无 Redis 实例），目前仅 resolveType() 归一化有单测
- A6 无 HTTP 层 429/Retry-After 断言：全仓无 429 字符串断言，仅 RequestDebouncerTest 覆盖核心逻辑，filter 的响应头与状态码未被验证
- A3 的排除清单与框架 jar 强耦合：框架 MCP 集群模式是否激活取决于其 @ConditionalOnBean(StringRedisTemplate)，框架升级若改变该条件需复核
- surefire 报告目录含回滚前的陈旧 ResultEnvelopeShapeTest 报告（对应源码已删），直接汇总会得到偏大的 685/679；本轮以新鲜报告为准（681/676+5）
- 缓存命中返回的是缓存内同一 Map 实例（Rows.lowerCaseKeys 产出的 LinkedHashMap），下游若就地修改会污染缓存；当前调用点均只读，风险存在但未触发

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | blocked | A1, A2, A3, A4, A5, A6, A7, A8, A9 | 独立只读验收对 A1-A9 的技术结论为全部通过（证据：缓存/锁/防抖/OpenAPI 均有对应单测；无 Redis 环境下 12 个全上下文 @SpringBootTest 全绿；六个向量后端接入未触及 EmbeddingService 与四类召回调用点；全量回归 681 passed / 0 failed / 0 errors / 5 skipped）。但本候选的 Runtime 检查是在工作区变更（响应信封改造提交）的窗口内执行的，候选输入指纹前后不一致，Runtime 明确判定本候选无法完整复核并拒绝采信刷新检查，因此本候选按 blocked 记录并退回 Build，以便在稳定工作区重新产出候选、重新执行检查后再完成验收。 | 2026-10-24T19:06:11.475Z |
| 1 | 1 | 1 | recovery | — | 用户决策：调整需求路径——回到 Shape 以生成与当前工作区一致的新提案（需求范围与已确认的 brief/spec 完全一致，未做修改） | 2026-10-24T19:21:42.001Z |
| 2 | 1 | 1 | pass | — | 独立只读验收：A1-A9 全部通过（Runtime 全量回归检查亦通过：681 tests / 0 failures / 0 errors / 5 skipped，exit 0，143.6s）。缓存后端三态可配、失效挂 Repository 写方法、Redis 真正可选（无 Redis 也能启动，框架 MCP 集群模式不激活）、Sa-Token 存储可选且显式注册给 SaManager、守卫锁后端可配含防抖、接口防抖 429+Retry-After、OpenAPI 元数据可配且 token 头同源、向量后端插件化且未改业务调用点。遗留风险集中在验证深度：Redis 相关分支无自动化测试、429 响应头无断言、缓存 Map 为共享可变实例。 | 2026-10-24T20:04:22.549Z |



## 结论

独立只读验收：A1-A9 全部通过（Runtime 全量回归检查亦通过：681 tests / 0 failures / 0 errors / 5 skipped，exit 0，143.6s）。缓存后端三态可配、失效挂 Repository 写方法、Redis 真正可选（无 Redis 也能启动，框架 MCP 集群模式不激活）、Sa-Token 存储可选且显式注册给 SaManager、守卫锁后端可配含防抖、接口防抖 429+Retry-After、OpenAPI 元数据可配且 token 头同源、向量后端插件化且未改业务调用点。遗留风险集中在验证深度：Redis 相关分支无自动化测试、429 响应头无断言、缓存 Map 为共享可变实例。
