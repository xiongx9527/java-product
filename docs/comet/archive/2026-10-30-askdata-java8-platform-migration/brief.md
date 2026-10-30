# 目标

把 askdata（`askdata/askdata-server`）整体从 **fw-plus 0.3.0 / Spring Boot 3.5.8 / JDK 17 / jakarta** 平台线降级到 **fw-plus 0.1.1 / Spring Boot 2.7.18 / JDK 1.8 / javax** 平台线，在 `dev` 分支交付。

迁移完成后，产品能力与 `main`（JDK 17 线）等价：主源码与测试源码都能在真实 JDK 8 上编译，测试全绿，服务能正常启动并对外提供原有 HTTP/MCP 接口。

不改变产品行为：本次只换平台线（构建坐标、依赖版本、语言特性、命名空间），HTTP/MCP 接口契约、请求响应字段、数据库表结构与迁移脚本内容全部保持原样；唯一的能力收窄是在线向量库后端（qdrant/milvus/weaviate/pgvector/elasticsearch/redis）因 0.35 版 langchain4j 缺少等价写入 API 而下线，已在「非目标」中列明，不新增产品规格文档。

# 范围

## 构建与依赖

- `pom.xml`：parent `fw-plus-ai-build` 0.3.0-SNAPSHOT → 0.1.1-SNAPSHOT；自身 `version`/`revision` → 0.1.1-SNAPSHOT；`maven.compiler.source/target` 17 → 1.8。
- `askdata/pom.xml`：parent 版本同步为 0.1.1-SNAPSHOT。
- `askdata/askdata-server/pom.xml`：parent 版本、`java.version` 1.8、compiler 1.8、`revision` 0.1.1-SNAPSHOT、`spring-boot.version` 2.7.18。
- `jsqlparser` 显式锁定 **4.9**：BOM 管理的 4.6 是旧 AST（`SelectBody`/`SubSelect`），4.9 已是 5.x 统一 AST（`PlainSelect extends Select`）且仍是 Java 8 字节码（major 52），因此行权限改写等 5.x AST 代码零重写。
- 移除 `langchain4j-agentic`、`flyway-database-postgresql`（Flyway 8.5 的 Postgres 支持在 core 内）与 6 个在线向量库依赖（`langchain4j-qdrant/milvus/weaviate/pgvector/elasticsearch/community-redis`）。
- **运行期构件必须全部是 Java 8 字节码**（实测扫描已定位 4 处越界）：
  - `springdoc-openapi-starter-*` 2.2.0（major 61）→ 换成 v1 线的 `springdoc-openapi-ui`（1.7.0，major 52），同步调整 `config/ApiDocsController.java` 的引用。
  - `ojdbc11` 21.9（major 55）→ `ojdbc8`。
  - `mssql-jdbc` 12.10.2.jre11（major 55）→ `jre8` 分类器版本。
  - `hive-jdbc 3.1.3` 经 `libthrift 0.21.0` 传递引入 `jakarta.servlet-api 6.0.0`（major 55，且与 SB 2.7 的 `javax.servlet` 冲突）→ 在 `hive-jdbc` 上 exclude。

## 目录结构

### 新建

- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/Maps.java` — 替代 `Map.of`/`Map.entry`（`of()`..`of(10 对)`、`ofEntries`、`entry`）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/Lists.java` — 替代 `List.of`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/Sets.java` — 替代 `Set.of`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/Texts.java` — 替代 `String.repeat`。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/util/Io.java` — 替代 `InputStream.readAllBytes`（`readAll`/`readAllUtf8`）。

### 修改

- `ai/vector/VectorStoreAutoConfiguration.java` — 只产出 `DbVectorStore`；`type` 为 qdrant/milvus/weaviate/pgvector/elasticsearch/redis 时打告警并回落，不再构造在线后端。
- 全量语言特性降级（约 136 个文件）：
  - `record` → 普通类（`StoredFile`、`ClusterReadinessReport` 及其内部 `Item`、`ConnectionPoolManager.PoolSettings`、`ChartImageService.TokenEntry`、`ChartTokenRepository.Entry`、`VectorStore.IdScore`、`ScheduledTaskService.ExportFile`），并**补回 record 自带的 `equals`/`hashCode`/`toString`**——已实测 `ConnectionPoolSettingsTest` 因 `PoolSettings` 丢 `equals` 而失败。
  - `instanceof X x` 模式匹配 → 显式强转；`switch` 箭头 → 冒号 + `break`；`var` → 显式类型；text block → 拼接字符串。
  - `Map.of`/`List.of`/`Set.of`/`Map.entry`/`String.repeat`/`InputStream.readAllBytes` → 上节新建的 `common/util` 工具。
  - `Path.of` → `Paths.get`；`Files.readString`/`writeString` → `Io.readAllUtf8`/`Files.write`；`Optional.isEmpty()` → `!isPresent()`；`String.isBlank()` → `trim().isEmpty()`；`ByteArrayOutputStream.toString(Charset)` → `new String(toByteArray(), UTF_8)`。
- `jakarta.*` → `javax.*`（注解、Servlet、校验、持久化等全部引用与内联全限定名）。
- `java.net.http.HttpClient` → `HttpURLConnection`：`system/sso/IdpHttpClient.java`、`scheduled/ScheduledTaskExecutor.java`（新增 `postJson` 辅助方法）。
- `common/util/PortableWorkerIdAssigner.java`、`cluster/NodeIdentity.java` — 用 `ManagementFactory.getRuntimeMXBean().getName()` 取 PID，替代 `ProcessHandle`。
- `system/sso/Saml2Provider.java` — `InflaterInputStream` 用 `readAll` 读取。
- `embedded/DynamicDatasourceResolver.java` — `URLEncoder` 增加 `encode(String)` 包装，避免受检 `UnsupportedEncodingException`。
- `datasource/engine/SqlSafety.java`、`SqlSemanticValidator.java`、`permission/PermissionEnforcer.java` — JSQLParser 4.9 下的访客与泛型适配（`ExpressionVisitorAdapter` 原始类型、`WithItem` 去泛型、空列表用 `Collections.emptyList()`）。
- `src/test/**`（约 46 个文件）— 同步上述降级；`NoHardcodedSecretsTest` 修正非法正则转义。

### 删除

- `ai/vector/AbstractLangchain4jVectorStore.java` 及 6 个 `Langchain4j*VectorStore.java`：0.35 版 langchain4j 没有"同时指定 point id 与 metadata"的写入 API，在线后端无法等价实现。
- 对应 7 个测试：`AbstractLangchain4jVectorStoreClientFilterTest`、`Langchain4jElasticsearchVectorStoreTest`、`Elasticsearch/Milvus/PgVector/Qdrant/WeaviateLiveIntegrationTest`。

# 非目标

- 不改动 `main` 分支（JDK 17 / SB 3.5 线继续保留）；本次只在 `dev` 交付。
- 不恢复 Qdrant/Milvus/Weaviate/pgvector/Elasticsearch/Redis 在线向量后端；这些 `type` 值降级为告警 + `DbVectorStore`。
- 不改业务语义、不改 HTTP/MCP 接口契约、不改数据库表结构与迁移脚本内容。
- 不做性能优化，不重构与平台迁移无关的代码。
- 不以"编译通过即完成"收尾：以真实 JDK 8 编译 + 测试通过 + 服务可启动为准。

# 验收示例

1. **构建**：JDK 8（Zulu 8.0.402）+ 本地已安装的 fw-plus 0.1.1，执行 `mvn -o -pl askdata/askdata-server test-compile -Dmaven.test.skip=false` → `BUILD SUCCESS`。
2. **测试**：`mvn -o -pl askdata/askdata-server test -Dmaven.test.skip=false` → 1053 个用例全部通过，无 failure / error（当前基线：1 failure + 145 errors，全部由上述依赖越界与 `PoolSettings` 丢 `equals` 引起）。
3. **字节码**：编译产物 `javap -v` 显示 `major version: 52`（Java 8）；且运行期 classpath 上的 275 个 jar 逐个扫描，除 `module-info.class` 外不存在 major > 52 的类（当前实测有 6 个越界 jar，见上文「构建与依赖」）。
4. **启动**：以 H2 + `askdata.vector-store.type=none` 启动，`/api-docs` 与 MCP 端点可访问，无 `NoClassDefFoundError` / `NoSuchMethodError`。
5. **降级路径**：`askdata.vector-store.type=qdrant` 启动只打告警并回落 `DbVectorStore`，不抛异常。
6. **残留检查**：主源码不存在 `jakarta.`、`java.net.http`、`Map.of(`/`List.of(`/`Set.of(`（自建工具类除外）、`Path.of(`、`.isBlank()`、`record ` 声明。
7. **行为等价**：SQL 行权限改写（JSQLParser 4.9 统一 AST）、MCP 工具调用、失败明细跨实例下载等既有回归测试全部通过。
