# Outcome

为 askdata-server 固化真实环境测试覆盖，确保核心链路在真实外部服务（PostgreSQL / MySQL / Redis）与真实 HTTP / SAML 回调路径下得到验证，并在文档中登记验证结论与残留缺口，满足「前后端联通、不能只是 mock」的要求。

# Scope

1. 复核并运行已有 live 测试：PgLiveDialectTest、MySqlLiveDialectTest、DebounceRedisCrossInstanceTest、AuthWhitelistParityTest、SamlSsoRealHttpE2ETest，确认其在真实服务下绿灯。
2. 在依赖可用（UnboundID ldapsdk）时，新增 LDAP 真 bind 端到端测试，覆盖 LdapSsoClient.loginWithPassword 的真实绑定路径。
3. 更新 docs/verification/sqlbot-parity-verification.md，登记真实环境验证结论、live 测试复现方式，以及残留缺口（Oracle / SQL Server / LLM / LDAP 真 bind）。

## Directory structure

### 新建

- askdata/askdata-server/src/test/java/com/xiongx9527/askdata/system/sso/ 下新增 LDAP 真 bind 端到端测试（依赖 UnboundID ldapsdk 且可用时）。

### 修改

- docs/verification/sqlbot-parity-verification.md：登记真实环境验证结论、live 测试复现命令与残留缺口清单。

### 删除

- 无。

### 明确不建

- 不新建 Oracle / SQL Server / LLM 的 live 测试（需专有外部引擎或端点，仅在文档登记缺口）。
- 不改动 askdata-server 运行期功能或 SSO 协议实现。

# Non-goals

- 不改动 askdata-server 运行期功能或 SSO 协议实现。
- 不实现 Oracle / SQL Server / LLM 等需要外部引擎或端点才能运行的 live 测试（仅在文档登记缺口）。
- 不迁移或重构既有测试框架。

# Acceptance examples

- A1：Pg / MySQL / Redis / SQLBot 四类 live 测试在本机真实服务下执行且 0 失败。
- A2：若 UnboundID 依赖可用，新增 LDAP 真 bind 端到端测试并 0 失败；否则在文档明确标注该缺口。
- A3：docs/verification 中 live 测试复现命令与残留缺口清单完整、可执行。

# 目标规格豁免

No product behavior change: 仅新增 / 补充测试与文档，不改动 askdata-server 运行期行为。
