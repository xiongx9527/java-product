---
generated_from_state_version: 20
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 2
- 迭代: 2
- 验证器尝试次数: 1
- 完成时间: 2026-10-25T05:30:12.384Z
- 摘要: 独立只读验收：A1-A9 全部通过（Runtime 全量回归检查 exit 0：685 tests / 0 failures / 0 errors / 5 skipped）。缓存后端三态可配、失效挂 Repository 写方法、Redis 真正可选（无 Redis 也能启动，框架 MCP 集群模式不激活）、Sa-Token 存储可选且显式注册给 SaManager、守卫锁后端可配含防抖、接口防抖 429+Retry-After、OpenAPI 元数据可配且 token 头同源、向量后端插件化且未改业务调用点；响应信封 Result 继承框架 JsonResult 仍保持 code/data/msg 三键契约、ok(String) 保持 data 语义、ResponseEnvelopeAdvice 仍识别 Result。遗留风险集中在验证深度：Redis 相关分支无自动化测试、429 响应头无断言、缓存 Map 为共享可变实例。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1: askdata.cache.type=memory 时热点数据（用户信息 / API Key 信息 / 助手信息）走 Caffeine 进程内缓存，鉴权热路径不再每次查库；type=redis 时走 Redis 且多实例共享；type=none 时不缓存。 | config/CacheConfig.java:54-84 三分支（redis→RedisCacheManager / none→NoOpCacheManager / 缺省→CaffeineCacheManager）；common/cache/HotCacheService.java 四类热点 @Cacheable（用户/账号/API Key/助手）；鉴权热路径改走缓存：common/web/ApiKeyTokenAuthenticator.java（apiKeyByAccessKey/userById）与 EmbeddedTokenAuthenticator.java（assistantById/userByAccount/userById）；ConfigurableRuntimeOptionsTest 4/0/0 |
| A2 | passed | brief.md | A2: 用户改密 / 停用 / 删除 / 切工作空间、API Key 停用或删除、助手配置或密钥变更后，对应缓存立即失效（失效挂在 Repository 写方法上），不会出现「已停用仍可鉴权」。 | 失效全部挂在 Repository 写方法：system/repository/UserRepository.java（updatePassword/updateStatus/deleteById/updateOid/updateProfile/updateLanguage 等共 7 方法 × @Caching 双 evict）、ApiKeyRepository.java:48,54,59（停用/删除 allEntries=true）、embedded/AssistantRepository.java:96,109,117,122,127,132；全仓无绕过 Repository 的 sys_user/sys_apikey/sys_assistant 直写 SQL |
| A3 | passed | brief.md | A3: 未启用 Redis 时应用无需部署 Redis 也能启动（框架的 MCP Redis 集群模式不会因 spring-data-redis 位于 classpath 而被激活并急切连接 Redis）。 | resources/application.yaml:12-17 排除 RedisAutoConfiguration 与 cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate；config/RedisClientConfig.java 用 @ConditionalOnExpression 仅在启用 Redis 时建 bean（无 bean 即不激活 mcpRedisMessageListenerContainer，框架 McpAutoConfiguration$RedisClusterConfiguration 经 javap 确认为 @ConditionalOnBean(StringRedisTemplate)+@ConditionalOnClass）；本机 6379 无 Redis，全量回归中的 @SpringBootTest 上下文均正常加载 |
| A4 | passed | brief.md | A4: askdata.cache.sa-token-store=redis 时登录态存 Redis、多实例共享；缺省为进程内。 | config/SaTokenStoreInitializer.java:39-52：sa-token-store=redis 时取 SaTokenDaoForRedisTemplate 并显式 SaManager.setSaTokenDao(dao)（框架自身不自动注册给 SaManager，该静态方法经 javap 确认存在）；缺省 memory 不触碰 SaManager，沿用进程内 SaTokenDaoDefaultImpl |
| A5 | passed | brief.md | A5: 单 worker 启动守卫的锁后端可配置（db / redis），并支持防抖窗口：一次成功执行后，窗口内的后续启动不再重复执行同一任务。 | common/lock/WorkerLockConfig.java 按 askdata.lock.type 选择 db（matchIfMissing）/redis，互斥 @ConditionalOnProperty；DbWorkerLock.java 与 RedisWorkerLock.java 实现 isDebounced（复用 acquired_at / done 标记）按 askdata.lock.debounce-seconds（缺省 300）判防抖窗口；SingleWorkerGuardLockTest 3/0/0 |
| A6 | passed | brief.md | A6: 开启 askdata.debounce.enabled 后，同一「调用方 + 接口」在窗口内的重复请求返回 429 且带 Retry-After；缺省关闭时既有行为不变。 | common/debounce/DebounceProperties.java:42 enabled 缺省 false（allow 直接放行）；RequestDebouncer 启用后 Cache.putIfAbsent 原子占位、缓存异常放行；RequestDebounceFilter 同「调用方+接口」窗口内返回 429 + Retry-After（键为 method+path+调用方摘要，头摘要优先、回退 IP）；RequestDebouncerTest 4/0/0 |
| A7 | passed | brief.md | A7: OpenAPI 文档的标题 / 版本 / 说明可配置，版本缺省取构建版本，文档中的 token 头名与 sa-token.token-name 一致。 | config/OpenApiConfig.java 取 properties.getTitle/resolveVersion/resolveDescription，version 缺省取 OpenApiProperties.buildVersion()（读 askdata-build.properties，Maven 过滤注入 project.version，实测 target/classes = 0.3.0-SNAPSHOT）；security scheme name=tokenHeader 来自 @Value(${sa-token.token-name})，与 application.yaml 同源；OpenApiDocsEndpointTest 7/0/0 |
| A8 | passed | brief.md | A8: 新增向量后端（milvus / weaviate / pgvector / elasticsearch / redis）只需实现 VectorStore 并在 VectorStoreAutoConfiguration 登记，不改动 EmbeddingService 与召回调用点。 | 六个后端均在 ai/vector/VectorStoreAutoConfiguration.java:73-118 的 onlineStores() 登记；各后端只实现客户端构造与建集合（extends AbstractLangchain4jVectorStore，共用 upsert/query/delete 与命中 id 解析）；git show --stat 证实新增后端提交未触及 ai/EmbeddingService.java 与四类召回调用点 |
| A9 | passed | brief.md | A9: 全量回归通过（681 passed / 0 failed / 0 errors / 5 skipped），既有测试的断言语义无需修改。 | target/surefire-reports 实测 113 份报告、685 tests / 0 failures / 0 errors / 5 skipped（680 passed；5 skipped 为需外部服务的 live 集成测试）；既有测试仅 2 处构造器适配（ApiKeyTokenAuthenticatorTest、EmbeddedTokenAuthenticatorTest 注入 HotCacheService）+ 新增信封形状用例，无任何 assert 删除或放宽；独立 Explore 验收确认全部通过 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| 全量回归（zulu-17 + mvn test） | -pl askdata/askdata-server test -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false | . | passed | 0 | 141205 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- 全量回归（mvn -pl askdata/askdata-server test）: passed — 685 passed / 0 failed / 0 errors / 5 skipped
- 新增单测（缓存 / 锁 / 防抖 / OpenAPI / 信封 / 向量后端）: passed — ConfigurableRuntimeOptionsTest 4、SingleWorkerGuardLockTest 3、RequestDebouncerTest 4、ResultEnvelopeShapeTest 4、Langchain4jElasticsearchVectorStoreTest 3、AbstractLangchain4jVectorStoreClientFilterTest 2 全绿
- 无 Redis 启动验证（@SpringBootTest 全上下文）: passed — 本机无 Redis，全量回归中的 @SpringBootTest 上下文均正常加载，未出现 RedisConnectionException
- 已知限制: A2 覆盖粒度：USER_INFO_BY_ACCOUNT 为 allEntries=true 全清，高频写场景缓存命中率会退化；AssistantRepository.insert 用 key=#result + beforeInvocation=false，语义正确但与其它方法风格不一致
- 已知限制: A5 Redis 锁后端无单测（需真实 Redis），仅 DB 后端有覆盖；RedisWorkerLock 在拿到锁时即写 done 标记，任务中途失败仍会抑制窗口内重跑（DB 侧因事务回滚不刷新时间戳），两者语义存在差异
- 已知限制: A1 的 redis / none 分支无单测覆盖（无 Redis 实例），目前仅 resolveType() 归一化有单测
- 已知限制: A6 无 HTTP 层 429/Retry-After 断言：全仓无 429 字符串断言，仅 RequestDebouncerTest 覆盖核心逻辑，filter 的响应头与状态码未被验证
- 已知限制: A3 的排除清单与框架 jar 强耦合：框架 MCP 集群模式是否激活取决于其 @ConditionalOnBean(StringRedisTemplate)，框架升级若改变该条件需复核
- 已知限制: 缓存命中返回的是缓存内同一 Map 实例（Rows.lowerCaseKeys 产出的 LinkedHashMap），下游若就地修改会污染缓存；当前调用点均只读，风险存在但未触发
- 已知限制: Result 继承框架 JsonResult 后，基类同名静态 ok(String)/ok(String,Object) 为「消息」语义而本项目为「数据」语义，已显式隐藏；新增 ok(null) 类调用点须写 Result.<T>ok((T) null) 否则编译期即报错（当前全仓仅 LoginController 一处，已显式转型）

