---
generated_from_state_version: 8
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-30T09:11:17.554Z
- 摘要: 独立复跑 7 项验收并全部通过：zulu-8 下 test-compile 与 1053 用例全量测试均 BUILD SUCCESS（0 failure/0 error/5 skipped，Runtime 刷新检查独立复核同样 1053 全绿）；243 个含类运行时 jar 最大 major=52（无 >52）；应用正常启动，/health=200、/v3/api-docs 返回 OpenAPI 3.0.1、/mcp/sse 返回 401，日志无 NoClassDefFoundError/NoSuchMethodError；qdrant 配置按预期 WARN 回落且服务健康；src/main 无 Java 9+ API 与语法残留；7 个关键测试类在 surefire 报告中确认执行并通过。判定 Java 8 平台迁移验收通过。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | **构建**：JDK 8（Zulu 8.0.402）+ 本地已安装的 fw-plus 0.1.1，执行 `mvn -o -pl askdata/askdata-server test-compile -Dmaven.test.skip=false` → `BUILD SUCCESS`。 | 独立复跑：zulu-8 下 mvn -o -pl askdata/askdata-server test-compile -Dmaven.test.skip=false -DskipTests 返回 EXIT=0 且 BUILD SUCCESS（Compiling 168 source files，耗时 14.1s）；抽样 target/classes 与 target/test-classes 的 class 文件 major=52。 |
| A2 | passed | brief.md | **测试**：`mvn -o -pl askdata/askdata-server test -Dmaven.test.skip=false` → 1053 个用例全部通过，无 failure / error（当前基线：1 failure + 145 errors，全部由上述依赖越界与 `PoolSettings` 丢 `equals` 引起）。 | 独立后台复跑：mvn -o -pl askdata/askdata-server test -Dmaven.test.skip=false（JVM 为 zulu-8 jre/bin/java），surefire 结果行 Tests run: 1053, Failures: 0, Errors: 0, Skipped: 5，BUILD SUCCESS；Runtime 刷新检查（verify.requested-checks）另行重跑同一命令 231.5s、exitCode=0，日志同样为 Tests run: 1053, Failures: 0, Errors: 0, Skipped: 5 + BUILD SUCCESS。 |
| A3 | passed | brief.md | **字节码**：编译产物 `javap -v` 显示 `major version: 52`（Java 8）；且运行期 classpath 上的 275 个 jar 逐个扫描，除 `module-info.class` 外不存在 major > 52 的类（当前实测有 6 个越界 jar，见上文「构建与依赖」）。 | dependency:build-classpath(includeScope=runtime) 得到 272 条路径，逐个 unzip 取首个非 META-INF/versions 的 class 读 major：成功扫描 243 个含类 jar，最大 major=52，major>52 的 jar 数为 0；其余 29 个为无顶层 class 的空壳/仅 versions 类 jar（starter、kotlin-stdlib-jdk7/8 等）。 |
| A4 | passed | brief.md | **启动**：以 H2 + `askdata.vector-store.type=none` 启动，`/api-docs` 与 MCP 端点可访问，无 `NoClassDefFoundError` / `NoSuchMethodError`。 | spring-boot:run 日志出现 Started AskDataApplication in 39.154 seconds；/health=200；/v3/api-docs 返回 {"openapi":"3.0.1","info":{"title":"askdata API"...；/mcp/sse 返回 401（路由已注册被鉴权拦截，非 404）；日志 NoClassDefFoundError/NoSuchMethodError 计数 0；进程已终止，8000 端口无监听。 |
| A5 | passed | brief.md | **降级路径**：`askdata.vector-store.type=qdrant` 启动只打告警并回落 `DbVectorStore`，不抛异常。 | 以 -Dspring-boot.run.arguments=--askdata.vector-store.type=qdrant 启动，日志出现 WARN VectorStoreAutoConfiguration：向量存储后端 qdrant 在当前版本不可用（需 langchain4j 1.x 写接口），回落到 DB JSON 列 + 应用内余弦；仍 Started AskDataApplication（17.819s），/health=200；进程已终止且端口释放。 |
| A6 | passed | brief.md | **残留检查**：主源码不存在 `jakarta.`、`java.net.http`、`Map.of(`/`List.of(`/`Set.of(`（自建工具类除外）、`Path.of(`、`.isBlank()`、`record ` 声明。 | 在 src/main 下 grep -rnE 逐项扫描 jakarta.、java.net.http、Path.of(、.isBlank()、record 类型声明、Map.of(/List.of(/Set.of(，8 类模式命中数全部为 0。 |
| A7 | passed | brief.md | **行为等价**：SQL 行权限改写（JSQLParser 4.9 统一 AST）、MCP 工具调用、失败明细跨实例下载等既有回归测试全部通过。 | surefire 报告逐项确认执行且通过：SqlSafetyTest 5、SqlSemanticValidatorTest 14、McpAssistantFlowTest 15、McpFrameworkE2ETest 1、SettingsFileOriginalNameFallbackTest 2、SettingsFileStorageFallbackTest 1、ExcelStorageCrossInstanceTest 2，合计 40 用例全部 Failures: 0 / Errors: 0。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| JDK 8 全量测试 | -lc export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home && mvn -o -pl askdata/askdata-server test -Dmaven.test.skip=false | . | passed | 0 | 231526 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- mvn -o -pl askdata/askdata-server test-compile（JDK 8）: passed — BUILD SUCCESS
- mvn -o -pl askdata/askdata-server test（JDK 8，全量）: passed — Tests run: 1053, Failures: 0, Errors: 0, Skipped: 5
- 运行期 classpath 字节码扫描（272 jar）: passed — major>52 的 jar 数量 0
- 启动验证 type=none / type=qdrant: passed — /health 200 UP；qdrant 走告警回落
- 主源码 Java 9+/SB3 API 残留扫描: passed — jakarta./java.net.http/Path.of/isBlank/record/Map.of 均为 0
- 已知限制: 在线向量库后端（qdrant/milvus/weaviate/pgvector/elasticsearch/redis）不再内置，配置这些 type 时回落 DbVectorStore（Shape 已确认）。
- 已知限制: 本地 data/askdata.mv.db 与 e2e.mv.db 是 JDK17 线 H2 2.3 生成的旧库（write format 3），已改名 *.h2v3.bak 备份，由 H2 2.1.214 重建。
- 已知限制: /mcp/sse 未带凭据时返回 401（鉴权拦截），本次只验证路由已注册，未做 MCP 带凭据端到端调用。

## 阻塞项

_无。_

## 风险与跳过的工作

- A3 覆盖 243/272 个 jar：29 个 jar 无顶层 class（空壳 starter、仅 META-INF/versions 的 kotlin-stdlib-jdk7/8），其多版本目录内类未纳入 major 校验。
- A2 有 5 个用例 Skipped，未逐条核实跳过原因是否为预期的环境条件跳过。
- A5 仅验证启动期 qdrant 回落告警与健康检查，未做真实向量检索链路的端到端查询。
- A4/A5 使用离线仓库(-o)与本地默认配置，未覆盖真实外部依赖（ES/Milvus/Qdrant、外部数据库）联调。
- 运行 A4/A5 会写入被忽略但纳入内容指纹的 data/*.mv.db，导致首次提交触发检查输入漂移；已按运行时指引以 refreshChecks 重跑原检查后重新验收。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | pass | — | 独立复跑 7 项验收并全部通过：zulu-8 下 test-compile 与 1053 用例全量测试均 BUILD SUCCESS（0 failure/0 error/5 skipped，Runtime 刷新检查独立复核同样 1053 全绿）；243 个含类运行时 jar 最大 major=52（无 >52）；应用正常启动，/health=200、/v3/api-docs 返回 OpenAPI 3.0.1、/mcp/sse 返回 401，日志无 NoClassDefFoundError/NoSuchMethodError；qdrant 配置按预期 WARN 回落且服务健康；src/main 无 Java 9+ API 与语法残留；7 个关键测试类在 surefire 报告中确认执行并通过。判定 Java 8 平台迁移验收通过。 | 2026-10-30T09:11:17.554Z |



## 结论

独立复跑 7 项验收并全部通过：zulu-8 下 test-compile 与 1053 用例全量测试均 BUILD SUCCESS（0 failure/0 error/5 skipped，Runtime 刷新检查独立复核同样 1053 全绿）；243 个含类运行时 jar 最大 major=52（无 >52）；应用正常启动，/health=200、/v3/api-docs 返回 OpenAPI 3.0.1、/mcp/sse 返回 401，日志无 NoClassDefFoundError/NoSuchMethodError；qdrant 配置按预期 WARN 回落且服务健康；src/main 无 Java 9+ API 与语法残留；7 个关键测试类在 surefire 报告中确认执行并通过。判定 Java 8 平台迁移验收通过。
