---
generated_from_state_version: 11
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 2
- 验证器尝试次数: 1
- 完成时间: 2026-10-29T15:42:19.639Z
- 摘要: 第二轮独立只读核验全部亲自取证：A1 通过（实跑 9 类 live 测试，Tests run: 15, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS）；A2 通过（UnboundID 7.0.5 在本地仓库可用，新增 LdapSsoRealBindE2ETest 用进程内真实 LDAP 服务与真实随机端口，经 LdapSsoClient 的 JNDI bind 覆盖正确口令成功/错误口令被拒/未知用户/validate，4 用例全绿）；A3 通过（§2.4 命令按原样实跑可执行，端口/容器/版本/门控与实测一致，§3 已删除网络不可达等与事实相反的表述且仅剩代码推送待授权，SSO 46 用例经我独立复跑确认）。上一轮 A2/A3 的两条失败根因（以错误前提登记缺口、未新增 LDAP 真 bind 测试）均已实质修复，而非换个说法。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1：Pg / MySQL / Redis / SQLBot 四类 live 测试在本机真实服务下执行且 0 失败。 | 独立实跑（自行执行，不引用 Builder 结论）：JAVA_HOME=zulu-17 + 给定 ASKDATA_* env，执行 mvn -o -pl askdata/askdata-server -am test -Dmaven.test.skip=false -DskipTests=false -DfailIfNoTests=false -Dtest=PgLiveDialectTest,MySqlLiveDialectTest,OracleLiveDialectTest,SqlServerLiveDialectTest,LlmNl2SqlLiveTest,AuthWhitelistParityTest,DebounceRedisCrossInstanceTest,SamlSsoRealHttpE2ETest,LdapSsoRealBindE2ETest -Daskdata.test.redis.port=13379 -Dsqlbot.root=.../SQLBot/backend。退出码 0；汇总 [INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS（总耗时 44s）。逐类：Ldap 4、Saml 1、Debounce 3、AuthWhitelist 2、Oracle 1、SqlServer 1、Llm 1、Pg 1、MySql 1。Skipped=0 证明 assumeTrue/@EnabledIfSystemProperty 门控全部满足，无静默跳过。Pg/MySQL/Redis/SQLBot 四类均在其中且通过。 |
| A2 | passed | brief.md | A2：若 UnboundID 依赖可用，新增 LDAP 真 bind 端到端测试并 0 失败；否则在文档明确标注该缺口。 | 独立取证：(1) 依赖可用：本机 Maven localRepository 被 settings.xml 覆盖为 /Users/xiongxiao/Workspace/Idea/maven/repo，其中存在 com/unboundid/unboundid-ldapsdk/7.0.5/unboundid-ldapsdk-7.0.5.jar（5,907,305 字节，下载时间 2026-10-29 23:11）；curl repo1.maven.org/maven2/ -> 200。(2) 新增测试：askdata/askdata-server/src/test/java/com/xiongx9527/askdata/system/sso/LdapSsoRealBindE2ETest.java（git 显示为新增未跟踪文件，130 行）。(3) 真实网络 bind：@BeforeAll 用 InMemoryDirectoryServer + InMemoryListenerConfig.createLDAPConfig("default", 0) 在随机端口启动真实 LDAP 监听；config() 把 ldap://127.0.0.1:<server.getListenPort()> 交给 LdapSsoClient；LdapSsoClient.loginWithPassword 使用 com.sun.jndi.ldap.LdapCtxFactory + InitialDirContext 做两次真实 JNDI simple bind（服务账号 bind → 按 ou+user_filter 搜索用户 DN → 用户 DN+口令 bind → 读属性），全程真实 TCP，无 mock/桩。(4) 断言覆盖：正确口令 bind 成功且 account/name/email 按 uid/cn/mail 映射；错误口令抛 IllegalStateException 且消息含 'LDAP bind failed'；未知用户 bob 报 'LDAP user not found'；validate 用服务账号 bind 返回 true。(5) 实测 4 用例全绿（Tests run: 4, Failures: 0, Errors: 0, Skipped: 0）。pom 仅新增 test scope 依赖，不进产物。Runtime 复跑 ldap-real-bind-e2e 亦 passed（exitCode 0，23.9s）。 |
| A3 | passed | brief.md | A3：docs/verification 中 live 测试复现命令与残留缺口清单完整、可执行。 | 逐条核对并实跑 §2.4：命令的 -pl/-am、-Dmaven.test.skip=false -DskipTests=false -DfailIfNoTests=false、-Dtest 九类、-Daskdata.test.redis.port、-Dsqlbot.root 与我实际成功执行的命令一致（退出码 0）。端口与实测环境一致：PG 16.14:5432、MySQL 5.7.44 容器 askdata-cluster-mysql-1:13306、Redis 7.4.12 容器 askdata-cluster-redis-1:13379、容器 oracle-xe:1521、容器 sqlserver:1433、Ollama gpt-oss:120b-cloud:11434；nc 探测 6 个端口全部 open，docker ps 容器名与文档一致。门控说明准确：DebounceRedisCrossInstanceTest 为 @EnabledIfSystemProperty(named="askdata.test.redis.port")，AuthWhitelistParityTest 需 sqlbot.root 下 whitelist.py 存在，四类方言 live 测试用 getenv+assumeTrue，LDAP 测试无门控。§2.3 的 fixture 声明属实（MySQL/Oracle/SQL Server live 测试内确有 CREATE DATABASE/CREATE TABLE VERIFY_TBL）。§3 缺口清单与事实相符：Oracle/SQL Server/LLM/LDAP 本轮均实跑通过、不再是缺口，仅剩『代码推送 origin/main 待授权』；github.com 我实测返回 200，不再有 repo1 返回 000 / github 不可达 / 无 SQL Server 引擎等错误表述；全文 grep 无 LdapSsoClientTest 等不存在的测试类引用。另独立复跑 system.sso + system.authentication：Tests run: 46, Failures: 0, Errors: 0, Skipped: 0，与文档 §2.3『46 个测试全绿』一致。Runtime 按我请求刷新复跑 verification-doc-claims / ldap-real-bind-e2e，均 passed。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| LDAP 真 bind 端到端测试（离线可复现） | -c export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home; mvn -o -pl askdata/askdata-server -am test -Dmaven.test.skip=false -DskipTests=false -DfailIfNoTests=false -Dtest=LdapSsoRealBindE2ETest | . | passed | 0 | 23930 ms |
| 验证文档结论、复现命令与缺口清单 | -c set -e; grep -q 'Tests run: 15, Failures: 0' docs/verification/sqlbot-parity-verification.md; grep -q LdapSsoRealBindE2ETest docs/verification/sqlbot-parity-verification.md; grep -q unboundid-ldapsdk docs/verification/sqlbot-parity-verification.md; grep -q '代码推送 origin/main' docs/verification/sqlbot-parity-verification.md; grep -q '46 个测试全绿' docs/verification/sqlbot-parity-verification.md | . | passed | 0 | 121 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- 9 类 live 测试统一复跑: passed — Tests run: 15, Failures: 0, Errors: 0, Skipped: 0；JDK 17 + Maven 离线 -o
- SSO 子系统（system.sso + system.authentication）: passed — Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
- LDAP 真 bind 测试离线复跑: passed — mvn -o ... -Dtest=LdapSsoRealBindE2ETest → Tests run: 4, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS
- 已知限制: Oracle / SQL Server / LLM / LDAP 的 live 证据依赖本机容器与 Ollama；其他机器未启动对应服务时相关测试按既有门控 skip（不是失败）
- 已知限制: 代码推送 origin/main 仍未执行，需用户明确授权；网络已恢复

## 阻塞项

_无。_

## 风险与跳过的工作

- §2.1 的「1097 个测试执行」与 §1 表格第 9 行「2192 级测试套件」两处既有数字互相矛盾，均非本次改动行，本轮未复跑全量套件，存在过期/不精确风险。
- §3 称残留缺口仅剩代码推送，但仓库仍有 5 个需显式 -D*.live=true 才执行的向量 live 测试（Elasticsearch/Milvus/PgVector/Qdrant/Weaviate LiveIntegrationTest），§2.4 未给其复现命令、§3 未登记；不在 brief 缺口枚举范围内，故未据此判 fail，但『清单完整』仍有解释空间。
- LdapSsoRealBindE2ETest 以 config.setSchema(null) 简化 fixture（合理裁剪），覆盖 JNDI simple bind 全链路，但不覆盖 LDAPS/StartTLS，文档未夸大。
- unboundid-ldapsdk 位于自定义 localRepository；换机或 CI 首次构建需联网一次，文档 §2.5 已说明。
- 本轮首次提交 final-result 时被 Runtime 以 OUTCOME_REJECTED（检查输入已变化）拒绝：原因是 Verifier 独立复跑测试改写了被纳入指纹的构建产物。已按提示以 refreshChecks: true 请求原检查重放（两项均 passed）后重新领取 Action :10/:11 提交，未绕过任一检查。
- 改动范围干净：git status --short 仅 M askdata/askdata-server/pom.xml、M docs/verification/sqlbot-parity-verification.md、新增 LdapSsoRealBindE2ETest.java，以及 Comet 产物 docs/comet/changes/；git diff --stat 为 pom +10、doc +93/-22，无运行期代码改动、无调试代码夹带。

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | 1 | fail | A2, A3 | 独立只读核验完成：A1 通过（我亲自复跑 5 个 live 测试类，8 用例 0 失败 0 跳过，BUILD SUCCESS，后端版本与文档一致）；A2 不通过（UnboundID 依赖实际可获取——Maven Central 返回 200 且 jar 成功下载 5.18MB，但候选既未新增 LDAP 真 bind 端到端测试，也以『网络返回 000』这一假前提登记缺口）；A3 不通过（复现命令可执行且经我实跑验证，但 §3 缺口清单中 LDAP/SQL Server/代码推送三条原因与实测相反：repo1 返回 200、SQL Server 容器在 1433 运行且驱动已在 pom、github.com 返回 200）。候选仅改文档、无夹带其他改动。 | 2026-10-29T15:05:48.901Z |
| 1 | 2 | 1 | pass | — | 第二轮独立只读核验全部亲自取证：A1 通过（实跑 9 类 live 测试，Tests run: 15, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS）；A2 通过（UnboundID 7.0.5 在本地仓库可用，新增 LdapSsoRealBindE2ETest 用进程内真实 LDAP 服务与真实随机端口，经 LdapSsoClient 的 JNDI bind 覆盖正确口令成功/错误口令被拒/未知用户/validate，4 用例全绿）；A3 通过（§2.4 命令按原样实跑可执行，端口/容器/版本/门控与实测一致，§3 已删除网络不可达等与事实相反的表述且仅剩代码推送待授权，SSO 46 用例经我独立复跑确认）。上一轮 A2/A3 的两条失败根因（以错误前提登记缺口、未新增 LDAP 真 bind 测试）均已实质修复，而非换个说法。 | 2026-10-29T15:42:19.639Z |



## 结论

第二轮独立只读核验全部亲自取证：A1 通过（实跑 9 类 live 测试，Tests run: 15, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS）；A2 通过（UnboundID 7.0.5 在本地仓库可用，新增 LdapSsoRealBindE2ETest 用进程内真实 LDAP 服务与真实随机端口，经 LdapSsoClient 的 JNDI bind 覆盖正确口令成功/错误口令被拒/未知用户/validate，4 用例全绿）；A3 通过（§2.4 命令按原样实跑可执行，端口/容器/版本/门控与实测一致，§3 已删除网络不可达等与事实相反的表述且仅剩代码推送待授权，SSO 46 用例经我独立复跑确认）。上一轮 A2/A3 的两条失败根因（以错误前提登记缺口、未新增 LDAP 真 bind 测试）均已实质修复，而非换个说法。