## 阻塞项

_无。_

## 风险与跳过的工作

- A9 计数口径：brief 写 681 passed，实际 685 tests / 680 passed / 5 skipped（含本轮 Result 继承改造新增 4 项信封用例）。结论不受影响，仅数字表述需以实际为准（brief 属已确认产物，未改以免破坏 shape 确认哈希）
- A5 Redis 锁后端无单测（需真实 Redis），仅 DB 后端有覆盖；RedisWorkerLock 在拿到锁时即写 done 标记，任务中途失败仍会抑制窗口内重跑（DB 侧因事务回滚不刷新时间戳），两者语义存在差异
- A1 的 redis / none 分支无单测覆盖（无 Redis 实例），目前仅 resolveType() 归一化有单测
- A6 无 HTTP 层 429/Retry-After 断言：全仓无 429 字符串断言，仅 RequestDebouncerTest 覆盖核心逻辑，filter 的响应头与状态码未被验证
- A3 的排除清单与框架 jar 强耦合：框架 MCP 集群模式是否激活取决于其 @ConditionalOnBean(StringRedisTemplate)，框架升级若改变该条件需复核
- 缓存命中返回的是缓存内同一 Map 实例（Rows.lowerCaseKeys 产出的 LinkedHashMap），下游若就地修改会污染缓存；当前调用点均只读，风险存在但未触发
- Result 继承框架 JsonResult 后，基类同名静态 ok(String)/ok(String,Object) 为「消息」语义而本项目为「数据」语义，已显式隐藏；新增 ok(null) 类调用点须写 Result.<T>ok((T) null) 否则编译期即报错（当前全仓仅 LoginController 一处，已显式转型）

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | blocked | A1, A2, A3, A4, A5, A6, A7, A8, A9 | 独立只读验收对 A1-A9 的技术结论为全部通过（证据：缓存/锁/防抖/OpenAPI 均有对应单测；无 Redis 环境下 12 个全上下文 @SpringBootTest 全绿；六个向量后端接入未触及 EmbeddingService 与四类召回调用点；全量回归 681 passed / 0 failed / 0 errors / 5 skipped）。但本候选的 Runtime 检查是在工作区变更（响应信封改造提交）的窗口内执行的，候选输入指纹前后不一致，Runtime 明确判定本候选无法完整复核并拒绝采信刷新检查，因此本候选按 blocked 记录并退回 Build，以便在稳定工作区重新产出候选、重新执行检查后再完成验收。 | 2026-10-24T19:06:11.475Z |
| 1 | 1 | 1 | recovery | — | 用户决策：调整需求路径——回到 Shape 以生成与当前工作区一致的新提案（需求范围与已确认的 brief/spec 完全一致，未做修改） | 2026-10-24T19:21:42.001Z |
| 2 | 1 | 1 | pass | — | 独立只读验收：A1-A9 全部通过（Runtime 全量回归检查亦通过：681 tests / 0 failures / 0 errors / 5 skipped，exit 0，143.6s）。缓存后端三态可配、失效挂 Repository 写方法、Redis 真正可选（无 Redis 也能启动，框架 MCP 集群模式不激活）、Sa-Token 存储可选且显式注册给 SaManager、守卫锁后端可配含防抖、接口防抖 429+Retry-After、OpenAPI 元数据可配且 token 头同源、向量后端插件化且未改业务调用点。遗留风险集中在验证深度：Redis 相关分支无自动化测试、429 响应头无断言、缓存 Map 为共享可变实例。 | 2026-10-24T20:04:22.549Z |
| 2 | 1 | 1 | recovery | — | The user chose to revise the implementation after Verify stopped. | 2026-10-24T22:49:57.814Z |
| 2 | 2 | 1 | pass | — | 独立只读验收：A1-A9 全部通过（Runtime 全量回归检查 exit 0：685 tests / 0 failures / 0 errors / 5 skipped）。缓存后端三态可配、失效挂 Repository 写方法、Redis 真正可选（无 Redis 也能启动，框架 MCP 集群模式不激活）、Sa-Token 存储可选且显式注册给 SaManager、守卫锁后端可配含防抖、接口防抖 429+Retry-After、OpenAPI 元数据可配且 token 头同源、向量后端插件化且未改业务调用点；响应信封 Result 继承框架 JsonResult 仍保持 code/data/msg 三键契约、ok(String) 保持 data 语义、ResponseEnvelopeAdvice 仍识别 Result。遗留风险集中在验证深度：Redis 相关分支无自动化测试、429 响应头无断言、缓存 Map 为共享可变实例。 | 2026-10-25T05:30:12.384Z |



## 结论

独立只读验收：A1-A9 全部通过（Runtime 全量回归检查 exit 0：685 tests / 0 failures / 0 errors / 5 skipped）。缓存后端三态可配、失效挂 Repository 写方法、Redis 真正可选（无 Redis 也能启动，框架 MCP 集群模式不激活）、Sa-Token 存储可选且显式注册给 SaManager、守卫锁后端可配含防抖、接口防抖 429+Retry-After、OpenAPI 元数据可配且 token 头同源、向量后端插件化且未改业务调用点；响应信封 Result 继承框架 JsonResult 仍保持 code/data/msg 三键契约、ok(String) 保持 data 语义、ResponseEnvelopeAdvice 仍识别 Result。遗留风险集中在验证深度：Redis 相关分支无自动化测试、429 响应头无断言、缓存 Map 为共享可变实例。
